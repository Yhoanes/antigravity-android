/**
 * core/backup-bridge.js - mounts the native "Backup & Restore" section inside
 * Settings -> Application (#agy-settings-backup-section) directly beneath
 * #agy-settings-updates-section, backed by com.agy.backup.BackupManager.
 */

var lastBackupState = {
  type: 'idle',
  message: '',
  percent: 0,
  conversations: true,
  skillsPlugins: true,
  projects: true,
  statsReady: false,
  convBytes: 0,
  convFiles: 0,
  skillsBytes: 0,
  skillsFiles: 0,
  projBytes: 0,
  projFiles: 0
};
var backupStatsRequestedOnMount = false;

function formatBackupBytes(bytes) {
  var b = Number(bytes) || 0;
  if (b <= 0) return '0 B';
  if (b < 1024) return b + ' B';
  var kb = b / 1024;
  if (kb < 1024) return kb.toFixed(1) + ' KB';
  var mb = kb / 1024;
  if (mb < 1024) return mb.toFixed(1) + ' MB';
  return (mb / 1024).toFixed(2) + ' GB';
}

function readNativeBackupState() {
  try {
    if (window.Android && typeof window.Android.getBackupStateJson === 'function') {
      var raw = window.Android.getBackupStateJson();
      if (raw) {
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed.type === 'string') {
          lastBackupState = parsed;
        }
      }
    }
  } catch (e) {
    warn('backup.getState', e);
  }
  return lastBackupState;
}

function createScopeCheckbox(id, labelSpanId, labelText, initialChecked, onChange) {
  var lbl = document.createElement('label');
  lbl.setAttribute('for', id);
  lbl.className = 'inline-flex items-center gap-2 text-xs select-none cursor-pointer py-1 pr-2';

  var inp = document.createElement('input');
  inp.type = 'checkbox';
  inp.id = id;
  inp.checked = !!initialChecked;
  inp.style.cssText = 'width:15px;height:15px;accent-color:#3b82f6;cursor:pointer;margin:0;';
  inp.addEventListener('change', onChange);

  var span = document.createElement('span');
  span.id = labelSpanId;
  span.textContent = labelText;

  lbl.appendChild(inp);
  lbl.appendChild(span);
  return lbl;
}

function syncBackupOptionsToNative() {
  var cEl = document.getElementById('agy-backup-opt-conv');
  var sEl = document.getElementById('agy-backup-opt-skills');
  var pEl = document.getElementById('agy-backup-opt-proj');
  var c = cEl ? !!cEl.checked : true;
  var s = sEl ? !!sEl.checked : true;
  var p = pEl ? !!pEl.checked : true;

  lastBackupState.conversations = c;
  lastBackupState.skillsPlugins = s;
  lastBackupState.projects = p;
  if (lastBackupState.type === 'success' || lastBackupState.type === 'error') {
    lastBackupState.type = 'idle';
    lastBackupState.message = '';
    lastBackupState.percent = 0;
  }
  renderSettingsBackupSection();

  try {
    if (window.Android && typeof window.Android.setBackupOptions === 'function') {
      window.Android.setBackupOptions(c, s, p);
    }
  } catch (e) {
    warn('backup.setOptions', e);
  }
}

