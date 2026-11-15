/**
 * core/ws-resume.js - StreamAgentStateUpdates & /connect-websocket resume guard.
 *
 * In standalone mode (`liblanguage_server.so --standalone`), ConnectRPC runs
 * over HTTP/2 `fetch` (`https://127.0.0.1:<port>/exa.../StreamAgentStateUpdates`)
 * rather than `/connect-websocket`. When Android backgrounds the Activity (user
 * switches to another app or locks the screen), Chromium freezes the WebView
 * renderer while the Go backend keeps executing tools and saving steps; if updates
 * accumulate for >5s (`pubsub.go`) or the stream stalls while frozen, the pending
 * `reader.read()` can remain stuck in "Working..." upon returning.
 *
 * This module wraps ConnectRPC state streams on complete 5-byte gRPC-Web /
 * Connect envelope boundaries with demand-driven `pull()` backpressure, O(N)
 * chunk queuing, and a linked child AbortController.
 */
var trackedConnectSockets = [];
var trackedStateStreams = [];
var lastResumeProbeAt = 0;
var lastStreamRecycleAt = 0;
var lastHiddenAt = 0;
var lastBlurredAt = 0;
var lastLiveCascadeActivityAt = 0;
var summaryWatchdogTimer = null;

var streamResumeStats = {
  activeStreams: 0,
  totalOpened: 0,
  recycledCount: 0,
  summaryWatchdogRecycles: 0,
  lastRecycleReason: null,
  lastRecycleAt: 0
};
global.__agStreamResumeStats = streamResumeStats;

function isConnectWebSocketUrl(url) {
  if (!url) return false;
  return String(url).indexOf('/connect-websocket') !== -1;
}

function isAgentStateStreamUrl(url) {
  if (!url) return false;
  return /\/(?:streamagentstateupdates|jetboxsubscribetosummaries|jetboxsubscribetostate|subscribetomcpserverstates|subscribetosidecars)(?:\?|$)/i.test(String(url));
}

function classifyStreamKind(url) {
  var s = String(url || '');
  if (/\/streamagentstateupdates(?:\?|$)/i.test(s)) return 'agent';
  if (/\/jetboxsubscribetosummaries(?:\?|$)/i.test(s)) return 'summaries';
  return 'other';
}

function isCascadeMutationUrl(url) {
  if (!url) return false;
  return /\/(?:startcascade|sendusercascademessage|cancelcascadeinvocation|forcestopcascadetree)(?:\?|$)/i.test(String(url));
}

function scheduleStaleSummaryCheck() {
  lastLiveCascadeActivityAt = Date.now();
  if (summaryWatchdogTimer) return;
  summaryWatchdogTimer = setTimeout(function () {
    summaryWatchdogTimer = null;
    var now = Date.now();
    var targetTs = lastLiveCascadeActivityAt;
    if (!targetTs) return;
    var snapshot = trackedStateStreams.slice();
    for (var i = 0; i < snapshot.length; i++) {
      var entry = snapshot[i];
      if (!entry || entry.closed || entry.kind !== 'summaries') continue;
      if (now - entry.startedAt < 9000) continue;
      if (entry.lastChunkAt < targetTs - 1500) {
        streamResumeStats.summaryWatchdogRecycles++;
        entry.recycle('stale-summary-sync');
      }
    }
  }, 8500);
}

function buildGrpcWebOkTrailer() {
  // 0x80 = gRPC-Web trailer frame, 16-byte payload "grpc-status: 0\r\n"
  var text = 'grpc-status: 0\r\n';
  var out = new Uint8Array(5 + text.length);
  out[0] = 0x80;
  out[1] = 0;
  out[2] = 0;
  out[3] = 0;
  out[4] = text.length;
  for (var i = 0; i < text.length; i++) {
    out[5 + i] = text.charCodeAt(i) & 0xff;
  }
  return out;
}

function buildConnectEndFrame() {
  // 0x02 = Connect end-of-stream flag, 2-byte payload "{}"
  return new Uint8Array([0x02, 0, 0, 0, 2, 0x7b, 0x7d]);
}

