// Electron-only reproduction of ElectronHostE2ETest.windowStates on macOS: what happens to minimize()?
const { app, BrowserWindow, Menu } = require('electron');
const t0 = Date.now();
const log = (...a) => console.log(String(Date.now() - t0).padStart(6), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(cond, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (cond()) return true; await sleep(25); }
  return cond();
}
const st = (w) => `min=${w.isMinimized()} max=${w.isMaximized()} vis=${w.isVisible()} foc=${w.isFocused()}`;

async function scenario(name, opts) {
  const w = new BrowserWindow({ width: 640, height: 420, x: 20, y: 20, show: false });
  for (const e of ['minimize', 'restore', 'maximize', 'unmaximize', 'show', 'hide', 'focus', 'blur']) {
    w.on(e, () => log(name, 'event', e, st(w)));
  }
  await w.loadURL('data:text/html,<h1>diag</h1>');
  w.show();
  await sleep(800);
  if (opts.bounds) { w.setBounds({ x: 40, y: 50, width: 700, height: 480 }); await sleep(500); }
  if (opts.maximize) {
    w.maximize();
    log(name, 'maximized?', await waitFor(() => w.isMaximized(), 10000), st(w));
    w.unmaximize();
    log(name, 'normal?', await waitFor(() => !w.isMaximized(), 10000), st(w));
  }
  if (opts.delay) await sleep(opts.delay);
  log(name, 'minimize()', st(w));
  w.minimize();
  let ok = await waitFor(() => w.isMinimized(), opts.retry ? 400 : 8000);
  for (let i = 0; !ok && opts.retry && i < 20; i++) {
    log(name, 'retry minimize()', st(w));
    w.minimize();
    ok = await waitFor(() => w.isMinimized(), 400);
  }
  log(name, 'RESULT minimized=' + ok, st(w));
  w.restore();
  log(name, 'restored?', await waitFor(() => !w.isMinimized(), 8000), st(w));
  w.destroy();
  return ok;
}

app.on('window-all-closed', () => {}); // keep running between scenarios
app.whenReady().then(async () => {
  const results = {};
  const record = async (name, opts) => {
    let r;
    try { r = (await scenario(name, opts)) ? 'o' : 'X'; } catch (e) { log(name, 'ERROR', e.message); r = 'E'; }
    results[name] = (results[name] || '') + r;
  };
  for (let i = 0; i < 5; i++) {
    await record('plain', {});
    await record('max-unmax-immediate', { bounds: true, maximize: true });
    await record('max-unmax-delay1s', { bounds: true, maximize: true, delay: 1000 });
    await record('max-unmax-retry', { bounds: true, maximize: true, retry: true });
  }
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: 'File', submenu: [{ label: 'Do' }, { type: 'separator' }, { role: 'quit' }] }, { role: 'editMenu' }, { role: 'windowMenu' }]));
  for (let i = 0; i < 5; i++) await record('custom-menu-max-unmax', { bounds: true, maximize: true });
  log('SUMMARY', JSON.stringify(results));
  app.quit();
});
