// Screenshots of the user manual (docs/manual/images), started by scripts/docs.sh. Starts the server with demo data
// (org.frifra.vibedock.server.DocsDemo: fixed clock, made-up usage and weather), brings the demo devices into their states over
// the device API and photographs every administration page. The same sources always give the same pictures.
import {chromium, expect} from '@playwright/test';
import {mkdtemp, mkdir, writeFile, readFile, rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {once} from 'node:events';
import {deviceScreens} from './device-screens.mjs';

const server = fileURLToPath(new URL('../../', import.meta.url));
const out = path.join(server, '../docs/manual/images');
const version = process.env.DOCS_VERSION || '0.1.0';
const password = 'docs-only-password';
// scripts/docs.sh serve: the same demo server stays up on the usual ports for local work, without screenshots.
const serve = process.argv.includes('--serve');
// DEMO_DATA=<data directory> with serve: that directory's settings and devices instead of the manual's (kept on exit).
const own = serve && process.env.DEMO_DATA ? path.resolve(server, process.env.DEMO_DATA) : null;
const sha256 = text => createHash('sha256').update(text).digest('hex');
async function freePort() {
  const socket = createServer(); socket.listen(0, '127.0.0.1'); await once(socket, 'listening');
  const port = socket.address().port; await new Promise(resolve => socket.close(resolve)); return port;
}

// ---- demo data: like a typical installation with work and private accounts and three VibeDock displays ----
const id = n => `00000000-0000-4000-8000-00000000000${n}`;
const sources = [
  {id: id(1), type: 'claude', label: 'Claude ACME', token: 'demo-claude-work'},
  {id: id(2), type: 'codex', label: 'Codex ACME', token: 'demo-codex-work'},
  {id: id(3), type: 'claude', label: 'Claude Private', token: 'demo-claude-private', suspended: true},
  {id: id(4), type: 'codex', label: 'Codex Private', token: 'demo-codex-private'},
  {id: id(5), type: 'grok', label: 'Grok', token: 'demo-grok'},
  {id: id(6), type: 'gemini', label: 'Gemini', token: 'demo-unavailable'}
];
const settings = {allowedIps: [], devices: [], accounts: sources.map(s => ({id: s.id, type: s.type, label: s.label,
  manual: {token: s.token, accountId: ''}, folder: 'accounts/' + s.id, suspended: !!s.suspended}))};
const place = name => ({name, latitude: 40.71, longitude: -74.01});
const usage = (list, compact) => ({type: 'usage', sources: list, ...(compact ? {compact: true} : {})});
const design = (scheme, mood, weather, bands, moodLeft = false) => ({scheme, mood, weather: place(weather), layout: {bands: [...bands, {type: 'context', moodLeft}]}});
const key = 'd'.repeat(64);
const webSecret = 'e'.repeat(64);
const now = Date.parse('2026-10-14T07:41:00Z') / 1000;  // DocsDemo.NOW
const device = (identifier, name, extra = {}) => ({identifier, name, keyHash: sha256(key), ip: '0.0.0.0', port: 80, pairedAt: now - 30 * 86400,
  lastPullAt: null, lastPullIp: null, design: null, serverFooter: true, firmware: null, display: null, web: null, board: null, ...extra});
const clients = {
  version: 3, server: {internalUrl: 'http://192.168.1.10:10002', externalUrl: 'https://vibedock.example.com', pollSeconds: 30},
  defaultDesign: design('midnight', '🥳', 'New York, US', [usage([id(1)]), usage([id(2)])]),
  devices: [
    device('vibedock-0a1b2c000001', 'Living room', {design: design('classic_windows', '😎', 'New York, US', [usage([id(1)]), usage([id(2)])])}),
    device('vibedock-0a1b2c000002', 'Home office', {design: design('classic_mac', '🤖', 'New York, US', [usage([id(1)]), usage([id(2)], true), usage([id(4)], true)])}),
    device('vibedock-0a1b2c000003', 'Test bench', {design: design('midnight', '😎', 'London, GB', [usage([id(1)]), usage([id(2)])])}),
    device('web-0123456789ab', 'Kitchen tablet', {keyHash: sha256(webSecret), design: design('paper', '🎉', 'New York, US', [usage([id(2), id(1)]), usage([id(4), id(5)])]),
      display: '320x240', web: {secret: webSecret, external: false, reloadSeconds: 30}, lastPullAt: now - 3600, lastPullIp: '192.168.1.40'})
  ]
};
const boards = ['vibedock-m1', 'esp32-cyd', 'lilygo-t-display', 'lilygo-t-display-s3', 'waveshare-s3-lcd-169', 'waveshare-s3-touch-lcd-147', 'waveshare-c6-touch-lcd-169', 'esp32-c3-st7789'];

// ---- server ----
const dir = own || await mkdtemp(path.join(tmpdir(), 'vibedock-docs-'));
if (!own) {
  await writeFile(path.join(dir, 'settings.json'), JSON.stringify(settings));
  await writeFile(path.join(dir, 'clients.json'), JSON.stringify(clients));
}
const firmware = path.join(dir, 'firmware');
// Made-up firmware for the pictures only; the demo server for local work offers none, so nothing can be installed from it.
if (!serve) {
  for (const board of boards) {
    await mkdir(path.join(firmware, board), {recursive: true});
    const image = Buffer.alloc(board === 'vibedock-m1' ? 563664 : 1201920, 0xe9);  // sizes of real builds, content made up
    await writeFile(path.join(firmware, board, 'flash.bin'), image); await writeFile(path.join(firmware, board, 'firmware.bin'), image);
  }
  await writeFile(path.join(firmware, 'version'), version + '\n');
  await writeFile(path.join(dir, 'manual.pdf'), '%PDF-1.7\n');  // so that the footer links the manual, as in the image
  await writeFile(path.join(firmware, 'esptool.js'), 'export class ESPLoader {} export class Transport {}');
}
const [admin, api] = serve ? [10001, 10002] : [await freePort(), await freePort()];
const classpath = ['target/classes', 'target/test-classes', (await readFile(path.join(server, 'target/docs-classpath.txt'), 'utf8')).trim()].join(path.delimiter);
const java = spawn('java', ['-cp', classpath, 'org.frifra.vibedock.server.DocsDemo', '--server.address=127.0.0.1', `--server.port=${api}`,
  `--vibedock.admin-port=${admin}`, '--vibedock.admin-address=127.0.0.1', '--vibedock.admin-user=admin', `--vibedock.admin-password=${password}`,
  `--vibedock.data-dir=${dir}`, `--vibedock.log-dir=${path.join(dir, 'logs')}`, `--vibedock.firmware-dir=${firmware}`,
  `--vibedock.emoji-dir=${process.env.EMOJI_DIR}`, `--vibedock.manual-file=${path.join(dir, 'manual.pdf')}`, '--vibedock.certificate-log=http://127.0.0.1:9', `--vibedock.license-inventory=${path.join(dir, 'none.json')}`],
  {cwd: server, stdio: ['ignore', 'pipe', 'pipe']});
let log = ''; java.stdout.on('data', c => { log += c; }); java.stderr.on('data', c => { log += c; });
const B = `http://127.0.0.1:${admin}`, API = `http://127.0.0.1:${api}`;
let browser;
try {
  for (let i = 0; ; i++) {
    try { if ((await fetch(B + '/login')).status === 200) break; } catch { /* starting */ }
    if (java.exitCode !== null || i > 120) throw new Error('Server did not start:\n' + log);
    await new Promise(r => setTimeout(r, 500));
  }
  // Devices report their state with a pull, as real ones do.
  const pull = (identifier, headers) => fetch(`${API}/api/v1/clients/${identifier}/image`, {headers: {Authorization: 'Bearer ' + key, 'X-VibeDock-Footer': 'server', ...headers}});
  const m1 = {'X-VibeDock-Ota': '1', 'X-VibeDock-Board': 'vibedock-m1', 'X-VibeDock-Display': '240x240'};
  if (!own) {
    await pull('vibedock-0a1b2c000001', {...m1, 'X-VibeDock-Device-IP': '192.168.1.21', 'X-VibeDock-Firmware': version});
    await pull('vibedock-0a1b2c000002', {...m1, 'X-VibeDock-Device-IP': '192.168.1.22', 'X-VibeDock-Firmware': '0.0.9'});  // older than any release: offered an update
    await pull('vibedock-0a1b2c000003', {...m1, 'X-VibeDock-Device-IP': '192.168.1.23', 'X-VibeDock-Firmware': version});
  }

  if (serve) {
    java.stdout.pipe(process.stdout); java.stderr.pipe(process.stderr);
    console.log(`Demo server: ${B}/admin (admin / ${password}), devices on ${API}. Data in ${dir}${own ? '' : ', removed on exit'}. Ctrl+C stops it.`);
    await new Promise(resolve => { process.once('SIGINT', resolve); process.once('SIGTERM', resolve); java.once('exit', resolve); });
  } else {
    browser = await chromium.launch(process.env.PLAYWRIGHT_CHROMIUM ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM} : {});
    // Dates and times the pages format in the browser: the same zone and language on every machine (also in Docker).
    const context = await browser.newContext({viewport: {width: 1280, height: 900}, deviceScaleFactor: 2, reducedMotion: 'reduce', colorScheme: 'light',
      timezoneId: 'Europe/Berlin', locale: 'en-US'});
    // No request leaves the demo server (fonts, provider sign-in, weather).
    await context.route('**/*', route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort());
    // example.com has no live TLS endpoint. Show a successful, made-up inspection in the manual,
    // just like its demo usage and weather; never probe a real certificate service for screenshots.
    await context.route('**/admin/tls-pin', route => route.fulfill({json: {
      known: true, fingerprint: '0123456789abcdef0123456789abcdef01234567',
      message: 'TLS pin available for https://vibedock.example.com',
      route: 'configured-probe', target: '192.168.1.20:10002', cached: false,
      certificate: {subject: 'CN=vibedock.example.com', issuer: 'CN=Example Certificate Authority',
        notBefore: '2026-10-01T10:00:00Z', notAfter: '2026-12-30T10:00:00Z',
        sha256: '0123456789abcdef'.repeat(4), protocol: 'TLSv1.3'}
    }}));
    const page = await context.newPage();
    await rm(out, {recursive: true, force: true}); await mkdir(out, {recursive: true});
    const shot = async (name, target = page, options = {}) => { await page.waitForTimeout(250); await target.screenshot({path: path.join(out, name + '.png'), animations: 'disabled', ...options}); console.log('  ' + name); };
    // The sticky app bar would cover the top of element shots further down the page; the footer's version is left out,
    // so the pictures do not show one release's number.
    const go = async url => { await page.goto(B + url); await page.waitForLoadState('networkidle'); await page.evaluate(() => document.fonts.ready);
      await page.addStyleTag({content: '.appbar{position:static!important}.app-footer-inner>span>span{display:none}'});
      if (await page.locator('[data-tls-pin-status]').count()) {
        await expect(page.locator('[data-tls-pin-value]').first()).toHaveText('0123456789abcdef0123456789abcdef01234567');
        await expect(page.locator('[data-tls-pin-refresh]').first()).toBeEnabled();
        // The status line shows the browser's clock and the request's duration: fixed, like DocsDemo.NOW (07:41 UTC).
        await page.locator('[data-tls-pin-action]').evaluateAll(lines => lines.forEach(line => { line.textContent = 'Last refresh: 9:41:00 AM · Pin available · 18 ms'; }));
      }
    };
    const main = () => page.locator('main');

    // Sign-in
    await go('/login'); await shot('01-login');
    await page.getByLabel('Username', {exact: true}).fill('admin'); await page.getByLabel('Password', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Sign in', exact: true}).click(); await page.waitForLoadState('networkidle');

    // Overview
    await go('/admin'); await shot('02-overview', page, {fullPage: true});
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; }); await shot('03-overview-dark', page, {fullPage: true});
    await page.evaluate(() => { delete document.documentElement.dataset.theme; });

    // Devices
    await go('/admin/devices'); await shot('10-devices', page, {fullPage: true});
    await shot('11-device-card', page.locator('#device-vibedock-0a1b2c000002'));
    await shot('12-web-display-card', page.locator('#device-web-0123456789ab'));
    await page.locator('[data-create-open="invite"]').click(); await shot('13-pair-device', page.locator('#invite'));
    await page.keyboard.press('Escape');
    await page.locator('[data-create-open="web"]').click(); await page.locator('#web-name').fill('Kitchen tablet');
    await shot('14-add-web-display', page.locator('#web')); await page.keyboard.press('Escape');
    await page.locator('#device-vibedock-0a1b2c000001 .device').click(); await page.waitForTimeout(400); await shot('15-enlarged-preview');
    await page.keyboard.press('Escape');

    // Firmware
    await go('/admin/firmware');
    await shot('21-firmware-card', page.locator('.firmware-card'));
    await shot('22-firmware-flasher', page.locator('#web-flasher'));
    await shot('23-firmware-esptool', page.locator('main > section.card').last());
    await go('/admin/firmware?board=esp32-cyd'); await shot('24-firmware-esp32', page.locator('.firmware-card'));
    // The flasher after an installation over USB, while the new display is paired over the same cable. A browser without
    // a display attached cannot get there, so the page is set to that state as firmware-flasher.js leaves it.
    await go('/admin/firmware');
    await page.evaluate(([v, size]) => {
      const card = document.querySelector('#web-flasher'), $ = s => card.querySelector(s);
      $('[data-flasher-unsupported]').hidden = true;
      $('[data-flash-device]').textContent = 'Not connected.';
      $('[data-flash="connect"]').disabled = false; $('[data-flash-confirm]').checked = true;
      Object.assign($('[data-flash-progress]'), {hidden: false, max: 100, value: 100});
      Object.assign($('[data-flash-log]'), {hidden: false, style: 'max-height:none', textContent: [
        'Connecting....', 'Chip is ESP8266EX', 'MAC: 0a:1b:2c:00:00:02', 'Stub running...',
        'Reading 4194304 bytes of flash …', 'Backup saved as vibedock-backup-0a1b2c000002-2026-10-14.bin. Keep it private.',
        `Writing firmware ${v} (${size} bytes) at 0x0 …`, 'Compressed 563664 bytes to 397212...', 'Wrote 563664 bytes (397212 compressed) at 0x00000000 in 37.9 seconds.',
        'Hash of data verified.', 'Done. Restarting the device …',
        `Firmware ${v} installed. The device shows the version while it starts.`, ''].join('\n')});
      const pair = $('[data-pair]'), form = pair.querySelector('[data-pair-form]');
      pair.hidden = false; pair.querySelector('[data-pair-ask]').hidden = true; form.hidden = false;
      form.elements.name.value = 'Desk display'; form.elements.ssid.value = 'Example-WiFi'; form.elements.password.value = 'not-a-real-password';
      pair.querySelector('[data-net-value]').textContent = 'Example-WiFi';
      pair.querySelector('[data-pair-state]').textContent = 'vibedock-0a1b2c000002 · firmware ' + v + ' · 7 networks found';
    }, [version, 563664]);
    await shot('25-firmware-installed', page.locator('#web-flasher'));

    // Display editor
    await go('/admin/design'); await page.waitForTimeout(800); await shot('30-display-default', page, {fullPage: true});
    await go('/admin/design?device=vibedock-0a1b2c000002'); await page.waitForTimeout(800); await shot('31-display-device', page, {fullPage: true});
    await page.locator('.design-band-add').click(); await page.waitForTimeout(800); await shot('32-display-hidden-band', page.locator('#design-bands'));
    await go('/admin/design?device=web-0123456789ab'); await page.waitForTimeout(800);
    await shot('33-display-type', page, {fullPage: true});
    await page.locator('#design-mood-button').click(); await page.waitForTimeout(400); await shot('34-emoji-picker');
    await page.keyboard.press('Escape');

    // Data sources
    await go('/admin/sources'); await shot('40-sources', page, {fullPage: true});
    await page.locator(`[data-dialog-open="manage-${id(1)}"]`).click(); await page.waitForTimeout(300);
    await shot('41-source-manage', page.locator(`#manage-${id(1)}`)); await page.keyboard.press('Escape');
    await page.locator('[data-create-open="add-source"]').click(); await shot('42-add-source', page.locator('#add-source')); await page.keyboard.press('Escape');
    await page.locator('[data-create-open="export-sources"]').click(); await shot('43-export-sources', page.locator('#export-sources')); await page.keyboard.press('Escape');
    await page.locator('[data-create-open="import-sources"]').click(); await shot('44-import-sources', page.locator('#import-sources')); await page.keyboard.press('Escape');

    // Settings
    await go('/admin/server'); await page.locator('details').evaluateAll(d => d.forEach(x => { x.open = true; }));
    // The demo runs in a fresh temporary directory; show the Docker image's paths instead, so the picture stays the same.
    await page.locator('code').evaluateAll((codes, temp) => codes.forEach(c => { c.textContent = c.textContent.replace(temp, '/data'); }), dir);
    // One picture per block, next to its explanation in the manual.
    const cards = page.locator('main section.card');
    await shot('50-settings-network', page.locator('#addresses'));
    await shot('50-settings-api', cards.nth(1));
    await shot('50-settings-legacy', cards.nth(2));
    await shot('50-settings-sign-in', page.locator('#sign-in'));
  await shot('50-settings-storage', page.locator('#storage'));

    // Web display page as a browser shows it; last, because its pull records this browser's address
    const tablet = await context.newPage(); await tablet.setViewportSize({width: 900, height: 600});
    await tablet.goto(`${API}/web/${webSecret}`); await tablet.waitForTimeout(800);
    await tablet.screenshot({path: path.join(out, '16-web-display-page.png')}); console.log('  16-web-display-page'); await tablet.close();

    // Display images of the demo devices, as the devices show them
    for (const [name, identifier] of [['60-image-living-room', 'vibedock-0a1b2c000001'], ['61-image-home-office', 'vibedock-0a1b2c000002'],
      ['62-image-test-bench', 'vibedock-0a1b2c000003'], ['63-image-kitchen-320x240', 'web-0123456789ab']]) {
      const image = await page.request.get(`${B}/admin/clients/${identifier}/preview.png`);
      await writeFile(path.join(out, name + '.png'), await image.body()); console.log('  ' + name);
    }
    // Every board in its case with a real image in its screen size, one screen pixel per CSS pixel: the test bench
    // reports one board after the other, and the devices page's own markup and styles show them side by side.
    const shown = [];
    for (const [board, display, name, tested] of [
      ['vibedock-m1', 'vibedock-m1', 'VibeTV M1', true], ['waveshare-s3-touch-lcd-154', 'vibedock-m1', 'Waveshare ESP32-S3-Touch-LCD-1.54', true],
      ['waveshare-s3-touch-lcd-147', '172x320', 'Waveshare ESP32-S3-Touch-LCD-1.47', true], ['waveshare-c6-touch-lcd-169', '240x280', 'Waveshare ESP32-C6-Touch-LCD-1.69', true],
      ['esp32-cyd', '320x240', 'Cheap Yellow Display', false], ['lilygo-t-display', '135x240', 'LilyGO T-Display', false],
      ['lilygo-t-display-s3', '170x320', 'LilyGO T-Display-S3', false], ['waveshare-s3-lcd-169', '240x280', 'Waveshare ESP32-S3-LCD-1.69', false],
      ['esp32-c3-st7789', 'vibedock-m1', 'ESP32-C3 with ST7789', false]]) {
      const bench = 'vibedock-0a1b2c000003';
      await go('/admin/devices');
      const saved = await page.evaluate(async ([bench, display]) => {
        const meta = n => document.querySelector(`meta[name="csrf-${n}"]`).content;
        const response = await fetch(`/admin/designs/${bench}`, {method: 'POST', headers: {'Content-Type': 'application/json', [meta('header')]: meta('token')},
          body: JSON.stringify({mood: '😎', scheme: 'midnight', weatherLocation: 'London, GB', inherit: false, display,
            layout: {bands: [{type: 'usage', sources: ['00000000-0000-4000-8000-000000000001']}, {type: 'usage', sources: ['00000000-0000-4000-8000-000000000002']}, {type: 'context', moodLeft: false}]}})});
        return response.status;
      }, [bench, display]);
      if (saved !== 200) throw new Error(`Display ${display} not saved: ${saved}`);
      await pull(bench, {'X-VibeDock-Ota': '1', 'X-VibeDock-Board': board, 'X-VibeDock-Device-IP': '192.168.1.23', 'X-VibeDock-Firmware': version});
      await go('/admin/devices');
      const image = await page.request.get(`${B}/admin/clients/${bench}/preview.png`);
      const [width] = display === 'vibedock-m1' ? [240] : display.split('x').map(Number);
      const markup = await page.locator(`#device-${bench} .device`).evaluate((device, [src, width]) => {
        const copy = device.cloneNode(true); copy.style.setProperty('--s', width + 'px');
        copy.querySelectorAll('[data-live],[data-client-preview]').forEach(e => { e.removeAttribute('data-live'); e.removeAttribute('data-client-preview'); });
        const img = copy.querySelector('img'); img.src = src; img.removeAttribute('width'); img.removeAttribute('height'); return copy.outerHTML;
      }, ['data:image/png;base64,' + (await image.body()).toString('base64'), width]);
      shown.push({markup, name, display: display === 'vibedock-m1' ? '240×240' : display.replace('x', '×'), tested});
    }
    await go('/admin/devices');
    await page.locator('main').evaluate((main, shown) => {
      main.innerHTML = '<div class="board-renderings" style="display:flex;flex-wrap:wrap;gap:40px 36px;align-items:flex-end;justify-content:center;padding:36px 28px">'
        + shown.map(b => `<figure style="margin:0;text-align:center;font-size:13px;line-height:1.4">${b.markup}<figcaption style="margin-top:18px"><strong>${b.name}</strong><br>${b.display} · `
          + (b.tested ? 'tested' : '<span style="color:#b45309;font-weight:600">prepared, untested</span>') + '</figcaption></figure>').join('') + '</div>';
    }, shown);
    await page.waitForFunction(() => [...document.querySelectorAll('.board-renderings img')].every(i => i.complete));
    await shot('26-boards', page.locator('.board-renderings'));
    // The logo for the title page and the page header of the PDF (docs/logo.svg, transparent background)
    const logo = await context.newPage(); await logo.setViewportSize({width: 256, height: 256});
    const svg = await readFile(path.join(server, '../docs/logo.svg'), 'utf8');
    await logo.setContent(`<!doctype html><style>html,body{margin:0;background:transparent}img{width:256px;height:256px;display:block}</style>
      <img src="data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}">`);
    await logo.locator('img').screenshot({path: path.join(out, 'logo.png'), omitBackground: true}); console.log('  logo'); await logo.close();
    // The device's own screens while it starts, is set up, paired and updated
    await deviceScreens(context, out, '70-screen', version);
    console.log(`Screenshots in ${out}`);
  }
} finally {
  await browser?.close();
  java.kill('SIGTERM'); await Promise.race([once(java, 'exit'), new Promise(r => setTimeout(r, 4000))]);
  if (java.exitCode === null) java.kill('SIGKILL');
  if (!own) await rm(dir, {recursive: true, force: true});
}