function createChunkQueue() {
  var chunks = [];
  var headOffset = 0;
  var totalBytes = 0;

  return {
    byteLength: function () {
      return totalBytes;
    },
    push: function (u8) {
      if (!u8 || u8.byteLength === 0) return;
      chunks.push(u8);
      totalBytes += u8.byteLength;
    },
    peekUint32At1: function () {
      if (totalBytes < 5) return -1;
      var first = chunks[0];
      if (first.byteLength - headOffset >= 5) {
        var dv = new DataView(first.buffer, first.byteOffset + headOffset, 5);
        return dv.getUint32(1, false);
      }
      var hdr = new Uint8Array(5);
      var copied = 0;
      var ci = 0;
      var off = headOffset;
      while (copied < 5 && ci < chunks.length) {
        var c = chunks[ci];
        var avail = c.byteLength - off;
        var take = Math.min(5 - copied, avail);
        hdr.set(c.subarray(off, off + take), copied);
        copied += take;
        ci++;
        off = 0;
      }
      return new DataView(hdr.buffer, 0, 5).getUint32(1, false);
    },
    readBytes: function (n) {
      if (n <= 0 || totalBytes === 0) return new Uint8Array(0);
      var count = Math.min(n, totalBytes);
      var first = chunks[0];
      if (first.byteLength - headOffset >= count) {
        var slice = first.slice(headOffset, headOffset + count);
        headOffset += count;
        totalBytes -= count;
        if (headOffset >= first.byteLength) {
          chunks.shift();
          headOffset = 0;
        }
        return slice;
      }
      var out = new Uint8Array(count);
      var written = 0;
      while (written < count && chunks.length > 0) {
        var cur = chunks[0];
        var avail = cur.byteLength - headOffset;
        var take = Math.min(count - written, avail);
        out.set(cur.subarray(headOffset, headOffset + take), written);
        written += take;
        headOffset += take;
        totalBytes -= take;
        if (headOffset >= cur.byteLength) {
          chunks.shift();
          headOffset = 0;
        }
      }
      return out;
    },
    drainAll: function () {
      return this.readBytes(totalBytes);
    }
  };
}

function syncStreamCount() {
  streamResumeStats.activeStreams = trackedStateStreams.length;
}

function recycleTrackedStateStreams(reason, awayDurationMs) {
  var now = Date.now();
  if (now - lastStreamRecycleAt < 450) return;
  if (reason !== 'agy:host-resume' && awayDurationMs < 1500) return;
  lastStreamRecycleAt = now;

  var snapshot = trackedStateStreams.slice();
  for (var i = 0; i < snapshot.length; i++) {
    var entry = snapshot[i];
    if (!entry || entry.closed) continue;
    // Do not interrupt a brand-new stream opened less than 1.2s ago.
    if (now - entry.startedAt < 1200) continue;
    entry.recycle(reason || 'resume');
  }
}

function probeTrackedSockets(reason, explicitAwayMs) {
  var now = Date.now();
  var hiddenDurationMs = lastHiddenAt > 0 ? Math.max(0, now - lastHiddenAt) : 0;
  var awayDurationMs = Math.max(hiddenDurationMs, explicitAwayMs || 0);
  if (!global.document || global.document.visibilityState === 'visible') {
    lastHiddenAt = 0;
  }

  recycleTrackedStateStreams(reason, awayDurationMs);

  if (now - lastResumeProbeAt < 250) return;
  lastResumeProbeAt = now;

  for (var i = trackedConnectSockets.length - 1; i >= 0; i--) {
    (function (entry) {
      var ws = entry.ws;
      if (!ws || ws.readyState === 2 || ws.readyState === 3) {
        var idx = trackedConnectSockets.indexOf(entry);
        if (idx !== -1) trackedConnectSockets.splice(idx, 1);
        return;
      }
      if (ws.readyState !== 1) return;

      if (entry.probeTimer) {
        clearTimeout(entry.probeTimer);
        entry.probeTimer = null;
      }
      var seqBefore = entry.inboundSeq;
      try {
        ws.send(JSON.stringify({
          streamId: 'agy-resume-' + now + '-' + Math.random().toString(36).slice(2, 8),
          type: 'ping'
        }));
      } catch (e) {
        try { ws.close(4000, 'agy-resume-send-failed'); } catch (closeErr) {}
        return;
      }

      entry.probeTimer = setTimeout(function () {
        entry.probeTimer = null;
        if (ws.readyState === 1 && entry.inboundSeq === seqBefore) {
          try {
            console.warn('[AGY ws-resume] zombie /connect-websocket after ' + (reason || 'resume') + '; forcing reconnect');
            ws.close(4000, 'agy-resume-probe-timeout');
          } catch (e) {}
        }
      }, 1200);
    })(trackedConnectSockets[i]);
  }
}

