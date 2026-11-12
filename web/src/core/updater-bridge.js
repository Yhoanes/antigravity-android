/**
 * core/updater-bridge.js - exposes window.electronUpdater backed by Android's
 * native AppUpdateManager so Antigravity's native AppUpdateButton ("Restart to
 * Update") and the app menu's "Check for Updates" item work seamlessly.
 */

var updaterListeners = [];
var lastUpdaterState = { type: 'idle' };

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
    return function() {
      var idx = updaterListeners.indexOf(callback);
      if (idx >= 0) {
        updaterListeners.splice(idx, 1);
      }
    };
  },
  getState: function() {
    return Promise.resolve(readNativeUpdaterState());
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
