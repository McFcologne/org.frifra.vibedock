// The device's own screens for the manual, drawn on a canvas the way vibedock-client/firmware/src/main.cpp draws them
// (message, drawSteps, showDetail, showSetupCode, showPairing, updateProgress) on its 240 × 240 screen. Change both
// together. The QR code is an illustration: the device draws a fresh code for a random setup password each time.

const screens = {
  'starting': {message: 'Starting network', step: 0},
  'wifi-setup': {qr: true},
  'waiting': {message: 'Network not found', step: 0, problem: true, detail: 'Waiting for Example-WiFi', small: 'Trying again every 15 s'},
  'pairing': {message: 'Invite in the administration', step: 1, code: '482 913'},
  'connecting': {message: 'Connecting to server', step: 2},
  'loading': {message: 'Loading image', step: 3},
  'unreachable': {message: 'Server unreachable', step: 2, problem: true},
  'updating': {message: 'Updating to v{version}', progress: 0.6},
  'factory-reset': {message: 'Unplug now for factory reset', problem: true, detail: 'or wait: Wi-Fi setup'}
};

function draw(screen, version) {
  const C = {muted: '#8a97a8', track: '#2a3546', done: '#34c759', active: '#7fd6ff', problem: '#ff9f0a',
    pill: '#2a1a05', pillEdge: '#6b4310', pillText: '#ffcf8a', orange: '#ffa500'};
  // TFT_eSPI fonts 1, 2 and 4 (8, 16 and 26 pixels high) as close as a canvas font gets.
  const FONTS = {1: '8px "DejaVu Sans Mono", monospace', 2: '13px "DejaVu Sans", Verdana, sans-serif', 4: 'bold 20px "DejaVu Sans", Verdana, sans-serif'};
  const OFFSET = {1: 0, 2: 1, 4: 2};
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 480;
  canvas.style.cssText = 'width:240px;height:240px;display:block';
  document.body.replaceChildren(canvas);
  const g = canvas.getContext('2d'); g.scale(2, 2);
  const rect = (x, y, w, h, c) => { g.fillStyle = c; g.fillRect(x, y, w, h); };
  const round = (x, y, w, h, r, fill, stroke) => {
    g.beginPath(); g.roundRect(x, y, w, h, r);
    if (fill) { g.fillStyle = fill; g.fill(); }
    if (stroke) { g.strokeStyle = stroke; g.lineWidth = 1; g.beginPath(); g.roundRect(x + .5, y + .5, w - 1, h - 1, r); g.stroke(); }
  };
  const text = (value, x, y, font, colour) => { g.font = FONTS[font]; g.fillStyle = colour; g.textAlign = 'center'; g.textBaseline = 'top'; g.fillText(value, x, y + OFFSET[font]); };
  const width = (value, font) => { g.font = FONTS[font]; return Math.ceil(g.measureText(value).width); };
  const circle = (x, y, r, c) => { g.beginPath(); g.arc(x, y, r, 0, 2 * Math.PI); g.fillStyle = c; g.fill(); };
  // drawSmoothArc: 0° is at the bottom, angles run clockwise.
  const arc = (x, y, from, to, c) => { g.beginPath(); g.arc(x, y, 5, (from + 90) * Math.PI / 180, (to + 90) * Math.PI / 180); g.strokeStyle = c; g.lineWidth = 2; g.stroke(); };
  const line = (points, c) => { g.beginPath(); g.moveTo(...points[0]); points.slice(1).forEach(p => g.lineTo(...p)); g.strokeStyle = c; g.lineWidth = 2; g.lineCap = 'round'; g.stroke(); };

  function logo(x, y, s) {
    round(x + 3 * s, y + 4 * s, 34 * s, 35 * s, 7 * s, '#b9bcb4');
    round(x + 3 * s, y + 2 * s, 34 * s, 34 * s, 7 * s, '#efefeb', '#b9bcb4');
    round(x + 7 * s, y + 6 * s, 26 * s, 22 * s, 2 * s, '#14254d');
    rect(x + 9 * s, y + 8 * s, 7 * s, 2 * s, '#7fd6ff');
    [17, 13, 5].forEach((fill, i) => {
      round(x + 9 * s, y + (12 + 4 * i) * s, 17 * s, 2 * s, s, '#284070');
      round(x + 9 * s, y + (12 + 4 * i) * s, fill * s, 2 * s, s, i === 2 ? C.problem : C.done);
    });
    circle(x + 29 * s, y + 24 * s, 2 * s, '#ffcc4d');
  }
  const stepX = i => 30 + 60 * i, STEP_Y = 180, NAMES = ['Wi-Fi', 'Pair', 'Server', 'Image'];
  function steps(current, problem) {
    for (let i = 0; i < 3; i++) rect(stepX(i) + 9, STEP_Y - 1, 42, 2, i < current ? C.done : C.track);
    for (let i = 0; i < 4; i++) {
      const x = stepX(i);
      if (i < current) { circle(x, STEP_Y, 6, C.done); line([[x - 3, STEP_Y], [x - 1, STEP_Y + 2], [x + 3, STEP_Y - 3]], '#000'); }
      else if (i === current && problem) { circle(x, STEP_Y, 6, C.problem); rect(x - 1, STEP_Y - 4, 2, 5, '#000'); rect(x - 1, STEP_Y + 2, 2, 2, '#000'); }
      else { arc(x, STEP_Y, 0, 360, C.track); if (i === current) arc(x, STEP_Y, 0, 120, C.active); }  // the spinner's first phase
      text(NAMES[i], x, STEP_Y + 10, 2, i <= current ? '#fff' : C.muted);
    }
  }
  function footer(value, colour) { rect(0, 220, 240, 20, '#000'); text(value, 120, 222, 2, colour); }

  rect(0, 0, 240, 240, '#000');
  if (screen.qr) {
    // Version 4 (33 × 33 modules), 5 pixels per module, quiet zone of 2 modules, the icon on 9 × 9 white modules.
    const n = 33, scale = 5, quiet = 2, size = (n + 2 * quiet) * scale, left = Math.floor((240 - size) / 2), top = 26;
    text('Scan to set up Wi-Fi', 120, 5, 2, '#fff');
    rect(left, top, size, size, '#fff');
    const on = Array.from({length: n}, () => new Array(n).fill(false)), fixed = Array.from({length: n}, () => new Array(n).fill(false));
    const finder = (fx, fy) => { for (let y = -1; y <= 7; y++) for (let x = -1; x <= 7; x++) {
      const X = fx + x, Y = fy + y; if (X < 0 || Y < 0 || X >= n || Y >= n) continue;
      const d = Math.max(Math.abs(x - 3), Math.abs(y - 3)); fixed[Y][X] = true; on[Y][X] = d !== 2 && d <= 3; } };
    finder(0, 0); finder(n - 7, 0); finder(0, n - 7);
    for (let y = -2; y <= 2; y++) for (let x = -2; x <= 2; x++) { const d = Math.max(Math.abs(x), Math.abs(y)); fixed[26 + y][26 + x] = true; on[26 + y][26 + x] = d !== 1; }
    for (let i = 8; i < n - 8; i++) { fixed[6][i] = fixed[i][6] = true; on[6][i] = on[i][6] = i % 2 === 0; }
    let seed = 20261014;  // fixed: the same picture every time
    const random = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (!fixed[y][x]) on[y][x] = random() < .5;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (on[y][x]) rect(left + (x + quiet) * scale, top + (y + quiet) * scale, scale, scale, '#000');
    const field = 9 * scale, centre = left + Math.floor(size / 2), middle = top + Math.floor(size / 2);
    rect(centre - Math.floor(field / 2), middle - Math.floor(field / 2), field, field, '#fff');
    logo(centre - 20, middle - 20, 1);
    footer('Browser: 192.168.4.1', '#fff');
    return;
  }
  const message = screen.message.replace('{version}', version);
  logo(80, 4, 2);
  text('VibeDock', 120, 86, 4, '#fff');
  text('v' + version, 120, 109, 1, C.muted);
  if (screen.problem) {
    const w = width(message, 2) + 24;
    round(120 - w / 2, 120, w, 22, 8, C.pill, C.pillEdge);
    text(message, 120, 123, 2, C.pillText);
  } else text(message, 120, 123, 2, C.muted);
  if (screen.step !== undefined) steps(screen.step, !!screen.problem);
  if (screen.code) text(screen.code, 120, 142, 4, C.done);
  if (screen.detail) text(screen.detail, 120, 148, 2, '#fff');
  if (screen.small) text(screen.small, 120, 164, 1, C.muted);
  if (screen.progress !== undefined) { round(40, 156, 160, 8, 4, C.track); round(40, 156, Math.max(Math.floor(160 * screen.progress), 8), 8, 4, C.active); }
  footer('IP: 192.168.1.22', C.muted);
}

/** Saves every screen as <prefix>-<name>.png (480 × 480, twice the device's pixels). */
export async function deviceScreens(context, out, prefix, version) {
  const page = await context.newPage();
  await page.setViewportSize({width: 240, height: 240});
  await page.setContent('<!doctype html><style>html,body{margin:0;background:#000}</style><body></body>');
  await page.evaluate(() => document.fonts.ready);
  for (const [name, screen] of Object.entries(screens)) {
    await page.evaluate(([fn, s, v]) => new Function('return ' + fn)()(s, v), [draw.toString(), screen, version]);
    await page.locator('canvas').screenshot({path: `${out}/${prefix}-${name}.png`}); console.log(`  ${prefix}-${name}`);
  }
  await page.close();
}
