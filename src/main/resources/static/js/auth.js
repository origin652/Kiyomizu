function applyAuthPanelCopy() {
  const s = STRINGS[currentLang];
  const setupMode = configAuthMode === 'setup';
  const passwordInput = document.getElementById('config-auth-password');
  const confirmInput = document.getElementById('config-auth-password-confirm');

  document.getElementById('auth-title').textContent = setupMode ? s.authSetupTitle : s.authTitle;
  document.getElementById('auth-body').textContent = setupMode ? s.authSetupBody : s.authBody;
  passwordInput.placeholder = setupMode ? s.authSetupPlaceholder : s.authPlaceholder;
  passwordInput.autocomplete = setupMode ? 'new-password' : 'current-password';
  confirmInput.placeholder = s.authSetupConfirmPlaceholder;
  document.getElementById('btn-auth-unlock').textContent = setupMode ? s.authSetupSave : s.authUnlock;
}
function setAuthPanelVisible(visible, mode = configAuthMode) {
  configAuthRequired = visible;
  configAuthMode = mode;
  const panel = document.getElementById('config-auth-panel');
  panel.classList.toggle('visible', visible);
  panel.classList.toggle('setup', mode === 'setup');
  document.querySelector('.app').classList.toggle('config-locked', visible);
  applyAuthPanelCopy();
}
function setAuthStatus(msg, kind = 'err') {
  const status = document.getElementById('auth-status');
  status.className = 'auth-panel-status ' + (kind === 'success' ? 'success' : 'err') + ' is-visible';
  status.textContent = msg;
}
function clearAuthStatus() {
  const status = document.getElementById('auth-status');
  status.className = 'auth-panel-status';
  status.textContent = '';
}
function persistConfigPassword(value) {
  configAuthPassword = value;
  if (value) {
    sessionStorage.setItem('kiyomizu-config-password', value);
  } else {
    sessionStorage.removeItem('kiyomizu-config-password');
  }
}
function refreshSecretFieldUi() {
  const f = document.getElementById('f');
  const s = STRINGS[currentLang];
  const summaryStatus = document.getElementById('t-comp-summary-key-status');
  if (summaryStatus) {
    summaryStatus.textContent = summaryKeyConfigured ? s.compKeyStored : s.compKeyEmpty;
    summaryStatus.classList.toggle('configured', summaryKeyConfigured);
  }
  if (f.memory_summary_key) {
    f.memory_summary_key.placeholder = summaryKeyConfigured ? s.compKeyPlaceholderStored : s.compKeyPlaceholderEmpty;
  }
  const recallStatus = document.getElementById('t-comp-recall-model-key-status');
  if (recallStatus) {
    recallStatus.textContent = recallModelKeyConfigured ? (s.compRecallKeyStored || 'Recall key stored; leave blank to keep it') : (s.compRecallKeyEmpty || 'No recall key stored; blank inherits summary key');
    recallStatus.classList.toggle('configured', recallModelKeyConfigured);
  }
  if (f.memory_recall_model_key) {
    f.memory_recall_model_key.placeholder = recallModelKeyConfigured
      ? (s.compRecallKeyPlaceholderStored || 'Enter a new recall key to replace it')
      : (s.compRecallKeyPlaceholderEmpty || 'Blank inherits summary key');
  }
}
function refreshPasswordChangeUi() {
  const f = document.getElementById('f');
  const s = STRINGS[currentLang];
  const section = document.getElementById('config-password-section');
  section.classList.toggle('is-disabled', !configPasswordChangeable);
  document.getElementById('t-password-change-body').textContent = configPasswordChangeable ? s.passwordChangeBody : s.passwordChangeDisabledBody;
  document.getElementById('btn-change-config-password').textContent = s.passwordChangeButton;
  f.config_password_current.disabled = !configPasswordChangeable;
  f.config_password_new.disabled = !configPasswordChangeable;
  f.config_password_confirm.disabled = !configPasswordChangeable;
  document.getElementById('btn-change-config-password').disabled = !configPasswordChangeable;
}
function getPasswordToggleTarget(button) {
  const id = button.dataset.togglePassword;
  if (id) return document.getElementById(id);
  const name = button.dataset.togglePasswordName;
  return name ? document.querySelector('[name="' + name + '"]') : null;
}
function updatePasswordToggleButton(button) {
  const input = getPasswordToggleTarget(button);
  if (!input) return;
  const visible = input.dataset.masked !== 'true';
  const label = visible ? STRINGS[currentLang].secretHide : STRINGS[currentLang].secretShow;
  button.classList.toggle('is-visible', visible);
  button.setAttribute('aria-label', label);
  button.title = label;
}
function updatePasswordToggleLabels() {
  document.querySelectorAll('.password-toggle').forEach(updatePasswordToggleButton);
}
function hasConfiguredValue(value) {
  return typeof value === 'string' && value.trim().length > 0;
}
