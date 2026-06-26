async function loadCompanionState() {
  const r = await fetchConfig('/api/companion/state');
  if (!r.ok) return;
  const d = await r.json();

  const intimacy = Math.round(d.intimacy || 0);
  const trust = Math.round(d.trust || 0);
  document.getElementById('companion-intimacy-bar').style.width = intimacy + '%';
  document.getElementById('companion-intimacy-val').textContent = intimacy + ' / 100';
  document.getElementById('companion-trust-bar').style.width = trust + '%';
  document.getElementById('companion-trust-val').textContent = trust + ' / 100';
  document.getElementById('companion-mood').textContent = d.mood || 'neutral';
  document.getElementById('companion-memory-count').textContent =
    `${d.active_graph_node_count ?? d.graph_node_count ?? '—'} active / ${d.graph_node_count ?? '—'} total / ${d.buffered_observation_count ?? 0} buffered`;
  document.getElementById('companion-related-edges').textContent = d.graph_edge_count ?? '—';
  document.getElementById('companion-search-terms').textContent = d.search_term_count ?? '—';
  document.getElementById('companion-working-memory').textContent = d.working_memory_count ?? '—';
  const aff = d.affect_distribution || {};
  document.getElementById('companion-affect').textContent =
    `+calm ${aff.positive_calm||0} / +intense ${aff.positive_intense||0} / −calm ${aff.negative_calm||0} / −intense ${aff.negative_intense||0} / neutral ${aff.neutral||0}`;
  const consAt = d.last_deep_recall_at || 0;
  document.getElementById('companion-consolidation').textContent = consAt
    ? `${new Date(consAt).toLocaleString()} (${d.last_deep_recall_candidates||0} candidates, ${d.last_deep_recall_clues||0} clues)`
    : '—';
  const dreamAt = d.last_dream_at || 0;
  const dreamSummary = d.last_dream_summary || d.last_dream_journal || '';
  const dreamText = dreamAt
    ? `${new Date(dreamAt * 1000).toLocaleString()} ${d.last_dream_status || ''}: ${dreamSummary || '—'}`
    : '—';
  const dreamEl = document.getElementById('companion-dream');
  if (dreamEl) {
    const diag = d.auto_maintenance_diagnostics || {};
    const autoDream = diag.auto_dream || {};
    const autoMaintenance = diag.auto_maintenance || {};
    const dreamBits = [];
    if (d.last_dream_error) dreamBits.push(`error: ${d.last_dream_error}`);
    if (Array.isArray(autoDream.blockers) && autoDream.blockers.length > 0) {
      dreamBits.push(`auto dream blocked: ${autoDream.blockers.join(', ')}`);
    }
    if (Array.isArray(autoMaintenance.blockers) && autoMaintenance.blockers.length > 0) {
      dreamBits.push(`auto maintenance blocked: ${autoMaintenance.blockers.join(', ')}`);
    }
    if (d.memory_long_idle_paused) dreamBits.push('idle paused');
    dreamEl.textContent = dreamBits.length > 0 ? `${dreamText} | ${dreamBits.join(' | ')}` : dreamText;
  }

  const list = document.getElementById('reflections-list');
  const noReflEl = document.getElementById('t-no-reflections');
  const reflections = d.reflections || [];
  if (reflections.length === 0) {
    list.textContent = '';
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.id = 't-no-reflections';
    empty.textContent = STRINGS[currentLang].noReflections || 'No reflections yet.';
    list.appendChild(empty);
  } else {
    list.textContent = '';
    reflections.forEach(refl => {
      const item = document.createElement('div');
      item.className = 'reflection-item';
      const text = document.createElement('div');
      text.textContent = refl.diary_entry;
      const time = document.createElement('div');
      time.className = 'reflection-time';
      time.textContent = new Date(refl.created_at * 1000).toLocaleString();
      item.appendChild(text);
      item.appendChild(time);
      list.appendChild(item);
    });
  }
  await loadModelRecallDebug(d.model_recall_diagnostics);
  await loadMemoryQueues();
  await loadSelfMemory();
  await loadTopics();
  await loadTrustHistory();
  await loadDreamDiary();
}

