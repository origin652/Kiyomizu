// Memories pane: filter / paginate / edit / delete / export / import graph memory.
// Runtime fields (stability/strength/access_count/timestamps) are read-only per FSRS design.

const MEMORY_KINDS = [
  'identity', 'preference', 'relationship', 'project_fact', 'episodic_event',
  'working_memory', 'self', 'material', 'person'
];

let memoriesState = {
  page: 0,
  pageSize: 50,
  total: 0,
  selectedId: null,
  selectedNode: null,
  neighbors: null,
  visNetwork: null,
  filterTimer: null
};

function memStr(key, fallback) {
  const s = STRINGS[currentLang];
  return (s && s[key]) || fallback || key;
}

function populateMemoryKinds() {
  const sel = document.getElementById('memories-filter-kind');
  if (!sel) return;
  if (sel.options.length > 1) return;
  MEMORY_KINDS.forEach(k => {
    const o = document.createElement('option');
    o.value = k; o.textContent = k;
    sel.appendChild(o);
  });
}

function memoryQueryParams() {
  return new URLSearchParams({
    q: document.getElementById('memories-filter-q').value.trim(),
    uri_prefix: document.getElementById('memories-filter-uri').value.trim(),
    kind: document.getElementById('memories-filter-kind').value,
    status: document.getElementById('memories-filter-status').value,
    disclosure: document.getElementById('memories-filter-disclosure').value,
    limit: String(memoriesState.pageSize),
    offset: String(memoriesState.page * memoriesState.pageSize),
    total: '1'
  });
}

async function loadMemories() {
  populateMemoryKinds();
  const params = memoryQueryParams();
  const r = await fetchConfig('/api/companion/memories?' + params.toString());
  if (!r.ok) { renderMemoriesError(r.status); return; }
  const data = await r.json();
  memoriesState.total = typeof data.total === 'number' ? data.total : (memoriesState.page * memoriesState.pageSize + (data.memories || []).length);
  renderMemoriesTable(data.memories || []);
  renderMemoriesPager();
}

function renderMemoriesError(status) {
  const tb = document.getElementById('memories-tbody');
  if (!tb) return;
  tb.innerHTML = `<tr><td colspan="6" class="empty">Load failed (HTTP ${status}).</td></tr>`;
}

function renderMemoriesTable(memories) {
  const tb = document.getElementById('memories-tbody');
  if (!tb) return;
  if (!memories.length) {
    tb.innerHTML = `<tr><td colspan="6" class="empty">${memStr('memEmpty', 'No memories loaded.')}</td></tr>`;
    return;
  }
  tb.innerHTML = memories.map(m => {
    const sel = m.id === memoriesState.selectedId ? ' is-selected' : '';
    const content = escapeHtml((m.content || '').slice(0, 120));
    const uri = escapeHtml(m.uri || '');
    const stable = typeof m.stability === 'number' ? m.stability.toFixed(2) : '—';
    const pinIcon = m.pinned ? '📌' : '';
    const pinTitle = m.pinned
      ? (m.always_inject ? 'pinned + always-inject' : 'pinned (click to unpin)')
      : 'click to pin';
    return `<tr class="mem-row${sel}" data-id="${m.id}" onclick="selectMemory(${m.id})">
      <td class="mem-col-uri" title="${uri}">${uri}</td>
      <td>${escapeHtml(m.kind || '')}</td>
      <td class="mem-col-content">${content}</td>
      <td>${escapeHtml(m.status || '')}</td>
      <td>${stable}</td>
      <td class="mem-col-pin" title="${pinTitle}"><span class="mem-pin-toggle" onclick="event.stopPropagation(); togglePin(${m.id}, ${m.pinned ? 'true' : 'false'})">${pinIcon}</span></td>
    </tr>`;
  }).join('');
}

async function togglePin(id, currentlyPinned) {
  const r = await fetchConfig('/api/companion/memories/' + id, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ pinned: !currentlyPinned })
  });
  const data = await r.json();
  if (r.ok) {
    await loadMemories();
    if (memoriesState.selectedId === id) {
      memoriesState.selectedNode = data.memory;
      renderMemoryDetail();
    }
  } else {
    setMemoriesStatus(data.error || 'Pin toggle failed');
  }
}

