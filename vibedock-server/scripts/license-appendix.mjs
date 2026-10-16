// The third-party licenses as a Markdown section for the manual: the Maven report of the JAR and, when given, the
// inventory of the Docker image (scripts/license-inventory.mjs), grouped by license. Only names, versions and
// licenses; the full texts stay in the administration (Licenses & notices).
//   node scripts/license-appendix.mjs <MAVEN-THIRD-PARTY.txt> [components.json] > licenses.md
import fs from 'node:fs';
const [report, inventory] = process.argv.slice(2);
const groups = new Map();
const add = (license, component) => {
  const key = license.trim() || 'Not declared';
  if (!groups.has(key)) groups.set(key, new Set());
  groups.get(key).add(component);
};
let java = 0, image = 0;
if (report && fs.existsSync(report)) for (const line of fs.readFileSync(report, 'utf8').split('\n')) {
  // "     (Apache-2.0) Name (group:artifact:version - url)"; several licenses come as "(A) (B) Name (…)".
  const m = line.match(/^\s*((?:\([^)]*\)\s*)+)(.+?) \(([^:()]+):([^:()]+):([^ ()]+)/);
  if (!m) continue;
  const licenses = [...m[1].matchAll(/\(([^)]*)\)/g)].map(x => x[1]).join(' / ');
  add(licenses, `${m[2].trim()} ${m[5]}`); java++;
}
if (inventory && fs.existsSync(inventory)) {
  let runtime = null;
  for (const e of JSON.parse(fs.readFileSync(inventory, 'utf8'))) {
    // The Java runtime's legal folder has one notice per module and file: one entry for the runtime here.
    if (e.name.startsWith('Java runtime · ')) { runtime ??= e.version; continue; }
    add(e.license, `${e.name}${e.version ? ' ' + e.version : ''}`); image++;
  }
  if (runtime !== null) { add('GPL-2.0 with Classpath Exception and bundled notices', `Java runtime (Eclipse Temurin)${runtime ? ' ' + runtime : ''}`); image++; }
}
if (!java && !image) process.exit(0);
const md = s => s.replace(/([\\`*_{}\[\]<>#|])/g, '\\$1');
// Small type (8 pt) and loose line breaking: long package names must not run out of the page.
const out = ['', '## Third-party components of this build', '', '```{=latex}', '\\begingroup\\footnotesize\\sloppy', '```', '',
  `Generated during the build: ${java} Java libraries${image ? ` and ${image} components installed in the Docker image (Node.js tools, system packages, Java runtime, Twemoji)` : ''}, grouped by the license they declare. The license texts are under **Licenses & notices** in the administration.`, ''];
for (const [license, components] of [...groups].sort((a, b) => b[1].size - a[1].size || a[0].localeCompare(b[0]))) {
  out.push(`**${md(license)}** (${components.size}): ${[...components].sort((a, b) => a.localeCompare(b)).map(md).join(', ')}`, '');
}
out.push('```{=latex}', '\\endgroup', '```', '');
process.stdout.write(out.join('\n'));
