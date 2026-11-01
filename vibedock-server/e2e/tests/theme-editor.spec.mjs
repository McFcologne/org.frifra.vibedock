import {test,expect,editor} from './fixtures.mjs';
import {readFile} from 'node:fs/promises';

async function open(page,app,id='midnight'){
  await page.goto(app.baseURL+'/admin/themes/'+id+'/edit');
  await expect(page.locator('#te-controls')).toBeEnabled();
  await page.locator('#te-details').evaluate(el=>el.open=true);
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
}
async function installed(page,id){return page.evaluate(async id=>(await(await fetch('/admin/designs')).json()).schemes.find(s=>s.id===id),id);}

test('customize opens a separate editor and an exported draft installs only after saving',async({page,app})=>{
  await editor(page,app);
  const popup=page.waitForEvent('popup');await page.locator('[data-theme="midnight"]').getByRole('link',{name:'Customize',exact:true}).click();
  const edit=await popup;await expect(edit.locator('#te-status')).toHaveText('Current draft · device renderer');await edit.close();
  await open(page,app);
  await page.locator('#te-name').fill('My night');await page.locator('#te-id').fill('browser.editor');
  await page.locator('#te-color-background').fill('#123456');await page.locator('#te-font').selectOption('DejaVu Sans Mono');
  await page.locator('#te-decoration-details > summary').click();
  await page.locator('.te-layer').first().getByRole('button',{name:'Add decoration'}).click();
  await expect(page.locator('.te-shape')).toHaveCount(1);await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  expect(await installed(page,'browser.editor')).toBeUndefined();
  const download=page.waitForEvent('download');await page.locator('#te-export').click();
  const bytes=await readFile(await(await download).path());expect(bytes.includes(Buffer.from('authoring/agent.txt'))).toBe(true);
  expect(await installed(page,'browser.editor')).toBeUndefined();
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('My night v1.0.0 saved');
  expect((await installed(page,'browser.editor')).builtin).toBe(false);await expect(page.locator('#te-version')).toHaveValue('1.0.1');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');await page.evaluate(()=>scrollTo(0,0));
  await page.setViewportSize({width:1280,height:1150});await page.screenshot({path:'test-results/theme-editor-desktop.png'});
  await page.getByRole('link',{name:'← All themes'}).click();await expect(page.locator('[data-theme="browser.editor"]')).toBeVisible();
  await expect(page.locator('#design-scheme')).toHaveValue('midnight');
});

test('uploaded icons can become the default; unsafe graphics cannot be saved',async({page,app})=>{
  await open(page,app,'blueprint');await expect(page.locator('.te-icon:visible')).toHaveCount(1);await expect(page.locator('#te-icons > details')).not.toHaveAttribute('open','');await page.locator('#te-id').fill('browser.icons');
  await page.locator('#te-icons').screenshot({path:'test-results/theme-icon-editor.png'});
  const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><circle cx="24" cy="24" r="20" fill="#ff0000"/></svg>');
  await page.locator('#te-icon-add').setInputFiles({name:'dot.svg',mimeType:'image/svg+xml',buffer:svg});
  const row=page.locator('.te-icon').filter({has:page.getByRole('img',{name:'dot.svg',exact:true})});await row.getByLabel('Creator',{exact:true}).fill('Test artist');await row.getByLabel('Theme default').check();
  await expect(page.locator('#te-upload-status')).toContainText('SVG cleaned; vector format retained');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  const bad=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>');
  await page.locator('.te-icon').first().locator('input[type=file]').setInputFiles({name:'bad.svg',mimeType:'image/svg+xml',buffer:bad});
  await expect(page.locator('#te-error')).toContainText('No visible static shapes');
  const sanitized=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><script>alert(1)</script><rect width="48" height="48" fill="#00ff00"/></svg>');
  await page.locator('.te-icon').first().locator('input[type=file]').setInputFiles({name:'static.svg',mimeType:'image/svg+xml',buffer:sanitized});
  await expect(page.locator('#te-upload-status')).toContainText('unsupported items removed');await page.locator('.te-icon').first().getByLabel('Creator',{exact:true}).fill('Test artist');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  expect((await installed(page,'browser.icons')).version).toBe('1.0.0');
});

