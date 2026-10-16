import {defineConfig} from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  workers: 2,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 45_000,
  expect: {timeout: 8_000},
  reporter: [['list'], ['html', {open: 'never'}]],
  use: {browserName: 'chromium', viewport: {width: 1024, height: 850}, trace: 'retain-on-failure', screenshot: 'only-on-failure'}
});
