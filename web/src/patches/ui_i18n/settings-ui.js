/**
 * patches/ui_i18n/settings-ui.js - mounts the "Language" selector inside
 * Settings -> Application (#agy-settings-language-section) and coordinates
 * instant live language switching across React UI and native Android bridges.
 */

var LANG_UI_TEXT = {
  en: {
    sectionTitle: 'Language',
    rowTitle: 'App Language',
    rowDesc: 'Choose the display language for menus, settings, and dialogs.',
    autoLabel: 'Auto (System)'
  },
  es: {
    sectionTitle: 'Idioma',
    rowTitle: 'Idioma de la aplicación',
    rowDesc: 'Elige el idioma de menús, ajustes y cuadros de diálogo.',
    autoLabel: 'Automático (Sistema)'
  },
  ar: {
    sectionTitle: 'اللغة',
    rowTitle: 'لغة التطبيق',
    rowDesc: 'اختر لغة العرض للقوائم والإعدادات ومربعات الحوار.',
    autoLabel: 'تلقائي (النظام)'
  }
};

function getLangUiCopy() {
  return LANG_UI_TEXT[currentLang] || LANG_UI_TEXT.en;
}

function setLanguageMode(nextMode) {
  var m = SUPPORTED_MODES[nextMode] ? nextMode : 'auto';
  var prevResolved = currentLang;

  try {
    localStorage.setItem(STORAGE_KEY, m);
  } catch (_) {}
  try {
    if (window.Android && typeof window.Android.setAppLanguage === 'function') {
      window.Android.setAppLanguage(m);
    }
  } catch (_) {}

  var nextResolved = resolveActiveLang(m);
  currentMode = m;
  currentLang = nextResolved;
  window.__AG_LANG__ = m;
  window.__AG_RESOLVED_LANG__ = nextResolved;

  applyLangDocumentStyles();
  ensureObserverInstalled();

  if (typeof document !== 'undefined' && document.body && (prevResolved !== nextResolved || nextResolved !== 'en')) {
    translateSubtree(document.body);
  }

  renderSettingsLanguageSection();

  try {
    if (typeof window.__agRefreshApplicationBridgeSections === 'function') {
      window.__agRefreshApplicationBridgeSections();
    }
  } catch (_) {}

  try {
    window.dispatchEvent(new CustomEvent('agy:lang-changed', {
      detail: { mode: currentMode, lang: currentLang }
    }));
  } catch (_) {}
}

