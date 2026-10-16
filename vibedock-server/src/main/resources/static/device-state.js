// Keeps device states current without reloading (device list, overview, firmware page): it fetches the page in the
// background and swaps in the parts that change (connection state, IP, firmware and update state, the "Update all"
// button), every 5 s while an update runs, otherwise every 30 s. A finished update so disappears everywhere.
(() => {
  const cards = () => [...document.querySelectorAll('[data-device]')];
  if (!cards().length) return;
  const swap = (part, fresh) => { if (fresh && fresh.outerHTML !== part.outerHTML) part.replaceWith(document.importNode(fresh, true)); };
  const delay = () => cards().some(c => c.dataset.updating === 'true') ? 5000 : 30000;
  let timer, running = false;
  const schedule = () => { clearTimeout(timer); timer = setTimeout(refresh, delay()); };
  async function refresh() {
    if (running) return; running = true; clearTimeout(timer);
    try {
      const response = await fetch(location.pathname + location.search, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
      if (response.ok) {
        const page = new DOMParser().parseFromString(await response.text(), 'text/html');
        for (const card of cards()) {
          const next = page.querySelector(`[data-device="${CSS.escape(card.dataset.device)}"]`);
          if (!next) continue;
          for (const part of card.querySelectorAll('[data-live]')) swap(part, next.querySelector(`[data-live="${part.dataset.live}"]`));
          card.dataset.updating = next.dataset.updating;
        }
        for (const part of document.querySelectorAll('[data-live-page]')) swap(part, page.querySelector(`[data-live-page="${part.dataset.livePage}"]`));
      }
    } catch { /* try again */ }
    running = false; schedule();
  }
  // Browsers slow timers down in background tabs and restore pages from the back/forward cache as they were: check
  // right away when the page is shown again.
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refresh(); });
  window.addEventListener('pageshow', event => { if (event.persisted) refresh(); });
  schedule();
})();
