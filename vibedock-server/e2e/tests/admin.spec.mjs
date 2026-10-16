import {test,expect,editor} from './fixtures.mjs';

test('pairing form hides its trigger and restores it after Cancel and Escape',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/devices');
  const trigger=page.locator('[data-create-open="invite"]'), panel=page.locator('#invite');
  await expect(panel).toBeHidden();
  await trigger.click();await expect(trigger).toBeHidden();await expect(panel).toBeVisible();
  await panel.getByRole('button',{name:'Cancel',exact:true}).click();
  await expect(panel).toBeHidden();await expect(trigger).toBeFocused();
  await trigger.click();await page.getByLabel('Device IP',{exact:true}).press('Escape');
  await expect(panel).toBeHidden();await expect(trigger).toBeVisible();
});

test('source creation has one visible Add action and persists a new account',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/sources');
  const trigger=page.locator('[data-create-open="add-source"]'), panel=page.locator('#add-source');
  await trigger.click();await expect(trigger).toBeHidden();
  await panel.getByRole('button',{name:'Cancel',exact:true}).click();await expect(trigger).toBeVisible();
  await trigger.click();await panel.getByLabel('Name on the display').fill('Browser test account');
  await panel.getByRole('button',{name:'Add data source',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Browser test account',exact:false})).toBeVisible();
  await expect(panel).toBeHidden();await expect(trigger).toBeVisible();
  await page.reload();await expect(page.getByRole('heading',{name:'Browser test account',exact:false})).toBeVisible();
});

test('source save appears only for changes and a saved rename survives reload',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/sources');
  const card=page.locator('.source').filter({has:page.getByRole('heading',{name:'Codex Codex',exact:true})});
  await card.getByRole('button',{name:'Manage',exact:true}).click();
  const dialog=card.locator('dialog.source-dialog');await expect(dialog).toBeVisible();
  const name=dialog.getByLabel('Name on the display',{exact:true}), save=page.getByRole('button',{name:'Save data sources',exact:true});
  await expect(save).toBeHidden();await name.fill('Renamed account');await expect(save).toBeVisible();
  await name.fill('Codex');await expect(save).toBeHidden();
  await name.fill('Renamed account');await page.keyboard.press('Escape');await expect(dialog).toBeHidden();
  await save.click();await page.reload();
  await expect(page.getByRole('heading',{name:'Renamed account',exact:false})).toBeVisible();await expect(save).toBeHidden();
});

test('default design supports Discard and persists Save',async ({page,app}) => {
  await editor(page,app);
  const scheme=page.locator('#design-scheme'), save=page.locator('#design-save'), discard=page.locator('#design-discard');
  await expect(save).toBeHidden();await scheme.selectOption('ocean');await expect(save).toBeEnabled();
  await discard.click();await expect(scheme).toHaveValue('midnight');await expect(save).toBeHidden();
  await scheme.selectOption('ocean');await expect(save).toBeEnabled();await save.click();
  await expect(save).toBeHidden();await page.reload();await expect(scheme).toHaveValue('ocean');
});

test('device inherits the default, can override it, and can return to inheritance',async ({page,app}) => {
  await editor(page,app);
  await page.locator('#design-scheme').selectOption('ocean');await expect(page.locator('#design-save')).toBeEnabled();await page.locator('#design-save').click();
  await expect(page.locator('#design-save')).toBeHidden();
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  const scheme=page.locator('#design-scheme'), override=page.locator('#design-override');
  await expect(scheme).toHaveValue('ocean');await expect(scheme).toBeDisabled();
  await override.check();await scheme.selectOption('forest');await expect(page.locator('#design-save')).toBeEnabled();await page.locator('#design-save').click();
  await expect(page.locator('#design-save')).toBeHidden();await page.reload();
  await expect(scheme).toHaveValue('forest');await expect(override).toBeChecked();
  await override.uncheck();await expect(page.locator('#design-save')).toBeEnabled();await page.locator('#design-save').click();
  await expect(page.locator('#design-save')).toBeHidden();await page.reload();
  await expect(override).not.toBeChecked();await expect(scheme).toHaveValue('ocean');await expect(scheme).toBeDisabled();
});

