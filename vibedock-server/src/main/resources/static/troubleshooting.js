// Troubleshooting: reads a display's log over USB, whatever board and firmware it has (serial-console.js).
import { openConsole, showAndSaveLog, restart } from './serial-console.js';
const card = document.getElementById('usb-log');
const output = card.querySelector('[data-usb-output]'), buttons = card.querySelectorAll('[data-usb-log],[data-usb-restart]');
const writeLine = line => { output.hidden = false; output.textContent += line + '\n'; output.scrollTop = output.scrollHeight; };
let port, busy = false;
// New logs (AdminEvents "logs"): the table of saved logs is fetched again and swapped in; the USB output stays.
try {
  new EventSource('/admin/events').addEventListener('logs', async () => {
    const table = document.querySelector('[data-log-targets]'); if (!table) return;
    const page = new DOMParser().parseFromString(await (await fetch(location.pathname, { credentials: 'same-origin' })).text(), 'text/html');
    const fresh = page.querySelector('[data-log-targets]'); if (fresh) table.replaceWith(document.importNode(fresh, true));
  });
} catch { /* reload by hand */ }

if (!('serial' in navigator) || !window.isSecureContext) {
  card.querySelector('[data-usb-unsupported]').hidden = false;
  buttons.forEach(b => b.disabled = true);
}
async function run(task) {
  if (busy) return; busy = true; buttons.forEach(b => b.disabled = true);
  try { await task(); } finally { busy = false; buttons.forEach(b => b.disabled = false); }
}
// The port chosen once stays; when it is gone (a board with the chip's own USB after a restart) the browser asks again.
async function chosen(open) {
  port ??= await navigator.serial.requestPort();
  try { return await open(port); }
  catch { port = await navigator.serial.requestPort(); return open(port); }
}
card.querySelector('[data-usb-log]').addEventListener('click', () => run(async () => {
  output.textContent = ''; let console_;
  try {
    console_ = await chosen(openConsole);
    writeLine('Connected, asking the display …');
    const info = await console_.hello();
    const lines = await console_.readLog(info);
    output.textContent = '';
    await showAndSaveLog(info, lines, writeLine);
  } catch (error) { writeLine('Reading the display log failed: ' + (error?.message || error)); }
  finally { await console_?.close(); }
}));
card.querySelector('[data-usb-restart]').addEventListener('click', () => run(async () => {
  try { await chosen(restart); writeLine('Display restarted.'); }
  catch (error) { writeLine('Restart failed: ' + (error?.message || error)); }
}));
