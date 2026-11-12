/**
 * core/ws-resume.js - loopback /connect-websocket liveness probe on host resume.
 *
 * When Android backgrounds the Activity (e.g. OAuth browser redirect, switching
 * apps, or screen off) and later resumes it, WebViewLifecycle dispatches
 * `agy:host-resume` (and document emits `visibilitychange`). Wrapping
 * window.WebSocket ahead of main.js lets us track active `/connect-websocket`
 * sockets without touching minified symbols: on resume, send an immediate JSON
 * ping frame and close the socket if no inbound frame arrives within 1200ms so
 * ConnectRPC's stream consumer resubscribes in <1s.
 */
var trackedConnectSockets = [];
var lastResumeProbeAt = 0;

function isConnectWebSocketUrl(url) {
  if (!url) return false;
  return String(url).indexOf('/connect-websocket') !== -1;
}

function probeTrackedSockets(reason) {
  var now = Date.now();
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

function installWebSocketResumeGuard() {
  if (global.__AG_WS_RESUME_GUARD__ || typeof global.WebSocket !== 'function') return;
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

  global.addEventListener('agy:host-resume', function () {
    probeTrackedSockets('agy:host-resume');
  });
  if (global.document && typeof global.document.addEventListener === 'function') {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') {
        probeTrackedSockets('visibilitychange');
      }
    });
  }
  global.addEventListener('pageshow', function (ev) {
    if (ev && ev.persisted) probeTrackedSockets('pageshow');
  });

  global.__AG_WS_RESUME_GUARD__ = true;
}

installWebSocketResumeGuard();
