/**
 * patches/ui_i18n/engine.js - React-safe WeakMap DOM translation & RTL/LTR isolator.
 */

var origTextMap = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
var origAttrMap = typeof WeakMap !== 'undefined' ? new WeakMap() : null;
var TRANSLATED_ATTRS = ['placeholder', 'aria-label', 'title'];
var SKIP_TAGS = {
  SCRIPT: 1,
  STYLE: 1,
  NOSCRIPT: 1,
  SVG: 1,
  PATH: 1,
  CODE: 1,
  PRE: 1,
  KBD: 1,
  SAMP: 1,
  TEXTAREA: 1
};

var isMutatingDom = false;
var i18nObserver = null;
var pendingRoots = [];
var flushScheduled = false;

function getActiveDict(lang) {
  if (lang === 'es') return DICT_ES;
  if (lang === 'ar') return DICT_AR;
  return null;
}

function getActivePatterns(lang) {
  if (lang === 'es') return PATTERNS_ES;
  if (lang === 'ar') return PATTERNS_AR;
  return null;
}

function translateStringForLang(raw, lang) {
  if (!raw || typeof raw !== 'string') return raw;
  var dict = getActiveDict(lang);
  if (!dict) return raw;

  if (Object.prototype.hasOwnProperty.call(dict, raw)) {
    return dict[raw];
  }

  var leadMatch = raw.match(/^\s+/);
  var trailMatch = raw.match(/\s+$/);
  var lead = leadMatch ? leadMatch[0] : '';
  var trail = trailMatch ? trailMatch[0] : '';
  var core = raw.slice(lead.length, raw.length - trail.length);
  if (!core) return raw;

  if (Object.prototype.hasOwnProperty.call(dict, core)) {
    return lead + dict[core] + trail;
  }

  var patterns = getActivePatterns(lang);
  if (patterns) {
    for (var i = 0; i < patterns.length; i++) {
      var re = patterns[i][0];
      var repl = patterns[i][1];
      if (re.test(core)) {
        return lead + core.replace(re, repl) + trail;
      }
    }
  }
  return raw;
}

function translateString(raw) {
  return translateStringForLang(raw, currentLang);
}

window.__agTranslateString = translateString;

function isIconOrCodeAncestor(el) {
  var cur = el;
  var depth = 0;
  while (cur && cur.nodeType === 1 && depth < 12) {
    if (SKIP_TAGS[cur.tagName]) return true;
    if (cur.hasAttribute('data-no-i18n') || cur.getAttribute('translate') === 'no') return true;
    if (cur.hasAttribute('data-icon')) return true;
    if (cur.isContentEditable || cur.getAttribute('contenteditable') === 'true') return true;

    var cls = cur.className;
    if (typeof cls === 'string' && cls) {
      if (
        cls.indexOf('google-symbols') !== -1 ||
        cls.indexOf('material-symbols') !== -1 ||
        cls.indexOf('material-icons') !== -1 ||
        cls.indexOf('codicon') !== -1 ||
        cls.indexOf('monaco-editor') !== -1 ||
        cls.indexOf('xterm') !== -1 ||
        cls.indexOf('cm-editor') !== -1 ||
        cls.indexOf('prose') !== -1 ||
        cls.indexOf('markdown-body') !== -1
      ) {
        return true;
      }
    }

    var tid = cur.getAttribute('data-testid');
    if (tid) {
      if (
        tid === 'user-message' ||
        tid === 'assistant-message' ||
        tid === 'live-user-turn' ||
        tid.indexOf('code-block') !== -1 ||
        tid.indexOf('file-tree-item') !== -1
      ) {
        return true;
      }
    }

    cur = cur.parentNode;
    depth++;
  }
  return false;
}

function processTextNode(node) {
  if (!node || node.nodeType !== 3 || !origTextMap) return;
  var parent = node.parentNode;
  if (!parent || parent.nodeType !== 1) return;
  if (isIconOrCodeAncestor(parent)) return;

  var val = node.data;
  if (!val || !val.trim()) return;

  var rec = origTextMap.get(node);
  var orig;
  if (!rec) {
    orig = val;
  } else if (val === rec.trans) {
    orig = rec.orig;
  } else {
    // React updated the underlying English text dynamically!
    orig = val;
  }

  if (currentLang === 'en') {
    if (rec && node.data !== orig) {
      node.data = orig;
    }
    if (rec) {
      origTextMap.set(node, { orig: orig, trans: orig });
    }
    return;
  }

  var next = translateStringForLang(orig, currentLang);
  if (next !== orig || rec) {
    origTextMap.set(node, { orig: orig, trans: next });
    if (node.data !== next) {
      node.data = next;
    }
  }
}