// ---- Trust curve: append-only samples from /api/companion/trust-history ----
async function loadTrustHistory() {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/trust-history?hours=168&limit=500');
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) return;
    renderTrustCurve(d.samples || []);
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e.message || 'Failed to load trust history'), 'err');
  }
}
function renderTrustCurve(samples) {
  const svg = document.getElementById('trust-curve-svg');
  const empty = document.getElementById('t-no-trust-history');
  if (!svg || !empty) return;
  if (!samples || samples.length === 0) {
    svg.innerHTML = '';
    svg.style.display = 'none';
    empty.style.display = '';
    return;
  }
  empty.style.display = 'none';
  svg.style.display = '';
  const W = 600, H = 120, pad = 6;
  const t0 = samples[0].at, t1 = samples[samples.length - 1].at;
  const span = Math.max(1, t1 - t0);
  const x = at => pad + ((at - t0) / span) * (W - 2 * pad);
  const y = v => pad + (1 - v / 100) * (H - 2 * pad);
  const pts = samples.map(s => `${x(s.at).toFixed(1)},${y(s.trust).toFixed(1)}`).join(' ');
  // 30/70 disclosure thresholds as reference lines.
  const line30 = `M${pad},${y(30)} L${W - pad},${y(30)}`;
  const line70 = `M${pad},${y(70)} L${W - pad},${y(70)}`;
  svg.innerHTML =
    `<line x1="${pad}" y1="${y(30)}" x2="${W - pad}" y2="${y(30)}" stroke="var(--border-default)" stroke-dasharray="3 3" />` +
    `<line x1="${pad}" y1="${y(70)}" x2="${W - pad}" y2="${y(70)}" stroke="var(--border-default)" stroke-dasharray="3 3" />` +
    `<polyline points="${pts}" fill="none" stroke="var(--accent)" stroke-width="2" />` +
    samples.map(s => `<circle cx="${x(s.at).toFixed(1)}" cy="${y(s.trust).toFixed(1)}" r="2" fill="${s.delta < 0 ? 'var(--danger)' : 'var(--accent)'}" />`).join('');
}

