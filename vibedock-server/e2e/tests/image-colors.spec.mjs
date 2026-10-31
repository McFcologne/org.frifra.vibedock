import {test,expect,editor} from './fixtures.mjs';
test('hardware color rules can be edited, reordered, removed and restored',async({page,app})=>{
  await editor(page,app);await page.goto(app.baseURL+'/admin/server#image-colors');
  const submit=async name=>Promise.all([page.waitForNavigation(),page.getByRole('button',{name,exact:true}).click()]);
  const section=page.locator('#image-colors'),rows=section.locator('tbody tr');
  await expect(rows).toHaveCount(2);await expect(rows.first().getByLabel('Colors',{exact:true})).toHaveValue('32');
  await expect(section.locator('[name=adaptive]')).toBeChecked();await expect(section.locator('[name=maxChangedPercent]')).toHaveCount(0);
  await section.locator('[name=adaptive]').uncheck();
  await section.getByRole('button',{name:'Add rule',exact:true}).click();await expect(rows).toHaveCount(3);
  await rows.nth(2).getByLabel('Maximum width').fill('128');await rows.nth(2).getByLabel('Maximum height').fill('160');
  await rows.nth(2).getByLabel('Colors',{exact:true}).selectOption('0');
  await rows.nth(2).getByRole('button',{name:'Move rule up'}).click();await rows.nth(1).getByRole('button',{name:'Move rule up'}).click();
  await submit('Save color rules');
  await expect(section.getByText('Saved. Compatible hardware displays use these rules on their next image pull.')).toBeVisible();
  await page.reload();await expect(section.locator('[name=adaptive]')).not.toBeChecked();await expect(rows.first().getByLabel('Maximum width')).toHaveValue('128');await expect(rows.first().getByLabel('Colors',{exact:true})).toHaveValue('0');
  await rows.first().getByRole('button',{name:'Remove rule'}).click();await submit('Save color rules');await expect(rows).toHaveCount(2);
  await submit('Restore defaults');await expect(section.locator('[name=adaptive]')).toBeChecked();await expect(section.locator('[name=maxChangedPercent]')).toHaveCount(0);await expect(rows.first().getByLabel('Colors',{exact:true})).toHaveValue('32');
  await page.setViewportSize({width:1280,height:1100});await section.scrollIntoViewIfNeeded();await page.screenshot({path:'test-results/hardware-color-rules.png'});
});