function renderMemoriesPager() {
  const info = document.getElementById('memories-page-info');
  const start = memoriesState.total > 0 ? memoriesState.page * memoriesState.pageSize + 1 : 0;
  const end = Math.min((memoriesState.page + 1) * memoriesState.pageSize, memoriesState.total);
  if (info) info.textContent = `${start}-${end} / ${memoriesState.total}`;
  const prev = document.getElementById('t-mem-prev');
  const next = document.getElementById('t-mem-next');
  if (prev) prev.disabled = memoriesState.page === 0;
  if (next) next.disabled = end >= memoriesState.total;
}

function memoriesPagePrev() { if (memoriesState.page > 0) { memoriesState.page--; loadMemories(); } }
function memoriesPageNext() { memoriesState.page++; loadMemories(); }

function scheduleMemoriesFilter() {
  clearTimeout(memoriesState.filterTimer);
  memoriesState.filterTimer = setTimeout(() => { memoriesState.page = 0; loadMemories(); }, 300);
}

async function selectMemory(id) {
  memoriesState.selectedId = id;
  document.querySelectorAll('.mem-row').forEach(r => r.classList.toggle('is-selected', r.dataset.id === String(id)));
  const r = await fetchConfig('/api/companion/memories/' + id);
  if (!r.ok) { renderMemoriesDetailError(r.status); return; }
  const data = await r.json();
  memoriesState.selectedNode = data.memory;
  await loadMemoryNeighbors(id);
  renderMemoryDetail();
}

async function loadMemoryNeighbors(id) {
  const r = await fetchConfig('/api/companion/memories/' + id + '/neighbors');
  if (!r.ok) { memoriesState.neighbors = null; return; }
  memoriesState.neighbors = await r.json();
  renderMemoryGraph();
}

function renderMemoriesDetailError(status) {
  const d = document.getElementById('memories-detail');
  if (d) d.innerHTML = `<div class="empty">Load failed (HTTP ${status}).</div>`;
}

