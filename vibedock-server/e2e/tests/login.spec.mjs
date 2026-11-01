import {test,expect,editor} from './fixtures.mjs';

test('persistent login restores an expired session and sign out revokes a copied cookie',async ({page,app},testInfo)=>{
  const context=page.context();
  const cookie=(await context.cookies()).find(c=>c.name==='vibedock-login');
  expect(cookie).toBeTruthy();expect(cookie.httpOnly).toBe(true);expect(cookie.sameSite).toBe('Lax');
  await context.clearCookies({name:'JSESSIONID'});
  await page.goto(app.baseURL+'/admin');await expect(page).toHaveURL(app.baseURL+'/admin');
  await page.goto(app.baseURL+'/logout');await expect(page).toHaveURL(app.baseURL+'/login?logout');
  await expect(page.getByLabel('Stay signed in',{exact:true})).toBeChecked();
  await page.screenshot({path:testInfo.outputPath('login.png'),fullPage:true});
  await context.addCookies([cookie]);await page.goto(app.baseURL+'/admin');
  await expect(page).toHaveURL(app.baseURL+'/login');
});


test('an open editor saves its draft after session loss without reloading',async ({page,app})=>{
  await editor(page,app);
  await expect(page.locator('#design-scheme')).toHaveValue('midnight');
  await page.context().clearCookies({name:'JSESSIONID'});
  const saved=page.waitForResponse(r=>r.url().endsWith('/admin/designs/default')&&r.request().method()==='POST');
  await page.locator('#design-scheme').selectOption('ocean');
  expect((await saved).status()).toBe(200);
  await expect(page.locator('#design-message')).toContainText('Default design saved');
  await page.reload();await expect(page.locator('#design-scheme')).toHaveValue('ocean');
});

test('a native form preserves entered values and its submit action after session loss',async ({page,app})=>{
  await page.goto(app.baseURL+'/admin/sources');
  await page.locator('[data-create-open="add-source"]').click();
  const panel=page.locator('#add-source');await panel.getByLabel('Name on the display').fill('Recovered form');
  await page.context().clearCookies({name:'JSESSIONID'});
  await panel.getByRole('button',{name:'Add data source',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Recovered form',exact:false})).toBeVisible();
  await page.reload();await expect(page.getByRole('heading',{name:'Recovered form',exact:false})).toBeVisible();
});

test('an expired persistent login keeps the form draft and asks for sign in',async ({page,app})=>{
  await page.goto(app.baseURL+'/admin/sources');await page.locator('[data-create-open="add-source"]').click();
  const panel=page.locator('#add-source'),name=panel.getByLabel('Name on the display');await name.fill('Unsaved draft');
  await page.request.get(app.baseURL+'/logout');
  let posts=0;page.on('request',request=>{if(request.method()==='POST')posts++;});
  await panel.getByRole('button',{name:'Add data source',exact:true}).click();
  await expect(page.locator('#session-recovery-error')).toContainText('Your session has expired');
  await expect(name).toHaveValue('Unsaved draft');expect(posts).toBe(0);
});

test('only CSRF rejections are retried and at most once',async ({page,app})=>{
  await editor(page,app);let writes=0;
  await page.route('**/admin/designs/default',async route=>{
    if(route.request().method()!=='POST')return route.continue();
    writes++;return route.fulfill({status:403,headers:{'X-VibeDock-CSRF':'invalid'},body:''});
  });
  await page.locator('#design-scheme').selectOption('ocean');
  await expect(page.locator('#design-error')).toBeVisible();expect(writes).toBe(2);
  await page.unroute('**/admin/designs/default');writes=0;
  await page.route('**/admin/designs/default',route=>{
    if(route.request().method()!=='POST')return route.continue();
    writes++;return route.fulfill({status:403,body:'Permission denied'});
  });
  await page.locator('#design-scheme').selectOption('forest');
  await expect.poll(()=>writes).toBe(1);
  await expect(page.locator('#design-error')).toBeVisible();expect(writes).toBe(1);
});


test('leaving the editor right after an edit through the navigation saves it first',async ({page,app})=>{
  await editor(page,app);await page.locator('#design-scheme').selectOption('ocean');
  // A link of the administration waits for the save; the next page then already has the edit.
  await page.getByRole('navigation',{name:'Administration'}).getByRole('link',{name:'Data sources'}).click();
  await expect(page).toHaveURL(app.baseURL+'/admin/sources');
  expect((await (await page.request.get(app.baseURL+'/admin/designs')).json()).defaults.scheme).toBe('ocean');
  await editor(page,app);await expect(page.locator('#design-scheme')).toHaveValue('ocean');
});
