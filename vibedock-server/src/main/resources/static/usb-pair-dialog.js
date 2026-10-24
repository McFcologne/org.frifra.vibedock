// Devices → Pair device: over Wi-Fi (IP address and pairing code, a server form) or over USB (usb-pairing.js, the same
// form as Firmware → Only pair). This browser remembers the choice; a pairing error of the Wi-Fi form shows that form.
import { usbPairing } from './usb-pairing.js';
const panel = document.getElementById('invite'), KEY = 'vibedock-pair-method';
const cancel = panel.querySelector('[data-create-close]');
let port;
const pairing = usbPairing(panel.querySelector('[data-pair]'), {
  port: () => port, setPort: p => { port = p; }, board: () => panel.querySelector('#invite-board').value,
  onSkip: () => cancel.click(), onPaired: () => setTimeout(() => location.reload(), 2000) });
function show(method, remember = true) {
  for (const part of panel.querySelectorAll('[data-pair-method]')) part.hidden = part.dataset.pairMethod !== method;
  for (const choice of panel.querySelectorAll('[name="pair-method"]')) choice.checked = choice.value === method;
  if (method !== 'usb') pairing.close();
  if (remember) try { localStorage.setItem(KEY, method); } catch { /* storage blocked */ }
}
let saved = 'wifi';
try { saved = localStorage.getItem(KEY) === 'usb' ? 'usb' : 'wifi'; } catch { /* storage blocked */ }
show(panel.dataset.createError === 'true' ? 'wifi' : saved, false);
for (const choice of panel.querySelectorAll('[name="pair-method"]')) choice.addEventListener('change', () => show(choice.value));
for (const link of panel.querySelectorAll('[data-pair-method-switch]')) link.addEventListener('click', () => show(link.dataset.pairMethodSwitch));
cancel.addEventListener('click', () => pairing.close());
