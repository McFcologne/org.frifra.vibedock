// Loaded in <head>: applies the instance appearance before the first paint (light is the default)
// and wires every [data-theme-toggle] button once the page is ready. Pages without a session (sign-in) cannot save
// it on the server: there the choice stays in this browser, which also remembers the last one saved.
(() => {
  const root = document.documentElement;
  const appearance = document.querySelector('meta[name="vibedock-ui-theme"]');
  const canSave = !!document.querySelector('meta[name="csrf-token"]');
  let local = null;
  try { local = localStorage.getItem('vibedock-theme'); } catch (e) { /* storage blocked */ }
  const remember = dark => { try { localStorage.setItem('vibedock-theme', dark ? 'dark' : 'light'); } catch (e) { /* storage blocked */ } };
  if ((!canSave && local ? local : appearance?.content) === 'dark') root.dataset.theme = 'dark';
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
      toggle.addEventListener('click', async () => {
        const dark = root.dataset.theme !== 'dark';
        const token = document.querySelector('meta[name="csrf-token"]'), header = document.querySelector('meta[name="csrf-header"]');
        if (token && header) {
          toggle.disabled = true;
          try {
            // redirect: 'error': an expired session answers with the sign-in page, which must not count as saved.
            const response = await fetch('/admin/ui-theme', {method: 'POST', redirect: 'error', headers: {[header.content]: token.content}, body: new URLSearchParams({dark: String(dark)})});
            if (!response.ok) throw new Error('save failed');
          } catch (e) { alert('The appearance could not be saved. Please try again.'); return; }
          finally { toggle.disabled = false; }
        }
        remember(dark);
        if (dark) root.dataset.theme = 'dark'; else delete root.dataset.theme;
        for (const other of document.querySelectorAll('[data-theme-toggle]')) other.dispatchEvent(new Event('vibedock-theme'));
      });
      toggle.addEventListener('vibedock-theme', sync);
      sync();
    }
  });
})();