test('a parallel edit cannot overwrite an already updated theme',async({page,app})=>{
  await open(page,app);await page.locator('#te-id').fill('browser.concurrent');await page.locator('#te-save').click();
  await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  const result=await page.evaluate(async()=>{
    const state=await(await fetch('/admin/themes/browser.concurrent/editor')).json();
    state.draft.drawing.palette.background='#334455';
    return (await fetch('/admin/theme-editor/save',{method:'POST',headers:{'Content-Type':'application/json',
      [document.querySelector('meta[name="csrf-header"]').content]:document.querySelector('meta[name="csrf-token"]').content},body:JSON.stringify(state.draft)})).status;
  });expect(result).toBe(200);
  await page.locator('#te-color-background').fill('#aabbcc');await page.locator('#te-version').fill('2.0.0');await page.locator('#te-save').click();
  await expect(page.locator('#te-error')).toContainText('source theme changed');await expect(page.locator('#te-color-background')).toHaveValue('#aabbcc');
  expect((await installed(page,'browser.concurrent')).version).toBe('1.0.1');
});

test('narrow screens keep preview and controls reachable and unsaved edits get a leave warning',async({page,app})=>{
  await page.setViewportSize({width:390,height:844});await open(page,app,'debugger');
  await expect(page.locator('#te-image')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.locator('#te-name').fill('Unsaved debugger');
  page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('link',{name:'← All themes'}).click();
  await expect(page.locator('#te-name')).toHaveValue('Unsaved debugger');
  await page.screenshot({path:'test-results/theme-editor-mobile.png',fullPage:true});
});

test('returning from a saved theme refreshes the gallery without replacing a pending design',async({page,app})=>{
  await editor(page,app);
  const other=await page.context().newPage();await open(other,app);await other.locator('#te-id').fill('browser.focus');
  let release,arrived;const gate=new Promise(resolve=>release=resolve),saving=new Promise(resolve=>arrived=resolve);
  await page.route('**/admin/designs/default',async route=>{arrived();await gate;await route.continue();});
  try{
    await page.locator('#design-scheme').selectOption('ocean');await saving;
    await other.locator('#te-save').click();await expect(other.locator('#te-saved')).toContainText('v1.0.0 saved');
    await page.bringToFront();await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
    await expect(page.locator('[data-theme="browser.focus"]')).toBeVisible();await expect(page.locator('#design-scheme')).toHaveValue('ocean');
    release();await expect.poll(async()=>page.evaluate(async()=>(await(await fetch('/admin/designs')).json()).defaults.scheme)).toBe('ocean');
  }finally{release();await other.close();}
});


test('transparent single-color PNG can be recolored, reset and exported with a safe offline page',async({page,app})=>{
  await open(page,app);await page.locator('#te-id').fill('browser.png');
  const base64=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=c.height=8;const g=c.getContext('2d');g.fillStyle='#000000';g.fillRect(2,2,4,4);return c.toDataURL('image/png').split(',')[1];});
  await page.locator('#te-icon-add').setInputFiles({name:'mark.png',mimeType:'image/png',buffer:Buffer.from(base64,'base64')});
  const row=page.locator('.te-icon').filter({has:page.getByRole('img',{name:'mark.png',exact:true})});
  await row.getByLabel('Creator',{exact:true}).fill('Test artist');await row.getByLabel('Theme default').check();await expect(row.getByLabel('Override main color')).toBeEnabled();
  await row.getByLabel('Override main color').check();
  await row.getByLabel('PNG main color').fill('#00ff00');await row.getByLabel('PNG main color').dispatchEvent('change');
  await expect(row.getByLabel('PNG main color')).toHaveValue('#00ff00');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  const download=page.waitForEvent('download');await page.locator('#te-export').click();const bytes=await readFile(await(await download).path());
  expect(bytes.includes(Buffer.from('preview.html'))).toBe(true);
  await row.getByLabel('Override main color').uncheck();await expect(row.getByLabel('PNG main color')).toBeHidden();
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  await expect(page.locator('.te-icon').first().getByLabel('Override main color')).toBeEnabled();
});


