// Loaded in <head>: applies the stored theme before the first paint (light is the default)
// and wires every [data-theme-toggle] button once the page is ready.
(() => {
  const root = document.documentElement;
  try { if (localStorage.getItem('vibedock-theme') === 'dark') root.dataset.theme = 'dark'; } catch (e) { /* storage blocked */ }
  // Forms post and the server redirects back: the page keeps its scroll position instead of jumping to the top or an
  // anchor (admin-ui.js stores it on submit). pagereveal comes before the first paint where browsers support it.
  let target = null;
  try {
    const saved = JSON.parse(sessionStorage.getItem('vibedock-scroll') || 'null'); sessionStorage.removeItem('vibedock-scroll');
    if (saved && saved.path === location.pathname && Date.now() - saved.at < 15000) target = saved.y;
  } catch (e) { /* storage blocked */ }
  // Applied again later, since the page grows while it loads and an anchor in the address would scroll elsewhere.
  const restore = () => { if (target !== null) window.scrollTo(0, target); };
  if (target !== null) {
    // The anchor the redirect points to would scroll the page away from where the user was.
    if (location.hash) history.replaceState(history.state, '', location.pathname + location.search);
    window.addEventListener('pagereveal', restore);
    document.addEventListener('DOMContentLoaded', restore);
    window.addEventListener('load', () => { restore(); target = null; });
  }
  document.addEventListener('DOMContentLoaded', () => {
    for (const toggle of document.querySelectorAll('[data-theme-toggle]')) {
      const sync = () => {
        const dark = root.dataset.theme === 'dark';
        toggle.textContent = dark ? '☀ Light' : '☾ Dark';
        toggle.setAttribute('aria-pressed', String(dark));
      };
      toggle.addEventListener('click', () => {
        const dark = root.dataset.theme !== 'dark';
        if (dark) root.dataset.theme = 'dark'; else delete root.dataset.theme;
        try { localStorage.setItem('vibedock-theme', dark ? 'dark' : 'light'); } catch (e) { /* storage blocked */ }
        for (const other of document.querySelectorAll('[data-theme-toggle]')) other.dispatchEvent(new Event('vibedock-theme'));
      });
      toggle.addEventListener('vibedock-theme', sync);
      sync();
    }
  });
})();