function installFetchStreamResumeGuard() {
  if (global.__AG_STREAM_RESUME_GUARD__ || typeof global.fetch !== 'function'
      || typeof global.ReadableStream !== 'function' || typeof global.Response !== 'function') {
    return;
  }
  var prevFetch = global.fetch.bind(global);

  global.fetch = function (resource, init) {
    var urlStr = '';
    try {
      if (typeof resource === 'string') urlStr = resource;
      else if (resource && typeof resource.url === 'string') urlStr = resource.url;
    } catch (e) {}

    if (isCascadeMutationUrl(urlStr)) {
      scheduleStaleSummaryCheck();
    }

    if (!isAgentStateStreamUrl(urlStr) || typeof AbortController !== 'function') {
      return prevFetch(resource, init);
    }

    var parentSignal = (init && init.signal) || (resource && typeof resource === 'object' ? resource.signal : null);
    var childAc = new AbortController();
    var onParentAbort = null;
    var parentListenerCleaned = false;

    function cleanupParentAbort() {
      if (parentListenerCleaned) return;
      parentListenerCleaned = true;
      if (parentSignal && onParentAbort && typeof parentSignal.removeEventListener === 'function') {
        try { parentSignal.removeEventListener('abort', onParentAbort); } catch (e) {}
      }
      onParentAbort = null;
    }

    if (parentSignal) {
      if (parentSignal.aborted) {
        try { childAc.abort(); } catch (e) {}
      } else if (typeof parentSignal.addEventListener === 'function') {
        onParentAbort = function () {
          parentListenerCleaned = true;
          try { childAc.abort(); } catch (e) {}
        };
        parentSignal.addEventListener('abort', onParentAbort, { once: true });
      }
    }

    var nextInit = {};
    try {
      if (init && typeof init === 'object') {
        for (var k in init) {
          if (Object.prototype.hasOwnProperty.call(init, k)) nextInit[k] = init[k];
        }
      }
      nextInit.signal = childAc.signal;
    } catch (syncErr) {
      cleanupParentAbort();
      throw syncErr;
    }

    var fetchPromise;
    try {
      fetchPromise = prevFetch(resource, nextInit);
    } catch (syncFetchErr) {
      cleanupParentAbort();
      throw syncFetchErr;
    }

    return fetchPromise.then(function (res) {
      if (!res || !res.ok || !res.body || typeof res.body.getReader !== 'function') {
        cleanupParentAbort();
        return res;
      }

      var contentType = '';
      try {
        contentType = String((res.headers && res.headers.get('content-type')) || '').toLowerCase();
      } catch (e) {}
      var isConnectProto = contentType.indexOf('application/connect+') !== -1;

      var reader = res.body.getReader();
      var queue = createChunkQueue();
      var streamCtrl = null;
      var pumping = false;
      var entry = {
        kind: classifyStreamKind(urlStr),
        startedAt: Date.now(),
        lastChunkAt: Date.now(),
        closed: false,
        recycle: null
      };

      function detachEntry() {
        if (entry.closed) return;
        entry.closed = true;
        var idx = trackedStateStreams.indexOf(entry);
        if (idx !== -1) trackedStateStreams.splice(idx, 1);
        syncStreamCount();
        cleanupParentAbort();
      }

      function drainCompleteEnvelopes() {
        while (queue.byteLength() >= 5) {
          var len = queue.peekUint32At1();
          if (len > 67108864) {
            streamCtrl.enqueue(queue.drainAll());
            break;
          }
          if (queue.byteLength() < 5 + len) break;
          var frame = queue.readBytes(5 + len);
          var nowChunk = Date.now();
          entry.lastChunkAt = nowChunk;
          if (entry.kind === 'agent' && nowChunk - entry.startedAt >= 2000) {
            scheduleStaleSummaryCheck();
          }
          streamCtrl.enqueue(frame);
        }
      }

      function pumpIfNeeded() {
        if (pumping || entry.closed || !streamCtrl) return;
        if (typeof streamCtrl.desiredSize === 'number' && streamCtrl.desiredSize <= 0) {
          return;
        }
        pumping = true;
        reader.read().then(function (chunk) {
          pumping = false;
          if (entry.closed) return;
          if (chunk.done) {
            detachEntry();
            try {
              if (queue.byteLength() > 0) streamCtrl.enqueue(queue.drainAll());
              streamCtrl.close();
            } catch (e) {}
            return;
          }
          if (chunk.value && chunk.value.byteLength > 0) {
            queue.push(chunk.value);
            try {
              drainCompleteEnvelopes();
            } catch (e) {
              detachEntry();
              try { reader.cancel().catch(function () {}); } catch (ce) {}
              try { streamCtrl.error(e); } catch (err2) {}
              return;
            }
          }
          pumpIfNeeded();
        }).catch(function (err) {
          pumping = false;
          if (entry.closed) return;
          detachEntry();
          try { streamCtrl.error(err); } catch (e) {}
        });
      }

      entry.recycle = function (reason) {
        if (entry.closed) return;
        detachEntry();
        streamResumeStats.recycledCount++;
        streamResumeStats.lastRecycleReason = reason;
        streamResumeStats.lastRecycleAt = Date.now();
        try {
          if (streamCtrl) {
            streamCtrl.enqueue(isConnectProto ? buildConnectEndFrame() : buildGrpcWebOkTrailer());
            streamCtrl.close();
          }
        } catch (e) {}
        try { childAc.abort(); } catch (e) {}
        try { reader.cancel().catch(function () {}); } catch (e) {}
      };

      trackedStateStreams.push(entry);
      streamResumeStats.totalOpened++;
      syncStreamCount();

      var wrappedBody = new ReadableStream({
        start: function (controller) {
          streamCtrl = controller;
          pumpIfNeeded();
        },
        pull: function (controller) {
          streamCtrl = controller;
          pumpIfNeeded();
        },
        cancel: function (reason) {
          detachEntry();
          try { childAc.abort(); } catch (e) {}
          try { return reader.cancel(reason); } catch (e) {}
        }
      }, { highWaterMark: 8 });

      return new Response(wrappedBody, {
        status: res.status,
        statusText: res.statusText,
        headers: res.headers
      });
    }, function (fetchErr) {
      cleanupParentAbort();
      throw fetchErr;
    });
  };

  global.__AG_STREAM_RESUME_GUARD__ = true;
}