test('unpacked preview.html opens offline with local artwork and an actual display snapshot',async({browser})=>{
  const context=await browser.newContext();const page=await context.newPage(),requests=[];
  page.on('request',r=>requests.push(r.url()));
  try{
    await page.goto(new URL('../../target/theme-preview-offline/preview.html',import.meta.url).href);
    await expect(page.getByRole('heading',{name:'Midnight',exact:true})).toBeVisible();
    await expect(page.getByRole('img',{name:'VibeDock',exact:true})).toBeVisible();await expect(page.locator('link[rel=icon]')).toHaveAttribute('href',/preview\/vibedock-.*\.png/);
    await expect.poll(()=>page.locator('img').evaluateAll(images=>images.length>=3&&images.every(i=>i.complete&&i.naturalWidth>0))).toBe(true);
    expect(requests.every(url=>url.startsWith('file:'))).toBe(true);
    await expect(page.locator('script')).toHaveCount(0);
  }finally{await context.close();}
});


test('export asks for a missing creator and can remember an editable suggestion on the server',async({page,app})=>{
  await open(page,app);await page.locator('#te-id').fill('browser.contributor');
  const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><rect width="40" height="40" fill="#123456"/></svg>');
  await page.locator('#te-icon-add').setInputFiles({name:'art.svg',mimeType:'image/svg+xml',buffer:svg});
  await expect(page.locator('#te-upload-status')).toContainText('vector format retained');
  await page.locator('#te-export').click();const dialog=page.locator('#te-contributor-dialog');await expect(dialog).toBeVisible();
  await page.locator('#te-contributor-fields input').fill('Alex Artist');await page.locator('#te-contributor-remember').check();
  const download=page.waitForEvent('download');await page.locator('#te-contributor-continue').click();await download;await expect(dialog).not.toBeVisible();
  const prefs=JSON.parse(await readFile(app.dir+'/themes.json','utf8'));expect(prefs.contributor).toBe('Alex Artist');
  expect(await installed(page,'browser.contributor')).toBeUndefined();
  page.once('dialog',d=>d.accept());await page.reload();await expect(page.locator('#te-controls')).toBeEnabled();
  await page.locator('#te-icon-add').setInputFiles({name:'other.svg',mimeType:'image/svg+xml',buffer:svg});
  await expect(page.locator('#te-upload-status')).toContainText('vector format retained');await page.locator('#te-export').click();
  await expect(page.locator('#te-contributor-fields input')).toHaveValue('Alex Artist');await page.locator('#te-contributor-cancel').click();
  await expect(dialog).not.toBeVisible();await expect(page.locator('.te-icon').filter({has:page.getByRole('img',{name:'other.svg',exact:true})}).getByLabel('Creator',{exact:true})).toHaveValue('');
  await page.locator('#te-forget-contributor').click();await expect(page.locator('#te-contributor-note')).toBeHidden();
  expect(JSON.parse(await readFile(app.dir+'/themes.json','utf8')).contributor).toBeUndefined();
});


