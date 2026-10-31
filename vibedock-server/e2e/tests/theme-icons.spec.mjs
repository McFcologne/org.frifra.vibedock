import {test,expect,editor} from './fixtures.mjs';

async function saved(page){await expect(page.locator('#design-message')).toContainText('saved');}
async function defaults(page,app){return page.evaluate(async base=>(await (await fetch(base+'/admin/designs')).json()).defaults,app.baseURL);}

test('theme default follows selection and persists while the old None choice stays intact',async({page,app})=>{
  await editor(page,app);
  await expect(page.locator('#design-icon-mode')).toHaveValue('emoji');
  expect((await defaults(page,app)).icon).toBeUndefined();
  await page.locator('#design-icon-mode').selectOption('theme-default');await saved(page);
  await expect(page.locator('#design-theme-icon')).toHaveAttribute('src','/admin/themes/midnight/icons/theme.png');
  await page.locator('#design-scheme').selectOption('ocean');
  await expect(page.locator('#design-theme-icon')).toHaveAttribute('src','/admin/themes/ocean/icons/theme.png');
  await expect.poll(async()=>(await defaults(page,app)).scheme).toBe('ocean');
  expect((await defaults(page,app)).mood).toBe('');
  await page.reload();await expect(page.locator('#design-icon-mode')).toHaveValue('theme-default');
  await expect(page.locator('#design-theme-icon')).toBeVisible();
  await page.locator('#design-icon-mode').selectOption('emoji');
  await expect.poll(async()=>(await defaults(page,app)).icon).toBeUndefined();
  await expect(page.locator('#design-mood-button')).toContainText(/none/i);
});

test('fixed catalog icon survives theme changes and works in the narrow editor',async({page,app},testInfo)=>{
  await page.setViewportSize({width:430,height:932});await editor(page,app);
  await page.locator('#design-icon-mode').selectOption('theme-catalog');
  await page.locator('#design-catalog-icon').selectOption('blueprint/theme');
  await expect(page.locator('#design-theme-icon')).toHaveAttribute('src','/admin/themes/blueprint/icons/theme.png');
  await page.locator('[data-theme-choose="forest"]').click();
  await expect.poll(async()=>(await defaults(page,app)).scheme).toBe('forest');
  await expect(page.locator('#design-catalog-icon')).toHaveValue('blueprint/theme');
  const design=await defaults(page,app);expect(design.icon).toEqual({mode:'theme-catalog',theme:'blueprint',id:'theme'});
  expect(design.mood).toBe('');
  await page.reload();await expect(page.locator('#design-catalog-icon')).toHaveValue('blueprint/theme');
  await page.locator('#design-icon-mode').scrollIntoViewIfNeeded();
  await expect(page.locator('#design-theme-icon')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:testInfo.outputPath('theme-icon-controls.png'),fullPage:true});
  await page.locator('#design-mood-settings').screenshot({path:testInfo.outputPath('theme-icon-picker.png')});
});
