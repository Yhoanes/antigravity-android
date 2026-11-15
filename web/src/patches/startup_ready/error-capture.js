/**
 * patches/startup_ready/error-capture.js - surface startup errors to native
 * with a bounded ring buffer so long sessions never accumulate unbounded arrays.
 */
var MAX_STARTUP_ERRORS = 50;
var MAX_ERROR_LEN = 1024;
var startupErrors = [];

function recordBoundedError(raw) {
  var text = String(raw || '');
  if (text.length > MAX_ERROR_LEN) {
    text = text.slice(0, MAX_ERROR_LEN) + '\u2026';
  }
  if (startupErrors.length > 0 && startupErrors[startupErrors.length - 1] === text) {
    return text;
  }
  if (startupErrors.length >= MAX_STARTUP_ERRORS) {
    startupErrors.shift();
  }
  startupErrors.push(text);
  return text;
}

try {
  global.addEventListener('error', function (ev) {
    var msg = ev && (ev.message || (ev.error && ev.error.message)) ? (ev.message || ev.error.message) : String(ev);
    var src = ev && ev.filename ? (ev.filename + ':' + ev.lineno + ':' + ev.colno) : '';
    var full = recordBoundedError('[JS Error ' + src + '] ' + msg);
    console.error(full);
    if (!state.signalled && global.Android && typeof global.Android.onUiError === 'function') {
      global.Android.onUiError(full);
    }
  });
  global.addEventListener('unhandledrejection', function (ev) {
    var reason = ev && ev.reason ? (ev.reason.stack || ev.reason.message || String(ev.reason)) : 'unknown rejection';
    var full = recordBoundedError('[Unhandled Rejection] ' + reason);
    console.error(full);
    if (!state.signalled && global.Android && typeof global.Android.onUiError === 'function') {
      global.Android.onUiError(full);
    }
  });
} catch (e) {}
