async function loadLogs() {
  const r = await fetchConfig('/api/logs?limit=50');
  if (!r.ok) return;
  const d = await r.json();
  const logs = d.logs || [];
  renderCacheDiagnostics(logs);
  const tbody = document.getElementById('logs-tbody');
  tbody.textContent = '';

  if (logs.length === 0) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 7;
    td.className = 'empty';
    td.textContent = STRINGS[currentLang].noLogs || 'No requests logged yet.';
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }

  logs.forEach(l => {
    const tr = document.createElement('tr');

    const tdTime = document.createElement('td');
    tdTime.textContent = new Date(l.at).toLocaleTimeString();
    tdTime.title = l.at;

    const tdMethod = document.createElement('td');
    tdMethod.textContent = l.method;

    const tdPath = document.createElement('td');
    tdPath.textContent = l.pathname;
    tdPath.className = 'model-cell';

    const tdPatched = document.createElement('td');
    const tag = document.createElement('span');
    tag.className = l.patched ? 'tag tag-yes' : 'tag tag-no';
    tag.textContent = l.patched ? '✓' : '—';
    tdPatched.appendChild(tag);

    const tdModel = document.createElement('td');
    tdModel.className = 'model-cell';
    tdModel.textContent = l.model ? l.model.split('/').pop() : '—';
    tdModel.title = l.model || '';

    const tdMsgs = document.createElement('td');
    tdMsgs.textContent = l.message_count ?? '—';

    const tdCache = document.createElement('td');
    tdCache.textContent = l.explicit_cache_blocks ?? '—';

    tr.appendChild(tdTime);
    tr.appendChild(tdMethod);
    tr.appendChild(tdPath);
    tr.appendChild(tdPatched);
    tr.appendChild(tdModel);
    tr.appendChild(tdMsgs);
    tr.appendChild(tdCache);
    tbody.appendChild(tr);
  });
}