function renderSettingsBackupSection() {
  if (typeof document === 'undefined') return;
  var container = document.getElementById('agy-application-settings-anchor');
  if (!container) {
    backupStatsRequestedOnMount = false;
    return;
  }
  if (!backupStatsRequestedOnMount) {
    backupStatsRequestedOnMount = true;
    try {
      if (window.Android && typeof window.Android.refreshBackupStats === 'function') {
        window.Android.refreshBackupStats(false);
      }
    } catch (e) {}
  }

  var updatesSec = document.getElementById('agy-settings-updates-section');
  var sec = document.getElementById('agy-settings-backup-section');
  if (!sec) {
    sec = document.createElement('div');
    sec.id = 'agy-settings-backup-section';
    sec.className = 'space-y-2';

    var hdrWrap = document.createElement('div');
    hdrWrap.className = 'flex flex-col gap-1';
    var hdrRow = document.createElement('div');
    hdrRow.className = 'flex items-center justify-between';
    var h3 = document.createElement('h3');
    h3.className = 'm-0 font-medium';
    h3.textContent = 'Backup & Restore';
    hdrRow.appendChild(h3);
    hdrWrap.appendChild(hdrRow);

    var card = document.createElement('div');
    card.className = 'agy-card bg-transparent rounded-xl border border-border overflow-hidden divide-y divide-border';

    // Top row: summary + Export / Restore buttons
    var topRow = document.createElement('div');
    topRow.className = 'py-2.5 px-3 w-full bg-transparent flex flex-wrap justify-between gap-3 items-center';

    var left = document.createElement('div');
    left.className = 'flex-1 min-w-[200px] flex flex-col gap-0.5';

    var titleLine = document.createElement('div');
    titleLine.className = 'flex items-center gap-1.5 flex-wrap';

    var labelSpan = document.createElement('span');
    labelSpan.textContent = 'Portable User Data';

    var badgeSpan = document.createElement('span');
    badgeSpan.id = 'agy-settings-backup-badge';
    badgeSpan.className = 'text-[10px] font-semibold leading-none bg-secondary text-secondary-foreground px-1.5 py-0.5 rounded-full select-none border border-border';
    badgeSpan.textContent = '.zip';

    titleLine.appendChild(labelSpan);
    titleLine.appendChild(badgeSpan);

    var descSpan = document.createElement('span');
    descSpan.id = 'agy-settings-backup-desc';
    descSpan.className = 'text-sm text-muted-foreground';

    var progWrap = document.createElement('div');
    progWrap.id = 'agy-settings-backup-prog-wrap';
    progWrap.style.cssText = 'display:none;width:100%;height:4px;border-radius:9999px;background:rgba(128,128,128,0.2);overflow:hidden;margin-top:4px;';
    var progBar = document.createElement('div');
    progBar.id = 'agy-settings-backup-prog-bar';
    progBar.style.cssText = 'width:0%;height:100%;background:#3b82f6;border-radius:9999px;';
    progWrap.appendChild(progBar);

    left.appendChild(titleLine);
    left.appendChild(descSpan);
    left.appendChild(progWrap);

    var btnGroup = document.createElement('div');
    btnGroup.className = 'flex items-center gap-2 flex-wrap shrink-0';

    var exportBtn = document.createElement('button');
    exportBtn.id = 'agy-settings-backup-export-btn';
    exportBtn.type = 'button';
    exportBtn.setAttribute('data-testid', 'agy-settings-backup-export-btn');
    exportBtn.textContent = 'Export Backup';
    exportBtn.addEventListener('click', function() {
      var cEl = document.getElementById('agy-backup-opt-conv');
      var sEl = document.getElementById('agy-backup-opt-skills');
      var pEl = document.getElementById('agy-backup-opt-proj');
      var c = cEl ? !!cEl.checked : false;
      var s = sEl ? !!sEl.checked : false;
      var p = pEl ? !!pEl.checked : false;
      if (!c && !s && !p) return;
      try {
        if (window.Android && typeof window.Android.exportBackup === 'function') {
          window.Android.exportBackup(c, s, p);
        }
      } catch (e) {
        warn('backup.export', e);
      }
    });

    var restoreBtn = document.createElement('button');
    restoreBtn.id = 'agy-settings-backup-restore-btn';
    restoreBtn.type = 'button';
    restoreBtn.setAttribute('data-testid', 'agy-settings-backup-restore-btn');
    restoreBtn.textContent = 'Restore Backup';
    restoreBtn.addEventListener('click', function() {
      try {
        if (window.Android && typeof window.Android.restoreBackup === 'function') {
          window.Android.restoreBackup();
        }
      } catch (e) {
        warn('backup.restore', e);
      }
    });

    btnGroup.appendChild(exportBtn);
    btnGroup.appendChild(restoreBtn);
    topRow.appendChild(left);
    topRow.appendChild(btnGroup);

    // Bottom row: scope toggles + security notice
    var bottomRow = document.createElement('div');
    bottomRow.className = 'py-2 px-3 w-full bg-transparent flex flex-col gap-1.5';

    var checksWrap = document.createElement('div');
    checksWrap.className = 'flex items-center gap-3 flex-wrap';
    checksWrap.appendChild(createScopeCheckbox(
      'agy-backup-opt-conv',
      'agy-backup-opt-conv-lbl',
      'Conversations & Artifacts',
      lastBackupState.conversations !== false,
      syncBackupOptionsToNative
    ));
    checksWrap.appendChild(createScopeCheckbox(
      'agy-backup-opt-skills',
      'agy-backup-opt-skills-lbl',
      'Skills, Rules & Plugins',
      lastBackupState.skillsPlugins !== false,
      syncBackupOptionsToNative
    ));
    checksWrap.appendChild(createScopeCheckbox(
      'agy-backup-opt-proj',
      'agy-backup-opt-proj-lbl',
      'Projects & Settings',
      lastBackupState.projects !== false,
      syncBackupOptionsToNative
    ));

    var secNote = document.createElement('span');
    secNote.className = 'text-xs text-muted-foreground opacity-80';
    secNote.textContent = 'OAuth sign-in tokens, private keys (.jks, .netrc, SSH/ADB), and scratch caches are excluded.';

    bottomRow.appendChild(checksWrap);
    bottomRow.appendChild(secNote);

    card.appendChild(topRow);
    card.appendChild(bottomRow);
    sec.appendChild(hdrWrap);
    sec.appendChild(card);
  }

  // Ensure #agy-settings-backup-section stays directly after #agy-settings-updates-section
  var validUpdatesSec = (updatesSec && updatesSec.parentElement === container) ? updatesSec : null;
  var desiredNext = validUpdatesSec ? validUpdatesSec.nextSibling : container.firstChild;
  if (sec.parentElement !== container || (validUpdatesSec && sec.previousSibling !== validUpdatesSec)) {
    container.insertBefore(sec, desiredNext);
  }

  var st = lastBackupState || { type: 'idle' };
  var isBusy = st.type === 'exporting' || st.type === 'restoring';

  var descEl = document.getElementById('agy-settings-backup-desc');
  var badgeEl = document.getElementById('agy-settings-backup-badge');
  var expEl = document.getElementById('agy-settings-backup-export-btn');
  var resEl = document.getElementById('agy-settings-backup-restore-btn');
  var pWrap = document.getElementById('agy-settings-backup-prog-wrap');
  var pBar = document.getElementById('agy-settings-backup-prog-bar');
  var cInput = document.getElementById('agy-backup-opt-conv');
  var sInput = document.getElementById('agy-backup-opt-skills');
  var pInput = document.getElementById('agy-backup-opt-proj');
  var cLbl = document.getElementById('agy-backup-opt-conv-lbl');
  var sLbl = document.getElementById('agy-backup-opt-skills-lbl');
  var pLbl = document.getElementById('agy-backup-opt-proj-lbl');

  if (!descEl || !expEl || !resEl) return;

  if (cInput && typeof st.conversations === 'boolean' && cInput.checked !== st.conversations) {
    cInput.checked = st.conversations;
  }
  if (sInput && typeof st.skillsPlugins === 'boolean' && sInput.checked !== st.skillsPlugins) {
    sInput.checked = st.skillsPlugins;
  }
  if (pInput && typeof st.projects === 'boolean' && pInput.checked !== st.projects) {
    pInput.checked = st.projects;
  }

  var cChecked = cInput ? !!cInput.checked : (st.conversations !== false);
  var sChecked = sInput ? !!sInput.checked : (st.skillsPlugins !== false);
  var pChecked = pInput ? !!pInput.checked : (st.projects !== false);
  var noneSelected = !cChecked && !sChecked && !pChecked;

  if (st.statsReady) {
    setTextIfChanged(cLbl, 'Conversations & Artifacts (' + formatBackupBytes(st.convBytes) + ')');
    setTextIfChanged(sLbl, 'Skills, Rules & Plugins (' + formatBackupBytes(st.skillsBytes) + ')');
    setTextIfChanged(pLbl, 'Projects & Settings (' + formatBackupBytes(st.projBytes) + ')');
  } else {
    setTextIfChanged(cLbl, 'Conversations & Artifacts');
    setTextIfChanged(sLbl, 'Skills, Rules & Plugins');
    setTextIfChanged(pLbl, 'Projects & Settings');
  }

  if (isBusy) {
    setTextIfChanged(descEl, st.message || (st.type === 'exporting' ? 'Exporting backup\u2026' : 'Restoring backup\u2026'));
    setClassIfChanged(descEl, 'text-sm text-muted-foreground');
    setTextIfChanged(badgeEl, st.type === 'exporting' ? 'Exporting' : 'Restoring');
  } else if (st.type === 'error' && st.message) {
    setTextIfChanged(descEl, String(st.message));
    setClassIfChanged(descEl, 'text-sm text-destructive');
    setTextIfChanged(badgeEl, 'Error');
  } else if (st.type === 'success' && st.message && !noneSelected) {
    setTextIfChanged(descEl, String(st.message));
    setClassIfChanged(descEl, 'text-sm text-muted-foreground');
    setTextIfChanged(badgeEl, 'Done');
  } else if (noneSelected) {
    setTextIfChanged(descEl, 'Select at least one category below to export.');
    setClassIfChanged(descEl, 'text-sm text-muted-foreground');
    setTextIfChanged(badgeEl, '0 B');
  } else if (st.statsReady) {
    var selBytes = (cChecked ? (Number(st.convBytes) || 0) : 0)
                 + (sChecked ? (Number(st.skillsBytes) || 0) : 0)
                 + (pChecked ? (Number(st.projBytes) || 0) : 0);
    var selFiles = (cChecked ? (Number(st.convFiles) || 0) : 0)
                 + (sChecked ? (Number(st.skillsFiles) || 0) : 0)
                 + (pChecked ? (Number(st.projFiles) || 0) : 0);
    setTextIfChanged(descEl, 'Selected: ' + formatBackupBytes(selBytes) + ' uncompressed (' + selFiles + ' files) \u00b7 compressed into .zip on save.');
    setClassIfChanged(descEl, 'text-sm text-muted-foreground');
    setTextIfChanged(badgeEl, '.zip \u00b7 ' + formatBackupBytes(selBytes));
  } else {
    setTextIfChanged(descEl, 'Calculating category sizes\u2026');
    setClassIfChanged(descEl, 'text-sm text-muted-foreground');
    setTextIfChanged(badgeEl, '.zip');
  }

  if (pWrap && pBar) {
    if (isBusy) {
      var pct = Math.max(2, Math.min(100, Number(st.percent) || 5));
      setCssIfChanged(pWrap, 'display:block;width:100%;height:4px;border-radius:9999px;background:rgba(128,128,128,0.2);overflow:hidden;margin-top:4px;');
      setCssIfChanged(pBar, 'width:' + pct + '%;height:100%;background:#3b82f6;border-radius:9999px;');
    } else {
      setCssIfChanged(pWrap, 'display:none;');
    }
  }

  var btnActiveCls = 'px-3 py-1.5 rounded-full text-xs font-medium bg-secondary text-secondary-foreground hover:opacity-90 border border-border cursor-pointer';
  var btnDisabledCls = 'px-3 py-1.5 rounded-full text-xs font-medium bg-secondary text-muted-foreground border border-border opacity-50 cursor-not-allowed';

  var exportDisabled = isBusy || noneSelected;
  if (expEl.disabled !== exportDisabled) expEl.disabled = exportDisabled;
  if (resEl.disabled !== isBusy) resEl.disabled = isBusy;
  if (cInput && cInput.disabled !== isBusy) cInput.disabled = isBusy;
  if (sInput && sInput.disabled !== isBusy) sInput.disabled = isBusy;
  if (pInput && pInput.disabled !== isBusy) pInput.disabled = isBusy;

  setClassIfChanged(expEl, exportDisabled ? btnDisabledCls : btnActiveCls);
  setClassIfChanged(resEl, isBusy ? btnDisabledCls : btnActiveCls);
  setTextIfChanged(expEl, st.type === 'exporting' ? 'Exporting\u2026' : 'Export Backup');
  setTextIfChanged(resEl, st.type === 'restoring' ? 'Restoring\u2026' : 'Restore Backup');
}

readNativeBackupState();

window.__agOnBackupStateChanged = function(nextState) {
  try {
    var parsed = typeof nextState === 'string' ? JSON.parse(nextState) : nextState;
    if (!parsed || typeof parsed.type !== 'string') return;
    lastBackupState = parsed;
    scheduleUpdaterDomSync();
  } catch (e) {
    warn('backup.onStateChanged', e);
  }
};
