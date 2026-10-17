import {test,expect,editor} from './fixtures.mjs';

test('pairing form hides its trigger and restores it after Cancel and Escape',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/devices');
  const trigger=page.locator('[data-create-open="invite"]'), panel=page.locator('#invite');
  await expect(panel).toBeHidden();
  await trigger.click();await expect(trigger).toBeHidden();await expect(panel).toBeVisible();
  await panel.getByRole('button',{name:'Cancel',exact:true}).click();
  await expect(panel).toBeHidden();await expect(trigger).toBeFocused();
  await trigger.click();await expect(panel.getByText('HTTP pairing is unencrypted.',{exact:false})).toBeVisible();
  await expect(panel.getByLabel('Device IP',{exact:true})).toBeVisible();
  await expect(panel.getByRole('button',{name:'Pair device',exact:true})).toBeEnabled();
  await panel.getByLabel('Device IP',{exact:true}).press('Escape');
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

test('default design is saved right away',async ({page,app}) => {
  await editor(page,app);
  const scheme=page.locator('#design-scheme'), message=page.locator('#design-message');
  await expect(page.locator('#design-save')).toHaveCount(0);
  await scheme.selectOption('ocean');await expect(message).toContainText('Default design saved');
  await page.locator('#design-align').selectOption('top');await expect(message).toContainText('Default design saved');
  await page.reload();await expect(scheme).toHaveValue('ocean');await expect(page.locator('#design-align')).toHaveValue('top');
  await page.locator('#design-align').selectOption('spread');await expect(message).toContainText('Default design saved');
});

test('device inherits the default, can override it, and can return to inheritance',async ({page,app}) => {
  await editor(page,app);const message=page.locator('#design-message');
  await page.locator('#design-scheme').selectOption('ocean');await expect(message).toContainText('Default design saved');
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  const scheme=page.locator('#design-scheme'), override=page.locator('#design-override');
  await expect(scheme).toHaveValue('ocean');await expect(scheme).toBeDisabled();
  await override.check();await scheme.selectOption('forest');await expect(message).toContainText('Device design saved');
  await page.reload();
  await expect(scheme).toHaveValue('forest');await expect(override).toBeChecked();
  await override.uncheck();await expect(message).toContainText('follows the default design again');await page.reload();
  await expect(override).not.toBeChecked();await expect(scheme).toHaveValue('ocean');await expect(scheme).toBeDisabled();
});

test('switching targets right after an edit saves it first',async ({page,app}) => {
  await editor(page,app);await page.locator('#design-scheme').selectOption('forest');
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  await expect(page.locator('#design-target')).toHaveValue('vibedock-e2e-office');await expect(page.locator('#design-scheme')).toHaveValue('forest');
  await expect(page.locator('#design-scheme')).toBeDisabled();
});

test('flip image saves in place and only touch boards offer it',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/devices');
  const shelf=page.locator('#device-vibedock-e2e-shelf'), flip=shelf.getByLabel('Flip image');
  await expect(page.locator('#device-vibedock-e2e-office').getByLabel('Flip image')).toHaveCount(0);
  await expect(flip).not.toBeChecked();await shelf.getByText('Flip image',{exact:true}).click();await expect(flip).toBeChecked();await expect(flip).toBeEnabled();
  await page.reload();await expect(flip).toBeChecked();
});

test('support file is created with a key shown once and opens again with it',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/troubleshooting');
  const download=page.waitForEvent('download');
  await page.getByLabel('In one line').fill('Shelf stays dark');await page.getByLabel('What happened').fill('Black after the update');
  await page.getByLabel(/Shelf/).check();
  await page.getByRole('button',{name:'Create support file',exact:true}).click();
  const file=await (await download).path();
  const key=await page.locator('[data-support-key-value]').textContent();
  expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/);
  const open=page.locator('[data-support-open]');
  await open.locator('input[type=file]').setInputFiles(file);
  await open.getByLabel('Key').fill('wrong');await open.getByRole('button',{name:'Open',exact:true}).click();
  await expect(page.locator('[data-support-open-error]')).toContainText('Wrong key');
  await open.getByLabel('Key').fill(key);await open.getByRole('button',{name:'Open',exact:true}).click();
  const contents=page.locator('[data-support-contents]');
  await expect(contents).toContainText('Displays (2)');await expect(contents).toContainText('Shelf');
  await expect(contents).toContainText('Shelf stays dark');await expect(contents).toContainText('Black after the update');
  // Raw: the whole content as JSON, to copy or download.
  const raw=JSON.parse(await page.locator('[data-support-raw]').textContent());
  expect(raw.report.title).toBe('Shelf stays dark');expect(raw.report.displays).toHaveLength(1);
  const json=page.waitForEvent('download');await page.getByRole('button',{name:'Download JSON'}).click();
  expect((await json).suggestedFilename()).toMatch(/\.json$/);  // named after the opened file
  await page.getByRole('button',{name:'Copy as JSON'}).click();
});

test('a device shows uploaded photos, saved right away with the time of day',async ({page,app}) => {
  await editor(page,app);
  await page.locator('#design-target').selectOption('vibedock-e2e-shelf');
  await page.locator('#design-override').check();
  await expect(page.locator('#design-usage')).toBeVisible();
  await page.getByRole('button',{name:'Photos',exact:true}).click();
  await expect(page.locator('#design-usage')).toBeHidden();
  await expect(page.locator('#design-photos')).toContainText('No photos yet');
  await page.locator('#design-photo-upload').setInputFiles({name:'red.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAACgAAAAeCAIAAADRv8uKAAAAK0lEQVR4nO3NMQ0AAAgDsMmZfxXIQgYcTfo3056IWCwWi8VisVgsFov/xguJMCDM2TzTeAAAAABJRU5ErkJggg==','base64')});
  await expect(page.locator('#design-photo-state')).toContainText('1 of 1 photos added');
  await expect(page.locator('.photo-tile.selected')).toHaveCount(1);
  await page.getByLabel('Only at certain times').check();
  await expect(page.locator('#design-photo-from')).toHaveValue('22:00');
  // The usage monitor for the other times shows below the photos.
  await expect(page.locator('#design-usage-heading')).toHaveText('Usage monitor · outside 22:00–07:00');
  await expect(page.locator('#design-message')).toContainText('Device design saved');
  await page.reload();await expect(page.locator('#design-scheme')).toBeEnabled();
  await expect(page.getByRole('button',{name:'Photos',exact:true})).toHaveAttribute('aria-pressed','true');await expect(page.locator('#design-photo-timed-check')).toBeChecked();
  await expect(page.locator('.photo-tile.selected .photo-order')).toHaveText('1');
  // Back to the usage monitor: the photo selection is kept for switching back.
  await page.getByRole('button',{name:'Usage monitor',exact:true}).click();
  await expect(page.locator('#design-photo-settings')).toBeHidden();await expect(page.locator('#design-photos-kept')).toBeVisible();
  await expect(page.locator('#design-message')).toContainText('Device design saved');
  await page.reload();await expect(page.locator('#design-scheme')).toBeEnabled();
  await expect(page.getByRole('button',{name:'Usage monitor',exact:true})).toHaveAttribute('aria-pressed','true');
  await page.getByRole('button',{name:'Photos',exact:true}).click();
  await expect(page.locator('.photo-tile.selected .photo-order')).toHaveText('1');
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
    for(const route of ['/admin','/admin/devices','/admin/sources','/admin/design','/admin/server','/admin/licenses','/admin/firmware','/admin/troubleshooting']) {
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
