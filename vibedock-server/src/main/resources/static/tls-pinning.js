// The status concerns the saved external address. A failed lookup never displays an old pin as current.
(() => {
  const panels = [...document.querySelectorAll('[data-tls-pin-status]')];
  if (!panels.length) return;
  let pending;
  function refresh() {
    if (pending) return pending;
    const started = performance.now();
    const summary = result => 'Last refresh: ' + new Date().toLocaleTimeString() + ' · ' + result + ' · ' + Math.round(performance.now() - started) + ' ms';
    panels.forEach(panel => {
      panel.setAttribute('aria-busy', 'true');
      panel.querySelector('[data-tls-pin-label]').textContent = 'Checking …';
      const action = panel.querySelector('[data-tls-pin-action]');
      action.hidden = false;action.textContent = 'Checking TLS pin …';
      panel.querySelector('[data-tls-pin-refresh]').disabled = true;
      panel.querySelector('[data-tls-pin-message]').textContent = 'Checking external HTTPS pin …';
      panel.querySelector('[data-tls-pin-value]').hidden = true;
      panel.querySelector('[data-tls-pin-route]').hidden = true;
      panel.querySelector('[data-tls-cert]').hidden = true;
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
          const route = panel.querySelector('[data-tls-pin-route]');
          const routes = {'system-dns':'System DNS', 'lan-fallback':'LAN fallback', 'public-dns':'Public DNS fallback (8.8.8.8 / 1.1.1.1)', 'configured-probe':'Configured TLS probe'};
          route.textContent = (routes[status.route] || 'Unknown route') + (status.target ? ' → ' + status.target : '') + (status.cached ? ' · cached (up to 1 minute)' : '');
          route.hidden = !status.known || !status.route;
          const details = panel.querySelector('[data-tls-cert]'), cert = status.certificate;
          details.hidden = !status.known || !cert;
          if (cert) {
            for (const key of ['subject', 'issuer', 'protocol', 'sha256']) details.querySelector('[data-cert-' + key + ']').textContent = cert[key] || '—';
            details.querySelector('[data-cert-not-before]').textContent = cert.notBefore ? new Date(cert.notBefore).toLocaleString() : '—';
            details.querySelector('[data-cert-not-after]').textContent = cert.notAfter ? new Date(cert.notAfter).toLocaleString() : '—';
          }
          panel.querySelector('[data-tls-pin-action]').textContent = summary(status.known ? 'Pin available' : 'Pin unavailable');
        });
      } catch (error) {
        panels.forEach(panel => {
          panel.querySelector('[data-tls-pin-message]').textContent = error.message;
          panel.querySelector('[data-tls-pin-action]').textContent = summary('Failed');
        });
      } finally {
        panels.forEach(panel => {
          panel.setAttribute('aria-busy', 'false');
          panel.querySelector('[data-tls-pin-label]').textContent = 'Check pin again';
          panel.querySelector('[data-tls-pin-refresh]').disabled = false;
        });
        pending = null;
      }
    })();
    return pending;
  }
  panels.forEach(panel => {panel.querySelector('[data-tls-pin-refresh]').addEventListener('click', refresh);});
  refresh();
})();
