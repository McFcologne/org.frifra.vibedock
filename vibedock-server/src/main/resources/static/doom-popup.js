// Devices: the "can it play doom?" link of the Pico-LCD-1.3 card opens a dialog with the Doom firmware to download.
const dialog = document.getElementById('doom-dialog');
for (const link of document.querySelectorAll('[data-doom]')) link.addEventListener('click', () => dialog.showModal());
dialog.querySelector('[data-doom-close]').addEventListener('click', () => dialog.close());
dialog.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
