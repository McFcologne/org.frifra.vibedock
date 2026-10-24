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
  await save.click();
  // Session/CSRF bootstrap is asynchronous: wait for the saved page before explicitly reloading it.
  await expect(page.getByRole('heading',{name:'Renamed account',exact:false})).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading',{name:'Renamed account',exact:false})).toBeVisible();await expect(save).toBeHidden();
});

test('default design is saved right away',async ({page,app}) => {
  await editor(page,app);
  const scheme=page.locator('#design-scheme'), message=page.locator('#design-message');
  await expect(page.locator('#design-save')).toHaveCount(0);
  await scheme.selectOption('ocean');await expect(message).toContainText('Default design saved');
  // The message still says saved from before: wait for this save itself.
  const saved=page.waitForResponse(r=>r.url().endsWith('/admin/designs/default')&&r.request().method()==='POST');
  await page.locator('#design-align').selectOption('top');await saved;
  await page.reload();await expect(scheme).toHaveValue('ocean');await expect(page.locator('#design-align')).toHaveValue('top');
  await page.locator('#design-align').selectOption('spread');await expect(message).toContainText('Default design saved');
});

test('theme gallery selects real themes and persists instance availability without replacing an existing design',async ({page,app}) => {
  await editor(page,app);
  const grid=page.locator('#theme-grid'),nordic=grid.locator('[data-theme="nordic"]');
  await expect(grid.locator('.theme-card')).toHaveCount(35);
  await expect(page.locator('#theme-count')).toHaveText('35 / 35 enabled');
  for(const id of ['github_dark','vs_code','terminal','git_diff','debugger']){
    const card=grid.locator(`[data-theme="${id}"]`);
    await expect(card.getByRole('checkbox')).toBeChecked();
    const selected=page.waitForResponse(r=>r.url().endsWith('/admin/designs/default')&&r.request().method()==='POST');
    await card.getByRole('button',{name:'Use theme',exact:true}).click();await selected;
    await expect(page.locator('#design-scheme')).toHaveValue(id);
    await expect.poll(()=>card.locator('img').evaluate(img=>img.complete&&img.naturalWidth>0)).toBe(true);
  }

  const save=page.waitForResponse(r=>r.url().endsWith('/admin/designs/default')&&r.request().method()==='POST');
  await nordic.getByRole('button',{name:'Use theme',exact:true}).click();await save;
  await expect(page.locator('#design-scheme')).toHaveValue('nordic');
  await nordic.getByRole('checkbox').uncheck();
  await expect(page.locator('#theme-message')).toContainText('Nordic disabled');
  await expect(page.locator('#theme-count')).toHaveText('34 / 35 enabled');
  await expect(page.locator('#design-scheme')).toHaveValue('nordic');
  await expect(page.locator('#design-scheme option[value=nordic]')).toBeDisabled();
  await page.reload();await expect(nordic.getByRole('checkbox')).not.toBeChecked();
  await expect(page.locator('#design-scheme')).toHaveValue('nordic');
  const changed=page.waitForResponse(r=>r.url().endsWith('/admin/designs/default')&&r.request().method()==='POST');
  await grid.locator('[data-theme="porcelain"]').getByRole('button',{name:'Use theme',exact:true}).click();await changed;
  await expect(nordic.getByRole('button',{name:'Use theme',exact:true})).toBeDisabled();
  await page.locator('#design-target').selectOption('vibedock-e2e-office');
  await expect(grid.locator('[data-theme="mission_control"] button')).toBeDisabled();
  await page.locator('#design-override').check();
  await expect(grid.locator('[data-theme="mission_control"] button')).toBeEnabled();
  await nordic.getByRole('checkbox').check();await expect(page.locator('#theme-count')).toHaveText('35 / 35 enabled');
  await expect(nordic.getByRole('button',{name:'Use theme',exact:true})).toBeEnabled();
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

test('orientation turns on each click and saves at once; only touch boards offer it',async ({page,app}) => {
  await page.goto(app.baseURL+'/admin/devices');
  const shelf=page.locator('#device-vibedock-e2e-shelf'), value=shelf.locator('[data-turn-value]'), rotate=shelf.getByRole('button',{name:'Rotate by 180 degrees'});
  await expect(page.locator('#device-vibedock-e2e-office [data-turn]')).toHaveCount(0);
  await expect(value).toHaveText('0°');
  // The rectangular 1.47 turns in 180° steps; the card turns with it.
  await rotate.click();await expect(value).toHaveText('180°');await expect(rotate).toBeEnabled();
  await expect(shelf.locator('.device')).toHaveAttribute('style',/--turn: ?180deg/);
  await page.reload();await expect(value).toHaveText('180°');
  await rotate.click();await expect(value).toHaveText('0°');await expect(rotate).toBeEnabled();
  await page.reload();await expect(value).toHaveText('0°');
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

test('a photo still uploading when the editor switches to another display only joins the library',async ({page,app}) => {
  await editor(page,app);
  const message=page.locator('#design-message');
  // Both designs show photos: the shelf its own, the default one.
  await page.locator('#design-target').selectOption('vibedock-e2e-shelf');
  await page.locator('#design-override').check();
  await page.getByRole('button',{name:'Photos',exact:true}).click();await expect(message).toContainText('Device design saved');
  await page.locator('#design-target').selectOption('default');await expect(page.locator('#design-target')).toHaveValue('default');
  await page.getByRole('button',{name:'Photos',exact:true}).click();await expect(message).toContainText('Default design saved');
  // The upload starts in the default design and answers only after the switch to the shelf.
  let release;const held=new Promise(resolve=>release=resolve);
  await page.route('**/admin/photos',async route=>{if(route.request().method()==='POST')await held;await route.continue();});
  const uploaded=page.waitForResponse(r=>r.url().endsWith('/admin/photos')&&r.request().method()==='POST');
  await page.locator('#design-photo-upload').setInputFiles({name:'red.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAACgAAAAeCAIAAADRv8uKAAAAK0lEQVR4nO3NMQ0AAAgDsMmZfxXIQgYcTfo3056IWCwWi8VisVgsFov/xguJMCDM2TzTeAAAAABJRU5ErkJggg==','base64')});
  await page.locator('#design-target').selectOption('vibedock-e2e-shelf');await expect(page.locator('#design-target')).toHaveValue('vibedock-e2e-shelf');
  release();const id=(await (await uploaded).json()).id;
  await expect(page.locator('#design-photo-state')).toContainText('not chosen');
  await expect(page.locator('.photo-tile.selected')).toHaveCount(0);
  await page.waitForTimeout(1200);  // a save would run within 650 ms
  const designs=await page.evaluate(async()=>(await fetch('/admin/designs',{credentials:'same-origin'})).text());
  expect(designs).not.toContain(id);
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


test('TLS pin status shows a known fingerprint and pairing can omit it',async ({page,app}) => {
  let known=true,failed=false;
  await page.route('**/admin/tls-pin',route=>failed ? route.fulfill({status:503,body:'Unavailable'}) : route.fulfill({json:known
    ? {known:true,fingerprint:'ab'.repeat(20),message:'TLS pin available for https://external.example.org',route:'public-dns',target:'203.0.113.5:443',cached:true,certificate:{subject:'CN=external.example.org',issuer:'CN=Test issuer',notBefore:'2026-10-01T00:00:00Z',notAfter:'2026-12-01T00:00:00Z',sha256:'cd'.repeat(32),protocol:'TLSv1.3'}}
    : {known:false,fingerprint:null,message:'TLS pin unavailable: proxy not reachable'}}));
  // The pairing form offers only to leave the pin out; status and certificate are under Settings › Network.
  await page.goto(app.baseURL+'/admin/devices');
  await page.locator('[data-create-open="invite"]').click();
  await expect(page.locator('#invite [data-tls-pin-status]')).toHaveCount(0);
  await page.goto(app.baseURL+'/admin/server');
  await expect(page.locator('[data-tls-pin-value]')).toHaveText('ab'.repeat(20));
  const refresh=page.getByRole('button',{name:'Check pin again'});
  await expect(refresh.locator('svg')).toBeVisible();
  await expect(page.locator('[data-tls-pin-route]')).toContainText('Public DNS fallback');
  await expect(page.locator('[data-tls-pin-route]')).toContainText('203.0.113.5:443');
  await expect(page.locator('[data-tls-pin-route]')).toContainText('cached');
  await page.locator('[data-tls-cert] summary').click();
  await expect(page.locator('[data-cert-issuer]')).toHaveText('CN=Test issuer');
  await expect(page.locator('[data-cert-sha256]')).toHaveText('cd'.repeat(32));
  await expect(page.locator('[data-tls-pin-action]')).toHaveText(/Last refresh: .* · Pin available · \d+ ms/);
  known=false;await page.getByRole('button',{name:'Check pin again'}).click();
  await expect(page.locator('[data-tls-pin-message]')).toHaveText('TLS pin unavailable: proxy not reachable');
  await expect(page.locator('[data-tls-pin-route]')).toBeHidden();
  await expect(page.locator('[data-tls-cert]')).toBeHidden();
  await expect(page.locator('[data-tls-pin-value]')).toBeHidden();
  await expect(page.locator('[data-tls-pin-action]')).toHaveText(/Last refresh: .* · Pin unavailable · \d+ ms/);
  failed=true;await refresh.click();
  await expect(page.locator('[data-tls-pin-action]')).toHaveText(/Last refresh: .* · Failed · \d+ ms/);
  await expect(page.locator('[data-tls-pin-value]')).toBeHidden();await expect(refresh).toBeEnabled();
  await page.goto(app.baseURL+'/admin/devices');
  await page.locator('[data-create-open="invite"]').click();
  const omit=page.getByLabel('Do not send TLS pin', {exact:false});
  await expect(omit).not.toBeChecked();await omit.check();
  await page.getByLabel('Device IP',{exact:true}).fill('192.168.1.99');
  await page.getByLabel('Pairing code',{exact:true}).fill('123456');
  let submitted;
  await page.route('**/admin/clients/invite',async route=>{
    submitted=new URLSearchParams(route.request().postData());
    await route.fulfill({status:200,body:'Pairing request captured'});
  });
  await page.locator('#invite').getByRole('button',{name:'Pair device',exact:true}).click();
  await expect(page.getByText('Pairing request captured')).toBeVisible();
  expect(submitted.get('omitTlsPin')).toBe('on');
});

test('troubleshooting loads and refreshes server logs only for an administrator',async ({page,app}) => {
  const denied=await fetch(app.baseURL+'/admin/server-log',{redirect:'manual'});
  expect(denied.status).toBe(401);
  await page.goto(app.baseURL+'/admin/troubleshooting');
  const section=page.locator('[data-server-log]');
  await section.locator('summary').click();
  await expect(section.locator('[data-server-log-output]')).toContainText('VibeDock server started');
  await expect(section.locator('[data-server-log-status]')).toContainText('Loaded at');
  await section.getByRole('button',{name:'Refresh server log'}).click();
  await expect(section.locator('[data-server-log-status]')).toContainText('Loaded at');
});


test('server log follows the end, preserves reading position and pauses live updates',async ({page,app}) => {
  let loads=0;
  await page.route('**/admin/server-log',route=>route.fulfill({contentType:'text/plain',body:
    Array.from({length:400},(_,i)=>'Server log line '+i).join('\n')+'\nLatest entry '+(++loads)}));
  await page.goto(app.baseURL+'/admin/troubleshooting');
  await page.clock.install();await page.clock.pauseAt(new Date());
  const panel=page.locator('[data-server-log]'),output=panel.locator('[data-server-log-output]');
  await panel.locator('summary').click();
  await expect(output).toContainText('Latest entry 1');
  await expect.poll(()=>output.evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop)).toBeLessThan(2);
  await page.clock.runFor(5100);await expect(output).toContainText('Latest entry 2');
  await expect.poll(()=>output.evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop)).toBeLessThan(2);
  await output.evaluate(el=>{el.scrollTop=0;});
  await page.clock.runFor(5100);await expect(output).toContainText('Latest entry 3');
  expect(await output.evaluate(el=>el.scrollTop)).toBe(0);
  const live=panel.getByLabel('Live updates (every 5 seconds)');
  await live.uncheck();const paused=loads;await page.clock.runFor(10000);expect(loads).toBe(paused);
  await live.check();await expect(output).toContainText('Latest entry 4');
  await panel.locator('summary').click();await expect(panel).not.toHaveAttribute('open','');const closed=loads;
  await page.clock.runFor(10000);expect(loads).toBe(closed);
});

test('the port choice names the displays allowed before by what they report over USB',async ({page,app}) => {
  // Web Serial without hardware: two ports allowed before, one runs VibeDock firmware, the other does not answer.
  await page.addInitScript(() => {
    class FakePort {
      constructor(vendor, info) { this.vendor = vendor; this.info = info; this.buffer = ''; }
      getInfo() { return { usbVendorId: this.vendor, usbProductId: 0x1001 }; }
      async open() {
        const port = this;
        this.readable = new ReadableStream({ start(controller) { port.controller = controller; } });
        this.writable = new WritableStream({ write(chunk) {
          port.buffer += new TextDecoder().decode(chunk);
          for (let i; (i = port.buffer.indexOf('\n')) >= 0; port.buffer = port.buffer.slice(i + 1)) port.answer(port.buffer.slice(0, i));
        } });
      }
      answer(line) {
        if (!this.info) return;
        const say = text => this.controller.enqueue(new TextEncoder().encode(text + '\n'));
        if (line === 'VIBEDOCK-HELLO') say('VIBEDOCK-READY ' + JSON.stringify(this.info));
        if (line === 'VIBEDOCK-LOG') say('VIBEDOCK-LOG ["10-24 09:00:00 Start v0.2.4 waveshare-s3-touch-lcd-154"]');
      }
      async setSignals() {}
      async close() { try { this.controller.close(); } catch { /* closed */ } }
    }
    const ports = [new FakePort(0x303a, { identifier: 'vibedock-e2e-shelf', name: 'Shelf', version: '0.2.4', board: 'waveshare-s3-touch-lcd-154', paired: true }),
      new FakePort(0x1a86, null)];
    Object.defineProperty(navigator, 'serial', { configurable: true, value: {
      getPorts: async () => ports,
      requestPort: async options => { window.requestedFilters = options?.filters; throw new DOMException('No port selected', 'NotFoundError'); }
    } });
  });
  await page.goto(app.baseURL+'/admin/troubleshooting');
  await page.getByRole('button',{name:'Read log over USB',exact:true}).click();
  const dialog=page.locator('dialog.port-dialog');await expect(dialog).toBeVisible();
  const shelf=dialog.getByRole('button',{name:/Shelf/});
  await expect(shelf).toContainText('vibedock-e2e-shelf · Waveshare ESP32-S3-Touch-LCD-1.54 · firmware 0.2.4');
  await expect(dialog.getByRole('button',{name:/No VibeDock answer/})).toContainText('WCH USB serial',{timeout:10000});
  // A new display: the browser's own choice, limited to the USB chips of the boards.
  await dialog.getByRole('button',{name:'Another display …'}).click();
  expect(await page.evaluate(() => window.requestedFilters.map(f => f.usbVendorId))).toContain(0x303a);
  await expect(dialog).toBeVisible();
  await shelf.click();await expect(dialog).toHaveCount(0);
  const output=page.locator('[data-usb-output]');
  await expect(output).toContainText('Log of Shelf (vibedock-e2e-shelf, firmware 0.2.4');
  await expect(output).toContainText('Start v0.2.4 waveshare-s3-touch-lcd-154');
});

test('closing the port choice after a port went away ends there, without the browser dialog',async ({page,app}) => {
  // A port allowed before that is gone (a board with the chip's own USB right after its restart): opening it fails.
  await page.addInitScript(() => {
    const gone = { getInfo: () => ({ usbVendorId: 0x303a, usbProductId: 0x1001 }), open: async () => { throw new DOMException('The device has been lost.', 'NetworkError'); } };
    window.browserDialogs = 0;
    Object.defineProperty(navigator, 'serial', { configurable: true, value: {
      getPorts: async () => [gone],
      requestPort: async () => { window.browserDialogs++; throw new DOMException('No port selected', 'NotFoundError'); }
    } });
  });
  await page.goto(app.baseURL+'/admin/troubleshooting');
  await page.getByRole('button',{name:'Read log over USB',exact:true}).click();
  const dialog=page.locator('dialog.port-dialog');
  await dialog.getByRole('button',{name:/No VibeDock answer/}).click({timeout:10000});
  // Opening it fails: the choice comes again; closing it ends the action.
  await expect(dialog.getByRole('button',{name:/No VibeDock answer/})).toBeVisible({timeout:10000});
  await dialog.getByRole('button',{name:'Close'}).click();
  await expect(page.locator('[data-usb-output]')).toContainText('no display chosen');
  expect(await page.evaluate(() => window.browserDialogs)).toBe(0);
});
