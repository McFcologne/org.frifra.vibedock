// The firmware's line console over USB (Web Serial, 115200 baud), shared by the firmware page and Troubleshooting.
// Commands, one per line: VIBEDOCK-HELLO (answer VIBEDOCK-READY {identifier,...}), VIBEDOCK-LOG (VIBEDOCK-LOG [...]),
// VIBEDOCK-SCAN (VIBEDOCK-NETWORKS [...]) and VIBEDOCK-SETUP {Wi-Fi + pairing} (VIBEDOCK-SETUP OK, then it restarts).
// Every board and firmware answers the same; the board need not be known.
export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export const csrf = { [document.querySelector('meta[name="csrf-header"]').content]: document.querySelector('meta[name="csrf-token"]').content };

// Line console on the device's serial port.
export async function openConsole(serialPort) {
  await serialPort.open({ baudRate: 115200, bufferSize: 8192 });
  // Run, not the bootloader. RTS first: with DTR still set, a released RTS cannot pull the chip's reset (EN) low on
  // the usual auto-reset wiring, so opening the console does not restart a running display.
  await serialPort.setSignals({ requestToSend: false });
  await serialPort.setSignals({ dataTerminalReady: false });
  const reader = serialPort.readable.getReader(), waiters = [];
  let open = true;
  (async () => {
    const decoder = new TextDecoder(); let buffer = '';
    try {
      while (open) {
        const { value, done } = await reader.read(); if (done) break;
        buffer += decoder.decode(value, { stream: true });
        for (let i; (i = buffer.indexOf('\n')) >= 0; buffer = buffer.slice(i + 1)) {
          const at = buffer.slice(0, i).indexOf('VIBEDOCK-'); if (at < 0) continue;  // boot messages of the chip
          const line = buffer.slice(at, i).trim();
          for (const w of [...waiters]) if (line.startsWith(w.prefix)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(line); }
        }
        if (buffer.length > 16384) buffer = '';
      }
    } catch { /* port closed */ }
  })();
  const console_ = {
    wait(prefix, ms) {
      return new Promise((resolve, reject) => {
        const w = { prefix, resolve: line => { clearTimeout(timer); resolve(line); } };
        const timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error('timeout')); }, ms);
        waiters.push(w);
      });
    },
    async send(text) {  // in small paced pieces: the device reads its 1 kB buffer between other work
      const writer = serialPort.writable.getWriter(), bytes = new TextEncoder().encode(text + '\n');
      try { for (let i = 0; i < bytes.length; i += 128) { await writer.write(bytes.slice(i, i + 128)); await sleep(40); } }
      finally { writer.releaseLock(); }
    },
    // Asks until the device answers; it may still be starting or trying its old Wi-Fi.
    async hello() {
      for (let attempt = 0; attempt < 20; attempt++) {
        const answer = console_.wait('VIBEDOCK-READY ', 1500);
        await console_.send('VIBEDOCK-HELLO');
        try { return JSON.parse((await answer).slice('VIBEDOCK-READY '.length)); } catch (e) { if (e.message !== 'timeout') throw e; }
      }
      throw new Error('The display does not answer over USB. Its firmware may be older than this page; install it first.');
    },
    // The display's own log: starts, Wi-Fi, pairing, server answers and why a connection failed (e.g. a TLS error).
    // It holds no keys or passwords.
    async readLog(info) {
      const answer = console_.wait('VIBEDOCK-LOG ', 20000);  // older firmware answers only after a TLS handshake
      await console_.send('VIBEDOCK-LOG');
      try { return JSON.parse((await answer).slice('VIBEDOCK-LOG '.length)); }
      catch (error) { throw new Error(error.message === 'timeout' ? `no answer from firmware ${info.version}; try again` : error.message); }
    },
    async close() { open = false; try { await reader.cancel(); } catch {} reader.releaseLock(); try { await serialPort.close(); } catch {} }
  };
  return console_;
}

// Writes a log read over USB to the terminal (writeLine) and keeps it on the server (the last ones per display).
export async function showAndSaveLog(info, lines, writeLine) {
  writeLine(`Log of ${info.name || info.identifier} (${info.identifier}, firmware ${info.version}${info.board ? ', ' + info.board : ''}), oldest first:`);
  if (/External System|External reset/.test(lines.at(-1) ?? '') && lines.at(-1).includes(' Start '))
    writeLine('  (The display restarted when the port was opened; the lines before the last start are from its earlier runs.)');
  for (const line of lines) writeLine('  ' + line);
  if (!lines.length) writeLine('  (empty)');
  if (!info?.identifier) return null;
  const page = '/admin/clients/' + encodeURIComponent(info.identifier) + '/logs';
  try {
    const response = await fetch(page, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', ...csrf },
      body: JSON.stringify({ firmware: info.version ?? null, board: info.board ?? null, lines }) });
    const saved = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(saved.error || 'HTTP ' + response.status);
    writeLine(`Saved on the server (${saved.count} of the last ${saved.keep} logs of this display): ${location.origin}${page}`);
    return page;
  } catch (error) { writeLine('The log could not be saved on the server: ' + (error?.message || error)); return null; }
}

// Restarts the display like the reset button: the USB adapter's RTS line pulls the chip's reset (EN) low while DTR
// keeps GPIO0 high, so it starts its firmware and not the bootloader. Works with any firmware.
export async function restart(serialPort) {
  await serialPort.open({ baudRate: 115200 });
  try {
    await serialPort.setSignals({ dataTerminalReady: false, requestToSend: true }); await sleep(150);
    await serialPort.setSignals({ dataTerminalReady: false, requestToSend: false });
  } finally { await serialPort.close(); }
}
