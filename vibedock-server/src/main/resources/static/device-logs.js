// A log page follows new logs of its display (AdminEvents "logs"): it reloads when one was saved, e.g. the one asked
// for with "Read log over the network".
(() => {
  const page = document.querySelector('[data-log-device]'); if (!page) return;
  try {
    new EventSource('/admin/events').addEventListener('logs', event => { if (event.data === page.dataset.logDevice) location.reload(); });
  } catch { /* reload by hand */ }
})();
