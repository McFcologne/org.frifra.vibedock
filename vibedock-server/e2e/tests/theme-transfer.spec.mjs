import {test,expect,editor} from './fixtures.mjs';
import {readFile} from 'node:fs/promises';

async function exported(page,agent){
  await page.locator('#theme-export-agent').setChecked(agent);
  const download=page.waitForEvent('download');
  const card=page.locator('[data-theme="midnight"]');
  if(!await card.locator('.theme-card-menu').evaluate(el=>el.open))await card.locator('summary').click();
  await card.getByRole('link',{name:'Export',exact:true}).click();
  return readFile(await (await download).path());
}

test('compressed theme exports can be copied, used and removed without restarting',async({page,app})=>{
  await editor(page,app);const guided=await exported(page,true),minimal=await exported(page,false);
  expect(guided.includes(Buffer.from('authoring/agent.txt'))).toBe(true);
  expect(minimal.includes(Buffer.from('authoring/agent.txt'))).toBe(false);expect(minimal.length).toBeLessThan(guided.length);
  await page.locator('#theme-import-file').setInputFiles({name:'midnight.vibetheme',mimeType:'application/zip',buffer:guided});
  await expect(page.locator('#theme-import-review')).toBeVisible();await expect(page.locator('#theme-install')).toBeHidden();
  await page.locator('#theme-copy-id').fill('browser.space');await page.locator('#theme-copy-name').fill('Browser Space');
  await page.locator('#theme-copy').click();const card=page.locator('[data-theme="browser.space"]');
  await expect(card).toBeVisible();await expect(card.getByRole('heading')).toHaveText('Browser Space');
  await card.locator('[data-theme-choose]').click();await expect(card.locator('[data-theme-remove]')).toBeDisabled();
  await expect.poll(async()=>page.evaluate(async()=>(await(await fetch('/admin/designs')).json()).defaults.scheme)).toBe('browser.space');
  await page.reload();await expect(page.locator('#design-scheme')).toHaveValue('browser.space');
  await page.locator('#design-icon-mode').selectOption('theme-default');
  await expect(page.locator('#design-theme-icon')).toHaveAttribute('src','/admin/themes/browser.space/icons/theme.png');
  await page.locator('#design-scheme').selectOption('midnight');
  await expect(page.locator('[data-theme="browser.space"] [data-theme-remove]')).toBeEnabled();
  await page.locator('[data-theme="browser.space"] summary').click();
  await page.locator('[data-theme="browser.space"] [data-theme-remove]').click();
  await expect(page.locator('[data-theme="browser.space"]')).toHaveCount(0);
});

test('invalid imports and cancelling an inspected archive leave the catalog unchanged',async({page,app})=>{
  await editor(page,app);
  await page.locator('#theme-import-file').setInputFiles({name:'bad.vibetheme',mimeType:'application/zip',buffer:Buffer.from('bad archive')});
  await expect(page.locator('#design-error')).toBeVisible();await expect(page.locator('.theme-card')).toHaveCount(45);
  const bytes=await exported(page,false);
  await page.locator('#theme-import-file').setInputFiles({name:'theme.vibetheme',mimeType:'application/zip',buffer:bytes});
  await expect(page.locator('#theme-import-review')).toBeVisible();await page.locator('#theme-import-cancel').click();
  await expect(page.locator('#theme-import-review')).toBeHidden();await expect(page.locator('.theme-card')).toHaveCount(45);
});

test('an import finishing during a pending design save does not replace that draft',async({page,app})=>{
  await editor(page,app);const bytes=await exported(page,false);
  await page.locator('#theme-import-file').setInputFiles({name:'theme.vibetheme',mimeType:'application/zip',buffer:bytes});
  await expect(page.locator('#theme-import-review')).toBeVisible();await page.locator('#theme-copy-id').fill('browser.pending');
  let releaseImport,releaseSave,importArrived,saveArrived;
  const importGate=new Promise(resolve=>releaseImport=resolve),saveGate=new Promise(resolve=>releaseSave=resolve);
  const importing=new Promise(resolve=>importArrived=resolve),saving=new Promise(resolve=>saveArrived=resolve);
  await page.route(/\/admin\/themes\/import\?/,async route=>{importArrived();await importGate;await route.continue();});
  await page.route('**/admin/designs/default',async route=>{saveArrived();await saveGate;await route.continue();});
  try{
    await page.locator('#theme-copy').click();await importing;
    await page.locator('#design-scheme').selectOption('ocean');await saving;
    releaseImport();await expect(page.locator('[data-theme="browser.pending"]')).toBeVisible();
    await expect(page.locator('#design-scheme')).toHaveValue('ocean');
    releaseSave();await expect.poll(async()=>page.evaluate(async()=>(await(await fetch('/admin/designs')).json()).defaults.scheme)).toBe('ocean');
  }finally{releaseImport();releaseSave();}
});
