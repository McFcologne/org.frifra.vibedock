// Flashes the VibeDock firmware over Web Serial with esptool-js: connect, back up the whole flash, write the image of the
// chosen board at 0x0 (on ESP32 chips bootloader, partition table and app in one; the LittleFS settings stay), then
// restart the device.
import { openConsole as openSerialConsole, restart, csrf, sleep } from './serial-console.js';
const card = document.getElementById('web-flasher');
const $ = selector => card.querySelector(selector);
const button = name => $(`[data-flash="${name}"]`);
const log = $('[data-flash-log]'), progress = $('[data-flash-progress]'), deviceInfo = $('[data-flash-device]'), confirm = $('[data-flash-confirm]');
let loader, transport, serialPort, flashSize, mac, busy = false, backedUp = false;
// Set up below when the browser can flash; used by the pairing and restart further down as well.
let terminal = { writeLine() {}, write() {}, clean() {} }, disconnect = async () => {};

if (!('serial' in navigator) || !window.isSecureContext) {
  $('[data-flasher-unsupported]').hidden = false;
  for (const b of card.querySelectorAll('[data-flash],[data-pair-only],[data-restart]')) b.disabled = true;
} else {
  const { ESPLoader, Transport } = await import(card.dataset.esptool);
  terminal = { clean() { log.textContent = ''; }, writeLine(line) { write(line + '\n'); }, write(text) { write(text); } };
  function write(text) { log.hidden = false; log.textContent += text; log.scrollTop = log.scrollHeight; }
  function update() {
    button('connect').disabled = busy || !!loader;
    button('disconnect').disabled = busy || !loader;
    button('backup').disabled = busy || !loader;
    button('install').disabled = busy || !loader || !(backedUp || confirm.checked);
  }
  function show(done, total) { progress.hidden = false; progress.max = total; progress.value = done; }
  async function run(task) {
    busy = true; update();
    try { await task(); }
    catch (error) { terminal.writeLine('Error: ' + (error?.message || error)); }
    finally { busy = false; update(); }
  }
  disconnect = async () => {
    try { await transport?.disconnect(); } catch { /* already closed */ }
    loader = transport = undefined; deviceInfo.textContent = 'Not connected.'; backedUp = false;
  };
  // Connects to the port (chosen by the user, or the one already allowed after the page switched to the right board).
  const connect = port => run(async () => {
    serialPort = port; pair.hidden = true;
    transport = new Transport(port, true);
    loader = new ESPLoader({ transport, baudrate: Number($('#flash-baud').value), terminal });
    try {
      const chip = await loader.main(), expected = card.dataset.chip;
      const name = chipFamily(chip);
      if (name !== expected.toUpperCase()) {
        // Another chip: when only one board of this installation has it, that board's page opens and connects again
        // by itself (the port stays allowed); otherwise the boards with that chip are named.
        const matching = JSON.parse(card.dataset.boards || '[]').filter(b => b.chip.toUpperCase() === name);
        if (matching.length === 1) {
          terminal.writeLine(`This display has an ${name}: switching to ${matching[0].label} …`);
          await disconnect();
          location.assign(`${location.pathname}?board=${encodeURIComponent(matching[0].id)}&connect=1#web-flasher`);
          return;
        }
        throw new Error(matching.length
          ? `This display has an ${name}; several boards have it: ${matching.map(b => b.label).join(', ')}. Choose yours under Board above.`
          : `This display has an ${name}; no board of this installation has that chip.`);
      }
      flashSize = await loader.detectFlashSize();
      if (expected === 'ESP8266' && flashSize !== '4MB') throw new Error(`The display has ${flashSize} flash; this firmware is built for 4MB`);
      try { mac = await loader.chip.readMac(loader); } catch { mac = 'unknown'; }
      deviceInfo.textContent = `${chip} · MAC ${mac} · flash ${flashSize}`;
    } catch (error) { await disconnect(); throw error; }
  });
  button('connect').addEventListener('click', async () => { try { connect(await navigator.serial.requestPort()); } catch { /* no port chosen */ } });
  // Opened after switching to the right board: connect to the port allowed before, without asking again.
  if (new URLSearchParams(location.search).get('connect') === '1') {
    history.replaceState(null, '', location.pathname + '?board=' + encodeURIComponent(card.dataset.board) + '#web-flasher');
    const ports = await navigator.serial.getPorts();
    if (ports.length === 1) { terminal.writeLine('Connecting again for this board …'); connect(ports[0]); }
  }
  button('disconnect').addEventListener('click', () => run(disconnect));
  button('backup').addEventListener('click', () => run(async () => {
    const bytes = loader.flashSizeBytes(flashSize);
    terminal.writeLine(`Reading ${bytes} bytes of flash …`);
    const data = await loader.readFlash(0, bytes, (packet, done, total) => show(done, total));
    const name = `vibedock-backup-${String(mac).replaceAll(':', '')}-${new Date().toISOString().slice(0, 10)}.bin`;
    const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([data])), download: name });
    link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    terminal.writeLine(`Backup saved as ${name}. Keep it private.`);
    backedUp = true; confirm.checked = true;
  }));
  confirm.addEventListener('change', update);
  button('install').addEventListener('click', () => run(async () => {
    const response = await fetch(card.dataset.firmware, { credentials: 'same-origin' });
    if (!response.ok) throw new Error('Firmware download failed: HTTP ' + response.status);
    const data = new Uint8Array(await response.arrayBuffer());
    terminal.writeLine(`Writing firmware ${card.dataset.version} (${data.length} bytes) at 0x0 …`);
    await loader.writeFlash({ fileArray: [{ data, address: 0 }], flashMode: 'dio', flashFreq: card.dataset.chip === 'ESP8266' ? '40m' : 'keep', flashSize: card.dataset.chip === 'ESP8266' ? flashSize : 'keep',
      eraseAll: false, compress: true, reportProgress: (index, written, total) => show(written, total) });
    terminal.writeLine('Done. Restarting the device …');
    await loader.after('hard_reset');
    await disconnect();
    terminal.writeLine(`Firmware ${card.dataset.version} installed. The device shows the version while it starts.`);
    askToPair();
  }));
  update();
}

