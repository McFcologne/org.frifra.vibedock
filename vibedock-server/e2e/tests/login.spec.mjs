import {test,expect} from './fixtures.mjs';

test('persistent login restores an expired session and sign out revokes a copied cookie',async ({page,app},testInfo)=>{
  const context=page.context();
  const cookie=(await context.cookies()).find(c=>c.name==='vibedock-login');
  expect(cookie).toBeTruthy();expect(cookie.httpOnly).toBe(true);expect(cookie.sameSite).toBe('Strict');
  await context.clearCookies({name:'JSESSIONID'});
  await page.goto(app.baseURL+'/admin');await expect(page).toHaveURL(app.baseURL+'/admin');
  await page.goto(app.baseURL+'/logout');await expect(page).toHaveURL(app.baseURL+'/login?logout');
  await expect(page.getByLabel('Stay signed in',{exact:true})).toBeChecked();
  await page.screenshot({path:testInfo.outputPath('login.png'),fullPage:true});
  await context.addCookies([cookie]);await page.goto(app.baseURL+'/admin');
  await expect(page).toHaveURL(app.baseURL+'/login');
});
