// Devices: the Metrics button of a card opens a dialog with the charts of that display (the last 6 h, or 24 h when the
// display has few samples; the server decides). The page of all metrics is one link away.
const dialog = document.getElementById('metrics-dialog'), body = dialog.querySelector('[data-metrics-body]');
for (const button of document.querySelectorAll('[data-metrics]')) button.addEventListener('click', async () => {
  body.textContent = 'Loading …';
  dialog.showModal();
  try {
    const response = await fetch(button.dataset.metricsUrl, { credentials: 'same-origin', headers: { Accept: 'text/html' } });
    if (!response.ok) throw new Error(response.status);
    body.innerHTML = await response.text();  // the server's own markup (inline SVG charts), no user text
  } catch { body.textContent = 'The metrics could not be loaded.'; }
});
dialog.querySelector('[data-metrics-close]').addEventListener('click', () => dialog.close());
dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
