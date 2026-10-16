// Shared page interactions; all mutations still use the existing server forms.
(() => {
  for (const panel of document.querySelectorAll('[data-create-panel]')) {
    const trigger = document.querySelector(`[data-create-open="${panel.id}"]`);
    const cancel = panel.querySelector('[data-create-close]');
    if (!trigger || !cancel) continue;
    function show(open, focus = true) {
      panel.hidden = !open;
      trigger.hidden = open;
      trigger.setAttribute('aria-expanded', String(open));
      if (focus) (open ? panel.querySelector('input, select') : trigger)?.focus();
    }
    trigger.addEventListener('click', () => show(true));
    cancel.addEventListener('click', () => show(false));
    panel.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !event.defaultPrevented && !event.isComposing) {
        event.preventDefault(); show(false);
      }
    });
    show(panel.dataset.createError === 'true', false);
  }
  // Confirmations of destructive buttons (data-confirm) and copy buttons with a short "Copied" note: data-copy holds
  // the text, data-copy-from the selector of an element whose text is copied (e.g. a log).
  document.addEventListener('click', event => {
    const confirmed = event.target.closest('[data-confirm]');
    if (confirmed && !window.confirm(confirmed.dataset.confirm)) { event.preventDefault(); return; }
    const copy = event.target.closest('[data-copy],[data-copy-from]'); if (!copy) return;
    const text = copy.dataset.copyFrom ? document.querySelector(copy.dataset.copyFrom)?.textContent ?? '' : copy.dataset.copy;
    const title = copy.title;
    navigator.clipboard.writeText(text).then(() => { copy.classList.add('copied'); copy.title = 'Copied'; setTimeout(() => { copy.classList.remove('copied'); copy.title = title; }, 1500); });
  });
  // Posting a form reloads the page; remember the scroll position for it (theme.js restores it).
  document.addEventListener('submit', event => {
    const form = event.target;
    if (event.defaultPrevented || (form.method || '').toLowerCase() !== 'post') return;
    try { sessionStorage.setItem('vibedock-scroll', JSON.stringify({ path: location.pathname, y: window.scrollY, at: Date.now() })); } catch (e) { /* storage blocked */ }
  });
  // Web displays: the external address switch saves in place and swaps the links, without reloading the page.
  document.addEventListener('change', async event => {
    const box = event.target.closest('[data-web-external]'); if (!box) return;
    const form = box.form, card = form.closest('.client-card'), body = new URLSearchParams(new FormData(form));
    if (!box.checked) body.set('external', 'false');
    const token = document.querySelector('meta[name="csrf-token"]').content, header = document.querySelector('meta[name="csrf-header"]').content;
    box.disabled = true;
    try {
      const response = await fetch(form.action, { method: 'POST', body, headers: { Accept: 'application/json', [header]: token } });
      if (!response.ok) throw new Error(String(response.status));
      const links = await response.json();
      card.querySelectorAll('.web-link').forEach((row, i) => { const url = i === 0 ? links.page : links.image;
        row.querySelector('code').textContent = url; row.querySelector('[data-copy]').dataset.copy = url; const open = row.querySelector('a'); if (open) open.href = url; });
    } catch { box.checked = !box.checked; }
    box.disabled = false;
  });
  // Cards with a grip (devices, data sources) are reordered like the bands in the design editor: drag the card (not
  // from a button, field or link), the card under it is outlined, dropping places it there. The arrow keys on the grip
  // move a card as well. The new order is saved at once.
  for (const list of document.querySelectorAll('[data-sortable]')) {
    let dragged = null;
    const cards = () => [...list.querySelectorAll(':scope > [data-sort-id]')];
    const save = async () => {
      const token = document.querySelector('meta[name="csrf-token"]').content, header = document.querySelector('meta[name="csrf-header"]').content;
      try {
        const response = await fetch(list.dataset.sortable, { method: 'POST', headers: { 'Content-Type': 'application/json', [header]: token },
          body: JSON.stringify({ ids: cards().map(c => c.dataset.sortId) }) });
        if (!response.ok) throw new Error(String(response.status));
      } catch { location.reload(); }
    };
    const clear = () => list.querySelectorAll('.drag-over,.dragging').forEach(c => c.classList.remove('drag-over', 'dragging'));
    for (const card of cards()) {
      const grip = card.querySelector('[data-sort-grip]'); if (!grip) continue;
      card.draggable = true;
      grip.addEventListener('keydown', event => {
        const all = cards(), i = all.indexOf(card);
        if ((event.key === 'ArrowUp' || event.key === 'ArrowLeft') && i > 0) all[i - 1].before(card);
        else if ((event.key === 'ArrowDown' || event.key === 'ArrowRight') && i < all.length - 1) all[i + 1].after(card);
        else return;
        event.preventDefault(); grip.focus(); save();
      });
      card.addEventListener('dragstart', event => {
        const from = event.target.closest?.('button,select,input,textarea,a,code,label,img');
        if (from && from !== grip) { event.preventDefault(); return; }
        dragged = card; card.classList.add('dragging');
        event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', card.dataset.sortId);
      });
      card.addEventListener('dragover', event => {
        if (!dragged || dragged === card) return;
        event.preventDefault(); event.dataTransfer.dropEffect = 'move';
        if (!card.classList.contains('drag-over')) { clear(); dragged.classList.add('dragging'); card.classList.add('drag-over'); }
      });
      card.addEventListener('dragleave', event => { if (!card.contains(event.relatedTarget)) card.classList.remove('drag-over'); });
      card.addEventListener('drop', event => {
        event.preventDefault(); if (!dragged || dragged === card) return;
        const all = cards();
        if (all.indexOf(dragged) < all.indexOf(card)) card.after(dragged); else card.before(dragged);
        dragged = null; clear(); save();
      });
      card.addEventListener('dragend', () => { dragged = null; clear(); });
    }
  }
  // Previews keep the case width and follow the image's aspect ratio (display types other than 240 x 240).
  const fitRatio = image => { const device = image.closest('.device');
    if (device && image.naturalWidth) device.style.setProperty('--ratio', String(image.naturalHeight / image.naturalWidth)); };
  document.addEventListener('load', event => { if (event.target instanceof HTMLImageElement) fitRatio(event.target); }, true);
  document.querySelectorAll('.device img').forEach(image => { if (image.complete) fitRatio(image); });
  // Previews: click (or Enter) shows the whole device, case and screen, enlarged; a click or Escape closes it.
  let lightbox;
  for (const device of document.querySelectorAll('.device')) {
    if (!device.querySelector('img')) continue;
    device.tabIndex = 0; device.title = 'Click to enlarge';
    device.setAttribute('role', 'button'); device.setAttribute('aria-haspopup', 'dialog');
    device.setAttribute('aria-label', 'Enlarge preview');
    const open = () => {
      if (!lightbox) {
        lightbox = document.createElement('dialog');
        lightbox.className = 'device-lightbox'; lightbox.setAttribute('aria-label', 'Enlarged preview');
        lightbox.addEventListener('click', () => lightbox.close());
        // The close event can arrive after the preview was opened again: only clear a dialog that is still closed.
        lightbox.addEventListener('close', () => { if (!lightbox.open) lightbox.replaceChildren(); });
        document.body.append(lightbox);
      }
      const copy = device.cloneNode(true);
      for (const attribute of ['tabindex', 'title', 'role', 'aria-haspopup', 'aria-label']) copy.removeAttribute(attribute);
      copy.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'));
      lightbox.replaceChildren(copy); lightbox.showModal();
    };
    device.addEventListener('click', open);
    device.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); }
    });
  }
  // A chosen file is read into a form field: the administration accepts no multipart uploads.
  for (const input of document.querySelectorAll('[data-file-into]')) {
    const target = document.getElementById(input.dataset.fileInto);
    input.addEventListener('change', async () => {
      const file = input.files[0];
      input.setCustomValidity(file && file.size > 1048576 ? 'This file is too large for a VibeDock export.' : '');
      target.value = file && file.size <= 1048576 ? await file.text() : '';
      input.reportValidity();
    });
  }
  for (const input of document.querySelectorAll('[data-same-as]')) {
    const other = document.getElementById(input.dataset.sameAs);
    const check = () => input.setCustomValidity(input.value === other.value ? '' : 'The passwords do not match.');
    input.addEventListener('input', check); other.addEventListener('input', check);
  }
  for (const form of document.querySelectorAll('[data-dirty-form]')) {
    const actions = form.querySelector('[data-dirty-actions]');
    if (!actions) continue;
    const fields = Array.from(form.querySelectorAll('input[name]:not([type=hidden]), select[name], textarea[name]'));
    const value = field => field.type === 'checkbox' ? field.checked : field.value;
    const original = fields.map(value);
    const update = () => { actions.hidden = !fields.some((field, i) => value(field) !== original[i]); };
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    // Restored browser form values are checked against the server-rendered defaults.
    fields.forEach((field, i) => { original[i] = field.type === 'checkbox' ? field.defaultChecked : field.tagName === 'SELECT' ? Array.from(field.options).find(o => o.defaultSelected)?.value ?? field.options[0]?.value : field.defaultValue; });
    window.addEventListener('pageshow', update);
    update();
  }
})();

