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
    execFileSync(process.execPath,[new URL('./license-inventory.mjs',import.meta.url).pathname,root]);
    return JSON.parse(fs.readFileSync(path.join(root,'app/legal/components.json'),'utf8'));
  };
  try {
    write('usr/local/lib/node_modules/@example/client/package.json',JSON.stringify({name:'@example/client',version:'1.2.3',license:'MIT'}));
    write('usr/local/lib/node_modules/@example/client/LICENSE','Example license text');
    write('usr/local/lib/node_modules/@example/client/node_modules/transitive/package.json',JSON.stringify({name:'transitive',version:'4.5.6'}));
    write('var/lib/dpkg/status','Package: example-font\nStatus: install ok installed\nVersion: 2.0\n\nPackage: no-notice\nStatus: install ok installed\nVersion: 3.0\n');
    write('usr/share/doc/example-font/copyright','Font license text');
    write('app/twemoji/LICENSE-GRAPHICS','Artwork license text');
    write('app/twemoji/README.md','Twemoji graphics by Twitter, Inc. and other contributors');
    write('app/twemoji/VERSION','fixture-version\n');
    write('opt/java/openjdk/legal/java.base/LICENSE','Runtime license text');
    let data=run();
    assert.equal(data.find(x=>x.name==='@example/client').text,'LICENSE\n\nExample license text');
    assert.equal(data.find(x=>x.name==='transitive').license,'Not declared in package metadata');
    const artwork=data.find(x=>x.name==='Twemoji artwork');
    assert.equal(artwork.version,'fixture-version');assert.equal(artwork.license,'CC-BY-4.0');
    assert.match(artwork.text,/^Twemoji graphics by Twitter, Inc\. and other contributors\n\nLICENSE-GRAPHICS\n\nArtwork license text$/);
    assert.equal(data.find(x=>x.name==='System · example-font').text,'Font license text');
    assert.match(data.find(x=>x.name==='System · no-notice').license,/No copyright file/);
    assert.equal(data.find(x=>x.name==='Java runtime · java.base/LICENSE').text,'Runtime license text');
    write('usr/local/lib/node_modules/@example/client/package.json',JSON.stringify({name:'@example/client',version:'2.0.0',license:'Apache-2.0'}));
    data=run();
    assert.equal(data.find(x=>x.name==='@example/client').version,'2.0.0');
    assert.equal(data.find(x=>x.name==='@example/client').license,'Apache-2.0');
  } finally { fs.rmSync(root,{recursive:true,force:true}); }
});

test('the Twemoji artwork of the Docker image is the repository\'s, with version, attribution and full license', () => {
  // The Dockerfile copies third-party/twemoji to /app/twemoji: the inventory of that copy.
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'vibedock-licenses-'));
  const bundled = new URL('../../third-party/twemoji/', import.meta.url).pathname;
  try {
    fs.mkdirSync(path.join(root,'app'),{recursive:true});
    for (const name of ['LICENSE-GRAPHICS','README.md','VERSION']) {
      fs.mkdirSync(path.join(root,'app/twemoji'),{recursive:true});
      fs.copyFileSync(path.join(bundled,name),path.join(root,'app/twemoji',name));
    }
    execFileSync(process.execPath,[new URL('./license-inventory.mjs',import.meta.url).pathname,root]);
    const artwork = JSON.parse(fs.readFileSync(path.join(root,'app/legal/components.json'),'utf8')).find(x=>x.name==='Twemoji artwork');
    assert.equal(artwork.version,fs.readFileSync(path.join(bundled,'VERSION'),'utf8').trim());
    assert.equal(artwork.license,'CC-BY-4.0');
    assert.match(artwork.text,/Twitter, Inc\. and other contributors/);
    assert.ok(artwork.text.endsWith(fs.readFileSync(path.join(bundled,'LICENSE-GRAPHICS'),'utf8')),'the full license text');
    assert.match(artwork.text,/Attribution 4\.0 International/);
  } finally { fs.rmSync(root,{recursive:true,force:true}); }
});
