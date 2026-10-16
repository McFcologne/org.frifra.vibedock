// Own firmware build on the firmware page: sends the chosen .bin files one by one to /admin/firmware/upload, which
// reads version and board from each file, and reloads the page when the build is complete.
(() => {
  const card = document.querySelector('[data-own-firmware]');
  if (!card) return;
  const files = card.querySelector('[data-own-files]'), button = card.querySelector('[data-own-upload]'), state = card.querySelector('[data-own-state]');
  const csrf = { [document.querySelector('meta[name="csrf-header"]').content]: document.querySelector('meta[name="csrf-token"]').content };
  const say = (text, error = false) => { state.textContent = text; state.classList.toggle('error-text', error); };
  files.addEventListener('change', () => { button.disabled = files.files.length === 0; say(''); });
  button.addEventListener('click', async () => {
    button.disabled = true;
    let complete = false;
    try {
      for (const file of files.files) {
        say(`Uploading ${file.name} …`);
        const response = await fetch('/admin/firmware/upload?board=' + encodeURIComponent(card.dataset.board), {
          method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/octet-stream', ...csrf }, body: file });
        const answer = await response.json().catch(() => ({ error: 'HTTP ' + response.status }));
        if (!response.ok) throw new Error(`${file.name}: ${answer.error || 'HTTP ' + response.status}`);
        complete = answer.complete;
        say(`${file.name}: ${answer.kind === 'usb' ? 'USB image' : 'app'} of ${answer.version} stored.`);
      }
      if (complete) { say('Build stored, reloading …'); location.reload(); }
      else say('Stored. Upload the other file of the same build (firmware.bin and flash.bin) to complete it.');
    } catch (error) {
      say(error.message, true);
    } finally {
      button.disabled = files.files.length === 0;
    }
  });
})();
