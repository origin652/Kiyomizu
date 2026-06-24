function setAutoSaveState(state) {
  const s = STRINGS[currentLang];
  const el = document.getElementById('autosave-state');
  el.className = 'autosave-state';
  if (!autoSaveEnabled && state !== 'err') {
    el.textContent = s.autosaveOff;
    return;
  }
  if (state === 'saving') {
    el.textContent = s.autosaveSaving;
    el.classList.add('saving');
  } else if (state === 'saved') {
    el.textContent = s.autosaveSaved;
    el.classList.add('saved');
  } else if (state === 'err') {
    el.textContent = s.autosaveError;
    el.classList.add('err');
  } else {
    el.textContent = s.autosaveOn;
  }
}
function refreshAutoSaveUi(state = 'idle') {
  const s = STRINGS[currentLang];
  const toggle = document.getElementById('autosave-toggle');
  toggle.checked = autoSaveEnabled;
  toggle.setAttribute('aria-label', s.autosaveToggleLabel);
  document.getElementById('t-autosave-title').textContent = s.autosaveTitle;
  document.getElementById('t-autosave-body').textContent = s.autosaveBody;
  setAutoSaveState(state);
}
function shouldAutoSaveEvent(event) {
  const target = event.target;
  if (!autoSaveEnabled || applyingConfigData || configAuthRequired || !target) return false;
  if (!target.matches('input, select, textarea')) return false;
  if (target.closest('#config-password-section')) return false;

  const manualOnlyNames = new Set([
    'memory_summary_key',
    'clear_memory_summary_key',
    'memory_recall_model_key',
    'clear_memory_recall_model_key',
    'config_password_current',
    'config_password_new',
    'config_password_confirm'
  ]);
  return !manualOnlyNames.has(target.name);
}
function scheduleAutoSave(event) {
  if (!shouldAutoSaveEvent(event)) return;
  if (hasPendingManualOnlyChanges()) return;

  const nextPayload = JSON.stringify(buildConfigPayload(false));
  if (nextPayload === lastAutoSavePayload) return;

  clearTimeout(autoSaveTimer);
  autoSaveTimer = setTimeout(runAutoSave, 900);
}
async function runAutoSave() {
  if (!autoSaveEnabled || configAuthRequired) return;
  if (hasPendingManualOnlyChanges()) return;

  const nextPayload = JSON.stringify(buildConfigPayload(false));
  if (nextPayload === lastAutoSavePayload) return;
  if (autoSaveInFlight) {
    autoSavePending = true;
    return;
  }

  autoSaveInFlight = true;
  autoSavePending = false;
  setAutoSaveState('saving');

  try {
    const ok = await saveConfig({ manual: false });
    if (ok) {
      lastAutoSavePayload = nextPayload;
      setAutoSaveState('saved');
      setTimeout(() => setAutoSaveState('idle'), 1800);
    } else {
      setAutoSaveState('err');
    }
  } finally {
    autoSaveInFlight = false;
    if (autoSavePending) {
      autoSavePending = false;
      scheduleAutoSave({ target: document.getElementById('f').preset });
    }
  }
}
