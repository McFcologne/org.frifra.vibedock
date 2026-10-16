// Build-time inventory of the files actually installed in the final Docker image.
import fs from 'node:fs';
import path from 'node:path';
const root = process.argv[2] ?? '/';
const at = file => path.join(root, file);

const entries = [];
const visited = new Set();
const read = file => fs.readFileSync(file, 'utf8');
const names = dir => fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
function packageNotices(dir) {
  const real = fs.realpathSync(dir);
  if (visited.has(real)) return;
  visited.add(real);
  const file = path.join(dir, 'package.json');
  if (fs.existsSync(file)) {
    const pkg = JSON.parse(read(file));
    const files = names(dir).filter(name => /^(licen[cs]e|copying|notice|copyright)([.-]|$)/i.test(name) && fs.statSync(path.join(dir, name)).isFile());
    const texts = files.map(name => `${name}\n\n${read(path.join(dir, name))}`);
    const declared = pkg.license ?? pkg.licenses ?? 'Not declared in package metadata';
    entries.push({name: pkg.name ?? path.basename(dir), version: pkg.version ?? '', license: typeof declared === 'string' ? declared : JSON.stringify(declared), text: texts.join('\n\n') || 'No license or notice file was bundled with this package. Consult the publisher’s distribution terms.'});
  }
  modules(path.join(dir, 'node_modules'));
}
function modules(dir) {
  for (const name of names(dir)) {
    if (name.startsWith('.')) continue;
    const target = path.join(dir, name);
    if (!fs.statSync(target).isDirectory()) continue;
    if (name.startsWith('@')) { for (const child of names(target)) packageNotices(path.join(target, child)); }
    else packageNotices(target);
  }
}
modules(at('/usr/local/lib/node_modules'));
entries.push({name:'Node.js',version:process.version,license:'Distribution license & third-party notices',text:`Node.js ${process.version}\nUpstream distribution notices: https://github.com/nodejs/node/blob/${process.version}/LICENSE`});
const artwork = at('/app/twemoji/LICENSE-GRAPHICS');
if (fs.existsSync(artwork)) entries.push({name:'Twemoji artwork',version:process.env.TWEMOJI_VERSION ?? '',license:'See bundled LICENSE-GRAPHICS',text:read(artwork)});
// Debian/Ubuntu copyright files include the installed fonts and their upstream notices.
const statusFile = at('/var/lib/dpkg/status');
if (fs.existsSync(statusFile)) for (const stanza of read(statusFile).split(/\n\s*\n/)) {
  if (!/^Status: install ok installed$/m.test(stanza)) continue;
  const name = stanza.match(/^Package: (.+)$/m)?.[1];
  const version = stanza.match(/^Version: (.+)$/m)?.[1] ?? '';
  if (!name) continue;
  const file = path.join(at('/usr/share/doc'),name,'copyright');
  const available = fs.existsSync(file) && fs.statSync(file).isFile();
  entries.push({name:`System · ${name}`,version,license:available?'See installed copyright & license notices':'No copyright file in installed package',text:available?read(file):'No copyright file was bundled with this installed package. Consult the package distributor.'});
}
function legalFiles(dir) {
  for (const name of names(dir)) {
    const file = path.join(dir,name);
    if (fs.statSync(file).isDirectory()) legalFiles(file);
    else entries.push({name:`Java runtime · ${path.relative(at('/opt/java/openjdk/legal'),file)}`,version:process.env.JAVA_VERSION ?? '',license:'Bundled runtime notice',text:read(file)});
  }
}
legalFiles(at('/opt/java/openjdk/legal'));
entries.sort((a,b)=>a.name.localeCompare(b.name)||a.version.localeCompare(b.version));
fs.mkdirSync(at('/app/legal'),{recursive:true});
fs.writeFileSync(at('/app/legal/components.json'),JSON.stringify(entries));
console.log(`Generated notices for ${entries.length} installed components.`);
