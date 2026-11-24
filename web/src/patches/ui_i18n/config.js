/**
 * patches/ui_i18n/config.js - configuration, guard, and language resolution.
 */
'use strict';

if (window.__agUiI18n && window.__agUiI18n.__installed) {
  return;
}

var STORAGE_KEY = 'agy_ui_lang';
var SUPPORTED_MODES = { auto: 1, en: 1, es: 1, ar: 1 };

function normalizeLangCode(raw) {
  if (!raw) return 'en';
  var s = String(raw).trim().toLowerCase();
  if (s.indexOf('ar') === 0) return 'ar';
  if (s.indexOf('es') === 0) return 'es';
  return 'en';
}

var BOOT_SYSTEM_LANG = (function() {
  try {
    if (window.__AG_LANG__ === 'auto' && typeof window.__AG_RESOLVED_LANG__ === 'string' && window.__AG_RESOLVED_LANG__) {
      return normalizeLangCode(window.__AG_RESOLVED_LANG__);
    }
  } catch (_) {}
  try {
    var nav0 = (navigator.languages && navigator.languages[0]) || navigator.language || '';
    if (nav0) return normalizeLangCode(nav0);
  } catch (_) {}
  return '';
})();

function detectSystemLang() {
  try {
    if (window.Android && typeof window.Android.getResolvedAppLanguage === 'function') {
      var nat = window.Android.getResolvedAppLanguage();
      if (nat === 'ar' || nat === 'es' || nat === 'en') return nat;
    }
  } catch (_) {}
  if (BOOT_SYSTEM_LANG) return BOOT_SYSTEM_LANG;
  try {
    var nav = (navigator.languages && navigator.languages[0]) || navigator.language || 'en';
    return normalizeLangCode(nav);
  } catch (_) {
    return 'en';
  }
}

function readPersistedMode() {
  try {
    if (window.Android && typeof window.Android.getAppLanguage === 'function') {
      var m = String(window.Android.getAppLanguage() || '').trim().toLowerCase();
      if (SUPPORTED_MODES[m]) return m;
    }
  } catch (_) {}
  try {
    if (typeof window.__AG_LANG__ === 'string' && SUPPORTED_MODES[window.__AG_LANG__]) {
      return window.__AG_LANG__;
    }
  } catch (_) {}
  try {
    var ls = String(localStorage.getItem(STORAGE_KEY) || '').trim().toLowerCase();
    if (SUPPORTED_MODES[ls]) return ls;
  } catch (_) {}
  return 'auto';
}

function resolveActiveLang(mode) {
  var m = SUPPORTED_MODES[mode] ? mode : 'auto';
  if (m === 'auto') return detectSystemLang();
  return m;
}

var currentMode = readPersistedMode();
var currentLang = resolveActiveLang(currentMode);
