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
 * This module wraps `/StreamAgentStateUpdates` fetch streams on complete 5-byte
 * gRPC-Web / Connect envelope boundaries with a linked child AbortController.
 * On `agy:host-resume` / `visibilitychange -> visible`, it injects a clean end
 * trailer frame (`grpc-status: 0`) and closes the stale stream without aborting
 * the session's parent AbortSignal, causing `consumeAgentStateStream` in main.js
 * to cleanly re-subscribe and rehydrate the full conversation within ~1s.
 */
var trackedConnectSockets = [];
var trackedStateStreams = [];
var lastResumeProbeAt = 0;
var lastStreamRecycleAt = 0;
var lastHiddenAt = 0;

var streamResumeStats = {
  activeStreams: 0,
  totalOpened: 0,
  recycledCount: 0,
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
  return /\/streamagentstateupdates(?:\?|$)/i.test(String(url));
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

function concatUint8(a, b) {
  if (!a || a.byteLength === 0) return b;
  if (!b || b.byteLength === 0) return a;
  var out = new Uint8Array(a.byteLength + b.byteLength);
  out.set(a, 0);
  out.set(b, a.byteLength);
  return out;
}

function syncStreamCount() {
  streamResumeStats.activeStreams = trackedStateStreams.length;
}

function recycleTrackedStateStreams(reason, hiddenDurationMs) {
  var now = Date.now();
  if (now - lastStreamRecycleAt < 450) return;
  if (reason !== 'agy:host-resume' && hiddenDurationMs < 1500) return;
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

function probeTrackedSockets(reason) {
  var now = Date.now();
  var hiddenDurationMs = lastHiddenAt > 0 ? Math.max(0, now - lastHiddenAt) : 0;
  if (!global.document || global.document.visibilityState === 'visible') {
    lastHiddenAt = 0;
  }

  recycleTrackedStateStreams(reason, hiddenDurationMs);

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

    if (!isAgentStateStreamUrl(urlStr) || typeof AbortController !== 'function') {
      return prevFetch(resource, init);
    }

    var parentSignal = (init && init.signal) || (resource && typeof resource === 'object' ? resource.signal : null);
    var childAc = new AbortController();
    var onParentAbort = null;
    if (parentSignal) {
      if (parentSignal.aborted) {
        try { childAc.abort(); } catch (e) {}
      } else if (typeof parentSignal.addEventListener === 'function') {
        onParentAbort = function () {
          try { childAc.abort(); } catch (e) {}
        };
        parentSignal.addEventListener('abort', onParentAbort, { once: true });
      }
    }

    var nextInit = {};
    if (init && typeof init === 'object') {
      for (var k in init) {
        if (Object.prototype.hasOwnProperty.call(init, k)) nextInit[k] = init[k];
      }
    }
    nextInit.signal = childAc.signal;

    return prevFetch(resource, nextInit).then(function (res) {
      if (!res || !res.ok || !res.body || typeof res.body.getReader !== 'function') {
        if (parentSignal && onParentAbort && typeof parentSignal.removeEventListener === 'function') {
          try { parentSignal.removeEventListener('abort', onParentAbort); } catch (e) {}
        }
        return res;
      }

      var contentType = '';
      try {
        contentType = String((res.headers && res.headers.get('content-type')) || '').toLowerCase();
      } catch (e) {}
      var isConnectProto = contentType.indexOf('application/connect+') !== -1;

      var reader = res.body.getReader();
      var acc = new Uint8Array(0);
      var streamCtrl = null;
      var entry = {
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
        if (parentSignal && onParentAbort && typeof parentSignal.removeEventListener === 'function') {
          try { parentSignal.removeEventListener('abort', onParentAbort); } catch (e) {}
        }
      }

      function drainCompleteEnvelopes() {
        var offset = 0;
        while (acc.byteLength - offset >= 5) {
          var view = new DataView(acc.buffer, acc.byteOffset + offset, 5);
          var len = view.getUint32(1, false);
          if (len > 67108864) {
            streamCtrl.enqueue(acc.slice(offset));
            offset = acc.byteLength;
            break;
          }
          if (acc.byteLength - offset < 5 + len) break;
          var frame = acc.slice(offset, offset + 5 + len);
          offset += 5 + len;
          entry.lastChunkAt = Date.now();
          streamCtrl.enqueue(frame);
        }
        if (offset > 0) {
          acc = offset === acc.byteLength ? new Uint8Array(0) : acc.slice(offset);
        }
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
          (function pump() {
            reader.read().then(function (chunk) {
              if (entry.closed) return;
              if (chunk.done) {
                detachEntry();
                try {
                  if (acc.byteLength > 0) controller.enqueue(acc);
                  controller.close();
                } catch (e) {}
                return;
              }
              if (chunk.value && chunk.value.byteLength > 0) {
                acc = concatUint8(acc, chunk.value);
                try {
                  drainCompleteEnvelopes();
                } catch (e) {
                  detachEntry();
                  try { controller.error(e); } catch (err2) {}
                  return;
                }
              }
              pump();
            }).catch(function (err) {
              if (entry.closed) return;
              detachEntry();
              try { controller.error(err); } catch (e) {}
            });
          })();
        },
        cancel: function (reason) {
          detachEntry();
          try { childAc.abort(); } catch (e) {}
          try { return reader.cancel(reason); } catch (e) {}
        }
      });

      return new Response(wrappedBody, {
        status: res.status,
        statusText: res.statusText,
        headers: res.headers
      });
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
    if (ev && ev.persisted) probeTrackedSockets('pageshow');
  });

  global.__AG_WS_RESUME_GUARD__ = true;
}

installWebSocketResumeGuard();
