// While a source is being collected (after resuming, signing in or a restart) its badge says "waiting"; this
// asks the server every few seconds and updates the badges and figures in place, so no reload is needed.
(() => {
  const badges = [...document.querySelectorAll('[data-source-badge]')];
  if (!badges.length) return;
  const pending = () => badges.some(b => b.dataset.status === 'waiting');
  let rounds = 0;
  async function tick() {
    if (!pending() || rounds++ > 60) return;
    try {
      const response = await fetch('/admin/sources/state.json', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (response.ok) {
        const states = await response.json();
        for (const badge of badges) {
          const state = states[badge.dataset.sourceBadge]; if (!state) continue;
          badge.dataset.status = state.status; badge.textContent = state.badge; badge.classList.toggle('ok', state.status === 'ok');
          const figures = document.querySelector(`[data-source-figures="${badge.dataset.sourceBadge}"]`);
          if (figures) figures.textContent = state.figures;
          // Overview: the reason alone; data sources page: its "Data" line.
          const reason = document.querySelector(`[data-source-reason="${badge.dataset.sourceBadge}"]`);
          if (reason) reason.textContent = reason.classList.contains('source-reason') ? state.reason
            : state.status === 'ok' ? 'Current usage available' : state.reason || 'Not available yet';
        }
      }
    } catch { /* try again */ }
    setTimeout(tick, 3000);
  }
  setTimeout(tick, 2000);
})();