function renderMemoryDetail() {
  const d = document.getElementById('memories-detail');
  if (!d || !memoriesState.selectedNode) return;
  const m = memoriesState.selectedNode;
  const list = (arr) => (arr || []).join(', ');
  d.innerHTML = `
    <div class="mem-detail-head">
      <div class="mem-detail-uri" title="${escapeHtml(m.uri)}">${escapeHtml(m.uri)}</div>
      <div class="mem-detail-actions">
        <button class="btn btn-ghost" onclick="saveMemoryEdit()">Save</button>
        <button class="btn btn-ghost" onclick="softDeleteMemory()">Soft delete</button>
        <button class="btn btn-ghost" onclick="restoreMemory()">Restore</button>
        <button class="btn btn-danger-ghost" onclick="purgeMemory()">Purge (permanent)</button>
      </div>
    </div>
    <div class="mem-edit-grid">
      <label>Content<textarea id="mem-edit-content" rows="3">${escapeHtml(m.content || '')}</textarea></label>
      <label>Kind<input type="text" id="mem-edit-kind" value="${escapeHtml(m.kind || '')}"></label>
      <label>Status<input type="text" id="mem-edit-status" value="${escapeHtml(m.status || '')}"></label>
      <label>Disclosure<input type="text" id="mem-edit-disclosure" value="${escapeHtml(m.disclosure || '')}"></label>
      <label>Priority<input type="number" step="0.01" min="0" max="1" id="mem-edit-priority" value="${m.priority}"></label>
      <label>Confidence<input type="number" step="0.01" min="0" max="1" id="mem-edit-confidence" value="${m.confidence}"></label>
      <label>Person URI<input type="text" id="mem-edit-person" value="${escapeHtml(m.person_uri || '')}"></label>
      <label>Project URI<input type="text" id="mem-edit-project" value="${escapeHtml(m.project_uri || '')}"></label>
      <label>Scope hint<input type="text" id="mem-edit-scope" value="${escapeHtml(m.scope_hint || '')}"></label>
      <label>Source<input type="text" id="mem-edit-source" value="${escapeHtml(m.source || '')}"></label>
      <label>Keywords<input type="text" id="mem-edit-keywords" value="${escapeHtml(list(m.keywords))}"></label>
      <label>Aliases<input type="text" id="mem-edit-aliases" value="${escapeHtml(list(m.aliases))}"></label>
      <label>Entities<input type="text" id="mem-edit-entities" value="${escapeHtml(list(m.entities))}"></label>
      <label>Topics<input type="text" id="mem-edit-topics" value="${escapeHtml(list(m.topics))}"></label>
      <label>Trigger phrases<input type="text" id="mem-edit-triggers" value="${escapeHtml(list(m.trigger_phrases))}"></label>
      <label>Raw evidence<textarea id="mem-edit-raw" rows="2">${escapeHtml(m.raw_evidence || '')}</textarea></label>
      <label class="label-inline"><span>Pinned (设定/锚定)</span><input type="checkbox" id="mem-edit-pinned" ${m.pinned ? 'checked' : ''} onchange="document.getElementById('mem-edit-always-inject').disabled = !this.checked"></label>
      <label class="label-inline"><span>Always inject</span><input type="checkbox" id="mem-edit-always-inject" ${m.always_inject ? 'checked' : ''} ${m.pinned ? '' : 'disabled'}></label>
      <div class="mem-readonly">
        <div><span>stability</span><strong>${typeof m.stability === 'number' ? m.stability.toFixed(2) : '—'}</strong> (read-only)</div>
        <div><span>strength</span><strong>${typeof m.strength === 'number' ? m.strength.toFixed(2) : '—'}</strong></div>
        <div><span>access_count</span><strong>${m.access_count ?? '—'}</strong></div>
        <div><span>updated_at</span><strong>${fmtTs(m.updated_at)}</strong></div>
      </div>
    </div>
    <div class="mem-graph-wrap">
      <div class="eyebrow">Neighbors</div>
      <div id="mem-graph" class="mem-graph"></div>
      <div class="mem-edges-list" id="mem-edges-list"></div>
    </div>
  `;
  renderMemoryGraph();
  renderMemoryEdgesList();
}

function renderMemoryGraph() {
  const container = document.getElementById('mem-graph');
  if (!container || !memoriesState.neighbors || typeof vis === 'undefined' || !vis.Network) {
    return;
  }
  const m = memoriesState.selectedNode;
  const byId = {};
  const nodes = [];
  nodes.push({ id: m.id, label: shortLabel(m.uri), color: { background: '#3b82f6', border: '#1d4ed8' }, font: { color: '#fff' } });
  byId[m.id] = m.uri;
  (memoriesState.neighbors.neighbors || []).forEach(n => {
    byId[n.id] = n.uri;
    nodes.push({ id: n.id, label: shortLabel(n.uri) });
  });
  const edges = (memoriesState.neighbors.edges || []).map(e => ({
    id: e.id,
    from: e.from_id,
    to: e.to_id,
    label: e.relation,
    arrows: 'to'
  }));
  try {
    if (memoriesState.visNetwork) memoriesState.visNetwork.destroy();
    memoriesState.visNetwork = new vis.Network(container, { nodes: new vis.DataSet(nodes), edges: new vis.DataSet(edges) }, {
      nodes: { shape: 'dot', size: 16 },
      edges: { font: { size: 11 }, smooth: false },
      physics: { stabilization: true }
    });
  } catch (e) {
    container.textContent = 'Graph render failed.';
  }
}

