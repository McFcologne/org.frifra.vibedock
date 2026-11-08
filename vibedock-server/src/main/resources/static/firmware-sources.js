// Firmware sources on the firmware page: "Check now" starts the check without leaving the page, and while a check runs
// (also one started by the daily schedule or by another window) the page asks the server every two seconds and
// reloads once it is over, so the result shows without a reload by hand.
(() => {
  const card = document.querySelector('[data-sources-state]');
  if (!card) return;
  const form = card.querySelector('[data-sources-check]'), button = form.querySelector('button'), status = card.querySelector('[data-sources-status]');
  const csrf = { [document.querySelector('meta[name="csrf-header"]').content]: document.querySelector('meta[name="csrf-token"]').content };
  const started = Date.now();
  let following = false;
  const busy = () => { button.disabled = true; button.textContent = 'Checking …'; status.hidden = false; };
  // Waits until the server says no check runs any more (at most five minutes), then shows the result.
  const follow = async () => {
    if (following) return;
    following = true; busy();
    let failures = 0;
    while (Date.now() - started < 300000) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      try {
        const answer = await fetch(card.dataset.sourcesState, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
        if (!answer.ok) throw new Error(String(answer.status));
        if (!(await answer.json()).running) break;
        failures = 0;
      } catch (e) { if (++failures >= 5) break; }
    }
    location.reload();
  };
  form.addEventListener('submit', async event => {
    event.preventDefault();
    busy();
    try {
      const answer = await fetch(form.action, { method: 'POST', credentials: 'same-origin', headers: csrf, body: new URLSearchParams(), redirect: 'manual' });
      if (answer.status >= 400) throw new Error(String(answer.status));
    } catch (e) { form.submit(); return; }  // without the script's help the form does the same, with a reload
    follow();
  });
  if (card.dataset.sourcesChecking === 'true') follow();
})();
