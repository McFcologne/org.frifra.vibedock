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
