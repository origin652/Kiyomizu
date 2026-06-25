function renderPane(name) {
  document.querySelectorAll('.nav-item').forEach(btn => {
    btn.classList.toggle('is-active', btn.dataset.nav === name);
  });
  document.querySelectorAll('.pane').forEach(pane => {
    pane.classList.toggle('is-active', pane.dataset.pane === name);
  });
  try { localStorage.setItem('kiyomizu-nav', name); } catch (e) {}
  if (!configAuthRequired) {
    if (name === 'cache') loadCacheDiagnostics();
    else if (name === 'logs') loadLogs();
    else if (name === 'companion') loadCompanionState();
    else if (name === 'memories') loadMemories();
  }
}
function setSectionCollapsed(section, collapsed) {
  const col = document.querySelector('.collapsible[data-section="' + section + '"]');
  if (!col) return;
  col.classList.toggle('is-open', !collapsed);
  try { localStorage.setItem('kiyomizu-sec-' + section, collapsed ? '0' : '1'); } catch (e) {}
}
function initCollapsibles() {
  document.querySelectorAll('.collapsible-header').forEach(header => {
    header.addEventListener('click', () => {
      const col = header.closest('.collapsible');
      if (!col) return;
      setSectionCollapsed(col.dataset.section, col.classList.contains('is-open'));
    });
  });
  document.querySelectorAll('.collapsible').forEach(col => {
    const section = col.dataset.section;
    if (!section) return;
    let saved;
    try { saved = localStorage.getItem('kiyomizu-sec-' + section); } catch (e) {}
    if (saved === '0') col.classList.remove('is-open');
    else if (saved === '1') col.classList.add('is-open');
  });
}
function closeNavDrawer() {
  const rail = document.querySelector('.nav-rail');
  if (rail) rail.classList.remove('is-open');
  const toggle = document.getElementById('nav-toggle');
  if (toggle) toggle.setAttribute('aria-expanded', 'false');
}
function initNav() {
  document.querySelectorAll('.nav-item').forEach(btn => {
    btn.addEventListener('click', () => {
      renderPane(btn.dataset.nav);
      // Collapse the mobile drawer after a selection.
      closeNavDrawer();
    });
  });
  const toggle = document.getElementById('nav-toggle');
  if (toggle) {
    toggle.addEventListener('click', () => {
      const rail = document.querySelector('.nav-rail');
      if (!rail) return;
      const open = rail.classList.toggle('is-open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }
  // Close the side drawer when the scrim is tapped.
  const scrim = document.getElementById('nav-scrim');
  if (scrim) scrim.addEventListener('click', closeNavDrawer);
  // Close on Escape for keyboard users.
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeNavDrawer();
  });
  let saved;
  try { saved = localStorage.getItem('kiyomizu-nav'); } catch (e) {}
  const panes = Array.from(document.querySelectorAll('.pane')).map(p => p.dataset.pane);
  if (saved && panes.includes(saved)) renderPane(saved);
  else renderPane('config');
}
