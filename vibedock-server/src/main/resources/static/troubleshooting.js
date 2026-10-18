// Troubleshooting: reads a display's log over USB, whatever board and firmware it has (serial-console.js).
import { openConsole, showAndSaveLog, restart, csrf } from './serial-console.js';
const card = document.getElementById('usb-log');
const output = card.querySelector('[data-usb-output]'), buttons = card.querySelectorAll('[data-usb-log],[data-usb-restart]');
const writeLine = line => { output.hidden = false; output.textContent += line + '\n'; output.scrollTop = output.scrollHeight; };
let busy = false;
// New logs (AdminEvents "logs"): the table of saved logs is fetched again and swapped in; the USB output stays.
try {
  new EventSource('/admin/events').addEventListener('logs', async () => {
    const table = document.querySelector('[data-log-targets]'); if (!table) return;
    const page = new DOMParser().parseFromString(await (await fetch(location.pathname, { credentials: 'same-origin' })).text(), 'text/html');
    const fresh = page.querySelector('[data-log-targets]'); if (fresh) table.replaceWith(document.importNode(fresh, true));
  });
} catch { /* reload by hand */ }

if (!('serial' in navigator) || !window.isSecureContext) {
  card.querySelector('[data-usb-unsupported]').hidden = false;
  buttons.forEach(b => b.disabled = true);
}
async function run(task) {
  if (busy) return; busy = true; buttons.forEach(b => b.disabled = true);
  try { await task(); } finally { busy = false; buttons.forEach(b => b.disabled = false); }
}
// Each action asks for the port again, so the next one can check another display without reloading the page. When
// the chosen port is gone at once (a board with the chip's own USB right after a restart), the browser asks again.
async function chosen(open) {
  const port = await navigator.serial.requestPort();
  try { return await open(port); }
  catch { return open(await navigator.serial.requestPort()); }
}
card.querySelector('[data-usb-log]').addEventListener('click', () => run(async () => {
  output.textContent = ''; let console_;
  try {
    console_ = await chosen(openConsole);
    writeLine('Connected, asking the display …');
    const info = await console_.hello();
    const lines = await console_.readLog(info);
    output.textContent = '';
    await showAndSaveLog(info, lines, writeLine);
  } catch (error) { writeLine('Reading the display log failed: ' + (error?.message || error)); }
  finally { await console_?.close(); }
}));
card.querySelector('[data-usb-restart]').addEventListener('click', () => run(async () => {
  try { await chosen(restart); writeLine('Display restarted.'); }
  catch (error) { writeLine('Restart failed: ' + (error?.message || error)); }
}));

