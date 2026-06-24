function authHeaders(extra = {}) {
  const headers = { ...extra };
  if (configAuthPassword) {
    headers['x-kiyomizu-config-password'] = configAuthPassword;
  }
  return headers;
}
async function fetchConfig(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: authHeaders(options.headers || {})
  });

  if (response.status === 401) {
    persistConfigPassword('');
    setAuthPanelVisible(true, 'unlock');
  }

  if (response.status === 428) {
    persistConfigPassword('');
    setAuthPanelVisible(true, 'setup');
  }

  return response;
}
async function readJsonResponse(response) {
  const text = await response.text();
  if (!text.trim()) {
    return { error: `Empty response from server (HTTP ${response.status})` };
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    return { error: `Non-JSON response from server (HTTP ${response.status}): ${text.slice(0, 240)}` };
  }
}
