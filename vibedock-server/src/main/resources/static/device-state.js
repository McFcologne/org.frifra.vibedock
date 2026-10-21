// Keeps device states current without reloading (device list, overview, firmware page): it fetches the page in the
// background and swaps in the parts that change (connection state, IP, firmware and update state, design and its
// preview, e.g. after a swipe on a touch display, the "Update all" button), every 5 s while an update runs, otherwise
// every 10 s. A finished update so disappears everywhere.
(() => {
  const cards = () => [...document.querySelectorAll('[data-device]')];
  if (!cards().length) return;
  const swap = (part, fresh) => {
    if (!fresh) return;
    // The preview only when its image changed: the page itself marks it while it builds up and sets its size.
    if (part.dataset.live === 'preview') { drawn(part, fresh); return; }
    if (fresh.outerHTML !== part.outerHTML) part.replaceWith(document.importNode(fresh, true));
  };
  // A new theme or photo builds up row by row from the top like on the device, over the previous image. --draw on the glass:
  // the board's time for a whole image (Board.drawSeconds). The new image loads first; then it builds up over the old
  // one, which stays behind it as the glass's background. Other changes (new values, the same theme) appear at once,
  // as the device only redraws the few rows that changed.
  const drawn = (part, fresh) => {
    const image = part.querySelector('img:not(.turn-old)'), next = fresh.querySelector('img:not(.turn-old)');
    if (!image || !next || image.getAttribute('src') === next.getAttribute('src')) return;
    const loaded = new Image(); loaded.src = next.src;
    loaded.decode().catch(() => {}).then(() => {
      const whole = next.dataset.look !== image.dataset.look; image.dataset.look = next.dataset.look || '';
      if (whole && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
        const screen = image.closest('.device-screen') || part;
        screen.style.backgroundImage = `url("${image.currentSrc || image.src}")`; screen.classList.add('drawing');
      }
      image.src = next.getAttribute('src');
      // The enlarged preview copies the card only now, with the new image in it (admin-ui.js).
      document.dispatchEvent(new CustomEvent('vibedock:preview-drawn', { detail: part }));
    });
  };
  // Also for the copy in the enlarged preview (admin-ui.js), which runs the same animation.
  document.addEventListener('animationend', event => {
    const screen = event.target.closest?.('.device-screen.drawing'); if (!screen || event.target.classList.contains('turn-old')) return;
    screen.classList.remove('drawing'); screen.style.backgroundImage = '';
  });
  const delay = () => cards().some(c => c.dataset.updating === 'true') ? 5000 : 10000;
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
          // The orientation, changed on the display (a circle gesture): the card turns as after a click (admin-ui.js).
          const form = card.querySelector('[data-turn]'), nextForm = next.querySelector('[data-turn]');
          if (form && nextForm && form.dataset.turn !== nextForm.dataset.turn)
            document.dispatchEvent(new CustomEvent('vibedock:turned', { detail: { form, degrees: Number(nextForm.dataset.turn) } }));
        }
        for (const part of document.querySelectorAll('[data-live-page]')) swap(part, page.querySelector(`[data-live-page="${part.dataset.livePage}"]`));
        document.dispatchEvent(new Event('vibedock:devices-refreshed'));  // an enlarged preview follows (admin-ui.js)
      }
    } catch { /* try again */ }
    running = false; schedule();
  }
  // Browsers slow timers down in background tabs and restore pages from the back/forward cache as they were: check
  // right away when the page is shown again.
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refresh(); });
  window.addEventListener('pageshow', event => { if (event.persisted) refresh(); });
  // Changes the server pushes (a swipe on a touch display): refresh at once. The browser reconnects by itself.
  try { new EventSource('/admin/events').addEventListener('devices', () => refresh()); } catch { /* timer only */ }
  schedule();
})();