// "Request current logs": every display that can is asked for its log; the list shows per display whether it arrived,
// is awaited (also when it could not be reached at once: then it comes with its next pull), timed out or is USB only.
const requestButton = document.querySelector('[data-request-logs]'), requestStates = document.querySelector('[data-request-states]');
const stateText = row => ({
  RECEIVED: 'Received', TIMED_OUT: 'No answer within 10 minutes', USB_ONLY: 'Only over USB (older firmware)', NONE: 'Not requested', ERROR: 'Could not be checked',
  PENDING: row.reach === 'UNREACHABLE' ? 'Not reachable now: it sends the log with its next pull' : row.reach === 'NO_ADDRESS' ? 'Waiting for its next pull' : 'Requested, waiting …'
}[row.state] || row.state);
const showStates = rows => {
  requestStates.replaceChildren(...rows.map(row => {
    const item = document.createElement('li'), name = document.createElement('strong'), state = document.createElement('span');
    name.textContent = row.name; state.className = 'badge' + (row.state === 'RECEIVED' ? ' ok' : row.state === 'PENDING' ? ' custom' : '');
    state.textContent = stateText(row); item.append(name, state); return item;
  }));
  requestStates.hidden = !rows.length;
};
let watching = null;
requestButton?.addEventListener('click', async () => {
  requestButton.disabled = true; clearTimeout(watching);
  try {
    const response = await fetch('/admin/support/request-logs', { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json', ...csrf } });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    showStates(await response.json());
    const started = Date.now();
    const watch = async () => {
      try {
        const rows = await (await fetch('/admin/support/log-requests', { credentials: 'same-origin', headers: { Accept: 'application/json' } })).json();
        showStates(rows);
        if (rows.some(r => r.state === 'PENDING' && r.reach !== 'UNREACHABLE') && Date.now() - started < 60000) watching = setTimeout(watch, 3000);
      } catch { /* the list stays as it is */ }
    };
    watching = setTimeout(watch, 2000);
  } catch (error) { showStates([{ name: 'Request failed', state: error.message }]); }
  requestButton.disabled = false;
});
// Support file: the server answers the encrypted file and its key once; the file is downloaded, the key shown.
const create = document.querySelector('[data-support-create]'), keyBox = document.querySelector('[data-support-key]');
const createError = document.querySelector('[data-support-error]');
create.addEventListener('submit', async event => {
  event.preventDefault(); const button = create.querySelector('button'); button.disabled = true; createError.hidden = true;
  try {
    const body = new URLSearchParams(new FormData(create)); if (!create.elements.hideHosts.checked) body.set('hideHosts', 'false');
    const response = await fetch('/admin/support/file', { method: 'POST', body, credentials: 'same-origin', headers: { Accept: 'application/json', ...csrf } });
    const answer = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(answer.error || 'HTTP ' + response.status);
    const bytes = Uint8Array.from(atob(answer.data), c => c.charCodeAt(0));
    const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' })), download: answer.name });
    link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    keyBox.querySelector('[data-support-name]').textContent = answer.name; keyBox.querySelector('[data-support-key-value]').textContent = answer.key;
    keyBox.hidden = false;
  } catch (error) { createError.textContent = 'The support file could not be created: ' + error.message; createError.hidden = false; }
  button.disabled = false;
});
// Opening one: shown as sections; nothing is kept.
const opener = document.querySelector('[data-support-open]'), contents = document.querySelector('[data-support-contents]');
const openError = document.querySelector('[data-support-open-error]');
const element = (tag, text) => { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; return node; };
const section = (title, text, open = false) => {
  const details = element('details'); details.open = open; details.append(element('summary', title));
  const pre = element('pre', text); pre.className = 'log-lines'; details.append(pre); return details;
};
const when = millis => millis ? new Date(millis).toLocaleString() : '-';
// The overview per display: observed facts, what they suggest and what is missing; the server's warnings.
const diagnosisText = d => [d.legend, '',
  `Server: ${d.server.warnings} warnings, ${d.server.errors} errors, ${d.server.savingFailed} failed saves in the log; data directory ${d.server.storageWritable ? 'writable' : 'NOT writable'}`,
  ...d.displays.flatMap(x => ['', `${x.name} (${x.identifier}) · ${x.board} · firmware ${x.firmwareNow ?? 'unknown'}`
    + (x.logFirmware ? ` · newest log ${x.logFirmware}, ${x.logAgeMinutes} min old` : ' · no log') + (x.firmwareDiffers ? ' · FIRMWARE DIFFERS' : ''),
    ...x.observed.map(t => '  observed:  ' + t), ...x.suspected.map(t => '  suspected: ' + t), ...x.missing.map(t => '  missing:   ' + t)])].join('\n');
const rawActions = document.querySelector('[data-support-raw-actions]');
let raw = null, rawName = 'vibedock-support.json';
document.querySelector('[data-support-download]').addEventListener('click', () => {
  if (!raw) return;
  const link = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([raw], { type: 'application/json' })), download: rawName });
  link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 10000);
});
// The user's description first; "-" where a field was left empty.
const reportText = (report, displays) => {
  if (!report) return 'No description (created before VibeDock 0.2.0).';
  const names = (report.displays || []).map(id => { const d = displays.find(x => x.identifier === id); return d ? `${d.name} (${id})` : id; });
  return [`${report.title ?? '-'}`, '', `What happened:\n${report.happened ?? '-'}`, '', `Expected:\n${report.expected ?? '-'}`, '',
    `Steps:\n${report.steps ?? '-'}`, '', `Since: ${report.since ?? '-'}`, `Displays: ${names.length ? names.join(', ') : '-'}`].join('\n');
};
opener.addEventListener('submit', async event => {
  event.preventDefault(); openError.hidden = true; contents.hidden = true; rawActions.hidden = true; raw = null; const button = opener.querySelector('button'); button.disabled = true;
  try {
    const file = opener.elements.file.files[0]; if (!file) throw new Error('Choose a support file');
    const response = await fetch('/admin/support/open', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...csrf }, body: JSON.stringify({ content: await file.text(), key: opener.elements.key.value }) });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || 'HTTP ' + response.status);
    const s = data.summary;
    raw = JSON.stringify(data, null, 2); rawName = file.name.replace(/\.vdsupport$/, '') + '.json';
    const rawSection = section('Raw (JSON)', raw); rawSection.querySelector('pre').dataset.supportRaw = '';
    contents.replaceChildren(
      section('Problem', reportText(data.report, data.displays), true),
      ...(data.diagnosis ? [section('Diagnosis', diagnosisText(data.diagnosis), true)] : []),
      section('Summary', `VibeDock ${s.version} · Java ${s.java} · ${s.platform}${s.docker ? ' · Docker' : ''} · ${s.timeZone}\n`
        + `Created ${when(s.createdAt)}, logs of ${s.days} day(s)${s.hostsHidden ? ', host names hidden' : ''} · up ${Math.round(s.uptimeSeconds / 3600)} h\n`
        + `Poll ${s.pollSeconds} s · ${s.ipRules} IP rules · ${s.sources} data sources · ${s.displays} displays${s.testMode ? ' · ' + s.testMode : ''}`, true),
      section(`Data sources (${data.sources.length})`, data.sources.map(x => `${x.label} (${x.type})${x.suspended ? ' paused' : ''}: ${x.status ?? '-'}, ${when(x.observedAt)}`).join('\n'), true),
      section(`Displays (${data.displays.length})`, data.displays.map(d => `${d.name} · ${d.identifier} · ${d.board} · ${d.display} · firmware ${d.firmware ?? '-'} · ${d.status}, last pull ${when(d.lastPullAt)} · ${d.ip}`
        + `${d.web ? ' · web display' : ''}${d.ota ? ' · Wi-Fi updates' : ''}${d.updating ? ' · updating' : ''}${d.updateError ? ' · update error: ' + d.updateError : ''}${d.flipped ? ' · flipped' : ''}`).join('\n'), true),
      ...data.deviceLogs.map(l => section(`Display log ${l.identifier} · ${when(l.savedAt)} · ${l.source} · ${l.firmware ?? '-'} · ${l.board ?? '-'}`, l.lines.join('\n'))),
      section(`Server log (${data.serverLog.length} lines)`, data.serverLog.join('\n')),
      section(`Access log (${data.accessLog.length} lines)`, data.accessLog.join('\n')), rawSection);
    contents.hidden = false; rawActions.hidden = false;
  } catch (error) { openError.textContent = 'Opening failed: ' + error.message; openError.hidden = false; }
  button.disabled = false;
});
