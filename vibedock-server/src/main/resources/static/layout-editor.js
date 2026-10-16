// Shared default/device editor. Previews are server-rendered; all edits remain local until Save.
(() => {
  const root = document.getElementById('layout-editor');
  if (!root) return;
  const $ = selector => root.querySelector(selector);
  // These controls keep their values, picker bindings and focus behaviour when their slots change sides.
  const moodSettings = $('#design-mood-settings'), weatherSettings = $('#design-weather-settings');
  const csrf = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  let catalog, target = 'default', draft, inherit = false, dirty = false, busy = false, previewPending = true;
  let timer, request, previewVersion = 0, previewUrl, dragged = null;
  const isContext = band => band.type === 'context';
  const bandName = band => isContext(band) ? 'Weather & mood' : 'Usage · ' + band.sources.map(title).join(' + ');
  const copy = value => structuredClone(value);
  const device = () => catalog.devices.find(d => d.identifier === target);
  const title = id => (catalog.providers.find(p => p.id === id) || {label: id}).label;
  const sourceIds = () => catalog.providers.map(p => p.id);
  const inputFor = design => ({mood: design.mood || '', scheme: design.scheme,
    weatherLocation: design.weather ? design.weather.name : '', layout: copy(design.layout), inherit: false});
  const error = message => { $('#design-error').textContent = message; $('#design-error').hidden = !message; };
  function controls() {
    $('#design-controls').disabled = busy || inherit || !catalog;
    $('#design-style-controls').disabled = busy || inherit || !catalog;
    $('#design-target').disabled = busy || !catalog;
    $('#design-override').disabled = busy;
    $('#design-save').disabled = busy || !dirty || previewPending;
    $('#design-discard').disabled = busy || !dirty;
    $('#design-save').closest('.savebar').hidden = !dirty && !busy;
    $('#design-save').textContent = target === 'default' ? 'Save default design' : inherit ? 'Use default design' : 'Save device design';
    $('#design-save-state').textContent = busy ? 'Saving…' : dirty ? 'Unsaved changes' : 'No unsaved changes';
  }
  function changed(delay = 250) { dirty = true; error(''); schedulePreview(delay); controls(); }
  function element(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; }
  function select(options, value, label, change) {
    const node = element('select'); node.setAttribute('aria-label', label);
    options.forEach(([id, name]) => { const option = element('option', '', name); option.value = id; node.append(option); });
    node.value = value; node.addEventListener('change', () => change(node.value)); return node;
  }
  function move(i, delta) {
    const bands = draft.layout.bands, j = i + delta;
    if (inherit || busy || j < 0 || j >= bands.length) return;
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
        remove.addEventListener('click', () => { if (inherit || busy) return; draft.layout.bands.splice(index, 1); renderBands(); changed(); });
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
            button.addEventListener('click', event => { event.stopPropagation(); if (inherit || busy) return; fix.apply(); renderBands(); changed(); }); head.append(button); }
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
      article.addEventListener('dragstart', event => { if (inherit || busy || event.target.closest('button,select,input')) { event.preventDefault(); return; }
        dragged = index; event.dataTransfer.setData('text/plain', String(index)); event.dataTransfer.effectAllowed = 'move'; });
      article.addEventListener('dragover', event => { if (dragged !== null && dragged !== index && !inherit) { event.preventDefault(); article.classList.add('drag-over'); } });
      article.addEventListener('dragleave', () => article.classList.remove('drag-over'));
      article.addEventListener('dragend', () => { dragged = null; container.querySelectorAll('.drag-over').forEach(n => n.classList.remove('drag-over')); });
      article.addEventListener('drop', event => { event.preventDefault(); if (dragged === null || inherit || busy) return;
        const bands = draft.layout.bands; bands.splice(index, 0, bands.splice(dragged, 1)[0]); dragged = null; renderBands(); changed(); });
  }
  function addButton(container) {
    // Up to four usage bands; the display shows as many as fit its height (compact rows take half the height).
    if (usageCount() < MAX_USAGE) {
      const add = element('button', 'secondary small design-band-add', '+ Add usage band'); add.type = 'button'; add.disabled = inherit;
      add.addEventListener('click', () => { if (inherit || busy) return;
        const bands = draft.layout.bands, last = bands.map(isContext).lastIndexOf(false);
        bands.splice(last + 1, 0, { type: 'usage', sources: [sourceIds()[0] || 'codex'], compact: true }); renderBands(); changed(); });
      container.append(add);
    }
  }
  const MAX_USAGE = 4;
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
    const parts = [type.width + ' × ' + type.height + ' px'];
    if (hidden) parts.push('shows ' + (usage.length - hidden) + ' of ' + usage.length + ' usage bands');
    if (!type.halfWidth && usage.some(b => b.sources.length > 1)) parts.push('split bands show their first source');
    note.textContent = parts.join(' · ');
  }
  function loadTarget() {
    [...$('#design-target').options].forEach(option => {
      const entry = catalog.devices.find(d => d.identifier === option.value);
      if (entry) option.textContent = entry.name + (entry.overridden ? ' · own design' : ' · default');
    });
    const design = target === 'default' ? catalog.defaults : device().design;
    $('#design-title').textContent = target === 'default' ? 'Default design' : device().name + (device().overridden ? ' · own design' : ' · follows the default');
    inherit = target !== 'default' && !device().overridden; draft = inputFor(inherit ? catalog.defaults : design);
    $('#design-default-summary').hidden = target !== 'default';
    dirty = false; $('#design-inheritance').hidden = target === 'default'; $('#design-override').checked = !inherit;
    $('#inheritance-description').textContent = inherit ? 'Follows the default design, including future changes.' : 'This device has its own complete design. Turn off the override and save to follow the default again.';
    if (target !== 'default') { draft.display = device().display; $('#design-display').value = draft.display; }
    else { const image = $('#design-preview-image'); image.width = 240; image.height = 240; }
    render(); schedulePreview();
  }
  function render() {
    $('#design-scheme').value = draft.scheme; $('#design-weather').value = draft.weatherLocation;
    $('#layout-emoji').value = draft.mood || '-';
    const emojiButton = root.querySelector('[data-emoji-open]'); emojiButton.dataset.file = '';
    emojiButton.dispatchEvent(new Event('vibedock-emoji-render'));
    renderBands(); displayNote(); controls();
  }
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
  const targetName = id => id === 'default' ? 'the default design' : catalog.devices.find(d => d.identifier === id).name;
  let pendingTarget = null;
  function switchTo(next) {
    target = next; $('#design-target').value = next; $('#design-switch').hidden = true; error(''); loadTarget();
    const url = new URL(location.href); if (target === 'default') url.searchParams.delete('device'); else url.searchParams.set('device', target); history.replaceState(null, '', url);
  }
  // Unsaved changes: ask in the page instead of a browser dialog, which browsers may suppress.
  $('#design-target').addEventListener('change', event => {
    if (!dirty) { switchTo(event.target.value); return; }
    pendingTarget = event.target.value; event.target.value = target;
    $('#design-switch span').textContent = 'Unsaved changes to ' + targetName(target) + '. Switch to ' + targetName(pendingTarget) + '?';
    $('#design-switch').hidden = false;
  });
  $('#design-switch').addEventListener('click', event => {
    const choice = event.target.dataset.switch; if (!choice) return;
    if (choice === 'discard') switchTo(pendingTarget); else $('#design-switch').hidden = true;
    pendingTarget = null;
  });
  $('#design-override').addEventListener('change', event => {
    inherit = !event.target.checked;
    if (inherit) draft = {...inputFor(catalog.defaults), display: draft.display};
    $('#inheritance-description').textContent = inherit ? 'Save to remove this device’s overrides and follow all future default changes.' : 'Editing a complete device-specific copy of the design.';
    render(); changed();
  });
  $('#design-scheme').addEventListener('change', event => { draft.scheme = event.target.value; changed(); });
  $('#design-display').addEventListener('change', event => { draft.display = event.target.value; renderBands(); changed(); });
  $('#design-weather').addEventListener('change', event => { draft.weatherLocation = event.target.value.trim(); changed(); });
  $('#design-weather').addEventListener('input', event => {
    // Capture typing before moving these same DOM nodes to the other slot; blur/change is not guaranteed then.
    draft.weatherLocation = event.target.value.trim(); changed(700);
  });
  $('#layout-emoji').addEventListener('change', event => { draft.mood = event.target.value === '-' ? '' : event.target.value; changed(); });
  $('#design-form').addEventListener('submit', async event => {
    event.preventDefault(); if (!dirty || busy || previewPending) return; busy = true; controls(); error('');
    try {
      await post('/admin/designs/' + encodeURIComponent(target), {...draft, inherit});
      const response = await fetch('/admin/designs', {credentials: 'same-origin'}); if (!response.ok) throw new Error('Saved, but designs could not be reloaded. Reload this page.');
      catalog = await response.json(); loadTarget(); $('#design-message').textContent = target === 'default' ? 'Default design saved. Devices that inherit it use it on their next image pull.' : inherit ? 'Device now follows the default design.' : 'Device design saved. It applies on the next image pull.';
    } catch (failure) { error(failure.message); }
    finally { busy = false; controls(); }
  });
  $('#design-discard').addEventListener('click', () => { if (!busy && catalog) { error(''); loadTarget(); } });
  window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
  fetch('/admin/designs', {credentials: 'same-origin'}).then(async response => {
    if (!response.ok) throw new Error('Designs could not be loaded. Reload and sign in again.');
    catalog = await response.json();
    $('#design-display').replaceChildren(...catalog.displays.map(d => { const o = document.createElement('option'); o.value = d.id; o.textContent = d.label; return o; }));
    catalog.devices.forEach(d => { const option = element('option', '', d.name + (d.overridden ? ' · own design' : ' · default')); option.value = d.identifier; $('#design-target').append(option); });
    catalog.schemes.forEach(s => { const option = element('option', '', s.label); option.value = s.id; $('#design-scheme').append(option); });
    const requested = new URLSearchParams(location.search).get('device'); if (catalog.devices.some(d => d.identifier === requested)) target = requested;
    $('#design-target').value = target; $('#design-message').textContent = 'Changes stay here until you save.'; loadTarget();
  }).catch(failure => error(failure.message));
})();
