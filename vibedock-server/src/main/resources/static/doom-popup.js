// Devices: the "can it play doom?" link of the Pico-LCD-1.3 card opens a dialog. It tells where the game data comes from and
// what is packed, then asks the server to fetch, convert and pack it (a few seconds the first time), shows the step the server
// is on, and saves the result.
// Wrapped in a function: the other page scripts declare a global "dialog" as well.
(() => {
  const dialog = document.getElementById('doom-dialog');
  const status = dialog.querySelector('[data-doom-status]');
  const button = dialog.querySelector('[data-doom-download]');
  const progress = dialog.querySelector('[data-doom-progress]');
  const steps = ['fetch', 'convert', 'pack', 'done'];
  const text = (selector, value) => { dialog.querySelector(selector).textContent = value; };
  // Marks the steps before the current one as done and the current one as active.
  const show = stage => {
    const at = steps.indexOf(stage);
    progress.hidden = at < 0;
    for (const item of progress.children) {
      const i = steps.indexOf(item.dataset.stage);
      item.className = i < at || stage === 'done' ? 'done' : i === at ? 'active' : '';
    }
  };
  async function loadPlan() {
    try {
      const plan = await (await fetch(dialog.dataset.plan, { headers: { Accept: 'application/json' } })).json();
      text('[data-doom-host]', plan.host); text('[data-doom-url]', plan.url); text('[data-doom-sha]', plan.sha1);
      button.disabled = !plan.available;
      status.textContent = plan.available ? '' : 'This installation cannot build the firmware (the converter is missing).';
    } catch (error) { status.textContent = 'The plan could not be loaded.'; }
  }
  button.addEventListener('click', async () => {
    button.disabled = true; status.textContent = ''; show('fetch');
    // The server knows which step it is on; ask for it while the request runs (a cached firmware answers at once).
    const poll = setInterval(async () => {
      try { const { stage } = await (await fetch(dialog.dataset.state)).json(); if (stage) show(stage); } catch (error) { /* the next poll tries again */ }
    }, 400);
    try {
      const response = await fetch(dialog.dataset.firmware);
      if (!response.ok) throw new Error((await response.text()) || ('Error ' + response.status));
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = Object.assign(document.createElement('a'), { href: url, download: 'vibedock-doom-pico2w-lcd-13.uf2' });
      document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000);
      show('done');
      status.textContent = 'Saved (' + (blob.size / 1048576).toFixed(1) + ' MB). Next: BOOTSEL, plug in, copy the file onto the drive.';
    } catch (error) { show(''); status.textContent = 'Failed: ' + error.message; }
    clearInterval(poll); button.disabled = false;
  });
  for (const link of document.querySelectorAll('[data-doom]')) link.addEventListener('click', () => { dialog.showModal(); loadPlan(); });
  dialog.querySelector('[data-doom-close]').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
})();
