// Devices: the "can it play doom?" link of the Pico-LCD-1.3 card opens a dialog. It tells where the game data comes from and
// what is packed, then asks the server to fetch, convert and pack it (a few seconds the first time) and saves the result.
// Wrapped in a function: the other page scripts declare a global "dialog" as well.
(() => {
  const dialog = document.getElementById('doom-dialog');
  const status = dialog.querySelector('[data-doom-status]');
  const button = dialog.querySelector('[data-doom-download]');
  const text = (selector, value) => { dialog.querySelector(selector).textContent = value; };
  async function loadPlan() {
    try {
      const plan = await (await fetch(dialog.dataset.plan, { headers: { Accept: 'application/json' } })).json();
      text('[data-doom-host]', plan.host); text('[data-doom-url]', plan.url); text('[data-doom-sha]', plan.sha1);
      button.disabled = !plan.available;
      status.textContent = plan.available ? '' : 'This installation cannot build the firmware (the converter is missing).';
    } catch (error) { status.textContent = 'The plan could not be loaded.'; }
  }
  button.addEventListener('click', async () => {
    button.disabled = true; status.textContent = 'Fetching the WAD, converting and packing … (a few seconds)';
    try {
      const response = await fetch(dialog.dataset.firmware);
      if (!response.ok) throw new Error((await response.text()) || ('Error ' + response.status));
      const url = URL.createObjectURL(await response.blob());
      const link = Object.assign(document.createElement('a'), { href: url, download: 'vibedock-doom-pico2w-lcd-13.uf2' });
      document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000);
      status.textContent = 'Done. Now: BOOTSEL, plug in, copy the file onto the drive.';
    } catch (error) { status.textContent = 'Failed: ' + error.message; }
    button.disabled = false;
  });
  for (const link of document.querySelectorAll('[data-doom]')) link.addEventListener('click', () => { dialog.showModal(); loadPlan(); });
  dialog.querySelector('[data-doom-close]').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
})();
