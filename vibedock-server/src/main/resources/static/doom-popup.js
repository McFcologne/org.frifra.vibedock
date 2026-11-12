// Devices: the "can it play doom?" link of the Pico-LCD-1.3 card opens a dialog. It tells where the game data comes from and
// what is packed, then asks the server to fetch, convert and pack it (a few seconds the first time), shows the step the server
// is on, and saves the result.
// Wrapped in a function: the other page scripts declare a global "dialog" as well.
(() => {
  const dialog = document.getElementById('doom-dialog');
  const status = dialog.querySelector('[data-doom-status]');
  const button = dialog.querySelector('[data-doom-download]');
  const term = dialog.querySelector('[data-doom-progress]');
  const fill = term.querySelector('.bar i');
  const percent = term.querySelector('.bar span');
  const steps = ['fetch', 'convert', 'pack', 'done'];
  // Where the bar should be while a step runs; it eases towards that and creeps on, so it never stands still.
  const goal = { fetch: 38, convert: 78, pack: 96, done: 100 };
  let shown = 0, target = 0, frame = 0;
  const draw = () => {
    shown += (target - shown) * 0.05 + (target < 100 && target > 0 ? 0.03 : 0);
    if (target === 100 && target - shown < 0.3) shown = 100;
    fill.style.width = shown + '%'; percent.textContent = Math.round(shown) + '%';
    frame = shown < 100 && target > 0 ? requestAnimationFrame(draw) : 0;
  };
  const text = (selector, value) => { dialog.querySelector(selector).textContent = value; };
  // Marks the steps before the current one as done and the current one as active; '' hides the block.
  const show = (stage, failed) => {
    const at = steps.indexOf(stage);
    term.classList.toggle('idle', at < 0 && !failed);
    term.classList.toggle('finished', stage === 'done'); term.classList.toggle('failed', !!failed);
    for (const row of term.querySelectorAll('.row')) {
      const i = steps.indexOf(row.dataset.stage);
      row.className = 'row' + (failed ? '' : i < at || stage === 'done' ? ' done' : i === at ? ' active' : '');
    }
    if (failed) { target = shown; return; }
    if (at < 0) { shown = target = 0; fill.style.width = '0'; percent.textContent = '0%'; return; }
    target = Math.max(target, goal[stage]);
    if (!frame) frame = requestAnimationFrame(draw);
  };
  async function loadPlan() {
    try {
      const plan = await (await fetch(dialog.dataset.plan, { headers: { Accept: 'application/json' } })).json();
      const hosts = [...new Set(plan.sources.map(source => source.host))];
      text('[data-doom-host]', hosts.length ? hosts.join(' (or ') + (hosts.length > 1 ? ')'.repeat(hosts.length - 1) : '') : 'no source set');
      text('[data-doom-host2]', hosts[0] || '?'); text('[data-doom-url]', plan.sources.map(source => source.url).join(' · ') || 'none'); text('[data-doom-sha]', plan.sha1);
      button.disabled = !plan.available || !hosts.length;
      status.textContent = !plan.available ? 'This installation cannot build the firmware (the converter is missing).' : !hosts.length ? 'No source for the game data is set.' : '';
    } catch (error) { status.textContent = 'The plan could not be loaded.'; }
  }
  button.addEventListener('click', async () => {
    button.disabled = true; status.textContent = ''; shown = target = 0; show('fetch');
    // The server knows which step it is on; ask for it while the request runs (a cached firmware answers at once).
    const poll = setInterval(async () => {
      try { const { stage, source } = await (await fetch(dialog.dataset.state)).json(); if (source) text('[data-doom-host2]', source); if (stage) show(stage); } catch (error) { /* the next poll tries again */ }
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
    } catch (error) { show(term.classList.contains('idle') ? '' : 'fetch', true); status.textContent = 'Failed: ' + error.message; }
    clearInterval(poll); button.disabled = false;
  });
  for (const link of document.querySelectorAll('[data-doom]')) link.addEventListener('click', () => { dialog.showModal(); loadPlan(); });
  dialog.querySelector('[data-doom-close]').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
})();