function processElementAttrs(el) {
  if (!el || el.nodeType !== 1 || !origAttrMap) return;
  if (SKIP_TAGS[el.tagName] || el.hasAttribute('data-no-i18n') || el.getAttribute('translate') === 'no') {
    return;
  }

  var saved = origAttrMap.get(el);
  for (var i = 0; i < TRANSLATED_ATTRS.length; i++) {
    var attr = TRANSLATED_ATTRS[i];
    if (!el.hasAttribute(attr)) continue;
    var curVal = el.getAttribute(attr);
    if (!curVal) continue;

    var rec = saved && saved[attr];
    var orig;
    if (!rec) {
      orig = curVal;
    } else if (curVal === rec.trans) {
      orig = rec.orig;
    } else {
      orig = curVal;
    }

    if (currentLang === 'en') {
      if (rec && curVal !== orig) {
        el.setAttribute(attr, orig);
      }
      if (saved && rec) {
        saved[attr] = { orig: orig, trans: orig };
      }
      continue;
    }

    var next = translateStringForLang(orig, currentLang);
    if (next !== orig || rec) {
      if (!saved) {
        saved = {};
        origAttrMap.set(el, saved);
      }
      saved[attr] = { orig: orig, trans: next };
      if (curVal !== next) {
        el.setAttribute(attr, next);
      }
    }
  }
}

function translateSubtree(root) {
  if (!root || typeof document === 'undefined') return;
  isMutatingDom = true;
  try {
    if (root.nodeType === 3) {
      processTextNode(root);
      return;
    }
    if (root.nodeType !== 1 && root.nodeType !== 9 && root.nodeType !== 11) {
      return;
    }
    if (root.nodeType === 1) {
      processElementAttrs(root);
      if (SKIP_TAGS[root.tagName]) return;
    }

    var tw = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, null, false);
    var n = tw.nextNode();
    while (n) {
      if (n.nodeType === 3) {
        processTextNode(n);
      } else if (n.nodeType === 1) {
        processElementAttrs(n);
      }
      n = tw.nextNode();
    }
  } catch (_) {
  } finally {
    isMutatingDom = false;
  }
}

function applyLangDocumentStyles() {
  if (typeof document === 'undefined' || !document.documentElement) return;
  document.documentElement.setAttribute('lang', currentLang);
  document.documentElement.setAttribute('data-agy-lang', currentLang);

  var styleEl = document.getElementById('agy-i18n-rtl-style');
  if (currentLang === 'ar') {
    if (!styleEl && document.head) {
      styleEl = document.createElement('style');
      styleEl.id = 'agy-i18n-rtl-style';
      styleEl.textContent = [
        'html[data-agy-lang="ar"] body {',
        '  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans Arabic", Tahoma, Arial, sans-serif;',
        '}',
        'html[data-agy-lang="ar"] h1, html[data-agy-lang="ar"] h2, html[data-agy-lang="ar"] h3,',
        'html[data-agy-lang="ar"] p, html[data-agy-lang="ar"] label, html[data-agy-lang="ar"] option,',
        'html[data-agy-lang="ar"] #agy-application-settings-anchor span {',
        '  unicode-bidi: plaintext;',
        '}',
        'html[data-agy-lang="ar"] pre, html[data-agy-lang="ar"] code, html[data-agy-lang="ar"] kbd,',
        'html[data-agy-lang="ar"] .monaco-editor, html[data-agy-lang="ar"] .xterm, html[data-agy-lang="ar"] .cm-editor,',
        'html[data-agy-lang="ar"] [data-no-i18n="1"] {',
        '  direction: ltr !important;',
        '  unicode-bidi: isolate !important;',
        '}'
      ].join('\n');
      document.head.appendChild(styleEl);
    }
  } else if (styleEl && styleEl.parentNode) {
    styleEl.parentNode.removeChild(styleEl);
  }
}

function scheduleFlushPending() {
  if (flushScheduled || typeof window === 'undefined') return;
  flushScheduled = true;
  var raf = window.requestAnimationFrame || function(fn) { return setTimeout(fn, 16); };
  raf(function() {
    flushScheduled = false;
    var roots = pendingRoots.slice();
    pendingRoots.length = 0;
    if (currentLang === 'en') return;
    for (var i = 0; i < roots.length; i++) {
      translateSubtree(roots[i]);
    }
  });
}

function ensureObserverInstalled() {
  if (i18nObserver || typeof MutationObserver === 'undefined' || typeof document === 'undefined' || !document.body) {
    return;
  }
  i18nObserver = new MutationObserver(function(mutations) {
    if (isMutatingDom || currentLang === 'en') return;
    for (var i = 0; i < mutations.length; i++) {
      var m = mutations[i];
      if (m.type === 'characterData') {
        if (m.target) pendingRoots.push(m.target);
      } else if (m.type === 'attributes') {
        if (m.target) processElementAttrs(m.target);
      } else if (m.type === 'childList' && m.addedNodes && m.addedNodes.length > 0) {
        for (var j = 0; j < m.addedNodes.length; j++) {
          var an = m.addedNodes[j];
          if (an && (an.nodeType === 1 || an.nodeType === 3)) {
            pendingRoots.push(an);
          }
        }
      }
    }
    if (pendingRoots.length > 0) {
      scheduleFlushPending();
    }
  });
  i18nObserver.observe(document.body, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: TRANSLATED_ATTRS
  });
}
