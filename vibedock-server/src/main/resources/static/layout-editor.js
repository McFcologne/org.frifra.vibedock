// Shared default/device editor. Previews are server-rendered; every edit is saved right away (debounced).
(() => {
  const root = document.getElementById('layout-editor');
  if (!root) return;
  const $ = selector => root.querySelector(selector);
  // These controls keep their values, picker bindings and focus behaviour when their slots change sides.
  const moodSettings = $('#design-mood-settings'), weatherSettings = $('#design-weather-settings');
  const csrf = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  let catalog, target = 'default', draft, inherit = false, dirty = false, busy = false, previewPending = true;
  let saveTimer, saving = Promise.resolve(), edits = 0;
  let timer, request, previewVersion = 0, previewUrl, dragged = null;
  const isContext = band => band.type === 'context';
  const bandName = band => isContext(band) ? 'Weather & mood' : 'Usage · ' + band.sources.map(title).join(' + ');
  const copy = value => structuredClone(value);
  const device = () => catalog.devices.find(d => d.identifier === target);
  const title = id => (catalog.providers.find(p => p.id === id) || {label: id}).label;
  const sourceIds = () => catalog.providers.map(p => p.id);
  const inputFor = design => ({mood: design.mood || '', scheme: design.scheme,
    weatherLocation: design.weather ? design.weather.name : '', layout: copy(design.layout), inherit: false,
    photos: design.photos ? copy(design.photos) : null});
  let library = [];  // the photo library (/admin/photos), oldest first
  const error = message => { $('#design-error').textContent = message; $('#design-error').hidden = !message; };
  function controls() {
    $('#design-controls').disabled = inherit || !catalog;
    $('#design-style-controls').disabled = inherit || !catalog;
    $('#design-target').disabled = !catalog;
    for(const button of root.querySelectorAll('[data-theme-choose]')){
      const theme=catalog?.schemes.find(s=>s.id===button.dataset.themeChoose);
      button.disabled=inherit||!theme?.enabled;
      button.setAttribute('aria-pressed',String(draft?.scheme===theme?.id));
      button.textContent=draft?.scheme===theme?.id?'Selected':'Use theme';
    }
  }
  function changed(delay = 250) {
    dirty = true; edits++; error(''); schedulePreview(delay); controls();
    clearTimeout(saveTimer); saveTimer = setTimeout(save, delay + 400);
  }
  // Saves the draft of the current target; edits made meanwhile are saved by the next run.
  function save() {
    clearTimeout(saveTimer);
    saving = saving.then(async () => {
      if (!dirty || !catalog) return;
      const savedTarget = target, savedInherit = inherit, version = edits; busy = true;
      $('#design-message').textContent = 'Saving…';
      try {
        await post('/admin/designs/' + encodeURIComponent(savedTarget), {...draft, inherit: savedInherit});
        if (version === edits) dirty = false;
        const response = await fetch('/admin/designs', {credentials: 'same-origin'});
        if (!response.ok) throw new Error('Saved, but designs could not be reloaded. Reload this page.');
        catalog = await response.json(); labels();
        $('#design-message').textContent = savedTarget === 'default' ? 'Default design saved. Devices that follow it use it with their next image pull.'
          : savedInherit ? 'The device follows the default design again.' : 'Device design saved. It applies with the next image pull.';
      } catch (failure) { error(failure.message); $('#design-message').textContent = 'Not saved.'; }
      finally { busy = false; }
    });
    return saving;
  }
  function element(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; }
  function select(options, value, label, change) {
    const node = element('select'); node.setAttribute('aria-label', label);
    options.forEach(([id, name]) => { const option = element('option', '', name); option.value = id; node.append(option); });
    node.value = value; node.addEventListener('change', () => change(node.value)); return node;
  }
  function move(i, delta) {
    const bands = draft.layout.bands, j = i + delta;
    if (inherit || j < 0 || j >= bands.length) return;
    [bands[i], bands[j]] = [bands[j], bands[i]];
    renderBands(); changed();
  }
  const expanded = new Set();  // hidden bands opened for editing
  const sizeName = band => band.compact ? 'compact row' : band.sources.length > 1 ? 'half width' : 'full width';
  // One click to make every usage band visible: hidden bands as compact rows, else all bands; null when neither fits.
  function visibilityFix() {
    const usage = draft.layout.bands.filter(b => !isContext(b)), shown = shownBands();
    const hiddenOnly = usage.map(b => shown.has(b) ? b : { ...b, compact: true, sources: b.sources.slice(0, 1) });
    if (shownBands(hiddenOnly).size === usage.length) return { label: 'Show as compact row', apply: () => usage.forEach(b => { if (!shown.has(b)) { b.compact = true; b.sources = b.sources.slice(0, 1); } }) };
    const all = usage.map(b => ({ ...b, compact: true, sources: b.sources.slice(0, 1) }));
    if (shownBands(all).size === usage.length) return { label: 'Make all bands compact', apply: () => usage.forEach(b => { b.compact = true; b.sources = b.sources.slice(0, 1); }) };
    return null;
  }
  function renderBands() {
    const container = $('#design-bands'); container.replaceChildren();
    const shown = shownBands(), fix = visibilityFix(); let cut = false, fixShown = false;
    draft.layout.bands.forEach((band, index) => {
      const name = bandName(band), hidden = !isContext(band) && !shown.has(band);
      // The line where the display ends: usage bands below it are not drawn (the weather/mood band always is).
      if (hidden && !cut) { cut = true; container.append(element('div', 'design-band-cut', shownType().label.split(' · ')[0] + ' ends here · usage bands below are not shown')); }
      const article = element('article', 'design-band'); article.draggable = !inherit; article.dataset.index = index;
      const head = element('div', 'design-band-head'); head.append(element('span', 'design-grip', '⠿'), element('h3', 'grow', hidden ? '⊘ ' + band.sources.map(title).join(' + ') + ' · ' + sizeName(band) : name));
      const actions = element('div', 'design-band-actions');
      [-1, 1].forEach(delta => { const button = element('button', 'secondary small', delta < 0 ? '↑' : '↓');
        button.type = 'button'; button.disabled = index + delta < 0 || index + delta >= draft.layout.bands.length;
        button.setAttribute('aria-label', 'Move ' + name + (delta < 0 ? ' up' : ' down'));
        button.addEventListener('click', () => move(index, delta)); actions.append(button); });
      if (!isContext(band) && usageCount() > 1) {
        const remove = element('button', 'secondary small', '×'); remove.type = 'button'; remove.disabled = inherit;
        remove.setAttribute('aria-label', 'Remove ' + name); remove.title = 'Remove band';
        remove.addEventListener('click', () => { if (inherit) return; draft.layout.bands.splice(index, 1); renderBands(); changed(); });
        actions.append(remove);
      }
      if (!isContext(band)) {
        head.append(select([['1', '1 × full width'], ['2', '2 × half width'], ['c', '1 × compact row']], band.compact ? 'c' : String(band.sources.length), name + ' widget sizes', size => {
          band.compact = size === 'c';
          if (size !== '2') band.sources = band.sources.slice(0, 1);
          else { const current = band.sources[0]; band.sources = [current, sourceIds().find(p => p !== current) || current]; }
          renderBands(); changed();
        }));
      }
      // Usage bands beyond what the display holds stay in the design but are not drawn: folded to one line with a fix;
      // a click on the line opens the band for editing.
      if (hidden) {
        article.classList.add('design-band-hidden');
        if (!expanded.has(band)) {
          article.classList.add('folded'); head.replaceChildren(head.firstChild, head.querySelector('h3'));
          if (fix && !fixShown) { fixShown = true; const button = element('button', 'secondary small design-band-fix', fix.label); button.type = 'button'; button.disabled = inherit;
            button.addEventListener('click', event => { event.stopPropagation(); if (inherit) return; fix.apply(); renderBands(); changed(); }); head.append(button); }
          const remove = actions.querySelector('button[aria-label^="Remove"]'); actions.replaceChildren(...(remove ? [remove] : []));
          head.title = 'Click to edit'; head.addEventListener('click', event => { if (event.target.closest('button')) return; expanded.add(band); renderBands(); });
          head.append(actions); article.append(head); addDrag(article, index); container.append(article); return;
        }
      } else expanded.delete(band);
      head.append(actions); article.append(head);
      const content = element('div', 'design-band-content');
      if (isContext(band)) {
        const pair = element('div', 'design-slot-pair');
        ['left', 'right'].forEach((side, i) => { const mood = band.moodLeft ? i === 0 : i === 1;
          const slot = element('div', 'design-slot ' + side); slot.append(element('span', 'meta', side === 'left' ? 'Short left slot (IP below)' : 'Tall right slot'),
            mood ? moodSettings : weatherSettings);
          pair.append(slot);
        }); content.append(pair);
        const swap = element('button', 'secondary small', 'Swap weather & mood ⇄'); swap.type = 'button';
        swap.addEventListener('click', () => { band.moodLeft = !band.moodLeft; renderBands(); changed(); });
        content.append(swap, element('small', '', 'The update time and the device IP move with this band. Weather shows the icon and temperature only.'));
      } else {
        const slots = element('div', 'design-source-slots');
        band.sources.forEach((source, i) => { const label = element('label', '', band.sources.length === 1 ? 'Data source' : i === 0 ? 'Left source' : 'Right source');
          // Keep old sources visible if a future integration is temporarily absent. The server validates saves.
          const ids = [...new Set([...sourceIds(), source])];
          label.append(select(ids.map(id => [id, title(id)]), source, name + ' source ' + (i + 1), value => { band.sources[i] = value; renderBands(); changed(); })); slots.append(label);
        }); content.append(slots);
      }
      article.append(content); addDrag(article, index); container.append(article);
    });
    addButton(container);
    displayNote();
  }
  function addDrag(article, index) {
    const container = $('#design-bands');
      article.addEventListener('dragstart', event => { if (inherit || event.target.closest('button,select,input')) { event.preventDefault(); return; }
        dragged = index; event.dataTransfer.setData('text/plain', String(index)); event.dataTransfer.effectAllowed = 'move'; });
      article.addEventListener('dragover', event => { if (dragged !== null && dragged !== index && !inherit) { event.preventDefault(); article.classList.add('drag-over'); } });
      article.addEventListener('dragleave', () => article.classList.remove('drag-over'));
      article.addEventListener('dragend', () => { dragged = null; container.querySelectorAll('.drag-over').forEach(n => n.classList.remove('drag-over')); });
      article.addEventListener('drop', event => { event.preventDefault(); if (dragged === null || inherit) return;
        const bands = draft.layout.bands; bands.splice(index, 0, bands.splice(dragged, 1)[0]); dragged = null; renderBands(); changed(); });
  }
  function addButton(container) {
    // Up to five usage bands; the display shows as many as fit its height (compact rows take half the height).
    if (usageCount() < MAX_USAGE) {
      const add = element('button', 'secondary small design-band-add', '+ Add usage band'); add.type = 'button'; add.disabled = inherit;
      add.addEventListener('click', () => { if (inherit) return;
        const bands = draft.layout.bands, last = bands.map(isContext).lastIndexOf(false);
        bands.splice(last + 1, 0, { type: 'usage', sources: [sourceIds()[0] || 'codex'], compact: true }); renderBands(); changed(); });
      container.append(add);
    }
  }
  const MAX_USAGE = 5;
  // The display the bands are checked against: the device's type, the VibeTV M1 for the default design.
  const shownType = () => target === 'default' ? catalog.displays[0] : displayOf(draft.display);
  // The usage bands the display shows, as the server's Layout.fit decides: the first ones that fit at their minimum
  // height (88 px, compact 44 px), at least one.
  function shownBands(usage = draft.layout.bands.filter(b => !isContext(b))) {
    const shown = new Set(); let used = 0, full = false;
    for (const band of usage) {
      const height = band.compact ? 44 : 88;
      if (full || (used > 0 && used + height > shownType().bandRoom)) { full = true; continue; }
      used += height; shown.add(band);
    }
    return shown;
  }
  const usageCount = () => draft.layout.bands.filter(b => !isContext(b)).length;
  const displayOf = id => catalog.displays.find(d => d.id === id) || catalog.displays[0];
  // What the chosen display shows of the design: fewer usage bands or single sources on small screens.
  function displayNote() {
    const note = $('#design-display-note'), usage = draft.layout.bands.filter(b => !isContext(b));
    const hidden = usage.length - shownBands().size;
    if (target === 'default') { note.textContent = hidden ? 'VibeTV M1 shows ' + (usage.length - hidden) + ' of ' + usage.length + ' usage bands.' : ''; return; }
    const type = displayOf(draft.display);
    const parts = [type.id === 'fit' ? 'the browser\'s size, preview ' + type.width + ' × ' + type.height + ' px' : type.width + ' × ' + type.height + ' px'];
    if (hidden) parts.push('shows ' + (usage.length - hidden) + ' of ' + usage.length + ' usage bands');
    if (!type.halfWidth && usage.some(b => b.sources.length > 1)) parts.push('split bands show their first source');
    note.textContent = parts.join(' · ');
  }
  function labels() {
    [...$('#design-target').options].forEach(option => {
      const entry = catalog.devices.find(d => d.identifier === option.value);
      if (entry) option.textContent = entry.name + (entry.overridden ? ' · own design' : ' · default');
    });
    $('#design-title').textContent = target === 'default' ? 'Default design' : device().name + (device().overridden ? ' · own design' : ' · follows the default');
  }
  // The case takes the display's shape before the new image arrives (it would otherwise follow it late).
  function fitCase() {
    if (!draft) return;
    const type = target === 'default' ? {width: 240, height: 240} : displayOf(draft.display), preview = document.querySelector('.design-preview .device');
    if (!preview || !type) return;
    preview.style.setProperty('--ratio', String(type.height / type.width));
    // --s is the active screen width, not the width of the complete case. Fit the measured case, including
    // its glass and bezel, so a wide frame cannot clip the fixed-width image inside it.
    preview.style.removeProperty('--s');
    const available = preview.parentElement.clientWidth - 2;
    const screenWidth = parseFloat(getComputedStyle(preview).getPropertyValue('--s'));
    const caseWidth = preview.getBoundingClientRect().width;
    if (available > 0 && caseWidth > available)
      preview.style.setProperty('--s', (screenWidth * available / caseWidth) + 'px');
  }
  new ResizeObserver(fitCase).observe(document.querySelector('.design-preview .screen'));
  function loadTarget() {
    labels();
    const design = target === 'default' ? catalog.defaults : device().design;
    inherit = target !== 'default' && !device().overridden; draft = inputFor(inherit ? catalog.defaults : design);
    $('#design-default-summary').hidden = target !== 'default';
    dirty = false; $('#design-inheritance').hidden = target === 'default'; $('#design-override').checked = !inherit;
    $('#inheritance-description').textContent = inherit ? 'Follows the default design, including future changes.' : 'This device has its own complete design. Turn off the override to follow the default again.';
    // The preview in the device's case (Board.previewCase), the default design in the VibeTV M1's.
    const front = document.querySelector('.design-preview .device-front');
    if (front) {
      for (const name of [...front.classList]) if (name.startsWith('case-')) front.classList.remove(name);
      if (target !== 'default' && device().previewCase) front.classList.add(device().previewCase);
    }
    // Fitting the browser is for web displays only.
    const fit = $('#design-display').querySelector('option[value="fit"]');
    if (fit) { fit.hidden = fit.disabled = target === 'default' || !device().web; }
    if (target !== 'default') { draft.display = device().display; $('#design-display').value = draft.display; }
    else { const image = $('#design-preview-image'); image.width = 240; image.height = 240; }
    fitCase();
    render(); schedulePreview();
  }
  function render() {
    $('#design-scheme').value = draft.scheme; $('#design-weather').value = draft.weatherLocation;
    $('#layout-emoji').value = draft.mood || '-';
    const emojiButton = root.querySelector('[data-emoji-open]'); emojiButton.dataset.file = '';
    emojiButton.dispatchEvent(new Event('vibedock-emoji-render'));
    $('#design-align').value = draft.layout.alignTop ? 'top' : 'spread';
    renderPhotos(); renderBands(); displayNote(); controls();
  }
  function themeOptions(){
    $('#design-scheme').replaceChildren(...catalog.schemes.map(s=>{
      const option=element('option','',s.label+(s.enabled?'':' · disabled'));option.value=s.id;option.disabled=!s.enabled;return option;
    }));
    if(draft)$('#design-scheme').value=draft.scheme;
  }
  function themeGallery(){
    $('#theme-count').textContent=catalog.schemes.filter(s=>s.enabled).length+' / '+catalog.schemes.length+' enabled';
    const cards=catalog.schemes.map(s=>{
      const card=element('article','theme-card');card.dataset.theme=s.id;card.classList.toggle('theme-disabled',!s.enabled);
      const heading=element('h4','',s.label), image=document.createElement('img');
      image.src='/admin/themes/'+encodeURIComponent(s.id)+'/preview.png';image.alt=s.label+' theme with sample usage';image.width=240;image.height=240;image.loading='lazy';
      const choose=element('button','secondary','Use theme');choose.type='button';choose.dataset.themeChoose=s.id;
      choose.addEventListener('click',()=>{draft.scheme=s.id;$('#design-scheme').value=s.id;changed();controls();});
      const label=element('label','theme-availability'),toggle=document.createElement('input');toggle.type='checkbox';toggle.checked=s.enabled;toggle.setAttribute('aria-label',s.label+' enabled');label.title='Enable or disable '+s.label;
      label.append(toggle);
      toggle.addEventListener('change',async()=>{
        root.querySelectorAll('.theme-card input').forEach(input=>input.disabled=true);
        try{
          const response=await fetch('/admin/themes/'+encodeURIComponent(s.id),{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json',[csrfHeader]:csrf},body:JSON.stringify({enabled:toggle.checked})});
          if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.error||body.message||'Could not update theme availability. Keep at least one theme enabled.');}
          catalog.schemes=await response.json();themeOptions();
          // Update in place so keyboard focus stays on the switch just used.
          for(const item of catalog.schemes){const tile=$('#theme-grid').querySelector('[data-theme="'+item.id+'"]');tile.classList.toggle('theme-disabled',!item.enabled);tile.querySelector('input').checked=item.enabled;}
          $('#theme-count').textContent=catalog.schemes.filter(t=>t.enabled).length+' / '+catalog.schemes.length+' enabled';
          $('#theme-message').textContent=s.label+(toggle.checked?' enabled.':' disabled. Existing designs keep this theme.');controls();
        }catch(e){toggle.checked=!toggle.checked;error(e.message);$('#theme-message').textContent=e.message;}
        finally{root.querySelectorAll('.theme-card input').forEach(input=>input.disabled=false);}
      });
      const title=element('div','theme-card-heading');title.append(heading,label);card.append(title,image,choose);return card;
    });
    $('#theme-grid').replaceChildren(...cards);controls();
  }
  $('#design-align').addEventListener('change', event => { draft.layout.alignTop = event.target.value === 'top'; changed(); });
  // ---- content: usage monitor or photos (a paused selection is kept for switching back) ----
  const photoDefaults = () => ({images: [], seconds: 30, shuffle: false, from: null, to: null, paused: false});
  let armed = null;  // a photo whose delete button was pressed once
  function renderPhotos() {
    const p = draft.photos, on = !!p && !p.paused, timed = on && !!p.from;
    root.querySelectorAll('[data-content-mode]').forEach(b => b.setAttribute('aria-pressed', String((b.dataset.contentMode === 'photos') === on)));
    $('#design-photo-timed').hidden = !timed;
    $('#design-photo-settings').hidden = !on;
    // The usage monitor: on its own, or below the photos for the times outside their schedule.
    $('#design-usage').hidden = on && !timed;
    $('#design-usage-heading').hidden = !timed;
    if (timed) $('#design-usage-heading').textContent = 'Usage monitor · outside ' + p.from + '–' + p.to;
    $('#design-photos-kept').hidden = on || !p || !p.images.length;
    if (!on) return;
    $('#design-photo-seconds').value = String(p.seconds); $('#design-photo-shuffle').checked = p.shuffle;
    $('#design-photo-timed-check').checked = !!p.from; $('#design-photo-times').hidden = !p.from;
    if (p.from) { $('#design-photo-from').value = p.from; $('#design-photo-to').value = p.to; }
    const grid = $('#design-photos'); grid.replaceChildren();
    if (!library.length) grid.append(element('p', 'meta', 'No photos yet: add some.'));
    for (const photo of library) {
      const at = p.images.indexOf(photo.id), tile = element('div', 'photo-tile' + (at >= 0 ? ' selected' : ''));
      const pick = element('button'); pick.type = 'button'; pick.className = 'photo-pick'; pick.setAttribute('aria-pressed', String(at >= 0));
      pick.setAttribute('aria-label', at >= 0 ? 'Photo ' + (at + 1) + ', click to leave it out' : 'Photo, click to show it');
      const image = element('img'); image.src = '/admin/photos/' + photo.id + '.jpg'; image.alt = ''; image.loading = 'lazy'; pick.append(image);
      if (at >= 0) pick.append(element('span', 'photo-order', String(at + 1)));
      pick.addEventListener('click', () => {
        if (inherit) return;
        const i = draft.photos.images.indexOf(photo.id);
        if (i >= 0) draft.photos.images.splice(i, 1); else draft.photos.images.push(photo.id);
        renderPhotos(); changed();
      });
      const remove = element('button', 'photo-delete' + (armed === photo.id ? ' armed' : ''), armed === photo.id ? 'Delete' : '✕'); remove.type = 'button';
      remove.title = 'Delete the photo from the library (all designs)'; remove.setAttribute('aria-label', 'Delete photo');
      remove.addEventListener('click', async () => {
        if (armed !== photo.id) { armed = photo.id; renderPhotos(); setTimeout(() => { if (armed === photo.id) { armed = null; renderPhotos(); } }, 3000); return; }
        armed = null;
        try { await post('/admin/photos/' + photo.id + '/delete', {}); } catch (failure) { error(failure.message); return; }
        library = library.filter(x => x.id !== photo.id);
        const i = draft.photos.images.indexOf(photo.id); if (i >= 0) { draft.photos.images.splice(i, 1); changed(); }
        renderPhotos();
      });
      tile.append(pick, remove); grid.append(tile);
    }
  }
  root.querySelectorAll('[data-content-mode]').forEach(button => button.addEventListener('click', () => {
    const photos = button.dataset.contentMode === 'photos';
    if (photos === (!!draft.photos && !draft.photos.paused)) return;
    if (!draft.photos) draft.photos = photoDefaults();
    draft.photos.paused = !photos;
    renderPhotos(); changed();
  }));
  $('#design-photo-seconds').addEventListener('change', event => { draft.photos.seconds = Number(event.target.value); changed(); });
  $('#design-photo-shuffle').addEventListener('change', event => { draft.photos.shuffle = event.target.checked; changed(); });
  $('#design-photo-timed-check').addEventListener('change', event => {
    if (event.target.checked) { draft.photos.from = '22:00'; draft.photos.to = '07:00'; } else { draft.photos.from = draft.photos.to = null; }
    renderPhotos(); changed();
  });
  for (const id of ['#design-photo-from', '#design-photo-to']) $(id).addEventListener('change', () => {
    const from = $('#design-photo-from').value, to = $('#design-photo-to').value;
    if (from && to && from !== to) { draft.photos.from = from; draft.photos.to = to; renderPhotos(); changed(); }
  });
  // One request per photo, as its bytes; the server keeps a reduced copy. New photos are chosen right away, but only
  // in the design the upload started in: switched to another device (or the default) meanwhile, they only join the
  // library.
  $('#design-photo-upload').addEventListener('change', async event => {
    const files = [...event.target.files]; event.target.value = ''; let done = 0, chosen = 0;
    const owner = draft, ownerTarget = target, same = () => draft === owner && target === ownerTarget && !inherit;
    for (const file of files) {
      $('#design-photo-state').textContent = 'Uploading ' + (done + 1) + ' of ' + files.length + ' …';
      try {
        const response = await fetch('/admin/photos', {method: 'POST', credentials: 'same-origin', body: file,
          headers: {'Content-Type': file.type || 'application/octet-stream', Accept: 'application/json', [csrfHeader]: csrf}});
        const answer = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(file.name + ': ' + (answer.error || 'HTTP ' + response.status));
        library.push(answer); done++;
        if (same() && owner.photos) { owner.photos.images.push(answer.id); chosen++; }
      } catch (failure) { error(failure.message); }
    }
    $('#design-photo-state').textContent = done + ' of ' + files.length + ' photos added' + (chosen < done ? ' to the library (not chosen: the editor shows another design now)' : '')
      + '. Kept reduced to 600 × 600 at most.';
    renderPhotos(); if (chosen) changed();
  });
  async function post(url, data, signal) {
    const response = await fetch(url, {method: 'POST', credentials: 'same-origin', signal,
      headers: {'Content-Type': 'application/json', [csrfHeader]: csrf}, body: JSON.stringify(data)});
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403 ? 'Your session expired. Reload and sign in again.' : (await response.text()).slice(0, 240) || 'Request failed.');
    return response;
  }
  function schedulePreview(delay = 250) {
    clearTimeout(timer); request?.abort(); const version = ++previewVersion;
    previewPending = true; $('#preview-status').textContent = 'Updating preview…'; controls();
    timer = setTimeout(async () => {
      const controller = new AbortController(); request = controller;
      try {
        const response = await post('/admin/design-preview.png?target=' + encodeURIComponent(target), {...draft, inherit}, controller.signal);
        const blob = await response.blob(); if (version !== previewVersion) return;
        const nextUrl = URL.createObjectURL(blob); const oldUrl = previewUrl;
        $('#design-preview-image').src = nextUrl; previewUrl = nextUrl; if (oldUrl) URL.revokeObjectURL(oldUrl);
        const oldFirmware = target !== 'default' && !device().serverFooter && !isContext(draft.layout.bands[draft.layout.bands.length - 1]);
        $('#design-preview-caption').textContent = target === 'default' ? 'Default design · IP placeholder'
          : device().name + ' · ' + device().ip + (oldFirmware ? ' · its firmware keeps the weather band at the bottom until it is updated' : '');
        $('#preview-status').textContent = 'Current server data · device renderer'; previewPending = false; controls();
      } catch (failure) { if (failure.name !== 'AbortError' && version === previewVersion) { $('#preview-status').textContent = 'Preview unavailable'; error(failure.message); } }
    }, delay);
  }
  function switchTo(next) {
    target = next; $('#design-target').value = next; error(''); loadTarget();
    const url = new URL(location.href); if (target === 'default') url.searchParams.delete('device'); else url.searchParams.set('device', target); history.replaceState(null, '', url);
  }
  // A pending save of the previous target finishes first; a failed one keeps the editor on it.
  $('#design-target').addEventListener('change', async event => {
    const next = event.target.value; event.target.value = target;
    await save(); if (dirty) return;
    switchTo(next);
  });
  $('#design-override').addEventListener('change', event => {
    inherit = !event.target.checked;
    if (inherit) draft = {...inputFor(catalog.defaults), display: draft.display};
    $('#inheritance-description').textContent = inherit ? 'This device follows the default design, including future changes.' : 'Editing a complete device-specific copy of the design.';
    render(); changed();
  });
  $('#design-scheme').addEventListener('change', event => { draft.scheme = event.target.value; changed(); });
  $('#design-display').addEventListener('change', event => { draft.display = event.target.value; fitCase(); renderBands(); changed(); });
  $('#design-weather').addEventListener('change', event => { draft.weatherLocation = event.target.value.trim(); changed(); });
  $('#design-weather').addEventListener('input', event => {
    // Capture typing before moving these same DOM nodes to the other slot; blur/change is not guaranteed then.
    draft.weatherLocation = event.target.value.trim(); changed(700);
  });
  $('#layout-emoji').addEventListener('change', event => { draft.mood = event.target.value === '-' ? '' : event.target.value; changed(); });
  $('#design-form').addEventListener('submit', event => { event.preventDefault(); save(); });
  // Leaving within the save delay: send the last edit along (keepalive outlives the page).
  window.addEventListener('pagehide', () => {
    if (!dirty || busy || !catalog) return;
    fetch('/admin/designs/' + encodeURIComponent(target), {method: 'POST', credentials: 'same-origin', keepalive: true,
      headers: {'Content-Type': 'application/json', [csrfHeader]: csrf}, body: JSON.stringify({...draft, inherit})});
  });
  fetch('/admin/designs', {credentials: 'same-origin'}).then(async response => {
    if (!response.ok) throw new Error('Designs could not be loaded. Reload and sign in again.');
    catalog = await response.json();
    try { const photos = await fetch('/admin/photos', {credentials: 'same-origin'}); if (photos.ok) library = await photos.json(); } catch { /* no library */ }
    $('#design-display').replaceChildren(...catalog.displays.map(d => { const o = document.createElement('option'); o.value = d.id; o.textContent = d.label; return o; }));
    catalog.devices.forEach(d => { const option = element('option', '', d.name + (d.overridden ? ' · own design' : ' · default')); option.value = d.identifier; $('#design-target').append(option); });
    themeOptions();themeGallery();
    const requested = new URLSearchParams(location.search).get('device'); if (catalog.devices.some(d => d.identifier === requested)) target = requested;
    $('#design-target').value = target; $('#design-message').textContent = 'Changes are saved right away.'; loadTarget();
  }).catch(failure => error(failure.message));
})();
