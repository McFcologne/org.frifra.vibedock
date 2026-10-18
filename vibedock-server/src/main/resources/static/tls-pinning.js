// The status concerns the saved external address. A failed lookup never displays an old pin as current.
(() => {
  const panels = [...document.querySelectorAll('[data-tls-pin-status]')];
  if (!panels.length) return;
  let pending;
  function refresh() {
    if (pending) return pending;
    panels.forEach(panel => {
      panel.querySelector('[data-tls-pin-refresh]').disabled = true;
      panel.querySelector('[data-tls-pin-message]').textContent = 'Checking external HTTPS pin …';
      panel.querySelector('[data-tls-pin-value]').hidden = true;
    });
    pending = (async () => {
      try {
        const response = await fetch('/admin/tls-pin', {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}});
        if (!response.ok) throw new Error('Could not check the TLS pin. Sign in again or retry.');
        const status = await response.json();
        panels.forEach(panel => {
          panel.querySelector('[data-tls-pin-message]').textContent = status.message;
          const pin = panel.querySelector('[data-tls-pin-value]');
          pin.textContent = status.known ? status.fingerprint : '';
          pin.hidden = !status.known;
        });
      } catch (error) {
        panels.forEach(panel => {panel.querySelector('[data-tls-pin-message]').textContent = error.message;});
      } finally {
        panels.forEach(panel => {panel.querySelector('[data-tls-pin-refresh]').disabled = false;});
        pending = null;
      }
    })();
    return pending;
  }
  panels.forEach(panel => {panel.querySelector('[data-tls-pin-refresh]').addEventListener('click', refresh);});
  refresh();
})();