// ---- Dream diary: recent dream journals from /api/companion/dream-runs ----
async function loadDreamDiary() {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/dream-runs?limit=20');
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) return;
    renderDreamDiary(d.runs || []);
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e.message || 'Failed to load dream diary'), 'err');
  }
}
function renderDreamDiary(runs) {
  const list = document.getElementById('dream-diary-list');
  const empty = document.getElementById('t-no-dream-diary');
  if (!list || !empty) return;
  list.textContent = '';
  if (!runs || runs.length === 0) {
    const e = document.createElement('div');
    e.className = 'empty';
    e.textContent = STRINGS[currentLang].noDreamDiary || 'No dream journals yet. Run a dream to produce one.';
    list.appendChild(e);
    return;
  }
  runs.forEach(run => {
    const item = document.createElement('div');
    item.className = 'memory-queue-item';
    const body = document.createElement('div');
    const journal = run.dream_journal || run.dream_summary || '—';
    body.textContent = journal;
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    const at = run.finished_at || run.started_at;
    const when = at ? new Date(at * 1000).toLocaleString() : '—';
    const tags = (run.dream_symbols || []).concat(run.dream_emotions || []).filter(t => t);
    const counts = `merged=${run.merged||0} archived=${run.archived||0} dream=${run.created_dream||0} consolidated=${run.created_consolidated||0}`;
    meta.textContent = `${when} · ${run.status} · ${counts}${tags.length ? ' · ' + tags.join(', ') : ''}`;
    item.appendChild(body);
    item.appendChild(meta);
    list.appendChild(item);
  });
}
function renderDreamRunMessage(message, kind = 'info') {
  const store = Alpine.store('dream');
  if (!store) return;
  store.summary = message;
  store.kind = kind;
  store.items = [];
  store.empty = null;
  store.visible = true;
}
function renderDreamDryRunResult(d) {
  const store = Alpine.store('dream');
  if (!store) return;
  const items = d.items || [];
  const counts = [
    `status=${d.status || 'unknown'}`,
    `input=${d.input_node_count || 0}`,
    `merged=${d.merged_count || 0}`,
    `archived=${d.archived_count || 0}`,
    `tombstoned=${d.tombstoned_count || 0}`,
    `dream=${d.created_dream_count || 0}`,
    `consolidated=${d.created_consolidated_count || 0}`,
    `skipped=${d.skipped_count || 0}`
  ].join(' · ');
  store.summary = `${counts}${d.dream_summary ? ': ' + d.dream_summary : ''}`;
  store.kind = 'info';
  store.items = items;
  store.empty = items.length === 0 ? 'No operations returned.' : null;
  store.visible = true;
}
async function runManualDream() {
  const str = STRINGS[currentLang];
  const btn = document.getElementById('t-dream-run');
  try {
    if (btn) btn.disabled = true;
    renderDreamRunMessage('Dream run started. Waiting for model response...');
    setStatus('Dream run started. Waiting for model response...', 'success');
    const r = await fetchConfig('/api/companion/dream/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: '{}'
    });
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      renderDreamRunMessage(d.error || 'Dream run failed', 'err');
      setStatus(str.error + (d.error || 'Dream run failed'), 'err');
      return;
    }
    renderDreamDryRunResult(d);
    setStatus(`Dream run: ${d.status}. ${d.dream_summary || d.dream_journal || ''}`, 'success', 6000);
    await loadCompanionState();
  } catch (e) {
    const message = e && e.message ? e.message : 'Dream run failed';
    renderDreamRunMessage(message, 'err');
    setStatus(str.error + message, 'err');
  } finally {
    if (btn) btn.disabled = false;
  }
}
async function runDreamDryRun() {
  const str = STRINGS[currentLang];
  const btn = document.getElementById('t-dream-dry-run');
  try {
    if (btn) btn.disabled = true;
    renderDreamRunMessage('Dream dry run started. Waiting for model response...');
    setStatus('Dream dry run started. Waiting for model response...', 'success');
    const r = await fetchConfig('/api/companion/dream/dry-run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: '{}'
    });
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      renderDreamRunMessage(d.error || 'Dream dry run failed', 'err');
      setStatus(str.error + (d.error || 'Dream dry run failed'), 'err');
      return;
    }
    renderDreamDryRunResult(d);
    setStatus(`Dream dry run: ${d.status}. ${d.dream_summary || d.dream_journal || ''}`, 'success', 6000);
    await loadCompanionState();
  } catch (e) {
    const message = e && e.message ? e.message : 'Dream dry run failed';
    renderDreamRunMessage(message, 'err');
    setStatus(str.error + message, 'err');
  } finally {
    if (btn) btn.disabled = false;
  }
}
async function createSelfMemory() {
  const s = STRINGS[currentLang];
  const input = document.getElementById('self-memory-content');
  const content = input.value.trim();
  if (!content) return;
  const r = await fetchConfig('/api/companion/self', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ content })
  });
  const d = await readJsonResponse(r);
  if (!r.ok || d.error) {
    setStatus((s.error || 'Error: ') + (d.error || 'Failed to create self memory'), 'err');
    return;
  }
  input.value = '';
  setStatus((s.saved || 'Saved: ') + 'self memory', 'success', 3000);
  await loadSelfMemory();
}
async function archiveSelfMemory(id) {
  const s = STRINGS[currentLang];
  const r = await fetchConfig('/api/companion/self/' + encodeURIComponent(id) + '/archive', { method: 'POST' });
  const d = await readJsonResponse(r);
  if (!r.ok || d.error || d.ok === false) {
    setStatus((s.error || 'Error: ') + (d.error || 'Failed to archive self memory'), 'err');
    return;
  }
  await loadSelfMemory();
}
async function confirmSelfObservation(id) {
  const s = STRINGS[currentLang];
  const r = await fetchConfig('/api/companion/self/observations/' + encodeURIComponent(id) + '/confirm', { method: 'POST' });
  const d = await readJsonResponse(r);
  if (!r.ok || d.error) {
    setStatus((s.error || 'Error: ') + (d.error || 'Failed to confirm self observation'), 'err');
    return;
  }
  await loadSelfMemory();
}
async function revertSelfEvent(id) {
  const s = STRINGS[currentLang];
  const r = await fetchConfig('/api/companion/self/events/' + encodeURIComponent(id) + '/revert', { method: 'POST' });
  const d = await readJsonResponse(r);
  if (!r.ok || d.error || d.ok === false) {
    setStatus((s.error || 'Error: ') + (d.error || 'Failed to revert self event'), 'err');
    return;
  }
  await loadSelfMemory();
}
async function loadSelfMemory() {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/self');
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      setStatus((s.error || 'Error: ') + (d.error || 'Failed to load self memory'), 'err');
      return;
    }
    renderSelfList(
      'stable-self-list',
      s.noStableSelf || 'No stable self memory.',
      d.stable_self || [],
      row => `${row.kind || 'self'} · ${row.source || 'unknown'} · conf=${row.confidence ?? '—'} · ${row.uri || ''}`,
      row => [{ label: s.archiveSelf || 'Archive', onClick: () => archiveSelfMemory(row.id) }]
    );
    renderSelfList(
      'buffered-self-list',
      s.noBufferedSelf || 'No buffered self observations.',
      d.buffered_self || [],
      row => `${row.kind || 'self'} · ${row.source || 'unknown'} · seen=${row.seen_count || 0} · conf=${row.confidence ?? '—'} · ${row.candidate_uri || ''}`,
      row => [{ label: s.confirmSelf || 'Confirm', onClick: () => confirmSelfObservation(row.id) }]
    );
    renderSelfList(
      'dream-self-list',
      s.noDreamSelf || 'No dream-source self observations.',
      d.dream_source_self || [],
      row => `${row.status || 'buffered'} · conf=${row.confidence ?? '—'} · ${row.candidate_uri || ''}`,
      row => [{ label: s.confirmSelf || 'Confirm', onClick: () => confirmSelfObservation(row.id) }]
    );
    renderSelfList(
      'conflict-self-list',
      s.noConflictSelf || 'No self conflicts.',
      d.conflicts || [],
      row => `${row.source || 'unknown'} · matched=${row.matched_node_id || '—'} · conf=${row.confidence ?? '—'} · ${row.candidate_uri || ''}`,
      row => [{ label: s.confirmSelf || 'Confirm', onClick: () => confirmSelfObservation(row.id) }]
    );
    renderSelfList(
      'self-events-list',
      s.noSelfEvents || 'No self events.',
      d.recent_events || [],
      row => `${row.event_type || 'event'} · ${row.source || 'unknown'} · ${row.created_at ? new Date(row.created_at * 1000).toLocaleString() : '—'} · ${row.node_uri || row.new_node_uri || row.previous_node_uri || ''}`,
      row => [{ label: s.revertSelf || 'Revert', onClick: () => revertSelfEvent(row.id) }]
    );
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e && e.message ? e.message : 'Failed to load self memory'), 'err');
  }
}
function renderSelfList(containerId, emptyText, rows, renderMeta, actionsBuilder) {
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
    content.textContent = row.content || row.content_after || row.candidate_uri || row.uri || '—';
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    meta.textContent = renderMeta(row);
    item.appendChild(content);
    item.appendChild(meta);
    const actions = actionsBuilder ? actionsBuilder(row) : [];
    if (actions.length) {
      const actionWrap = document.createElement('div');
      actionWrap.className = 'memory-queue-actions';
      actions.forEach(action => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-ghost';
        btn.textContent = action.label;
        btn.onclick = action.onClick;
        actionWrap.appendChild(btn);
      });
      item.appendChild(actionWrap);
    }
    list.appendChild(item);
  });
}

