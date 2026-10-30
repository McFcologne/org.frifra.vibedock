import {test,expect,editor} from './fixtures.mjs';
import {readFile} from 'node:fs/promises';

async function open(page,app,id='midnight'){
  await page.goto(app.baseURL+'/admin/themes/'+id+'/edit');
  await expect(page.locator('#te-controls')).toBeEnabled();
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
  await open(page,app,'blueprint');await page.locator('#te-id').fill('browser.icons');
  const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><circle cx="24" cy="24" r="20" fill="#ff0000"/></svg>');
  await page.locator('#te-icon-add').setInputFiles({name:'dot.svg',mimeType:'image/svg+xml',buffer:svg});
  const row=page.locator('.te-icon').last();await row.getByLabel('Theme default').check();
  await expect(page.locator('#te-status')).toHaveText('Current draft · device renderer');
  await page.locator('#te-save').click();await expect(page.locator('#te-saved')).toContainText('v1.0.0 saved');
  const bad=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>');
  await page.locator('.te-icon').last().locator('input[type=file]').setInputFiles({name:'bad.svg',mimeType:'image/svg+xml',buffer:bad});
  await expect(page.locator('#te-error')).toContainText('Unsupported theme graphic');await expect(page.locator('#te-image')).toBeHidden();
  await page.locator('#te-save').click();await expect(page.locator('#te-error')).toContainText('Unsupported theme graphic');
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
