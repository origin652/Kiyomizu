function updateCacheModeOptions() {
  const f = document.getElementById('f');
  const cacheModeSelect = f.cache_mode;
  const automaticOption = cacheModeSelect.querySelector('option[value="automatic"]');
  const anthropicMode = f.preset.value === 'anthropic';

  if (!anthropicMode) {
    automaticOption.disabled = true;
    if (cacheModeSelect.value === 'automatic') {
      cacheModeSelect.value = 'explicit';
    }
  } else {
    automaticOption.disabled = false;
  }
  updateCacheNote();
}
function updateCacheNote() {
  const f = document.getElementById('f');
  let note = document.getElementById('cache-note');
  if (!note) {
    note = document.createElement('p');
    note.id = 'cache-note';
    note.className = 'cache-note';
    const claudeSec = document.getElementById('claude-cache-settings');
    if (claudeSec) claudeSec.appendChild(note);
  }
  const isAutomatic = f.cache_mode && f.cache_mode.value === 'automatic';
  note.textContent = (f.preset.value !== 'anthropic' && isAutomatic) ? STRINGS[currentLang].cacheNote : '';
}
function updateVisualizer() {
  const f = document.getElementById('f');
  const preset = f.preset.value;
  const isCacheActive = preset === 'anthropic';
  const strategy = f.cache_strategy.value;
  const bpCount = parseInt(f.cache_breakpoints.value) || 0;
  const cacheTtl = f.cache_ttl.value;

  const container = document.getElementById('visualizer-container');
  const timeline = document.getElementById('timeline-track');
  if (!container || !timeline) return;

  // Clear existing pins
  timeline.querySelectorAll('.cache-pin').forEach(pin => pin.remove());

  if (!isCacheActive || cacheTtl === 'none') {
    container.classList.add('disabled');
    return;
  }

  container.classList.remove('disabled');

  const stableBlocks = timeline.querySelectorAll('.timeline-msg.stable');
  const stableCount = stableBlocks.length; // 7 stable blocks

  const pinIndices = [];
  if (strategy === 'last') {
    pinIndices.push(stableCount - 1);
  } else {
    // stable-prefix distribution mapping
    if (bpCount === 1) {
      pinIndices.push(stableCount - 1);
    } else if (bpCount === 2) {
      pinIndices.push(Math.floor(stableCount / 2) - 1);
      pinIndices.push(stableCount - 1);
    } else if (bpCount === 3) {
      pinIndices.push(1);
      pinIndices.push(4);
      pinIndices.push(6);
    } else if (bpCount === 4) {
      pinIndices.push(0);
      pinIndices.push(2);
      pinIndices.push(4);
      pinIndices.push(6);
    }
  }

  pinIndices.forEach(idx => {
    if (idx >= 0 && idx < stableCount) {
      const pin = document.createElement('div');
      pin.className = 'cache-pin';
      stableBlocks[idx].appendChild(pin);
    }
  });
}
function updateFlowDiagram() {
  const f = document.getElementById('f');
  const preset = f.preset.value;
  const cacheTtl = f.cache_ttl.value;
  const isCacheActive = (preset === 'anthropic') && (cacheTtl !== 'none');

  const proxyNode = document.getElementById('node-proxy');
  const cacheCylinder = document.getElementById('cache-cylinder');
  const lineProxyCache = document.getElementById('line-proxy-cache');
  const upstreamText = document.getElementById('flow-lbl-upstream');
  const lineProxyUpstream = document.getElementById('line-proxy-upstream');
  if (!proxyNode || !cacheCylinder || !lineProxyCache || !upstreamText || !lineProxyUpstream) return;

  // Update Upstream text label in diagram
  let presetText = f.preset.options[f.preset.selectedIndex].text;
  if (presetText.includes(' (')) {
    presetText = presetText.split(' (')[0];
  }
  if (presetText.includes(' API')) {
    presetText = presetText.split(' API')[0];
  }
  upstreamText.textContent = presetText;

  if (isCacheActive) {
    cacheCylinder.classList.remove('cache-off');
    cacheCylinder.classList.add('cache-on');
    lineProxyCache.classList.remove('flow-idle');
    lineProxyCache.classList.add('flow-active');
  } else {
    cacheCylinder.classList.remove('cache-on');
    cacheCylinder.classList.add('cache-off');
    lineProxyCache.classList.remove('flow-active');
    lineProxyCache.classList.add('flow-idle');
  }
}
function updateStatusSummary() {
  const f = document.getElementById('f');
  const preset = f.preset.value;
  const cacheTtl = f.cache_ttl.value;
  const isCacheActive = (preset === 'anthropic') && (cacheTtl !== 'none');
  const bpCount = f.cache_breakpoints.value;

  // Upstream
  const upstreamVal = document.getElementById('val-status-upstream');
  upstreamVal.textContent = f.upstream.value || f.preset.options[f.preset.selectedIndex].text;

  // Cache Status
  const cacheVal = document.getElementById('val-status-cache');
  const s = STRINGS[currentLang];
  if (isCacheActive) {
    cacheVal.textContent = `${s.statusCacheActive} (${bpCount} BP)`;
    cacheVal.className = 'stat-value text-success';
  } else {
    cacheVal.textContent = s.statusCacheInactive;
    cacheVal.className = 'stat-value text-muted';
  }
}
function updateCompanionVisibility() {
  const f = document.getElementById('f');
  const wrapper = document.getElementById('companion-fields-wrapper');
  wrapper.classList.toggle('is-hidden', !f.memory_enabled.checked);
}
