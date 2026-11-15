/**
 * patches/startup_ready/arm.js - watch for the first real UI paint.
 */
function renderEmptyRootRecoveryBanner() {
  try {
    if (!global.document || global.document.getElementById('agy-empty-root-recovery')) return;
    var wrap = global.document.createElement('div');
    wrap.id = 'agy-empty-root-recovery';
    wrap.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:24px;background:var(--background,#101010);color:var(--foreground,#f4f4f5);font-family:system-ui,-apple-system,sans-serif;text-align:center;';
    var title = global.document.createElement('div');
    title.style.cssText = 'font-size:16px;font-weight:600;';
    title.textContent = 'The interface stopped responding';
    var sub = global.document.createElement('div');
    sub.style.cssText = 'font-size:12px;opacity:0.75;max-width:360px;';
    sub.textContent = 'Your background tasks and agent sessions are still active.';
    var btn = global.document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Reload Interface';
    btn.style.cssText = 'margin-top:6px;padding:9px 16px;border-radius:8px;border:none;background:#3b82f6;color:#fff;font-size:13px;font-weight:500;cursor:pointer;';
    btn.onclick = function () {
      try { global.location.reload(); } catch (e) {}
    };
    wrap.appendChild(title);
    wrap.appendChild(sub);
    wrap.appendChild(btn);
    (global.document.body || global.document.documentElement).appendChild(wrap);
  } catch (e) {}
}

function installPostBootEmptyRootGuard() {
  try {
    var root = global.document && global.document.getElementById('root');
    if (!root || !global.MutationObserver) return;
    if (root.childElementCount > 0) {
      state.hasEverMountedRoot = true;
    }
    var rootGuard = new MutationObserver(function () {
      if (root.childElementCount > 0) {
        state.hasEverMountedRoot = true;
        if (state.emptyRootTimer) {
          global.clearTimeout(state.emptyRootTimer);
          state.emptyRootTimer = null;
        }
        var staleBanner = global.document && global.document.getElementById('agy-empty-root-recovery');
        if (staleBanner && staleBanner.parentNode) {
          staleBanner.parentNode.removeChild(staleBanner);
        }
        return;
      }
      if (!state.signalled || !state.hasEverMountedRoot || state.emptyRootTimer) return;
      state.emptyRootTimer = global.setTimeout(function () {
        state.emptyRootTimer = null;
        if (root.childElementCount > 0) return;
        console.warn('[AGY Startup] Post-boot empty #root detected; recovering UI');
        var canAutoReload = true;
        try {
          var lastTs = parseInt(global.sessionStorage.getItem('__ag_empty_root_reload_at') || '0', 10) || 0;
          if (Date.now() - lastTs < 25000) {
            canAutoReload = false;
          } else {
            global.sessionStorage.setItem('__ag_empty_root_reload_at', String(Date.now()));
          }
        } catch (e) {}
        try {
          if (global.history && global.location && global.location.search.indexOf('settingsOpen') !== -1) {
            var u = new URL(global.location.href);
            u.searchParams.delete('settingsOpen');
            u.searchParams.delete('settingsScreen');
            global.history.replaceState(global.history.state, '', u.pathname + u.search + u.hash);
          }
        } catch (e2) {}
        if (canAutoReload) {
          try { global.location.reload(); return; } catch (e3) {}
        }
        renderEmptyRootRecoveryBanner();
      }, 1500);
    });
    rootGuard.observe(root, { childList: true });
  } catch (e) {}
}

function arm() {
  if (!global.document) {
    signal('no-document');
    return;
  }

  installPostBootEmptyRootGuard();

  if (isUiRendered()) return;

  try {
    if (global.MutationObserver) {
      var observer = new MutationObserver(function () {
        if (isUiRendered()) {
          try { observer.disconnect(); } catch (e) {}
        }
      });
      var target = global.document.getElementById('root') || global.document.body || global.document.documentElement;
      if (target) {
        observer.observe(target, { childList: true, subtree: true });
      }
    }
  } catch (e) {}

  var poller = global.setInterval(function () {
    if (isUiRendered() || state.signalled) {
      global.clearInterval(poller);
    }
  }, 40);

  // Fallback: 5.5 seconds maximum waiting time
  global.setTimeout(function () {
    global.clearInterval(poller);
    if (isUiRendered()) {
      return;
    }
    signal('fallback-timeout');
  }, 5500);
}
