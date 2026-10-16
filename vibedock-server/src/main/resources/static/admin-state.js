// Saving a form reloads the page; keep the scroll position and the open sections so it does not jump.
(() => {
  const KEY = 'vibedock-admin-view';
  const details = () => Array.from(document.querySelectorAll('details'));
  document.addEventListener('submit', event => {
    if (event.target.method && event.target.method.toLowerCase() !== 'post') return;
    try {
      sessionStorage.setItem(KEY, JSON.stringify({
        path: location.pathname, y: window.scrollY,
        open: details().map((d, i) => d.open ? i : -1).filter(i => i >= 0)
      }));
    } catch (e) { /* storage unavailable: the page simply starts at the top */ }
  }, true);
  let view = null;
  try { view = JSON.parse(sessionStorage.getItem(KEY)); sessionStorage.removeItem(KEY); } catch (e) { view = null; }
  if (!view || view.path !== location.pathname) return;
  const all = details();
  (view.open || []).forEach(i => { if (all[i]) all[i].open = true; });
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  window.scrollTo(0, view.y || 0);
})();