function installWebSocketResumeGuard() {
  if (global.__AG_WS_RESUME_GUARD__) return;

  if (typeof global.WebSocket === 'function') {
    var NativeWebSocket = global.WebSocket;

    function WrappedWebSocket(url, protocols) {
      var ws = (arguments.length > 1 && protocols !== undefined)
        ? new NativeWebSocket(url, protocols)
        : new NativeWebSocket(url);

      if (isConnectWebSocketUrl(url)) {
        var entry = { ws: ws, inboundSeq: 0, probeTimer: null };
        trackedConnectSockets.push(entry);
        ws.addEventListener('message', function () {
          entry.inboundSeq++;
          if (entry.probeTimer) {
            clearTimeout(entry.probeTimer);
            entry.probeTimer = null;
          }
        });
        var cleanup = function () {
          if (entry.probeTimer) {
            clearTimeout(entry.probeTimer);
            entry.probeTimer = null;
          }
          var idx = trackedConnectSockets.indexOf(entry);
          if (idx !== -1) trackedConnectSockets.splice(idx, 1);
        };
        ws.addEventListener('close', cleanup);
        ws.addEventListener('error', cleanup);
      }
      return ws;
    }

    WrappedWebSocket.prototype = NativeWebSocket.prototype;
    WrappedWebSocket.CONNECTING = NativeWebSocket.CONNECTING;
    WrappedWebSocket.OPEN = NativeWebSocket.OPEN;
    WrappedWebSocket.CLOSING = NativeWebSocket.CLOSING;
    WrappedWebSocket.CLOSED = NativeWebSocket.CLOSED;
    try {
      Object.setPrototypeOf(WrappedWebSocket, NativeWebSocket);
    } catch (e) {}

    global.WebSocket = WrappedWebSocket;
  }

  installFetchStreamResumeGuard();

  global.addEventListener('agy:host-resume', function () {
    probeTrackedSockets('agy:host-resume');
  });
  if (global.document && typeof global.document.addEventListener === 'function') {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'hidden') {
        lastHiddenAt = Date.now();
      } else if (global.document.visibilityState === 'visible') {
        probeTrackedSockets('visibilitychange');
      }
    });
  }
  global.addEventListener('pagehide', function () {
    lastHiddenAt = Date.now();
  });
  global.addEventListener('pageshow', function (ev) {
    if (ev && ev.persisted) probeTrackedSockets('pageshow', 2000);
  });
  global.addEventListener('blur', function () {
    lastBlurredAt = Date.now();
  });
  global.addEventListener('focus', function () {
    var blurMs = lastBlurredAt > 0 ? Math.max(0, Date.now() - lastBlurredAt) : 0;
    lastBlurredAt = 0;
    if (blurMs >= 2000) {
      probeTrackedSockets('window-focus', blurMs);
    }
  });

  global.__AG_WS_RESUME_GUARD__ = true;
}

installWebSocketResumeGuard();