function renderMemoryEdgesList() {
  const el = document.getElementById('mem-edges-list');
  if (!el) return;
  const edges = (memoriesState.neighbors && memoriesState.neighbors.edges) || [];
  if (!edges.length) { el.innerHTML = '<div class="empty">No edges.</div>'; return; }
  el.innerHTML = edges.map(e =>
    `<div class="mem-edge-row">
      <span class="mem-edge-relation">${escapeHtml(e.relation)}</span>
      <span class="mem-edge-uri" title="${escapeHtml(e.from_uri)}">${escapeHtml(shortLabel(e.from_uri))}</span>
      → <span class="mem-edge-uri" title="${escapeHtml(e.to_uri)}">${escapeHtml(shortLabel(e.to_uri))}</span>
      <span class="mem-edge-w">w=${e.weight.toFixed ? e.weight.toFixed(2) : e.weight}</span>
      <button class="btn btn-danger-ghost btn-tiny" onclick="deleteMemoryEdge(${e.id})">delete</button>
    </div>`
  ).join('');
}

function collectMemoryEdit() {
  const csv = (id) => (document.getElementById(id)?.value || '').split(',').map(s => s.trim()).filter(Boolean);
  return {
    content: val('mem-edit-content'),
    kind: val('mem-edit-kind'),
    status: val('mem-edit-status'),
    disclosure: val('mem-edit-disclosure'),
    priority: num('mem-edit-priority'),
    confidence: num('mem-edit-confidence'),
    person_uri: val('mem-edit-person'),
    project_uri: val('mem-edit-project'),
    scope_hint: val('mem-edit-scope'),
    source: val('mem-edit-source'),
    keywords: csv('mem-edit-keywords'),
    aliases: csv('mem-edit-aliases'),
    entities: csv('mem-edit-entities'),
    topics: csv('mem-edit-topics'),
    trigger_phrases: csv('mem-edit-triggers'),
    raw_evidence: val('mem-edit-raw'),
    pinned: document.getElementById('mem-edit-pinned')?.checked || false,
    always_inject: document.getElementById('mem-edit-always-inject')?.checked || false
  };
}

async function saveMemoryEdit() {
  if (!memoriesState.selectedId) return;
  const patch = collectMemoryEdit();
  const r = await fetchConfig('/api/companion/memories/' + memoriesState.selectedId, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(patch)
  });
  const data = await r.json();
  if (r.ok) {
    memoriesState.selectedNode = data.memory;
    renderMemoryDetail();
    await loadMemories();
  } else {
    setMemoriesStatus((data.error || 'Save failed (HTTP ' + r.status + ')'));
  }
}

async function softDeleteMemory() {
  if (!memoriesState.selectedId) return;
  const isPinned = memoriesState.selectedNode && memoriesState.selectedNode.pinned;
  const msg = isPinned
    ? '这是钉住的设定，确定要删除吗？ / This is a pinned setting — soft-delete it (archived, restorable)?'
    : 'Soft-delete this memory? (restorable, becomes archived)';
  if (!confirm(msg)) return;
  const r = await fetchConfig('/api/companion/memories/' + memoriesState.selectedId, {
    method: 'DELETE',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ reason: 'manual memory delete' })
  });
  const data = await r.json();
  setMemoriesStatus(data.ok ? 'Deleted (archived).' : (data.error || 'Delete failed'));
  memoriesState.selectedId = null; memoriesState.selectedNode = null; memoriesState.neighbors = null;
  const d = document.getElementById('memories-detail'); if (d) d.innerHTML = `<div class="empty">${memStr('memDetailEmpty', 'Select a memory to edit.')}</div>`;
  await loadMemories();
}

async function restoreMemory() {
  if (!memoriesState.selectedId) return;
  const r = await fetchConfig('/api/companion/memories/' + memoriesState.selectedId + '/restore', { method: 'POST' });
  const data = await r.json();
  setMemoriesStatus(data.ok ? 'Restored to active.' : (data.error || 'Restore failed'));
  await loadMemories();
}