test('SVG uniform color stays vector, resets source colors and survives save and reload',async({page,app})=>{
  await open(page,app);await page.locator('#te-id').fill('browser.svg.color');
  const inherited=page.locator('.te-icon').first();await inherited.getByLabel('Use a uniform SVG color').check();
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await expect(inherited.getByLabel('Creator',{exact:true})).toHaveAttribute('readonly','');
  await inherited.getByLabel('Use a uniform SVG color').uncheck();
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><rect x="8" y="8" width="32" height="32" fill="none" stroke="#000000" stroke-width="3"/></svg>');
  await page.locator('#te-icon-add').setInputFiles({name:'outline.svg',mimeType:'image/svg+xml',buffer:svg});
  const row=page.locator('.te-icon').filter({has:page.getByRole('img',{name:'outline.svg',exact:true})});
  await row.getByLabel('Creator',{exact:true}).fill('Example artist');await row.getByLabel('Theme default').check();
  const toggle=row.getByLabel('Use a uniform SVG color'),color=row.getByLabel('SVG color',{exact:true});
  await expect(color).toBeVisible();await expect(color).toBeDisabled();await expect(color).toHaveValue('#000000');
  await toggle.check();await expect(color).toBeEnabled();
  await color.fill('#1267ab');await color.dispatchEvent('change');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  expect(await row.locator('img').evaluate(img=>atob(img.src.split(',')[1]))).toContain('#1267ab');
  await toggle.uncheck();await expect(color).toBeDisabled();await expect(color).toHaveValue('#000000');
  expect(await row.locator('img').evaluate(img=>atob(img.src.split(',')[1]))).toContain('#000000');
  await toggle.check();await color.fill('#1267ab');await color.dispatchEvent('change');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await expect(row.getByLabel('Creator',{exact:true})).toHaveValue('Example artist');
  const download=page.waitForEvent('download');await page.locator('#te-export').click();await download;
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  await page.reload();await expect(page.locator('#te-controls')).toBeEnabled();
  await expect(color).toHaveValue('#1267ab');await expect(color).toBeDisabled();
  const stored=await page.evaluate(async()=>{
    const state=await(await fetch('/admin/themes/browser.svg.color/editor')).json();
    const icon=state.draft.icons.find(i=>i.label==='outline.svg');
    const data=await(await fetch('/admin/themes/browser.svg.color/editor-icons/'+icon.id)).json();
    return {file:icon.file,svg:atob(data.data),author:icon.author};
  });
  expect(stored.file.endsWith('.svg')).toBe(true);expect(stored.svg).toContain('#1267ab');expect(stored.author).toBe('Example artist');
  await row.screenshot({path:'test-results/svg-color-controls.png'});
});

test('window colors are editable and reset can recover invalid drafts and saved appearances',async({page,app})=>{
  await open(page,app,'classic_windows');
  await expect(page.locator('#te-window-section')).toBeVisible();
  await expect(page.locator('#te-window-titleBar')).toHaveValue('#000080');
  await page.locator('#te-id').fill('browser.window.reset');await page.locator('#te-name').fill('My window');
  await page.locator('#te-window-surface').fill('#ff00ff');await page.locator('#te-window-surface').dispatchEvent('input');
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  const drawing=()=>page.evaluate(async()=>(await(await fetch('/admin/themes/browser.window.reset/editor')).json()).draft.drawing);
  expect((await drawing()).window.surface).toBe('#ff00ff');
  await page.locator('#te-name').fill('');await page.locator('#te-window-surface').fill('#00ff00');await page.locator('#te-window-surface').dispatchEvent('input');
  await page.locator('#te-reset').click();await expect(page.locator('#te-name')).toHaveValue('My window');
  await expect(page.locator('#te-window-surface')).toHaveValue('#ff00ff');await expect(page.locator('#te-reset')).toBeDisabled();
  await page.locator('.te-reset-template > summary').click();await page.locator('#te-reset-template').selectOption('classic_windows');
  await page.locator('#te-restore-template').click();await expect(page.locator('#te-window-surface')).toHaveValue('#c0c0c0');
  await expect(page.locator('#te-name')).toHaveValue('My window');expect((await drawing()).window.surface).toBe('#ff00ff');
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.1 saved');
  expect((await drawing()).window.surface).toBe('#c0c0c0');
  await page.locator('#te-details').evaluate(el=>el.open=false);
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await page.setViewportSize({width:1280,height:1100});await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:'test-results/editor-windows-reset.png'});
});
