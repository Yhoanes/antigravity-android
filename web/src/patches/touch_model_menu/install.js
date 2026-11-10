/**
 * patches/touch_model_menu/install.js - arm the observer on touch devices.
 */
function install() {
  if (state.installed) return;
  state.hasTouch = detectTouch();
  state.installed = true;

  if (!state.hasTouch) {
    console.log('[agTouchModelMenu] no touch input detected; patch is a no-op.');
    return;
  }

  scan();

  var root = document.querySelector(CONFIG.root) || document.body || document.documentElement;
  if (window.MutationObserver && root) {
    state.observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        if (!added || !added.length) continue;
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (n && n.nodeType === 1) {
            if ((n.matches && n.matches(CONFIG.effortGroup)) ||
                (n.querySelector && n.querySelector(CONFIG.effortGroup))) {
              scheduleScan();
              return;
            }
          }
        }
      }
    });
    state.observer.observe(root, { childList: true, subtree: true });
  }
  console.log('[agTouchModelMenu] active (rows=' + state.rows + ').');
}