async function purgeMemory() {
  if (!memoriesState.selectedId) return;
  const isPinned = memoriesState.selectedNode && memoriesState.selectedNode.pinned;
  const msg = isPinned
    ? '这是钉住的设定，确定要永久清除吗？此操作不可撤销。 / This is a pinned setting — permanently purge it? This cannot be undone.'
    : 'Permanently purge this memory and its incident edges? This cannot be undone.';
  if (!confirm(msg)) return;
  const r = await fetchConfig('/api/companion/memories/' + memoriesState.selectedId + '/purge', { method: 'POST' });
  const data = await r.json();
  setMemoriesStatus(data.ok ? 'Purged.' : (data.error || 'Purge failed'));
  memoriesState.selectedId = null; memoriesState.selectedNode = null; memoriesState.neighbors = null;
  const d = document.getElementById('memories-detail'); if (d) d.innerHTML = `<div class="empty">${memStr('memDetailEmpty', 'Select a memory to edit.')}</div>`;
  await loadMemories();
}

async function deleteMemoryEdge(edgeId) {
  if (!confirm('Delete this edge permanently?')) return;
  const r = await fetchConfig('/api/companion/memories/edges/' + edgeId, { method: 'DELETE' });
  const data = await r.json();
  if (data.ok && memoriesState.selectedId) {
    await loadMemoryNeighbors(memoriesState.selectedId);
    renderMemoryDetail();
  } else {
    setMemoriesStatus(data.error || 'Edge delete failed');
  }
}

async function exportMemories() {
  const params = memoryQueryParams();
  params.delete('limit'); params.delete('offset'); params.delete('total');
  const url = '/api/companion/memories/export?' + params.toString();
  const r = await fetchConfig(url);
  if (!r.ok) { setMemoriesStatus('Export failed (HTTP ' + r.status + ')'); return; }
  const blob = await r.blob();
  triggerDownload(blob, 'memories-' + Date.now() + '.json');
}

function previewImportMemories(event) {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  const reader = new FileReader();
  reader.onload = async () => {
    let bundle;
    try { bundle = JSON.parse(reader.result); } catch (e) { setMemoriesStatus('Import failed: invalid JSON'); return; }
    const r = await fetchConfig('/api/companion/memories/import/preview', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: reader.result
    });
    const prev = await r.json();
    if (prev.error) { setMemoriesStatus('Preview failed: ' + prev.error); return; }
    const msg = `Replace mode: will clear ${prev.clear_nodes} nodes / ${prev.clear_edges} edges, then import ${prev.import_nodes} nodes / ${prev.import_edges} edges (${prev.skipped_edges} edges skipped — dangling endpoints).`;
    if (!confirm(msg + '\n\nProceed? This is irreversible.')) return;
    const r2 = await fetchConfig('/api/companion/memories/import', {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: reader.result
    });
    const res = await r2.json();
    if (r2.ok) {
      setMemoriesStatus(`Imported: ${res.inserted_nodes} nodes, ${res.inserted_edges} edges, ${res.skipped_edges} skipped.`);
      memoriesState.selectedId = null; memoriesState.selectedNode = null;
      await loadMemories();
    } else {
      setMemoriesStatus(res.error || 'Import failed (HTTP ' + r2.status + ')');
    }
  };
  reader.readAsText(file);
}

