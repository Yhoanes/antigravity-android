// Polyfill Node process global for third-party libraries (TanStack Virtual, vscode-uri).
window.process = window.process || {};
window.process.env = window.process.env || {};
if (!window.process.env.NODE_ENV) {
  window.process.env.NODE_ENV = (window.__APP_CONFIG__ && window.__APP_CONFIG__.devMode) ? "development" : "production";
}
if (!window.process.platform) {
  var ua = (typeof navigator !== "undefined" && navigator.userAgent) || "";
  window.process.platform = ua.indexOf("Windows") >= 0
    ? "win32"
    : (ua.indexOf("Macintosh") >= 0 || ua.indexOf("Mac OS") >= 0 ? "darwin" : "linux");
}

// Workaround for custom elements in overlay bundle.
// Re-defining an already-registered custom element throws; swallow the second
// define so both the host and the overlay bundle can register safely.
if (window.customElements) {
  var originalDefine = window.customElements.define;
  window.customElements.define = function (name, constructor, options) {
    if (!window.customElements.get(name)) {
      originalDefine.call(window.customElements, name, constructor, options);
    }
  };
}
