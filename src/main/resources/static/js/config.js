function applyConfigData(d) {
  const f = document.getElementById('f');
  applyingConfigData = true;
  f.preset.value = d.preset || 'custom';
  f.upstream.value = d.upstream || '';
  f.cache_ttl.value = d.cache_ttl;
  f.cache_mode.value = d.cache_mode;
  f.cache_strategy.value = d.cache_strategy;
  f.cache_breakpoints.value = d.cache_breakpoints;

  f.memory_enabled.checked = d.memory_enabled || false;
  f.memory_summary_url.value = d.memory_summary_url || '';
  f.memory_summary_key.value = '';
  f.clear_memory_summary_key.checked = false;
  f.memory_summary_model.value = d.memory_summary_model || '';
  f.memory_model_recall_enabled.checked = d.memory_model_recall_enabled || false;
  f.memory_recall_model_url.value = d.memory_recall_model_url || '';
  f.memory_recall_model_key.value = '';
  f.clear_memory_recall_model_key.checked = false;
  f.memory_recall_model_model.value = d.memory_recall_model_model || '';
  f.memory_model_recall_failure_threshold.value = d.memory_model_recall_failure_threshold !== undefined ? d.memory_model_recall_failure_threshold : 3;
  f.memory_model_recall_cooldown_seconds.value = d.memory_model_recall_cooldown_seconds !== undefined ? d.memory_model_recall_cooldown_seconds : 300;
  f.memory_model_recall_trace_retention.value = d.memory_model_recall_trace_retention !== undefined ? d.memory_model_recall_trace_retention : 200;
  f.memory_local_recall_enhanced_enabled.checked = d.memory_local_recall_enhanced_enabled !== undefined ? d.memory_local_recall_enhanced_enabled : true;
  f.memory_tag_graph_enabled.checked = d.memory_tag_graph_enabled !== undefined ? d.memory_tag_graph_enabled : true;
  f.memory_tag_graph_max_expanded_terms.value = d.memory_tag_graph_max_expanded_terms !== undefined ? d.memory_tag_graph_max_expanded_terms : 16;
  f.memory_timeline_recall_enabled.checked = d.memory_timeline_recall_enabled !== undefined ? d.memory_timeline_recall_enabled : true;
  f.memory_summary_sanitize_internal_prompts.checked = d.memory_summary_sanitize_internal_prompts !== undefined ? d.memory_summary_sanitize_internal_prompts : true;
  f.memory_summary_prompt.value = d.memory_summary_prompt || '';
  f.memory_decay_interval_hours.value = d.memory_decay_interval_hours !== undefined ? d.memory_decay_interval_hours : 24;
  f.memory_decay_rate.value = d.memory_decay_rate !== undefined ? d.memory_decay_rate : 0.1;
  f.memory_threshold.value = d.memory_threshold !== undefined ? d.memory_threshold : 0.1;
  f.memory_recovery_amount.value = d.memory_recovery_amount !== undefined ? d.memory_recovery_amount : 0.3;
  f.memory_initial_strength.value = d.memory_initial_strength !== undefined ? d.memory_initial_strength : 0.8;
  f.memory_max_strength.value = d.memory_max_strength !== undefined ? d.memory_max_strength : 1.0;
  f.intimacy_decay_rate.value = d.intimacy_decay_rate !== undefined ? d.intimacy_decay_rate : 0.5;
  f.memory_decay_tau_hours.value = d.memory_decay_tau_hours !== undefined ? d.memory_decay_tau_hours : 360;
  f.memory_salience_k.value = d.memory_salience_k !== undefined ? d.memory_salience_k : 1.0;
  f.memory_recall_max_nodes.value = d.memory_recall_max_nodes !== undefined ? d.memory_recall_max_nodes : 6;
  f.memory_deep_recall_enabled.checked = d.memory_deep_recall_enabled !== undefined ? d.memory_deep_recall_enabled : true;
  f.memory_deep_recall_max_candidates.value = d.memory_deep_recall_max_candidates !== undefined ? d.memory_deep_recall_max_candidates : 40;
  f.memory_deep_recall_max_clues.value = d.memory_deep_recall_max_clues !== undefined ? d.memory_deep_recall_max_clues : 10;
  f.memory_person_context_max_clues.value = d.memory_person_context_max_clues !== undefined ? d.memory_person_context_max_clues : 2;
  f.memory_buffered_ingestion_enabled.checked = d.memory_buffered_ingestion_enabled !== undefined ? d.memory_buffered_ingestion_enabled : true;
  f.memory_promote_repeat_threshold.value = d.memory_promote_repeat_threshold !== undefined ? d.memory_promote_repeat_threshold : 2;
  f.memory_self_enabled.checked = d.memory_self_enabled !== undefined ? d.memory_self_enabled : true;
  f.memory_self_direct_update_enabled.checked = d.memory_self_direct_update_enabled !== undefined ? d.memory_self_direct_update_enabled : true;
  f.memory_self_recall_max_nodes.value = d.memory_self_recall_max_nodes !== undefined ? d.memory_self_recall_max_nodes : 8;
  f.memory_self_promote_repeat_threshold.value = d.memory_self_promote_repeat_threshold !== undefined ? d.memory_self_promote_repeat_threshold : 3;
  f.memory_project_fact_promote_repeat_threshold.value = d.memory_project_fact_promote_repeat_threshold !== undefined ? d.memory_project_fact_promote_repeat_threshold : 2;
  f.memory_working_memory_slots_per_project.value = d.memory_working_memory_slots_per_project !== undefined ? d.memory_working_memory_slots_per_project : 3;
  f.memory_observation_retention_days.value = d.memory_observation_retention_days !== undefined ? d.memory_observation_retention_days : 14;
  f.memory_low_confidence_observation_retention_days.value = d.memory_low_confidence_observation_retention_days !== undefined ? d.memory_low_confidence_observation_retention_days : 3;
  f.memory_observation_daily_cap.value = d.memory_observation_daily_cap !== undefined ? d.memory_observation_daily_cap : 200;
  f.memory_promoted_nodes_daily_cap.value = d.memory_promoted_nodes_daily_cap !== undefined ? d.memory_promoted_nodes_daily_cap : 20;
  f.memory_dream_enabled.checked = d.memory_dream_enabled || false;
  f.memory_auto_maintenance_enabled.checked = d.memory_auto_maintenance_enabled || false;
  f.memory_maintenance_aggressiveness.value = d.memory_maintenance_aggressiveness || 'aggressive';
  f.memory_dream_idle_hours.value = d.memory_dream_idle_hours !== undefined ? d.memory_dream_idle_hours : 12;
  f.memory_dream_daily_limit.value = d.memory_dream_daily_limit !== undefined ? d.memory_dream_daily_limit : 1;
  f.memory_long_idle_pause_days.value = d.memory_long_idle_pause_days !== undefined ? d.memory_long_idle_pause_days : 7;
  f.memory_dream_recall_max_traces.value = d.memory_dream_recall_max_traces !== undefined ? d.memory_dream_recall_max_traces : 2;
  f.memory_dream_batch_max_nodes.value = d.memory_dream_batch_max_nodes !== undefined ? d.memory_dream_batch_max_nodes : 40;
  f.memory_dream_dry_run_daily_limit.value = d.memory_dream_dry_run_daily_limit !== undefined ? d.memory_dream_dry_run_daily_limit : 3;
  f.memory_recycle_retention_days.value = d.memory_recycle_retention_days !== undefined ? d.memory_recycle_retention_days : 30;

  summaryKeyConfigured = d.memory_summary_key_configured || false;
  recallModelKeyConfigured = d.memory_recall_model_key_configured || false;
  configPasswordChangeable = d.config_password_changeable || false;
  lastAutoSavePayload = JSON.stringify(buildConfigPayload(false));

  updateCacheModeOptions();
  updateUiVisibility();
  updateVisualizer();
  updateFlowDiagram();
  updateStatusSummary();
  updateCompanionVisibility();
  refreshSecretFieldUi();
  refreshPasswordChangeUi();
  refreshAutoSaveUi();
  applyingConfigData = false;
}
function buildConfigPayload(includeManualOnlyFields = true) {
  const f = document.getElementById('f');
  const payload = {
    preset: f.preset.value,
    upstream: f.upstream.value,
    cache_ttl: f.cache_ttl.value,
    cache_mode: f.cache_mode.value,
    cache_strategy: f.cache_strategy.value,
    cache_breakpoints: Number(f.cache_breakpoints.value),

    memory_enabled: f.memory_enabled.checked,
    memory_summary_url: f.memory_summary_url.value,
    memory_summary_model: f.memory_summary_model.value,
    memory_model_recall_enabled: f.memory_model_recall_enabled.checked,
    memory_recall_model_url: f.memory_recall_model_url.value,
    memory_recall_model_model: f.memory_recall_model_model.value,
    memory_model_recall_failure_threshold: Number(f.memory_model_recall_failure_threshold.value),
    memory_model_recall_cooldown_seconds: Number(f.memory_model_recall_cooldown_seconds.value),
    memory_model_recall_trace_retention: Number(f.memory_model_recall_trace_retention.value),
    memory_local_recall_enhanced_enabled: f.memory_local_recall_enhanced_enabled.checked,
    memory_tag_graph_enabled: f.memory_tag_graph_enabled.checked,
    memory_tag_graph_max_expanded_terms: Number(f.memory_tag_graph_max_expanded_terms.value),
    memory_timeline_recall_enabled: f.memory_timeline_recall_enabled.checked,
    memory_summary_sanitize_internal_prompts: f.memory_summary_sanitize_internal_prompts.checked,
    memory_summary_prompt: f.memory_summary_prompt.value,
    memory_decay_interval_hours: Number(f.memory_decay_interval_hours.value),
    memory_decay_rate: Number(f.memory_decay_rate.value),
    memory_threshold: Number(f.memory_threshold.value),
    memory_recovery_amount: Number(f.memory_recovery_amount.value),
    memory_initial_strength: Number(f.memory_initial_strength.value),
    memory_max_strength: Number(f.memory_max_strength.value),
    intimacy_decay_rate: Number(f.intimacy_decay_rate.value),
    memory_decay_tau_hours: Number(f.memory_decay_tau_hours.value),
    memory_salience_k: Number(f.memory_salience_k.value),
    memory_recall_max_nodes: Number(f.memory_recall_max_nodes.value),
    memory_deep_recall_enabled: f.memory_deep_recall_enabled.checked,
    memory_deep_recall_max_candidates: Number(f.memory_deep_recall_max_candidates.value),
    memory_deep_recall_max_clues: Number(f.memory_deep_recall_max_clues.value),
    memory_person_context_max_clues: Number(f.memory_person_context_max_clues.value),
    memory_buffered_ingestion_enabled: f.memory_buffered_ingestion_enabled.checked,
    memory_promote_repeat_threshold: Number(f.memory_promote_repeat_threshold.value),
    memory_self_enabled: f.memory_self_enabled.checked,
    memory_self_direct_update_enabled: f.memory_self_direct_update_enabled.checked,
    memory_self_recall_max_nodes: Number(f.memory_self_recall_max_nodes.value),
    memory_self_promote_repeat_threshold: Number(f.memory_self_promote_repeat_threshold.value),
    memory_project_fact_promote_repeat_threshold: Number(f.memory_project_fact_promote_repeat_threshold.value),
    memory_working_memory_slots_per_project: Number(f.memory_working_memory_slots_per_project.value),
    memory_observation_retention_days: Number(f.memory_observation_retention_days.value),
    memory_low_confidence_observation_retention_days: Number(f.memory_low_confidence_observation_retention_days.value),
    memory_observation_daily_cap: Number(f.memory_observation_daily_cap.value),
    memory_promoted_nodes_daily_cap: Number(f.memory_promoted_nodes_daily_cap.value),
    memory_dream_enabled: f.memory_dream_enabled.checked,
    memory_auto_maintenance_enabled: f.memory_auto_maintenance_enabled.checked,
    memory_maintenance_aggressiveness: f.memory_maintenance_aggressiveness.value,
    memory_dream_idle_hours: Number(f.memory_dream_idle_hours.value),
    memory_dream_daily_limit: Number(f.memory_dream_daily_limit.value),
    memory_long_idle_pause_days: Number(f.memory_long_idle_pause_days.value),
    memory_dream_recall_max_traces: Number(f.memory_dream_recall_max_traces.value),
    memory_dream_batch_max_nodes: Number(f.memory_dream_batch_max_nodes.value),
    memory_dream_dry_run_daily_limit: Number(f.memory_dream_dry_run_daily_limit.value),
    memory_recycle_retention_days: Number(f.memory_recycle_retention_days.value),
  };

  if (includeManualOnlyFields) {
    payload.clear_memory_summary_key = f.clear_memory_summary_key.checked;
    if (f.memory_summary_key.value.trim()) payload.memory_summary_key = f.memory_summary_key.value.trim();
    payload.clear_memory_recall_model_key = f.clear_memory_recall_model_key.checked;
    if (f.memory_recall_model_key.value.trim()) payload.memory_recall_model_key = f.memory_recall_model_key.value.trim();
  }

  return payload;
}
function hasPendingManualOnlyChanges() {
  const f = document.getElementById('f');
  return Boolean(
    f.memory_summary_key.value.trim() ||
    f.clear_memory_summary_key.checked ||
    f.memory_recall_model_key.value.trim() ||
    f.clear_memory_recall_model_key.checked ||
    f.config_password_current.value.trim() ||
    f.config_password_new.value.trim() ||
    f.config_password_confirm.value.trim()
  );
}
function humanConfigSummary(d) {
  const preset = d.preset || 'custom';
  const cacheMode = d.cache_mode || 'explicit';
  const bp = d.cache_breakpoints != null ? d.cache_breakpoints : 0;
  const cache = preset === 'anthropic' && d.cache_ttl !== 'none'
    ? `${cacheMode}/${bp} BP`
    : 'off';
  const memory = d.memory_enabled ? 'on' : 'off';
  return `preset=${preset}, cache=${cache}, memory=${memory}`;
}
function setStatus(msg, kind, autoHideMs) {
  const status = document.getElementById('status');
  status.className = (kind === 'err' ? 'err' : 'success') + ' is-visible';
  status.textContent = msg;
  if (autoHideMs) setTimeout(() => { status.classList.remove('is-visible'); }, autoHideMs);
}
async function saveConfig(options = {}) {
  const manual = options.manual === true;
  const payload = buildConfigPayload(manual);
  const str = STRINGS[currentLang];

  let r;
  let d;
  try {
    r = await fetchConfig('/api/config', {
      method: 'POST',
      headers: {'content-type':'application/json'},
      body: JSON.stringify(payload)
    });
    d = await readJsonResponse(r);
  } catch (e) {
    setStatus(str.error + (e && e.message ? e.message : 'Failed to save config'), 'err');
    return false;
  }

  if (r.ok && !d.error) {
    setAuthPanelVisible(false, 'unlock');
    applyConfigData(d);
    if (manual) {
      setStatus(str.saved + humanConfigSummary(d), 'success', 4000);
    }
    return true;
  }

  const errors = d.errors || [d.error || str.authInvalid];
  setStatus(str.error + errors.join('; '), 'err');
  return false;
}
async function exportConfig() {
  const r = await fetchConfig('/api/config/export');
  if (!r.ok) {
    setStatus((STRINGS[currentLang].error || 'Error: ') + 'Export failed (' + r.status + ')', 'err');
    return;
  }
  const d = await r.json();
  const blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'kiyomizu-config.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
async function importConfig(event) {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  const text = await file.text();
  const str = STRINGS[currentLang];
  let body;
  try { body = JSON.parse(text); } catch (e) {
    setStatus((str.importError || 'Import failed: ') + 'Invalid JSON', 'err');
    return;
  }
  const r = await fetchConfig('/api/config/import', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: text
  });
  const d = await r.json();
  if (r.ok) {
    applyConfigData(d);
    setStatus(str.importSuccess || 'Config imported successfully.', 'success', 4000);
  } else {
    const errors = d.errors || [d.error || 'Unknown error'];
    setStatus((str.importError || 'Import failed: ') + errors.join('; '), 'err');
  }
}
function onPresetChange() {
  const f = document.getElementById('f');
  const preset = f.preset.value;
  if (UPSTREAM_DEFAULTS[preset] !== undefined) {
    f.upstream.value = UPSTREAM_DEFAULTS[preset];
  }
  updateUiVisibility();
  updateVisualizer();
  updateFlowDiagram();
  updateStatusSummary();
}
function updateUiVisibility() {
  const f = document.getElementById('f');
  const preset = f.preset.value;

  const sectionClaude = document.getElementById('section-claude');
  const claudeCacheSettings = document.getElementById('claude-cache-settings');
  const msgNotApplicable = document.getElementById('msg-not-applicable');
  const claudeCollapsible = document.querySelector('.collapsible[data-section="claude"]');
  const anthropic = preset === 'anthropic';

  sectionClaude.classList.toggle('is-hidden', !anthropic);
  claudeCacheSettings.classList.toggle('is-hidden', !anthropic);
  msgNotApplicable.classList.toggle('is-hidden', anthropic);
  if (claudeCollapsible) claudeCollapsible.classList.toggle('is-hidden', !anthropic);
  updateCacheNote();
}
