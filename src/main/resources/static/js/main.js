async function load() {
  let r;
  try {
    r = await fetchConfig('/api/config');
  } catch (e) {
    updateFlowDiagram();
    updateVisualizer();
    updateStatusSummary();
    return;
  }
  const d = await readJsonResponse(r);
  if (r.ok) {
    setAuthPanelVisible(false, 'unlock');
    clearAuthStatus();
    applyConfigData(d);
    loadCompanionState();
    loadLogs();
    return;
  }

  if (r.status === 401) {
    const str = STRINGS[currentLang];
    setAuthStatus(str.authInvalid, 'err');
    setStatus(str.error + str.authInvalid, 'err');
    return;
  }

  if (r.status === 428 && d.config_password_setup_required) {
    setAuthPanelVisible(true, 'setup');
    setAuthStatus(STRINGS[currentLang].authSetupRequired, 'err');
    setStatus(STRINGS[currentLang].authSetupRequired, 'err');
    return;
  }

  throw new Error((d.errors || [d.error || 'Failed to load config']).join('; '));
}

document.getElementById('f').cache_mode.addEventListener('change', updateCacheModeOptions);
document.querySelector('[name="memory_enabled"]').addEventListener('change', updateCompanionVisibility);
document.getElementById('autosave-toggle').addEventListener('change', e => {
  autoSaveEnabled = e.target.checked;
  localStorage.setItem('kiyomizu-autosave-enabled', autoSaveEnabled ? 'true' : 'false');
  refreshAutoSaveUi();
  if (autoSaveEnabled) {
    scheduleAutoSave({ target: document.getElementById('f').preset });
  }
});
document.getElementById('btn-auth-unlock').addEventListener('click', async () => {
  const input = document.getElementById('config-auth-password');
  const confirmInput = document.getElementById('config-auth-password-confirm');
  const str = STRINGS[currentLang];
  const password = input.value.trim();

  if (configAuthMode === 'setup') {
    const confirmPassword = confirmInput.value.trim();
    if (!password || password !== confirmPassword) {
      setAuthStatus(!password ? str.authSetupRequired : str.authSetupMismatch, 'err');
      return;
    }

    let r;
    let d;
    try {
      r = await fetch('/api/config/password', {
        method: 'POST',
        headers: {'content-type':'application/json'},
        body: JSON.stringify({ password, confirm_password: confirmPassword })
      });
      d = await readJsonResponse(r);
    } catch (e) {
      setAuthStatus(e && e.message ? e.message : str.authSetupRequired, 'err');
      return;
    }
    if (r.ok) {
      persistConfigPassword(password);
      input.value = '';
      confirmInput.value = '';
      await load();
      clearAuthStatus();
      setStatus(str.authSetupSaved, 'success', 4000);
      return;
    }

    if (r.status === 409) {
      setAuthPanelVisible(true, 'unlock');
    }
    const errors = d.errors || [
      (r.status === 403 && d.config_password_setup_required)
        ? str.authRemoteSetupDisabled
        : (d.error || str.authSetupRequired)
    ];
    setAuthStatus(errors.join('; '), 'err');
    return;
  }

  persistConfigPassword(password);
  await load();
  if (configAuthRequired) {
    setAuthStatus(str.authInvalid, 'err');
  }
});

document.getElementById('config-auth-password').addEventListener('keydown', async e => {
  if (e.key === 'Enter') {
    e.preventDefault();
    document.getElementById('btn-auth-unlock').click();
  }
});

document.getElementById('config-auth-password-confirm').addEventListener('keydown', async e => {
  if (e.key === 'Enter') {
    e.preventDefault();
    document.getElementById('btn-auth-unlock').click();
  }
});

document.querySelectorAll('.password-toggle').forEach(button => {
  const input = getPasswordToggleTarget(button);
  if (!input) return;
  updatePasswordToggleButton(button);
  button.addEventListener('click', () => {
    const selectionStart = input.selectionStart;
    const selectionEnd = input.selectionEnd;
    const masked = input.dataset.masked === 'true';
    if (masked) {
      delete input.dataset.masked;
    } else {
      input.dataset.masked = 'true';
    }
    updatePasswordToggleButton(button);
    input.focus();
    if (selectionStart !== null && selectionEnd !== null) {
      input.setSelectionRange(selectionStart, selectionEnd);
    }
  });
});

document.getElementById('btn-change-config-password').addEventListener('click', async () => {
  const f = document.getElementById('f');
  const str = STRINGS[currentLang];
  const currentPassword = f.config_password_current.value.trim();
  const newPassword = f.config_password_new.value.trim();
  const confirmPassword = f.config_password_confirm.value.trim();

  if (!currentPassword || !newPassword || newPassword !== confirmPassword) {
    setStatus(str.error + (!currentPassword || !newPassword ? str.authSetupRequired : str.authSetupMismatch), 'err');
    return;
  }

  const r = await fetchConfig('/api/config/password/change', {
    method: 'POST',
    headers: {'content-type':'application/json'},
    body: JSON.stringify({
      current_password: currentPassword,
      new_password: newPassword,
      confirm_password: confirmPassword
    })
  });
  const d = await r.json();

  if (r.ok) {
    persistConfigPassword(newPassword);
    f.config_password_current.value = '';
    f.config_password_new.value = '';
    f.config_password_confirm.value = '';
    setStatus(str.passwordChangeSaved, 'success', 4000);
    await load();
    return;
  }

  const errors = d.errors || [d.error || str.authInvalid];
  setStatus(str.error + errors.join('; '), 'err');
});

// Global form change listener to keep widgets in perfect sync
document.getElementById('f').addEventListener('change', e => {
  updateVisualizer();
  updateFlowDiagram();
  updateStatusSummary();
  scheduleAutoSave(e);
});

document.getElementById('f').addEventListener('input', e => {
  updateFlowDiagram();
  updateStatusSummary();
  scheduleAutoSave(e);
});

document.getElementById('f').addEventListener('submit', async e => {
  e.preventDefault();
  clearTimeout(autoSaveTimer);
  await saveConfig({ manual: true });
});

initCollapsibles();
initNav();
setLang(currentLang);
load();