// --- create ---
function showCreateMemoryForm() {
  const d = document.getElementById('memories-detail');
  if (!d) return;
  memoriesState.selectedId = null;
  memoriesState.selectedNode = null;
  memoriesState.neighbors = null;
  document.querySelectorAll('.mem-row').forEach(r => r.classList.remove('is-selected'));
  const kindOpts = MEMORY_KINDS.map(k => `<option value="${k}">${k}</option>`).join('');
  d.innerHTML = `
    <div class="mem-detail-head">
      <div class="mem-detail-uri">新建记忆 / New memory</div>
      <div class="mem-detail-actions">
        <button class="btn btn-ghost" onclick="createMemory()">Create</button>
        <button class="btn btn-ghost" onclick="loadMemories(); renderMemoryDetailEmpty();">Cancel</button>
      </div>
    </div>
    <div class="mem-edit-grid">
      <label>Content<textarea id="mem-create-content" rows="3"></textarea></label>
      <label>Kind<select id="mem-create-kind">${kindOpts}</select></label>
      <label>Disclosure<input type="text" id="mem-create-disclosure" value="private"></label>
      <label>Priority<input type="number" step="0.01" min="0" max="1" id="mem-create-priority" value="0.5"></label>
      <label>Confidence<input type="number" step="0.01" min="0" max="1" id="mem-create-confidence" value="0.5"></label>
      <label>Person URI<input type="text" id="mem-create-person" value=""></label>
      <label>Project URI<input type="text" id="mem-create-project" value=""></label>
      <label>Scope hint<input type="text" id="mem-create-scope" value=""></label>
      <label>Source<input type="text" id="mem-create-source" value="user"></label>
      <label>Keywords (comma-separated)<input type="text" id="mem-create-keywords" value=""></label>
      <label>Aliases (comma-separated)<input type="text" id="mem-create-aliases" value=""></label>
      <label>Entities (comma-separated)<input type="text" id="mem-create-entities" value=""></label>
      <label>Topics (comma-separated)<input type="text" id="mem-create-topics" value=""></label>
      <label>Trigger phrases (comma-separated)<input type="text" id="mem-create-triggers" value=""></label>
      <label>Raw evidence<textarea id="mem-create-raw" rows="2"></textarea></label>
      <label class="label-inline"><span>Pinned (设定/锚定)</span><input type="checkbox" id="mem-create-pinned" onchange="document.getElementById('mem-create-always-inject').disabled = !this.checked"></label>
      <label class="label-inline"><span>Always inject</span><input type="checkbox" id="mem-create-always-inject" disabled></label>
    </div>
  `;
}

function renderMemoryDetailEmpty() {
  const d = document.getElementById('memories-detail');
  if (d) d.innerHTML = `<div class="empty">${memStr('memDetailEmpty', 'Select a memory to edit.')}</div>`;
}

function collectMemoryCreate() {
  const csv = (id) => (document.getElementById(id)?.value || '').split(',').map(s => s.trim()).filter(Boolean);
  const pinned = document.getElementById('mem-create-pinned')?.checked || false;
  const alwaysInject = pinned && (document.getElementById('mem-create-always-inject')?.checked || false);
  return {
    content: val('mem-create-content'),
    kind: val('mem-create-kind') || 'preference',
    disclosure: val('mem-create-disclosure') || 'private',
    priority: num('mem-create-priority'),
    confidence: num('mem-create-confidence'),
    person_uri: val('mem-create-person'),
    project_uri: val('mem-create-project'),
    scope_hint: val('mem-create-scope'),
    source: val('mem-create-source') || 'user',
    keywords: csv('mem-create-keywords'),
    aliases: csv('mem-create-aliases'),
    entities: csv('mem-create-entities'),
    topics: csv('mem-create-topics'),
    trigger_phrases: csv('mem-create-triggers'),
    raw_evidence: val('mem-create-raw'),
    pinned,
    always_inject: alwaysInject
  };
}

async function createMemory() {
  const body = collectMemoryCreate();
  if (!body.content) { setMemoriesStatus('Content is required.'); return; }
  const r = await fetchConfig('/api/companion/memories', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await r.json();
  if (r.ok && data.memory) {
    setMemoriesStatus('Created.');
    await loadMemories();
    selectMemory(data.memory.id);
  } else {
    setMemoriesStatus((data && data.error) || 'Create failed (HTTP ' + r.status + ')');
  }
}

// --- helpers ---
function val(id) { return (document.getElementById(id)?.value || '').trim(); }
function num(id) { const v = parseFloat(document.getElementById(id)?.value); return isNaN(v) ? null : v; }
function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
}
function shortLabel(uri) {
  if (!uri) return '';
  const i = uri.lastIndexOf('/'); return i >= 0 ? uri.slice(i + 1) : uri;
}
function fmtTs(ts) {
  if (!ts) return '—';
  try { return new Date(ts * 1000).toISOString().slice(0, 19).replace('T', ' '); } catch (e) { return String(ts); }
}
function triggerDownload(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name;
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
function setMemoriesStatus(msg) {
  const el = document.getElementById('memories-import-result');
  if (el) el.textContent = msg;
}