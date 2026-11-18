/**
 * core/updater-bridge.js - exposes window.electronUpdater backed by Android's
 * native AppUpdateManager, plus:
 *   1. Mobile floating update banner (#agy-mobile-update-pill) when state is
 *      "ready" and the desktop header AppUpdateButton is not mounted/visible
 *      (e.g. mobileLayout on viewports < 640px).
 *   2. Native-styled "Application Updates" section inside Settings -> General
 *      (#agy-settings-updates-section) for manual Check for Updates / Restart
 *      to Update on all screen sizes.
 */

var updaterListeners = [];
var lastUpdaterState = { type: 'idle' };
var dismissedBannerVersion = null;
var updaterDomSyncQueued = false;

function setTextIfChanged(el, text) {
  if (el && el.textContent !== text) {
    el.textContent = text;
  }
}

function setAttrIfChanged(el, name, val) {
  if (el && el.getAttribute(name) !== val) {
    el.setAttribute(name, val);
  }
}

function setClassIfChanged(el, cls) {
  if (el && el.className !== cls) {
    el.className = cls;
  }
}

function setCssIfChanged(el, css) {
  if (!el) return;
  if (el.getAttribute('data-agy-css') !== css) {
    el.style.cssText = css;
    el.setAttribute('data-agy-css', css);
  }
}

function isOwnedUpdaterNode(node) {
  var cur = node;
  while (cur && cur !== document.body && cur !== document.documentElement) {
    if (cur.nodeType === 1 && (cur.id === 'agy-mobile-update-pill' || cur.id === 'agy-settings-updates-section')) {
      return true;
    }
    cur = cur.parentNode;
  }
  return false;
}

function getInstalledAppVersion() {
  try {
    if (window.Android && typeof window.Android.getAppVersion === 'function') {
      var v = window.Android.getAppVersion();
      if (v) return String(v);
    }
  } catch (e) {}
  return (CONFIG && CONFIG.appVersion) || VERSION || '2.33.0';
}

function readNativeUpdaterState() {
  try {
    if (window.Android && typeof window.Android.getUpdateStateJson === 'function') {
      var raw = window.Android.getUpdateStateJson();
      if (raw) {
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed.type === 'string') {
          lastUpdaterState = parsed;
        }
      }
    }
  } catch (e) {
    warn('updater.getState', e);
  }
  return lastUpdaterState;
}

