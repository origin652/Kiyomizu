function renderQueueList(containerId, emptyText, rows, renderMeta) {
  const list = document.getElementById(containerId);
  if (!list) return;
  list.textContent = '';
  if (!rows || rows.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = emptyText;
    list.appendChild(empty);
    return;
  }
  rows.forEach(row => {
    const item = document.createElement('div');
    item.className = 'memory-queue-item';
    const content = document.createElement('div');
    content.textContent = row.content || row.candidate_uri || row.uri || '—';
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    meta.textContent = renderMeta(row);
    item.appendChild(content);
    item.appendChild(meta);
    list.appendChild(item);
  });
}
async function loadMemoryQueues() {
  const s = STRINGS[currentLang];
  try {
    const observationsResponse = await fetchConfig('/api/companion/observations?status=buffered&limit=20');
    if (observationsResponse.ok) {
      const observationsData = await readJsonResponse(observationsResponse);
      renderQueueList(
        'observations-list',
        s.noObservations || 'No buffered observations.',
        observationsData.observations || [],
        row => `${row.kind || 'memory'} · seen=${row.seen_count || 0} · conf=${row.confidence ?? '—'} · expires=${row.expires_at ? new Date(row.expires_at * 1000).toLocaleString() : '—'} · ${row.candidate_uri || ''}`
      );
    }

    const recycleResponse = await fetchConfig('/api/companion/recycle-bin?limit=20');
    if (recycleResponse.ok) {
      const recycleData = await readJsonResponse(recycleResponse);
      renderQueueList(
        'recycle-list',
        s.noRecycle || 'Recycle bin is empty.',
        recycleData.items || [],
        row => `${row.reason || 'archived'} · purge=${row.purge_after ? new Date(row.purge_after * 1000).toLocaleString() : '—'} · ${row.uri || ''}`
      );
    }
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e && e.message ? e.message : 'Failed to load memory queues'), 'err');
  }
}
async function rebuildMemoryIndex() {
  const btn = document.getElementById('t-memory-index-rebuild');
  try {
    if (btn) btn.disabled = true;
    const r = await fetchConfig('/api/companion/memory-index/rebuild', { method: 'POST' });
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      setStatus((STRINGS[currentLang].error || 'Error: ') + (d.error || 'Failed to rebuild memory index'), 'err');
      return;
    }
    renderMemoryIndex(d);
    setStatus('Memory index rebuilt.', 'success', 4000);
    await loadModelRecallDebug();
  } finally {
    if (btn) btn.disabled = false;
  }
}
function renderMemoryIndex(indexJson) {
  const list = document.getElementById('memory-index-list');
  if (!list) return;
  const segments = indexJson && Array.isArray(indexJson.segments) ? indexJson.segments : [];
  list.textContent = '';
  if (segments.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = STRINGS[currentLang].noMemoryIndex || 'No memory index loaded.';
    list.appendChild(empty);
    return;
  }
  if (indexJson.term_graph_stats) {
    const stats = indexJson.term_graph_stats;
    const statsItem = document.createElement('div');
    statsItem.className = 'memory-queue-item';
    const statsLine = document.createElement('div');
    statsLine.textContent =
      `term_graph_stats: terms=${stats.term_count || 0} edges=${stats.edge_count || 0} dirty=${stats.dirty ? 'true' : 'false'}${stats.error ? ' error=' + stats.error : ''}`;
    statsItem.appendChild(statsLine);
    const statsMeta = document.createElement('div');
    statsMeta.className = 'memory-queue-meta';
    statsMeta.textContent = stats.last_rebuilt_at ? new Date(stats.last_rebuilt_at * 1000).toLocaleString() : 'not built';
    statsItem.appendChild(statsMeta);
    list.appendChild(statsItem);
  }
  segments.forEach(segment => {
    const item = document.createElement('div');
    item.className = 'memory-queue-item';
    const summary = document.createElement('div');
    summary.textContent =
      `${segment.segment_key} v${segment.version || 0} · chars=${segment.char_count || 0} · dirty=${segment.dirty ? 'true' : 'false'}${segment.error ? ' · error=' + segment.error : ''}`;
    item.appendChild(summary);
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    meta.textContent = segment.updated_at ? new Date(segment.updated_at * 1000).toLocaleString() : 'not built';
    item.appendChild(meta);
    if (segment.preview) {
      const preview = document.createElement('pre');
      preview.className = 'index-preview';
      preview.textContent = segment.preview;
      item.appendChild(preview);
    }
    list.appendChild(item);
  });
}
function renderModelRecallDiagnostics(diag) {
  const list = document.getElementById('model-recall-status-list');
  if (!list) return;
  const safe = diag || {};
  list.textContent = '';
  const cooldown = Number(safe.cooldown_until || 0);
  const cooldownText = cooldown > Date.now() ? new Date(cooldown).toLocaleString() : 'inactive';
  const rows = [
    ['enabled', safe.enabled === true ? 'true' : 'false'],
    ['index_version', safe.index_version || 'unknown'],
    ['dirty', safe.index_dirty ? 'true' : 'false'],
    ['error', safe.index_error || 'none'],
    ['consecutive_failures', formatUnknown(safe.consecutive_failures)],
    ['cooldown', cooldownText]
  ];
  const last = safe.last_trace;
  if (last) {
    rows.push(['last_trace', `#${last.id} candidates=${last.candidate_count} injected=${last.injected_count} fallback=${last.fallback_reason || 'none'} error=${last.error || 'none'}`]);
  }
  rows.forEach(([label, value]) => {
    const item = document.createElement('div');
    item.className = 'memory-queue-item';
    const body = document.createElement('div');
    body.textContent = `${label}: ${value}`;
    item.appendChild(body);
    list.appendChild(item);
  });
}
function renderRecallTraces(tracesJson) {
  const list = document.getElementById('model-recall-traces-list');
  if (!list) return;
  const traces = tracesJson && Array.isArray(tracesJson.traces) ? tracesJson.traces : [];
  list.textContent = '';
  if (traces.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = STRINGS[currentLang].noModelRecallTraces || 'No recall traces yet.';
    list.appendChild(empty);
    return;
  }
  traces.forEach(trace => {
    const item = document.createElement('div');
    item.className = 'memory-queue-item';
    const line = document.createElement('div');
    line.textContent =
      `#${trace.id} ${trace.query || ''} · candidates=${trace.candidate_count || 0} injected=${trace.injected_count || 0} duration=${trace.duration_ms || 0}ms`;
    item.appendChild(line);
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    meta.textContent =
      `${trace.created_at ? new Date(trace.created_at * 1000).toLocaleString() : 'unknown'} · fallback=${trace.fallback_reason || 'none'} · error=${trace.error || 'none'}`;
    item.appendChild(meta);
	    if (trace.plan_json) {
	      const plan = document.createElement('pre');
	      plan.className = 'index-preview';
	      plan.textContent = trace.plan_json;
	      item.appendChild(plan);
	    }
	    if (trace.debug_json) {
	      const debug = document.createElement('pre');
	      debug.className = 'index-preview';
	      debug.textContent = trace.debug_json;
	      item.appendChild(debug);
	    }
	    list.appendChild(item);
	  });
	}
async function loadModelRecallDebug(existingDiagnostics) {
  let diagnostics = existingDiagnostics;
  if (!diagnostics) {
    const stateResponse = await fetchConfig('/api/companion/state');
    if (stateResponse.ok) {
      const stateJson = await stateResponse.json();
      diagnostics = stateJson.model_recall_diagnostics;
    }
  }
  renderModelRecallDiagnostics(diagnostics || {});

  const [indexResponse, tracesResponse] = await Promise.all([
    fetchConfig('/api/companion/memory-index'),
    fetchConfig('/api/companion/recall-traces?limit=20')
  ]);
  if (indexResponse.ok) renderMemoryIndex(await indexResponse.json());
  if (tracesResponse.ok) renderRecallTraces(await tracesResponse.json());
}
