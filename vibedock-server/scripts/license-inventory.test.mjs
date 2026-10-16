import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

test('inventory follows installed versions, nested dependencies, notices and missing declarations', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'vibedock-licenses-'));
  const write = (name,text) => { const file=path.join(root,name); fs.mkdirSync(path.dirname(file),{recursive:true}); fs.writeFileSync(file,text); };
  const run = () => {
    execFileSync(process.execPath,[new URL('./license-inventory.mjs',import.meta.url).pathname,root],{env:{...process.env,TWEMOJI_VERSION:'fixture-version'}});
    return JSON.parse(fs.readFileSync(path.join(root,'app/legal/components.json'),'utf8'));
  };
  try {
    write('usr/local/lib/node_modules/@example/client/package.json',JSON.stringify({name:'@example/client',version:'1.2.3',license:'MIT'}));
    write('usr/local/lib/node_modules/@example/client/LICENSE','Example license text');
    write('usr/local/lib/node_modules/@example/client/node_modules/transitive/package.json',JSON.stringify({name:'transitive',version:'4.5.6'}));
    write('var/lib/dpkg/status','Package: example-font\nStatus: install ok installed\nVersion: 2.0\n\nPackage: no-notice\nStatus: install ok installed\nVersion: 3.0\n');
    write('usr/share/doc/example-font/copyright','Font license text');
    write('app/twemoji/LICENSE-GRAPHICS','Artwork license text');
    write('opt/java/openjdk/legal/java.base/LICENSE','Runtime license text');
    let data=run();
    assert.equal(data.find(x=>x.name==='@example/client').text,'LICENSE\n\nExample license text');
    assert.equal(data.find(x=>x.name==='transitive').license,'Not declared in package metadata');
    assert.equal(data.find(x=>x.name==='Twemoji artwork').version,'fixture-version');
    assert.equal(data.find(x=>x.name==='System · example-font').text,'Font license text');
    assert.match(data.find(x=>x.name==='System · no-notice').license,/No copyright file/);
    assert.equal(data.find(x=>x.name==='Java runtime · java.base/LICENSE').text,'Runtime license text');
    write('usr/local/lib/node_modules/@example/client/package.json',JSON.stringify({name:'@example/client',version:'2.0.0',license:'Apache-2.0'}));
    data=run();
    assert.equal(data.find(x=>x.name==='@example/client').version,'2.0.0');
    assert.equal(data.find(x=>x.name==='@example/client').license,'Apache-2.0');
  } finally { fs.rmSync(root,{recursive:true,force:true}); }
});