function isNativeUpdateButtonVisible() {
  if (typeof document === 'undefined') return false;
  var btn = document.querySelector('[data-testid="app-update-button"]');
  if (!btn) return false;
  if (btn.offsetWidth <= 0 || btn.offsetHeight <= 0) return false;
  try {
    var st = window.getComputedStyle(btn);
    if (st && (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0')) {
      return false;
    }
  } catch (e) {}
  return true;
}

function renderMobileUpdatePill() {
  if (typeof document === 'undefined' || !document.body) return;
  var existing = document.getElementById('agy-mobile-update-pill');
  var st = lastUpdaterState || { type: 'idle' };
  var readyVer = st.type === 'ready' && st.update && st.update.version ? String(st.update.version) : '';

  if (!readyVer || dismissedBannerVersion === readyVer || isNativeUpdateButtonVisible()) {
    if (existing && existing.parentNode) {
      existing.parentNode.removeChild(existing);
    }
    return;
  }

  if (!existing) {
    existing = document.createElement('div');
    existing.id = 'agy-mobile-update-pill';
    existing.setAttribute('role', 'region');
    existing.setAttribute('aria-label', 'Application update ready');
    existing.className = 'bg-popover text-popover-foreground border border-border rounded-xl shadow-lg';
    existing.style.cssText = [
      'position:fixed',
      'top:54px',
      'left:12px',
      'right:12px',
      'max-width:440px',
      'margin:0 auto',
      'z-index:9998',
      'padding:10px 12px',
      'display:flex',
      'align-items:center',
      'justify-content:space-between',
      'gap:10px',
      'border-radius:12px',
      'box-shadow:0 10px 28px rgba(0,0,0,0.38)',
      'backdrop-filter:blur(10px)'
    ].join(';');

    var left = document.createElement('div');
    left.style.cssText = 'display:flex;align-items:center;gap:9px;min-width:0;flex:1;';

    var dot = document.createElement('span');
    dot.style.cssText = 'width:8px;height:8px;border-radius:9999px;background:#3b82f6;flex-shrink:0;box-shadow:0 0 0 3px rgba(59,130,246,0.22);';

    var textCol = document.createElement('div');
    textCol.style.cssText = 'display:flex;flex-direction:column;min-width:0;';

    var titleEl = document.createElement('span');
    titleEl.id = 'agy-mobile-update-pill-title';
    titleEl.style.cssText = 'font-size:13px;font-weight:600;line-height:1.25;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';

    var subEl = document.createElement('span');
    subEl.style.cssText = 'font-size:11px;opacity:0.75;line-height:1.25;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
    subEl.textContent = 'Downloaded & verified · keeps all data';

    textCol.appendChild(titleEl);
    textCol.appendChild(subEl);
    left.appendChild(dot);
    left.appendChild(textCol);

    var right = document.createElement('div');
    right.style.cssText = 'display:flex;align-items:center;gap:6px;flex-shrink:0;';

    var installBtn = document.createElement('button');
    installBtn.type = 'button';
    installBtn.setAttribute('data-testid', 'agy-mobile-update-install');
    installBtn.style.cssText = [
      'background:#3b82f6',
      'color:#ffffff',
      'border:none',
      'border-radius:9999px',
      'padding:6px 12px',
      'font-size:12px',
      'font-weight:600',
      'line-height:1.2',
      'cursor:pointer'
    ].join(';');
    installBtn.textContent = 'Update';
    installBtn.addEventListener('click', function() {
      window.electronUpdater.applyUpdate();
    });

    var closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.setAttribute('aria-label', 'Dismiss update banner');
    closeBtn.style.cssText = [
      'background:transparent',
      'color:inherit',
      'opacity:0.65',
      'border:none',
      'border-radius:6px',
      'padding:4px 6px',
      'font-size:14px',
      'line-height:1',
      'cursor:pointer'
    ].join(';');
    closeBtn.textContent = '\u2715';
    closeBtn.addEventListener('click', function() {
      var curVer = existing.getAttribute('data-version') || readyVer;
      dismissedBannerVersion = curVer;
      if (existing.parentNode) {
        existing.parentNode.removeChild(existing);
      }
    });

    right.appendChild(installBtn);
    right.appendChild(closeBtn);
    existing.appendChild(left);
    existing.appendChild(right);
    document.body.appendChild(existing);
  }

  setAttrIfChanged(existing, 'data-version', readyVer);
  var tNode = document.getElementById('agy-mobile-update-pill-title');
  setTextIfChanged(tNode, 'Update v' + readyVer + ' ready');
}

function renderSettingsUpdatesSection() {
  if (typeof document === 'undefined') return;
  var anchor = document.getElementById('agent-permissions');
  if (!anchor || !anchor.parentElement) return;
  var container = anchor.parentElement;

  var sec = document.getElementById('agy-settings-updates-section');
  if (!sec) {
    sec = document.createElement('div');
    sec.id = 'agy-settings-updates-section';
    sec.className = 'space-y-2';

    var hdrWrap = document.createElement('div');
    hdrWrap.className = 'flex flex-col gap-1';
    var hdrRow = document.createElement('div');
    hdrRow.className = 'flex items-center justify-between';
    var h3 = document.createElement('h3');
    h3.className = 'm-0 font-medium';
    h3.textContent = 'Application Updates';
    hdrRow.appendChild(h3);
    hdrWrap.appendChild(hdrRow);

    var card = document.createElement('div');
    card.className = 'agy-card bg-transparent rounded-xl border border-border overflow-hidden divide-y divide-border';

    var row = document.createElement('div');
    row.className = 'py-2.5 px-3 w-full bg-transparent flex justify-between gap-4 items-center';

    var left = document.createElement('div');
    left.className = 'flex-1 flex flex-col gap-0.5';

    var titleLine = document.createElement('div');
    titleLine.className = 'flex items-center gap-1.5 flex-wrap';

    var labelSpan = document.createElement('span');
    labelSpan.textContent = 'Antigravity for Android';

    var badgeSpan = document.createElement('span');
    badgeSpan.id = 'agy-settings-updates-badge';
    badgeSpan.className = 'text-[10px] font-semibold leading-none bg-secondary text-secondary-foreground px-1.5 py-0.5 rounded-full select-none border border-border';

    titleLine.appendChild(labelSpan);
    titleLine.appendChild(badgeSpan);

    var descSpan = document.createElement('span');
    descSpan.id = 'agy-settings-updates-desc';
    descSpan.className = 'text-sm text-muted-foreground';

    left.appendChild(titleLine);
    left.appendChild(descSpan);

    var right = document.createElement('div');
    right.className = 'shrink-0';

    var actionBtn = document.createElement('button');
    actionBtn.id = 'agy-settings-updates-btn';
    actionBtn.type = 'button';
    actionBtn.setAttribute('data-testid', 'agy-settings-updates-btn');
    actionBtn.addEventListener('click', function() {
      var stNow = lastUpdaterState || { type: 'idle' };
      if (stNow.type === 'ready') {
        window.electronUpdater.applyUpdate();
      } else if (stNow.type !== 'checking for updates' && stNow.type !== 'downloading') {
        window.electronUpdater.checkForUpdates();
      }
    });

    right.appendChild(actionBtn);
    row.appendChild(left);
    row.appendChild(right);
    card.appendChild(row);
    sec.appendChild(hdrWrap);
    sec.appendChild(card);

    container.insertBefore(sec, container.firstChild);
  } else if (sec.parentElement !== container) {
    container.insertBefore(sec, container.firstChild);
  }

  var installedVer = getInstalledAppVersion();
  var st = lastUpdaterState || { type: 'idle' };
  var targetVer = st.update && st.update.version ? String(st.update.version) : '';

  var badge = document.getElementById('agy-settings-updates-badge');
  var desc = document.getElementById('agy-settings-updates-desc');
  var btn = document.getElementById('agy-settings-updates-btn');
  if (!badge || !desc || !btn) return;

  if (st.type === 'ready' && targetVer) {
    setTextIfChanged(badge, 'v' + installedVer + ' \u2192 v' + targetVer);
    setTextIfChanged(desc, 'Update v' + targetVer + ' is downloaded and ready to install.');
    if (btn.disabled !== false) btn.disabled = false;
    setTextIfChanged(btn, 'Restart to Update');
    setClassIfChanged(btn, 'px-3 py-1.5 rounded-full text-xs font-semibold cursor-pointer border-none');
    setCssIfChanged(btn, 'background:#3b82f6;color:#ffffff;border:none;border-radius:9999px;padding:6px 13px;font-size:12px;font-weight:600;cursor:pointer;');
  } else if (st.type === 'downloading' || st.type === 'available for download') {
    setTextIfChanged(badge, 'v' + installedVer);
    setTextIfChanged(desc, 'Downloading update' + (targetVer ? ' v' + targetVer : '') + ' in background\u2026');
    if (btn.disabled !== true) btn.disabled = true;
    setTextIfChanged(btn, 'Downloading\u2026');
    setClassIfChanged(btn, 'px-3 py-1.5 rounded-full text-xs font-medium bg-secondary text-muted-foreground border border-border opacity-70');
    setCssIfChanged(btn, '');
  } else if (st.type === 'checking for updates') {
    setTextIfChanged(badge, 'v' + installedVer);
    setTextIfChanged(desc, 'Checking GitHub Releases for updates\u2026');
    if (btn.disabled !== true) btn.disabled = true;
    setTextIfChanged(btn, 'Checking\u2026');
    setClassIfChanged(btn, 'px-3 py-1.5 rounded-full text-xs font-medium bg-secondary text-muted-foreground border border-border opacity-70');
    setCssIfChanged(btn, '');
  } else {
    setTextIfChanged(badge, 'v' + installedVer);
    setTextIfChanged(desc, 'Automatic GitHub Releases OTA updates enabled.');
    if (btn.disabled !== false) btn.disabled = false;
    setTextIfChanged(btn, 'Check for Updates');
    setClassIfChanged(btn, 'px-3 py-1.5 rounded-full text-xs font-medium bg-secondary text-secondary-foreground hover:opacity-90 border border-border cursor-pointer');
    setCssIfChanged(btn, '');
  }
}

function scheduleUpdaterDomSync() {
  if (updaterDomSyncQueued || typeof window === 'undefined') return;
  updaterDomSyncQueued = true;
  var raf = window.requestAnimationFrame || function(fn) { return setTimeout(fn, 16); };
  raf(function() {
    updaterDomSyncQueued = false;
    try {
      renderMobileUpdatePill();
      renderSettingsUpdatesSection();
    } catch (e) {
      warn('updater.domSync', e);
    }
  });
}

readNativeUpdaterState();

window.__agOnUpdaterStateChanged = function(nextState) {
  try {
    var parsed = typeof nextState === 'string' ? JSON.parse(nextState) : nextState;
    if (!parsed || typeof parsed.type !== 'string') {
      return;
    }
    lastUpdaterState = parsed;
    var snapshot = updaterListeners.slice();
    for (var i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i](parsed);
      } catch (cbErr) {
        warn('updater.listener', cbErr);
      }
    }
    scheduleUpdaterDomSync();
  } catch (e) {
    warn('updater.onStateChanged', e);
  }
};

