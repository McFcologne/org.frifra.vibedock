// A log page follows new logs of its display (AdminEvents "logs"): it reloads when one was saved, e.g. the one asked
// for with "Read log over the network". An open request shows whether the display was asked to pull now, when its
// next pull is due and until when it may answer, counting down.
(() => {
  const page = document.querySelector('[data-log-device]'); if (!page) return;
  try {
    new EventSource('/admin/events').addEventListener('logs', event => { if (event.data === page.dataset.logDevice) location.reload(); });
  } catch { /* reload by hand */ }

  // Per log: the switch shows the server's lines between the display's own, and the download includes them while on.
  for (const toggle of document.querySelectorAll('[data-log-server]')) {
    const card = toggle.closest('.device-log'), download = card.querySelector('[data-log-download]');
    const plain = download.getAttribute('href');
    toggle.addEventListener('change', () => {
      card.classList.toggle('show-server', toggle.checked);
      download.setAttribute('href', toggle.checked ? plain + (plain.includes('?') ? '&' : '?') + 'server=true' : plain);
      if (toggle.checked) card.querySelector('details').open = true;
    });
  }

  const status = document.querySelector('[data-log-request]'); if (!status) return;
  const number = name => Number(status.dataset[name] || 0);
  const requested = number('requested'), until = number('until'), next = number('nextPull'), poll = number('poll');
  const reach = status.dataset.reach;
  const clock = ms => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const span = seconds => seconds < 90 ? `${seconds} s` : `${Math.round(seconds / 60)} min`;
  const render = () => {
    const now = Date.now();
    if (now > until) { status.classList.add('error'); status.textContent = `No log arrived: the request of ${clock(requested)} expired at ${clock(until)}. Request it again, or read it over USB.`; return false; }
    let text = `Requested at ${clock(requested)}. `;
    if (reach === 'ASKED' && now - requested < 30000) text += 'The display was asked to pull right away; the log should arrive within seconds. ';
    else if (next > 0 && now <= next) text += `${reach === 'ASKED' ? 'It has not arrived yet; ' : 'The display could not be asked directly; '}it comes with the next pull, due at ${clock(next)} (in ${span(Math.ceil((next - now) / 1000))}). `;
    else if (next > 0) text += `The pull due at ${clock(next)} is ${span(Math.round((now - next) / 1000))} late${poll ? `; the display retries at least every ${poll} s` : ''}. `;
    else text += 'It comes with the display\'s next pull. ';
    text += `The request is valid until ${clock(until)} (${span(Math.ceil((until - now) / 1000))}).`;
    status.textContent = text; return true;
  };
  if (render()) { const timer = setInterval(() => { if (!render()) clearInterval(timer); }, 1000); }
})();
