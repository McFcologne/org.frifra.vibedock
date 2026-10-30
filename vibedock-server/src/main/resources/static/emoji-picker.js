// Emoji picker with the Twemoji images the display actually shows. A "Choose" button opens the dialog;
// a click writes the emoji into the button's field. The catalog is loaded once on first use.
(() => {
  const dialog = document.getElementById('emoji-picker');
  if (!dialog) return;
  const search = dialog.querySelector('[data-emoji-search]');
  const status = dialog.querySelector('[data-emoji-status]');
  const quick = dialog.querySelector('[data-emoji-quick]');
  const quickTitle = dialog.querySelector('[data-emoji-quick-title]');
  const all = dialog.querySelector('[data-emoji-all]');
  const images = dialog.dataset.images;
  let catalog = null, target = null;

  const button = entry => {
    const b = document.createElement('button');
    b.type = 'button'; b.dataset.emoji = entry.emoji; b.title = entry.name;
    const img = document.createElement('img');
    img.src = images + entry.file + '.png'; img.alt = entry.name; img.loading = 'lazy'; img.width = 28; img.height = 28;
    b.append(img); return b;
  };
  const show = () => {
    const term = search.value.trim().toLowerCase();
    const words = term.split(/\s+/).filter(Boolean);
    // Every search word has to match the start of a word in the name, so "cat" finds cats but not "indicator".
    const hits = catalog.all.filter(e => words.every(w => e.words.some(t => t.startsWith(w))));
    quick.hidden = quickTitle.hidden = words.length > 0;
    all.replaceChildren(...hits.slice(0, 600).map(button));
    status.textContent = words.length ? hits.length + ' found' + (hits.length > 600 ? ', showing 600' : '') : catalog.all.length + ' emoji';
  };
  const load = async () => {
    if (catalog) return;
    try {
      const response = await fetch(dialog.dataset.catalog, {credentials: 'same-origin'});
      if (!response.ok) throw new Error(response.status);
      catalog = await response.json();
      catalog.all.forEach(e => { e.words = e.name.split(/[\s-]+/); });
      if (!catalog.all.length) { status.textContent = 'The emoji set is not installed on the server.'; return; }
      quick.replaceChildren(...catalog.quick.map(button));
      show();
    } catch (e) { status.textContent = 'Emoji list could not be loaded.'; }
  };
  // The emoji button shows the chosen Twemoji image; an empty device value shows the default faded, "-" shows "none".
  const render = (b, value, file) => {
    b.classList.remove('inherited'); b.replaceChildren();
    if (value === '' && b.dataset.allowDefault) { b.classList.add('inherited'); file = b.dataset.defaultFile; value = file ? 'default' : '-'; }
    if (value === '-' || !value) { const n = document.createElement('span'); n.className = 'none'; n.textContent = 'none'; b.append(n); return; }
    // Saved designs only carry the Unicode identifier. Resolve the bundled asset exactly as Twemoji.fileNames does.
    const points=Array.from(value.trim()).map(c=>c.codePointAt(0));
    const full=points.map(c=>c.toString(16)).join('-'),plain=points.filter(c=>c!==0xfe0f).map(c=>c.toString(16)).join('-');
    const candidates=file?[file]:[...new Set(points.includes(0x200d)?[full,plain]:[plain,full])];
    const img=document.createElement('img');img.alt='Selected emoticon';let index=0;
    img.addEventListener('error',()=>{
      if(!b.contains(img))return; // A response from a previous selection must not replace the current one.
      if(++index<candidates.length){img.src=images+candidates[index]+'.png';return;}
      const unavailable=document.createElement('span');unavailable.className='none';unavailable.textContent='Unavailable';b.replaceChildren(unavailable);
    });
    img.src=images+candidates[0]+'.png';b.append(img);
  };
  let opener = null;
  document.querySelectorAll('[data-emoji-open]').forEach(b => {
    const input = document.getElementById(b.dataset.emojiOpen);
    render(b, input.value, b.dataset.file);
    b.addEventListener('vibedock-emoji-render', () => render(b, input.value, b.dataset.file));
    b.addEventListener('click', () => {
      target = input; opener = b;
      dialog.querySelector('[data-emoji-default]').hidden = !b.dataset.allowDefault;
      dialog.showModal(); search.focus(); load();
    });
  });
  search.addEventListener('input', () => { if (catalog) show(); });
  dialog.addEventListener('click', event => {
    const b = event.target.closest('button');
    if (event.target === dialog || (b && b.hasAttribute('data-emoji-close'))) { dialog.close(); return; }
    if (!b || !b.hasAttribute('data-emoji') || !target) return;
    target.value = b.dataset.emoji;
    const img = b.querySelector('img');
    render(opener, target.value, img ? img.src.split('/').pop().replace(/\.png$/, '') : null);
    target.dispatchEvent(new Event('change', {bubbles: true}));
    dialog.close(); opener.focus();
  });
})();
