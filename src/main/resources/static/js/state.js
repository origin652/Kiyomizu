const UPSTREAM_DEFAULTS = {
  anthropic: 'https://api.anthropic.com',
  custom: ''
};

let currentLang = detectLang();
let summaryKeyConfigured = false;
let recallModelKeyConfigured = false;
let configPasswordChangeable = false;
let configAuthRequired = false;
let configAuthMode = 'unlock';
let configAuthPassword = sessionStorage.getItem('kiyomizu-config-password') || '';
let autoSaveEnabled = localStorage.getItem('kiyomizu-autosave-enabled') === 'true';
let autoSaveTimer = null;
let autoSaveInFlight = false;
let autoSavePending = false;
let lastAutoSavePayload = '';
let applyingConfigData = false;
