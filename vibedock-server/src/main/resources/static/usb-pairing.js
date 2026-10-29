// Pairing over USB (Web Serial): the display gets the Wi-Fi and this server over the cable and shows its image after
// its restart, without setup Wi-Fi or pairing code. One form for two places, the markup is the fragment usbPairing in
// fragments.html: the firmware page (after installing, or "Only pair", firmware-flasher.js) and Devices → Pair device →
// USB (usb-pair-dialog.js). Change both through this module and that fragment.
// options: port() and setPort(p): the serial port shared with the page; board(): the board for firmware that does not
// report its own; onSkip(): after "Not now"/"Cancel"; onPaired(state): once the display shows its image.
import { openConsole as openSerialConsole, csrf, sleep, chooseDisplayPort } from './serial-console.js';

export function usbPairing(pair, { port = () => undefined, setPort = () => {}, board = () => undefined, onSkip = () => { pair.hidden = true; }, onPaired = () => {} } = {}) {
  const pairForm = pair.querySelector('[data-pair-form]'), pairAsk = pair.querySelector('[data-pair-ask]');
  const pairState = pair.querySelector('[data-pair-state]');
  let console_, device;
  let serialPort;  // read from and written back to the page through port() and setPort()
  function say(text, error = false) { pairState.textContent = text; pairState.classList.toggle('error-text', error); }
  function askToPair() { pair.hidden = false; pairAsk.hidden = false; pairForm.hidden = true; say(''); }
  const supported = 'serial' in navigator && window.isSecureContext;
  if (!supported) { pair.querySelector('[data-pair-unsupported]').hidden = false; pair.querySelector('[data-pair-start]').disabled = true; }

  const openConsole = () => openSerialConsole(serialPort);
  const hello = () => console_.hello();
  async function closeConsole() { if (console_) { const c = console_; console_ = undefined; await c.close(); } }
  // Every way out (Cancel, Escape, the other pairing way, a restart): the port is freed and the form goes back to its
  // first step, so nothing can be sent over a connection that is closed. Whether the area shows stays with the page.
  // A connection still being set up belongs to an attempt; close() starts a new one, so the old attempt notices after
  // each wait that it was given up, and closes a port it opened only afterwards.
  let attempt = 0;
  async function close() { attempt++; await closeConsole(); pairAsk.hidden = false; pairForm.hidden = true; say(''); }

  pair.querySelectorAll('[data-pair-skip]').forEach(b => b.addEventListener('click', async () => { await close(); askToPair(); onSkip(); }));
  pair.querySelector('[data-pair-start]').addEventListener('click', async () => {
    const mine = ++attempt, current = () => mine === attempt;
    pairAsk.hidden = true; say('Connecting to the display …');
    let opened;
    try {
      serialPort = port() ?? await chooseDisplayPort(); setPort(serialPort);
      if (!current()) return;
      if (!serialPort) { pairAsk.hidden = false; say(''); return; }
      // Boards with the chip's own USB (ESP32-S3, -C3) come back as a new port after their restart.
      // Choosing none ends it here, without the browser's own dialog on top.
      try { opened = await openConsole(); }
      catch {
        if (!current()) return;
        serialPort = await chooseDisplayPort(); setPort(serialPort);
        if (!current()) return;
        if (!serialPort) { pairAsk.hidden = false; say(''); return; }
        opened = await openConsole();
      }
      // Given up while the port opened (Cancel, Escape, Wi-Fi): close it again instead of keeping it.
      if (!current()) { await opened.close(); return; }
      console_ = opened;
      device = await hello();
      const list = await scanNetworks();
      if (!current()) return;
      choose(list[0]?.ssid ?? '');
      pairForm.elements.name.value = device.name || '';
      pairForm.hidden = false; pairForm.elements.password.focus();
      say(`${device.identifier} · firmware ${device.version}${list.length ? ` · ${list.length} networks found` : ''}`);
    } catch (error) {
      if (!current()) { if (opened && opened !== console_) await opened.close().catch(() => {}); return; }
      await closeConsole(); pairAsk.hidden = false; say(error.message, true);
    }
  });
  // The networks the display sees, strongest first; empty when it does not answer (the name can be typed instead).
  async function scanNetworks() {
    say('Looking for Wi-Fi networks near the display …');
    const scanned = console_.wait('VIBEDOCK-NETWORKS ', 20000);
    await console_.send('VIBEDOCK-SCAN');
    let list = []; try { list = JSON.parse((await scanned).slice('VIBEDOCK-NETWORKS '.length)); } catch { /* type the name instead */ }
    networks = list;
    return list;
  }
  pair.querySelector('[data-net-refresh]').addEventListener('click', async event => {
    const button = event.currentTarget; if (button.disabled || !console_) return;
    button.disabled = true; button.textContent = 'Scanning …';
    // An open list shows that it is being replaced, not the old networks until the scan ends.
    if (!pop.hidden) options.innerHTML = '<li class="net-empty">Scanning …</li>';
    try {
      const kept = pairForm.elements.ssid.value, list = await scanNetworks();
      if (!kept || !list.some(n => n.ssid === kept)) choose(list[0]?.ssid ?? kept);
      if (!pop.hidden) renderPicker();  // the open list shows the new scan, not the old one
      say(list.length ? `${list.length} networks found` : 'No networks found; type the name in the search field.', !list.length);
    } catch (error) { say(error.message, true); }
    finally { button.disabled = false; button.textContent = 'Refresh networks'; if (!pop.hidden) renderPicker(); }
  });
  // Network picker: a dropdown with a search field over the networks the display sees (strongest first). A name that
  // is not in the list (a hidden network) can be typed into the search and used as it is.
  const picker = pairForm.querySelector('[data-net-pick]'), pickButton = picker.querySelector('.net-pick-button');
  const pop = picker.querySelector('.net-pick-pop'), search = pop.querySelector('input'), options = pop.querySelector('ul');
  let networks = [], active = 0;
  const LOCK = '<svg class="net-lock" viewBox="0 0 12 12" aria-label="password"><rect x="2.5" y="5.5" width="7" height="5" rx="1"/><path d="M4 5.5V4a2 2 0 0 1 4 0v1.5" fill="none" stroke-width="1.3"/></svg>';
  // The same bars as on the device card and in the overview (DeviceDiagnostics.Network.level).
  function bars(rssi) {
    const level = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -75 ? 2 : 1;
    return `<svg class="net-bars" viewBox="0 0 16 12" aria-label="${rssi} dBm">${[0, 1, 2, 3].map(i =>
      `<rect x="${i * 4}" y="${9 - i * 3}" width="3" height="${3 + i * 3}" rx=".8"${i < level ? '' : ' class="off"'}/>`).join('')}</svg>`;
  }
  function choose(ssid) {
    pairForm.elements.ssid.value = ssid;
    picker.querySelector('[data-net-value]').textContent = ssid || 'Choose a network …';
    closePicker(false);
  }
  function renderPicker() {
    const query = search.value.trim(), lower = query.toLowerCase();
    const items = networks.filter(n => n.ssid.toLowerCase().includes(lower)).map(n => ({ ssid: n.ssid, html:
      `<span class="net-name"></span><span class="net-meta">${n.open ? 'open' : LOCK} ${bars(n.rssi)}</span>` }));
    if (query && !networks.some(n => n.ssid === query)) items.push({ ssid: query, html: '<span class="net-name"></span><span class="net-meta">hidden network</span>', typed: true });
    options.replaceChildren();
    active = Math.min(active, Math.max(items.length - 1, 0));
    items.forEach((item, i) => {
      const li = document.createElement('li'); li.setAttribute('role', 'option'); li.innerHTML = item.html;
      li.querySelector('.net-name').textContent = item.typed ? `Use “${item.ssid}”` : item.ssid;
      li.dataset.ssid = item.ssid; li.classList.toggle('active', i === active);
      li.setAttribute('aria-selected', String(item.ssid === pairForm.elements.ssid.value));
      li.addEventListener('mousedown', e => { e.preventDefault(); choose(item.ssid); });
      options.append(li);
    });
    if (!items.length) options.innerHTML = '<li class="net-empty">No network found</li>';
  }
  function openPicker() {
    pop.hidden = false; pickButton.setAttribute('aria-expanded', 'true'); search.value = '';
    active = Math.max(0, networks.findIndex(n => n.ssid === pairForm.elements.ssid.value)); renderPicker(); search.focus();
  }
  function closePicker(focus = true) { if (pop.hidden) return; pop.hidden = true; pickButton.setAttribute('aria-expanded', 'false'); if (focus) pickButton.focus(); }
  pickButton.addEventListener('click', () => pop.hidden ? openPicker() : closePicker());
  search.addEventListener('input', () => { active = 0; renderPicker(); });
  search.addEventListener('keydown', e => {
    const count = options.querySelectorAll('[role=option]').length;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); if (count) { active = (active + (e.key === 'ArrowDown' ? 1 : count - 1)) % count; renderPicker(); options.children[active]?.scrollIntoView({ block: 'nearest' }); } }
    else if (e.key === 'Enter') { e.preventDefault(); const li = options.querySelectorAll('[role=option]')[active]; if (li) choose(li.dataset.ssid); }
    else if (e.key === 'Escape') { e.preventDefault(); closePicker(); }
  });
  document.addEventListener('mousedown', e => { if (!picker.contains(e.target)) closePicker(false); });
  pairForm.addEventListener('submit', async event => {
    event.preventDefault();
    if (!console_) { await close(); say('The connection to the display was closed. Choose Pair now again.', true); return; }
    if (!pairForm.elements.ssid.value) { say('Choose the Wi-Fi network first.', true); openPicker(); return; }
    const button = pairForm.querySelector('[type=submit]'); button.disabled = true;
    try {
      say('Pairing on the server …');
      const response = await fetch('/admin/firmware/setup', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', ...csrf }, body: JSON.stringify({ identifier: device.identifier, name: pairForm.elements.name.value, board: device.board || board(), omitTlsPin: pairForm.elements.omitTlsPin.checked }) });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || 'Pairing failed: HTTP ' + response.status);
      const setup = { ...result.pairing, ssid: pairForm.elements.ssid.value, password: pairForm.elements.password.value };
      say('Sending Wi-Fi and pairing to the display …');
      let answer;
      for (let attempt = 0; attempt < 3 && !answer?.endsWith(' OK'); attempt++) {
        const reply = console_.wait('VIBEDOCK-SETUP ', 8000);
        await console_.send('VIBEDOCK-SETUP ' + JSON.stringify(setup));
        try { answer = await reply; } catch { answer = undefined; }
        if (answer && !answer.endsWith(' OK') && !answer.includes('invalid request')) throw new Error('The display refused: ' + answer.slice('VIBEDOCK-SETUP ERROR '.length));
      }
      if (!answer?.endsWith(' OK')) throw new Error('The display did not confirm the setup. Try again.');
      await closeConsole(); pairForm.hidden = true;
      const warnings = result.warnings?.length ? ` Note: ${result.warnings.join('; ')}.` : '';
      say(`Saved on the display. It restarts and connects to ${setup.ssid} …${warnings}`);
      for (let i = 0; i < 40; i++) {
        await sleep(3000);
        const state = await fetch('/admin/firmware/setup/' + encodeURIComponent(device.identifier), { credentials: 'same-origin' }).then(r => r.json()).catch(() => null);
        if (state?.status === 'Connected') { say(`${state.name} is paired and shows its image (${state.ip}).${warnings}`); onPaired(state); return; }
      }
      say('The display has not fetched its image yet. If the Wi-Fi password is wrong, it opens its setup Wi-Fi after the restart; it then appears under Devices once connected.', true);
    } catch (error) { say(error.message, true); }
    finally { button.disabled = false; }
  });
  return { ask: askToPair, close, say };
}
