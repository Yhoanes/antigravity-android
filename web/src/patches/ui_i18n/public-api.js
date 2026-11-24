/**
 * patches/ui_i18n/public-api.js - expose window.__agUiI18n.
 */

window.__agUiI18n = {
  __installed: true,
  getMode: function() { return currentMode; },
  getResolvedLang: function() { return currentLang; },
  setMode: setLanguageMode,
  translate: translateString,
  refresh: function() {
    if (typeof document !== 'undefined' && document.body) {
      translateSubtree(document.body);
      renderSettingsLanguageSection();
    }
  }
};
