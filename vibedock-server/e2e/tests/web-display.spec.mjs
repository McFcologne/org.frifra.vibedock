import {test, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

// Exercise the shipped page and real image loads without provider accounts or physical displays.
test('web display samples every image reload and keeps its background when offline', async ({page}) => {
  const template = await readFile(new URL('../../src/main/resources/templates/web-display.html', import.meta.url), 'utf8');
  const html = template
    .replace('th:style="${\'--display-background:\' + background}"', 'style="--display-background:#152035"')
    .replace('content="#000000" th:content="${background}"', 'content="#152035"')
    .replace(/<body[^>]*>/, '<body data-image="/image.png" data-state="/state" data-swipe="/swipe" data-reload="1">');
  let colour = '#203040', offline = false, releaseImage;
  const firstImage = new Promise(resolve => { releaseImage = resolve; });
  await page.route('http://display.test/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/') return route.fulfill({contentType: 'text/html', body: html});
    // The tag stays unchanged: the repeated colour update must come from timed image loads, not a state change.
    if (path === '/state') return route.fulfill({json: {tag: 'same-tag'}});
    if (path === '/swipe') { colour = '#f3e8d2'; return route.fulfill({json: {}}); }
    if (path === '/image.png') {
      await firstImage;
      if (offline) return route.abort();
      return route.fulfill({contentType: 'image/svg+xml', body: `<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><rect width="4" height="4" fill="${colour}"/></svg>`});
    }
    return route.abort();
  });
  await page.goto('http://display.test/', {waitUntil: 'domcontentloaded'});
  const theme = page.locator('meta[name="theme-color"]');
  // Explicit fallback is visible even while the image is still loading.
  await expect(theme).toHaveAttribute('content', '#152035');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(21, 32, 53)');
  // Native iOS chrome is outside Playwright; verify its transparent-background opt-in separately.
  await expect(page.locator('meta[name="apple-mobile-web-app-status-bar-style"]')).toHaveAttribute('content', 'black-translucent');
  releaseImage();
  await expect(theme).toHaveAttribute('content', 'rgb(32,48,64)');
  colour = '#304050';
  await expect(theme).toHaveAttribute('content', 'rgb(48,64,80)');
  await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(48, 64, 80)');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(48, 64, 80)');
  await page.keyboard.press('ArrowLeft');
  await expect(theme).toHaveAttribute('content', 'rgb(243,232,210)');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(243, 232, 210)');
  offline = true;
  await expect(page.locator('.stale')).toBeVisible();
  await expect(theme).toHaveAttribute('content', 'rgb(243,232,210)');
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(243, 232, 210)');
});

test('a turned or resized screen shows the last image stretched and blurred until the new one has loaded', async ({page}) => {
  const template = await readFile(new URL('../../src/main/resources/templates/web-display.html', import.meta.url), 'utf8');
  const html = template.replace(/<body[^>]*>/, '<body data-image="/image.png" data-state="/state" data-swipe="/swipe" data-reload="600">');
  let hold = null;
  await page.route('http://display.test/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/') return route.fulfill({contentType: 'text/html', body: html});
    if (path === '/state') return route.fulfill({json: {tag: 'same-tag'}});
    if (path === '/image.png') {
      if (hold) await hold;
      return route.fulfill({contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><rect width="4" height="4" fill="#203040"/></svg>'});
    }
    return route.abort();
  });
  await page.setViewportSize({width: 390, height: 844});
  await page.goto('http://display.test/');
  const screen = page.locator('.screen');
  await expect(screen).toHaveCSS('background-image', /image\.png/);
  await expect(screen).not.toHaveClass(/reshaping/);
  let release; hold = new Promise(resolve => { release = resolve; });
  await page.setViewportSize({width: 844, height: 390});  // the phone turned
  await expect(screen).toHaveClass(/reshaping/);
  await expect(screen).toHaveCSS('background-size', '100% 100%');
  await expect(screen).toHaveCSS('filter', /blur/);
  release();
  await expect(screen).not.toHaveClass(/reshaping/);
  await expect(screen).toHaveCSS('background-size', 'contain');
});

test('an answer to a request from before the turn does not end the stretched, blurred image', async ({page}) => {
  const template = await readFile(new URL('../../src/main/resources/templates/web-display.html', import.meta.url), 'utf8');
  const html = template.replace(/<body[^>]*>/, '<body data-image="/image.png" data-state="/state" data-swipe="/swipe" data-reload="600">');
  const held = [];  // requests waiting for their answer: {width, release}
  let holding = false;
  await page.route('http://display.test/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname === '/') return route.fulfill({contentType: 'text/html', body: html});
    if (url.pathname === '/state') return route.fulfill({json: {tag: 'same-tag'}});
    if (url.pathname === '/swipe') return route.fulfill({json: {}});
    if (url.pathname === '/image.png') {
      if (holding) await new Promise(resolve => held.push({width: Number(url.searchParams.get('w')), release: resolve}));
      return route.fulfill({contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4"><rect width="4" height="4" fill="#203040"/></svg>'});
    }
    return route.abort();
  });
  await page.setViewportSize({width: 390, height: 844});
  await page.goto('http://display.test/');
  const screen = page.locator('.screen');
  await expect(screen).toHaveCSS('background-image', /image\.png/);
  holding = true;
  await page.keyboard.press('ArrowLeft');  // a swipe: a portrait request is under way ...
  await expect.poll(() => held.length).toBe(1);
  await page.setViewportSize({width: 844, height: 390});  // ... when the phone turns
  await expect(screen).toHaveClass(/reshaping/);
  await expect.poll(() => held.length).toBe(2);
  expect(held.map(h => h.width)).toEqual([390, 844]);
  held[0].release();  // the portrait answer arrives first
  await page.waitForTimeout(300);
  await expect(screen).toHaveClass(/reshaping/);
  await expect(screen).toHaveCSS('filter', /blur/);
  held[1].release();  // then the landscape one
  await expect(screen).not.toHaveClass(/reshaping/);
  await expect(screen).toHaveCSS('background-image', /w=844/);
});
