// Flashes the VibeDock firmware over Web Serial with esptool-js: connect, back up the whole flash, write the image of the
// chosen board at 0x0 (on ESP32 chips bootloader, partition table and app in one; the LittleFS settings stay), then
// restart the device.
import { restart, chooseDisplayPort } from './serial-console.js';
import { usbPairing } from './usb-pairing.js';
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
    await closeConsole();  // the pairing's console holds the port esptool needs
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
      // esptool-js marks the ESP8266's missing SPI length registers with 0 instead of null, so its flash ID query takes
      // the ESP32 path and reads 0 (the warning above). With null it uses the ESP8266 layout, like the Python esptool.
      if (name === 'ESP8266' && loader.chip.SPI_MOSI_DLEN_OFFS === 0) {
        loader.chip.SPI_MOSI_DLEN_OFFS = null; loader.chip.SPI_MISO_DLEN_OFFS = null;
        terminal.writeLine('Reading the flash ID again with the ESP8266 SPI layout …');
      }
      flashSize = await loader.detectFlashSize();
      if (!flashSize) throw new Error('The flash chip of the display does not answer (Flash ID 0): connect again, with a lower speed or another cable; nothing was written');
      if (expected === 'ESP8266' && flashSize !== '4MB') throw new Error(`The display has ${flashSize} flash; this firmware is built for 4MB`);
      try { mac = await loader.chip.readMac(loader); } catch { mac = 'unknown'; }
      deviceInfo.textContent = `${chip} · MAC ${mac} · flash ${flashSize}`;
    } catch (error) { await disconnect(); throw error; }
  });
  button('connect').addEventListener('click', async () => { try { const port = await chooseDisplayPort(); if (port) connect(port); } catch { /* no port chosen */ } });
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

// ---- pairing over USB right after the installation, or alone (usb-pairing.js, shared with Devices → Pair device) ----
const pair = card.querySelector('[data-pair]');
const pairing = usbPairing(pair, { port: () => serialPort, setPort: p => { serialPort = p; }, board: () => card.dataset.board });
const askToPair = pairing.ask, say = pairing.say, closeConsole = pairing.close;
// Restarts the display like its reset button (serial-console.js); esptool restarts it itself while connected.
card.querySelector('[data-restart]').addEventListener('click', async () => {
  if (busy) return;
  try {
    if (loader) { await loader.after('hard_reset'); await disconnect(); say(''); terminal.writeLine('Display restarted.'); return; }
    await closeConsole();
    serialPort ??= await chooseDisplayPort();
    if (!serialPort) return;
    await restart(serialPort);
    terminal.writeLine('Display restarted.');
  } catch (error) { terminal.writeLine('Restart failed: ' + (error?.message || error)); }
});
card.querySelector('[data-pair-only]').addEventListener('click', async () => {
  if (busy) return;
  if (loader) await disconnect();  // esptool holds the port
  askToPair();
});