test('switching targets protects unsaved changes with Keep editing or Discard',async ({page,app}) => {
  await editor(page,app);await page.locator('#design-scheme').selectOption('forest');
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  await expect(page.locator('#design-switch')).toBeVisible();
  await page.getByRole('button',{name:'Keep editing',exact:true}).click();
  await expect(page.locator('#design-target')).toHaveValue('default');await expect(page.locator('#design-scheme')).toHaveValue('forest');
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  await page.getByRole('button',{name:'Discard and switch',exact:true}).click();
  await expect(page.locator('#design-target')).toHaveValue('vibedock-e2e-office');await expect(page.locator('#design-scheme')).toHaveValue('midnight');
  await expect(page.locator('#design-scheme')).toBeDisabled();
});

test('footer links to the generated license page and uses the packaged version',async ({page}) => {
  const footer=page.getByRole('contentinfo');await expect(footer).toContainText('VibeDock');await expect(footer).not.toContainText('development');
  await expect(footer.getByRole('link',{name:'README',exact:true})).toHaveAttribute('href',/github\.com\/McFcologne\/org\.frifra\.vibedock#readme/);
  await footer.getByRole('link',{name:'Licenses & notices',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Licenses & notices',exact:true})).toBeVisible();
  await expect(page.locator('.license-report')).toContainText('spring-boot');
  await expect(page.locator('.license-report')).not.toContainText('junit-jupiter');
});

for(const width of [1024,360]) for(const dark of [false,true]) {
  test(`layout stays usable at ${width}px in ${dark?'dark':'light'} mode`,async ({page,app}) => {
    await page.setViewportSize({width,height:850});
    if(dark) {
      // The toggle works once theme.js has wired it (aria-pressed is set then); a click before is lost.
      const toggle=page.locator('[data-theme-toggle]');
      await expect(toggle).toHaveAttribute('aria-pressed','false');await toggle.click();
      await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
    }
    for(const route of ['/admin','/admin/devices','/admin/sources','/admin/design','/admin/server','/admin/licenses','/admin/firmware']) {
      await page.goto(app.baseURL+route);
      if(route==='/admin/design') await expect(page.locator('#design-target')).toBeEnabled();
      await expect(page.locator('main h2').first()).toBeVisible();
      expect(await page.evaluate(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth)).toBe(true);
      expect(await page.locator('html').getAttribute('data-theme')).toBe(dark?'dark':null);
      const footer=await page.getByRole('contentinfo').boundingBox(), main=await page.locator('main').boundingBox();
      expect(footer.y).toBeGreaterThanOrEqual(main.y+main.height-1);
      if(route==='/admin'&&width===1024) {
        const sources=await page.locator('#status').boundingBox(), display=await page.locator('.overview-display').boundingBox(), devices=await page.locator('#device-summary').boundingBox();
        expect(Math.abs(sources.y-display.y)).toBeLessThan(2);expect(sources.height).toBeGreaterThanOrEqual(display.height-1);
        expect(devices.y).toBeGreaterThanOrEqual(Math.max(sources.y+sources.height,display.y+display.height));
      }
      if(route==='/admin/design') {
        // The preview image loads after the page; measure once the layout has settled.
        await expect.poll(async()=>{
          const preview=await page.locator('.design-preview').boundingBox(), appearance=await page.locator('#design-style-controls').boundingBox();
          return appearance.y>=preview.y+preview.height-1&&Math.abs(appearance.x-preview.x)<2;
        }).toBe(true);
      }
    }
  });
}

test('previews open the whole device enlarged and close again',async ({page,app}) => {
  for(const path of ['/admin','/admin/devices','/admin/design']){
    await page.goto(app.baseURL+path);
    const device=page.locator('main .device[role="button"]').first(), dialog=page.locator('dialog.device-lightbox');
    const small=await device.boundingBox();
    await device.click();await expect(dialog).toBeVisible();
    const large=await dialog.locator('.device').boundingBox();
    expect(large.width).toBeGreaterThan(small.width*1.5);
    expect(large.width).toBeLessThanOrEqual(page.viewportSize().width);
    await page.keyboard.press('Escape');await expect(dialog).toBeHidden();
    await device.press('Enter');await expect(dialog).toBeVisible();
    await dialog.click();await expect(dialog).toBeHidden();
  }
});
