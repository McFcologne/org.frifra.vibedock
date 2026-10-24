import {test as base, expect} from '@playwright/test';
import {mkdtemp, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {once} from 'node:events';

const serverDir = fileURLToPath(new URL('../../', import.meta.url));
const jar = path.join(serverDir, 'target/vibedock-server.jar');
const password = 'local-browser-tests-only';
async function freePort() {
  const socket = createServer();
  socket.listen(0, '127.0.0.1');
  await once(socket, 'listening');
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  return port;
}

export const test = base.extend({
  app: async ({}, use, testInfo) => {
    const dir = await mkdtemp(path.join(tmpdir(), 'vibedock-browser-'));
    const port = await freePort();
    const design = {scheme:'midnight',mood:'',weather:null,layout:{bands:[{type:'usage',sources:['codex']},{type:'usage',sources:['claude']},{type:'context',moodLeft:false}]}};
    const device = {identifier:'vibedock-e2e-office',name:'Office',keyHash:'0'.repeat(64),ip:'127.0.0.1',port:9,pairedAt:1,lastPullAt:null,lastPullIp:null,design:null,serverFooter:true};
    await writeFile(path.join(dir,'clients.json'),JSON.stringify({version:3,server:{internalUrl:'http://127.0.0.1:9',externalUrl:'',pollSeconds:30},defaultDesign:design,devices:[device,{...device,identifier:'vibedock-e2e-shelf',name:'Shelf',board:'waveshare-s3-touch-lcd-147',display:'172x320'},
      {...device,identifier:'vibedock-e2e-desk',name:'Desk',board:'waveshare-s3-touch-lcd-154',display:'vibedock-m1'}]}));
    const processHandle = spawn('java', ['-jar',jar,'--server.address=127.0.0.1','--server.port=0',`--vibedock.admin-port=${port}`,'--vibedock.admin-address=127.0.0.1','--vibedock.admin-user=admin',`--vibedock.admin-password=${password}`,'--vibedock.polling=false',`--vibedock.data-dir=${dir}`,`--vibedock.log-dir=${path.join(dir,'logs')}`,`--vibedock.license-inventory=${path.join(dir,'no-container-inventory.json')}`],{stdio:['ignore','pipe','pipe']});
    let output = '', startupError;
    processHandle.stdout.on('data', chunk => {output += chunk;});
    processHandle.stderr.on('data', chunk => {output += chunk;});
    processHandle.on('error', error => {startupError=error;});
    const baseURL = `http://127.0.0.1:${port}`;
    try {
      await expect.poll(async () => {
        if (startupError) throw startupError;
        if (processHandle.exitCode !== null) throw new Error(`Test server exited: ${output}`);
        try {return (await fetch(baseURL+'/login')).status;} catch {return 0;}
      },{timeout:25_000,message:'Isolated server starts from the packaged JAR'}).toBe(200);
      await use({baseURL,password});
    } finally {
      const exited = once(processHandle,'exit').catch(()=>{});
      if (processHandle.exitCode===null) processHandle.kill('SIGTERM');
      await Promise.race([exited,new Promise(resolve=>setTimeout(resolve,4000))]);
      if (processHandle.exitCode===null) {processHandle.kill('SIGKILL');await exited;}
      if (testInfo.status!==testInfo.expectedStatus) await testInfo.attach('isolated-server.log',{body:output,contentType:'text/plain'});
      await rm(dir,{recursive:true,force:true});
    }
  },
  page: async ({page,app},use) => {
    // Browsers cannot contact provider sign-in, weather services or real devices in this suite.
    await page.context().route('**/*', route => {
      const url = new URL(route.request().url());
      return url.origin===app.baseURL||['data:','blob:'].includes(url.protocol) ? route.continue() : route.abort();
    });
    await page.goto(app.baseURL+'/login');
    await page.getByLabel('Username',{exact:true}).fill('admin');
    await page.getByLabel('Password',{exact:true}).fill(app.password);
    await page.getByRole('button',{name:'Sign in',exact:true}).click();
    await expect(page).toHaveURL(app.baseURL+'/admin');
    await use(page);
  }
});
export {expect};
export async function editor(page,app) {
  await page.goto(app.baseURL+'/admin/design');
  await expect(page.locator('#design-scheme')).toBeEnabled();
  await expect(page.locator('#preview-status')).toContainText('device renderer');
}
