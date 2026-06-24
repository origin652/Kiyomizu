function renderCacheDiagnostics(logs) {
  const tbody = document.getElementById('cache-diagnostics-tbody');
  if (!tbody) return;
  const safeLogs = Array.isArray(logs) ? logs : [];
  const cacheRead = formatTokenTotal(safeLogs, 'cache_read_input_tokens');
  const cachedPrompt = formatTokenTotal(safeLogs, 'cached_prompt_tokens');

  document.getElementById('cache-diag-requests').textContent = String(safeLogs.length);
  document.getElementById('cache-diag-input').textContent = formatTokenTotal(safeLogs, 'input_tokens');
  document.getElementById('cache-diag-read').textContent =
    cachedPrompt === 'unknown' ? cacheRead : `${cacheRead} / cached ${cachedPrompt}`;
  document.getElementById('cache-diag-create').textContent = formatTokenTotal(safeLogs, 'cache_creation_input_tokens');

  tbody.textContent = '';
  if (safeLogs.length === 0) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 6;
    td.className = 'empty';
    td.textContent = STRINGS[currentLang].noCacheDiagnostics || 'No request diagnostics yet.';
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }

  safeLogs.forEach(l => {
    const tr = document.createElement('tr');

    const tdTime = document.createElement('td');
    tdTime.textContent = l.at ? new Date(l.at).toLocaleTimeString() : 'unknown';
    tdTime.title = l.at || '';

    const tdModel = document.createElement('td');
    tdModel.className = 'model-cell';
    tdModel.textContent = l.model ? l.model.split('/').pop() : 'unknown';
    tdModel.title = l.model || '';

    const tdPatch = document.createElement('td');
    tdPatch.className = 'diagnostic-cell';
    const eligible = l.patch_eligible === undefined ? 'unknown' : (l.patch_eligible ? 'eligible' : 'not eligible');
    tdPatch.textContent = `${l.patched ? 'patched' : 'unchanged'} · ${eligible} · ${l.cache_mode || 'unknown'}/${l.cache_strategy || 'unknown'}`;

    const tdBreakpoints = document.createElement('td');
    tdBreakpoints.className = 'diagnostic-mono diagnostic-cell';
    const indexes = Array.isArray(l.cache_breakpoint_indexes) && l.cache_breakpoint_indexes.length > 0
      ? l.cache_breakpoint_indexes.join(',')
      : 'none';
    tdBreakpoints.textContent = `configured=${formatUnknown(l.cache_breakpoints)} explicit=${formatUnknown(l.explicit_cache_blocks)} indexes=${indexes}`;

    const tdUsage = document.createElement('td');
    tdUsage.className = 'diagnostic-mono';
    tdUsage.textContent = `in=${formatUnknown(l.input_tokens)} out=${formatUnknown(l.output_tokens)}`;

    const tdCache = document.createElement('td');
    tdCache.className = 'diagnostic-mono diagnostic-cell';
    tdCache.textContent =
      `read=${formatUnknown(l.cache_read_input_tokens)} create=${formatUnknown(l.cache_creation_input_tokens)} cached_prompt=${formatUnknown(l.cached_prompt_tokens)}`;

    tr.appendChild(tdTime);
    tr.appendChild(tdModel);
    tr.appendChild(tdPatch);
    tr.appendChild(tdBreakpoints);
    tr.appendChild(tdUsage);
    tr.appendChild(tdCache);
    tbody.appendChild(tr);
  });
}
async function loadCacheDiagnostics() {
  const r = await fetchConfig('/api/logs?limit=50');
  if (!r.ok) return;
  const d = await r.json();
  renderCacheDiagnostics(d.logs || []);
}
function formatUnknown(value) {
  return value === null || value === undefined || value === '' ? 'unknown' : String(value);
}
function formatTokenTotal(logs, field) {
  const known = logs
    .map(l => l[field])
    .filter(v => typeof v === 'number' && Number.isFinite(v));
  if (known.length === 0) return 'unknown';
  const total = known.reduce((acc, v) => acc + v, 0);
  const unknownCount = logs.length - known.length;
  return unknownCount > 0 ? `${total} (${unknownCount} unknown)` : String(total);
}