// The chip family of esptool-js' description, as the boards name it: "ESP8266EX" → ESP8266,
// "ESP32-D0WD-V3 (revision v3.1)" → ESP32, "ESP32-S3 (QFN56) (revision v0.2)" → ESP32-S3, "ESP32-C3 (QFN32)" → ESP32-C3.
function chipFamily(description) {
  const text = String(description).toUpperCase();
  if (text.startsWith('ESP8266')) return 'ESP8266';
  const variant = text.match(/^ESP32-(S2|S3|C2|C3|C5|C6|H2|P4)\b/);
  return variant ? 'ESP32-' + variant[1] : text.startsWith('ESP32') ? 'ESP32' : text.split(' ')[0];
}

// ---- pairing over USB right after the installation (the console: serial-console.js) ----
const pair = card.querySelector('[data-pair]');
const pairForm = pair.querySelector('[data-pair-form]'), pairAsk = pair.querySelector('[data-pair-ask]');
const pairState = pair.querySelector('[data-pair-state]');
let console_, device;
function say(text, error = false) { pairState.textContent = text; pairState.classList.toggle('error-text', error); }
function askToPair() { pair.hidden = false; pairAsk.hidden = false; pairForm.hidden = true; say(''); }

const openConsole = () => openSerialConsole(serialPort);
const hello = () => console_.hello();
async function closeConsole() { if (console_) { const c = console_; console_ = undefined; await c.close(); } }

pair.querySelectorAll('[data-pair-skip]').forEach(b => b.addEventListener('click', async () => { await closeConsole(); pair.hidden = true; }));
// Restarts the display like its reset button (serial-console.js); esptool restarts it itself while connected.
card.querySelector('[data-restart]').addEventListener('click', async () => {
  if (busy) return;
  try {
    if (loader) { await loader.after('hard_reset'); await disconnect(); say(''); terminal.writeLine('Display restarted.'); return; }
    await closeConsole();
    serialPort ??= await navigator.serial.requestPort();
    await restart(serialPort);
    terminal.writeLine('Display restarted.');
  } catch (error) { terminal.writeLine('Restart failed: ' + (error?.message || error)); }
});
card.querySelector('[data-pair-only]').addEventListener('click', async () => {
  if (busy) return;
  if (loader) await disconnect();  // esptool holds the port
  askToPair();
});
pair.querySelector('[data-pair-start]').addEventListener('click', async () => {
  pairAsk.hidden = true; say('Connecting to the display …');
  try {
    serialPort ??= await navigator.serial.requestPort();
    // Boards with the chip's own USB (ESP32-S3, -C3) come back as a new port after their restart.
    try { console_ = await openConsole(); }
    catch { serialPort = await navigator.serial.requestPort(); console_ = await openConsole(); }
    device = await hello();
    const list = await scanNetworks();
    choose(list[0]?.ssid ?? '');
    pairForm.elements.name.value = device.name || '';
    pairForm.hidden = false; pairForm.elements.password.focus();
    say(`${device.identifier} · firmware ${device.version}${list.length ? ` · ${list.length} networks found` : ''}`);
  } catch (error) { await closeConsole(); pairAsk.hidden = false; say(error.message, true); }
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
  try {
    const kept = pairForm.elements.ssid.value, list = await scanNetworks();
    if (!kept || !list.some(n => n.ssid === kept)) choose(list[0]?.ssid ?? kept);
    if (!pop.hidden) renderPicker();  // the open list shows the new scan, not the old one
    say(list.length ? `${list.length} networks found` : 'No networks found; type the name in the search field.', !list.length);
  } catch (error) { say(error.message, true); }
  finally { button.disabled = false; button.textContent = 'Refresh networks'; }
});
// Network picker: a dropdown with a search field over the networks the display sees (strongest first). A name that
// is not in the list (a hidden network) can be typed into the search and used as it is.
const picker = pairForm.querySelector('[data-net-pick]'), pickButton = picker.querySelector('.net-pick-button');
const pop = picker.querySelector('.net-pick-pop'), search = pop.querySelector('input'), options = pop.querySelector('ul');
let networks = [], active = 0;
const LOCK = '<svg class="net-lock" viewBox="0 0 12 12" aria-label="password"><rect x="2.5" y="5.5" width="7" height="5" rx="1"/><path d="M4 5.5V4a2 2 0 0 1 4 0v1.5" fill="none" stroke-width="1.3"/></svg>';
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
  if (!pairForm.elements.ssid.value) { say('Choose the Wi-Fi network first.', true); openPicker(); return; }
  const button = pairForm.querySelector('[type=submit]'); button.disabled = true;
  try {
    say('Pairing on the server …');
    const response = await fetch('/admin/firmware/setup', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', ...csrf }, body: JSON.stringify({ identifier: device.identifier, name: pairForm.elements.name.value, board: device.board || card.dataset.board, omitTlsPin: pairForm.elements.omitTlsPin.checked }) });
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
      if (state?.status === 'Connected') { say(`${state.name} is paired and shows its image (${state.ip}).${warnings}`); return; }
    }
    say('The display has not fetched its image yet. If the Wi-Fi password is wrong, it opens its setup Wi-Fi after the restart; it then appears under Devices once connected.', true);
  } catch (error) { say(error.message, true); }
  finally { button.disabled = false; }
});