// Dialogs opened by [data-dialog-open="<id>"] (e.g. a data source's Manage); a click on the backdrop or a
// [data-dialog-close] button closes them. Their fields still belong to the page's form and are saved with it.
document.addEventListener('click', event => {
  const opener = event.target.closest('[data-dialog-open]');
  if (opener) { document.getElementById(opener.dataset.dialogOpen)?.showModal(); return; }
  const closer = event.target.closest('[data-dialog-close]');
  if (closer) { closer.closest('dialog')?.close(); return; }
  if (event.target instanceof HTMLDialogElement && event.target.open) {
    const r = event.target.getBoundingClientRect();
    if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) event.target.close();
  }
});

// Rename in place: the pen next to a device or source name turns it into a field; Enter or the tick saves it
// without reloading the page, Escape or the cross keeps the old name.
document.addEventListener('click', event => {
  const pen = event.target.closest('[data-rename-start]'); if (!pen) return;
  const box = pen.closest('[data-rename]'), shown = box.querySelector('[data-name]'), old = shown.textContent;
  const form = document.createElement('form'); form.className = 'rename-form';
  form.innerHTML = '<input required><button class="icon-button" title="Save" aria-label="Save"><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></button>'
    + '<button type="button" class="icon-button" title="Cancel" aria-label="Cancel"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>';
  const input = form.querySelector('input'); input.value = old; input.maxLength = Number(box.dataset.renameMax || 40);
  const done = () => { form.remove(); box.classList.remove('renaming'); pen.focus(); };
  form.querySelector('[type=button]').addEventListener('click', done);
  input.addEventListener('keydown', e => { if (e.key === 'Escape') { e.preventDefault(); done(); } });
  form.addEventListener('submit', async e => {
    e.preventDefault(); e.stopPropagation();
    const token = document.querySelector('meta[name="csrf-token"]').content, header = document.querySelector('meta[name="csrf-header"]').content;
    const response = await fetch(box.dataset.rename, { method: 'POST', credentials: 'same-origin',
      headers: { [header]: token, 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ name: input.value.trim() }) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) { input.setCustomValidity(result.error || 'Not saved'); input.reportValidity(); input.addEventListener('input', () => input.setCustomValidity(''), { once: true }); return; }
    for (const el of box.closest('section,dialog')?.querySelectorAll('[data-name]') ?? [shown]) el.textContent = result.name;
    const field = box.dataset.renameField && document.getElementById(box.dataset.renameField);
    if (field) { field.value = result.name; field.defaultValue = result.name; }
    done();
  });
  box.classList.add('renaming'); box.append(form); input.focus(); input.select();
});