window.electronUpdater = {
  onStateChanged: function(callback) {
    if (typeof callback !== 'function') {
      return function() {};
    }
    updaterListeners.push(callback);
    var cur = readNativeUpdaterState();
    if (cur && cur.type && cur.type !== 'idle') {
      Promise.resolve().then(function() {
        try {
          callback(cur);
        } catch (e) {}
      });
    }
    scheduleUpdaterDomSync();
    return function() {
      var idx = updaterListeners.indexOf(callback);
      if (idx >= 0) {
        updaterListeners.splice(idx, 1);
      }
    };
  },
  getState: function() {
    var st = readNativeUpdaterState();
    scheduleUpdaterDomSync();
    return Promise.resolve(st);
  },
  checkForUpdates: function() {
    try {
      if (window.Android && typeof window.Android.checkForAppUpdates === 'function') {
        window.Android.checkForAppUpdates(true);
      }
    } catch (e) {
      warn('updater.checkForUpdates', e);
    }
    return Promise.resolve();
  },
  applyUpdate: function() {
    try {
      if (window.Android && typeof window.Android.applyAppUpdate === 'function') {
        window.Android.applyAppUpdate();
      }
    } catch (e) {
      warn('updater.applyUpdate', e);
    }
    return Promise.resolve();
  },
  quitAndInstall: function() {
    try {
      if (window.Android && typeof window.Android.applyAppUpdate === 'function') {
        window.Android.applyAppUpdate();
      }
    } catch (e) {
      warn('updater.quitAndInstall', e);
    }
    return Promise.resolve();
  }
};