// ---- 话题 (Topics): proxy-side chat-starter pool ----
async function loadTopics() {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/topics');
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      setStatus((s.error || 'Error: ') + (d.error || 'Failed to load topics'), 'err');
      return;
    }
    const line = document.getElementById('topics-count-line');
    if (line) line.textContent = `${d.unused_count ?? 0}/${d.unused_slot_cap ?? 0} unused · ${d.used_count ?? 0} used`;
    renderTopicList('topics-unused-list', 't-no-topics-unused', 'No unused topics. The pool fills after dream runs.', d.unused || [], true);
    renderTopicList('topics-used-list', 't-no-topics-used', 'No used topics yet.', d.used || [], false);
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e.message || 'Failed to load topics'), 'err');
  }
}

function renderTopicList(containerId, emptyId, emptyText, rows, allowDelete) {
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
    content.textContent = row.title || '—';
    const meta = document.createElement('div');
    meta.className = 'memory-queue-meta';
    const gen = row.generated_at ? new Date(row.generated_at * 1000).toLocaleString() : '';
    const used = row.used_at ? ` · used ${new Date(row.used_at * 1000).toLocaleString()}` : '';
    meta.textContent = `${row.status}${gen ? ' · ' + gen : ''}${used}${row.lead_in ? ' · ' + row.lead_in : ''}`;
    item.appendChild(content);
    item.appendChild(meta);
    if (allowDelete) {
      const wrap = document.createElement('div');
      wrap.className = 'memory-queue-actions';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn btn-ghost';
      btn.textContent = 'Delete';
      btn.onclick = () => deleteTopic(row.id);
      wrap.appendChild(btn);
      item.appendChild(wrap);
    }
    list.appendChild(item);
  });
}

async function generateTopics() {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/topics/generate', { method: 'POST' });
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      setStatus((s.error || 'Error: ') + (d.error || 'Failed to generate topics'), 'err');
      return;
    }
    setStatus(`Topics: generated ${d.generated ?? 0} (${d.skipped ? 'skipped: ' + d.skipped : 'ok'})`, 'success', 6000);
    await loadTopics();
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e.message || 'Failed to generate topics'), 'err');
  }
}

async function deleteTopic(id) {
  const s = STRINGS[currentLang];
  try {
    const r = await fetchConfig('/api/companion/topics/' + encodeURIComponent(id), { method: 'DELETE' });
    const d = await readJsonResponse(r);
    if (!r.ok || d.error) {
      setStatus((s.error || 'Error: ') + (d.error || 'Failed to delete topic'), 'err');
      return;
    }
    await loadTopics();
  } catch (e) {
    setStatus((s.error || 'Error: ') + (e.message || 'Failed to delete topic'), 'err');
  }
}
