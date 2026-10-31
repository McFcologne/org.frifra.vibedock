import {test,expect,editor} from './fixtures.mjs';
test('live color comparison exposes compact palette counts, all resolutions and lossless web output',async({page,app})=>{
  await editor(page,app);await page.goto(app.baseURL+'/admin/color-preview?source=reference0');
  const cards=page.locator('#color-preview-grid article'),status=page.locator('#color-preview-status');
  await expect(cards).toHaveCount(45);await expect(status).toContainText('45 themes');
  const lcd=page.locator('[data-theme="handheld"]');await expect(lcd).toContainText('4 colors · 2 bit');await expect(lcd).toContainText('14,410');
  await expect(page.locator('#color-fallback')).toBeChecked();await expect(page.locator('input[type=number]')).toHaveCount(0);
  await page.getByRole('button',{name:'64 colors',exact:true}).click();await expect(status).toContainText('45 themes');await expect(lcd).toContainText('4 colors · 2 bit');
  await page.locator('#color-size').selectOption('native');await expect(lcd.locator('img')).toHaveCSS('width','240px');
  await page.locator('#color-display').selectOption('172x320');await expect(status).toContainText('172×320');await expect(lcd.locator('img')).toHaveAttribute('width','172');await expect(lcd.locator('img')).toHaveCSS('width','172px');
  await page.locator('#color-display').selectOption('web');await expect(status).toContainText('480×480');
  await expect(page.getByRole('button',{name:'32 colors',exact:true})).toBeDisabled();await expect(lcd).toContainText('0.00% pixels changed');
  await page.locator('#color-display').selectOption('vibedock-m1');await expect(status).toContainText('240×240');
  await page.locator('#color-size').selectOption('fixed');await page.setViewportSize({width:1440,height:1100});
  await page.screenshot({path:'test-results/live-color-comparison.png'});await page.evaluate(()=>{const r=document.querySelector('[data-theme=handheld]').getBoundingClientRect();scrollTo(0,scrollY+r.top-450)});await lcd.screenshot({path:'test-results/live-color-lcd.png'});
});