function installUpdaterDomWatchers() {
  if (typeof document === 'undefined' || typeof window === 'undefined') return;
  if (!document.body) {
    document.addEventListener('DOMContentLoaded', installUpdaterDomWatchers, { once: true });
    return;
  }
  scheduleUpdaterDomSync();
  window.addEventListener('resize', scheduleUpdaterDomSync, { passive: true });
  window.addEventListener('agy:host-resume', function() {
    readNativeUpdaterState();
    scheduleUpdaterDomSync();
  });
  document.addEventListener('visibilitychange', function() {
    if (document.visibilityState === 'visible') {
      readNativeUpdaterState();
      scheduleUpdaterDomSync();
    }
  });
  if (typeof MutationObserver !== 'undefined') {
    var obs = new MutationObserver(function(mutations) {
      if (!mutations || mutations.length === 0) return;
      var hasExternalChange = false;
      for (var i = 0; i < mutations.length; i++) {
        if (!isOwnedUpdaterNode(mutations[i].target)) {
          hasExternalChange = true;
          break;
        }
      }
      if (hasExternalChange) {
        scheduleUpdaterDomSync();
      }
    });
    obs.observe(document.body, { childList: true, subtree: true });
  }
}

installUpdaterDomWatchers();

setTimeout(function() {
  try {
    if (window.Android && typeof window.Android.checkForAppUpdates === 'function') {
      window.Android.checkForAppUpdates(false);
    }
  } catch (e) {}
}, 9000);

setInterval(function() {
  try {
    if (window.Android && typeof window.Android.checkForAppUpdates === 'function') {
      window.Android.checkForAppUpdates(false);
    }
  } catch (e) {}
}, 60 * 60 * 1000);