function renderSettingsLanguageSection() {
  if (typeof document === 'undefined') return;
  var container = document.getElementById('agy-application-settings-anchor');
  if (!container) return;

  var copy = getLangUiCopy();
  var sec = document.getElementById('agy-settings-language-section');
  if (!sec) {
    sec = document.createElement('div');
    sec.id = 'agy-settings-language-section';
    sec.setAttribute('data-no-i18n', '0');
    sec.className = 'space-y-2';

    var hdrWrap = document.createElement('div');
    hdrWrap.className = 'flex flex-col gap-1';
    var hdrRow = document.createElement('div');
    hdrRow.className = 'flex items-center justify-between';
    var h3 = document.createElement('h3');
    h3.id = 'agy-settings-lang-h3';
    h3.className = 'm-0 font-medium';
    hdrRow.appendChild(h3);
    hdrWrap.appendChild(hdrRow);

    var card = document.createElement('div');
    card.className = 'agy-card bg-transparent rounded-xl border border-border overflow-hidden';

    var row = document.createElement('div');
    row.className = 'py-2.5 px-3 w-full bg-transparent flex flex-wrap justify-between gap-3 items-center';

    var left = document.createElement('div');
    left.className = 'flex-1 min-w-[190px] flex flex-col gap-0.5';

    var titleLine = document.createElement('div');
    titleLine.className = 'flex items-center gap-1.5 flex-wrap';

    var labelSpan = document.createElement('span');
    labelSpan.id = 'agy-settings-lang-title';

    var badgeSpan = document.createElement('span');
    badgeSpan.id = 'agy-settings-lang-badge';
    badgeSpan.setAttribute('data-no-i18n', '1');
    badgeSpan.className = 'text-[10px] font-semibold leading-none bg-secondary text-secondary-foreground px-1.5 py-0.5 rounded-full select-none border border-border uppercase';

    titleLine.appendChild(labelSpan);
    titleLine.appendChild(badgeSpan);

    var descSpan = document.createElement('span');
    descSpan.id = 'agy-settings-lang-desc';
    descSpan.className = 'text-sm text-muted-foreground';

    left.appendChild(titleLine);
    left.appendChild(descSpan);

    var right = document.createElement('div');
    right.className = 'shrink-0';

    var sel = document.createElement('select');
    sel.id = 'agy-settings-lang-select';
    sel.setAttribute('data-testid', 'agy-settings-lang-select');
    sel.setAttribute('data-no-i18n', '1');
    sel.className = 'px-3 py-1.5 rounded-lg text-xs font-medium bg-secondary text-secondary-foreground border border-border cursor-pointer outline-none';
    sel.style.cssText = 'min-width:152px;';

    var opts = [
      { value: 'auto', id: 'agy-settings-lang-opt-auto', text: copy.autoLabel },
      { value: 'en', id: 'agy-settings-lang-opt-en', text: 'English' },
      { value: 'es', id: 'agy-settings-lang-opt-es', text: 'Español' },
      { value: 'ar', id: 'agy-settings-lang-opt-ar', text: 'العربية' }
    ];
    for (var i = 0; i < opts.length; i++) {
      var opt = document.createElement('option');
      opt.value = opts[i].value;
      opt.id = opts[i].id;
      opt.textContent = opts[i].text;
      sel.appendChild(opt);
    }

    sel.addEventListener('change', function() {
      setLanguageMode(sel.value);
    });

    right.appendChild(sel);
    row.appendChild(left);
    row.appendChild(right);
    card.appendChild(row);
    sec.appendChild(hdrWrap);
    sec.appendChild(card);

    container.insertBefore(sec, container.firstChild);
  } else if (sec.parentElement !== container || container.firstChild !== sec) {
    container.insertBefore(sec, container.firstChild);
  }

  var h3El = document.getElementById('agy-settings-lang-h3');
  var titleEl = document.getElementById('agy-settings-lang-title');
  var descEl = document.getElementById('agy-settings-lang-desc');
  var badgeEl = document.getElementById('agy-settings-lang-badge');
  var selEl = document.getElementById('agy-settings-lang-select');
  var autoOptEl = document.getElementById('agy-settings-lang-opt-auto');

  isMutatingDom = true;
  try {
    if (h3El && h3El.textContent !== copy.sectionTitle) h3El.textContent = copy.sectionTitle;
    if (titleEl && titleEl.textContent !== copy.rowTitle) titleEl.textContent = copy.rowTitle;
    if (descEl && descEl.textContent !== copy.rowDesc) descEl.textContent = copy.rowDesc;
    if (badgeEl) {
      var bTxt = currentMode === 'auto' ? ('AUTO · ' + currentLang.toUpperCase()) : currentLang.toUpperCase();
      if (badgeEl.textContent !== bTxt) badgeEl.textContent = bTxt;
    }
    if (autoOptEl && autoOptEl.textContent !== copy.autoLabel) {
      autoOptEl.textContent = copy.autoLabel;
    }
    if (selEl && selEl.value !== currentMode) {
      selEl.value = currentMode;
    }
  } finally {
    isMutatingDom = false;
  }
}

function bootUiI18n() {
  applyLangDocumentStyles();
  if (typeof document === 'undefined') return;
  if (!document.body) {
    document.addEventListener('DOMContentLoaded', bootUiI18n, { once: true });
    return;
  }
  ensureObserverInstalled();
  if (currentLang !== 'en') {
    translateSubtree(document.body);
  }
  renderSettingsLanguageSection();

  if (typeof MutationObserver !== 'undefined') {
    var anchorWatcher = new MutationObserver(function() {
      if (document.getElementById('agy-application-settings-anchor') && !document.getElementById('agy-settings-language-section')) {
        renderSettingsLanguageSection();
      }
    });
    anchorWatcher.observe(document.body, { childList: true, subtree: true });
  }
}

bootUiI18n();
