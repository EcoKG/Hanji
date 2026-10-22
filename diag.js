// Does Hanji's 3 s "reveal" fallback (show() if not visible) undo an early minimize on macOS?
const { app, BrowserWindow } = require('electron');
const t0 = Date.now();
const log = (...a) => console.log(String(Date.now() - t0).padStart(6), ...a);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const st = (w) => `min=${w.isMinimized()} vis=${w.isVisible()}`;

async function scenario(name, fixed) {
  const created = Date.now();
  const w = new BrowserWindow({ width: 640, height: 420, show: false });
  for (const e of ['minimize', 'restore', 'show', 'hide']) w.on(e, () => log(name, '+' + (Date.now() - created), 'event', e, st(w)));
  let revealed = false;
  const reveal = fixed
    ? () => { if (revealed || w.isDestroyed()) return; revealed = true; if (!w.isVisible()) w.show(); }
    : () => { if (!w.isDestroyed() && !w.isVisible()) w.show(); };          // main.js today
  w.once('ready-to-show', reveal);
  setTimeout(() => { log(name, '+' + (Date.now() - created), 'reveal timer', st(w)); reveal(); }, 3000);
  w.loadURL('data:text/html,<h1>diag</h1>');
  await new Promise((r) => w.once('show', r));
  await sleep(300);
  log(name, '+' + (Date.now() - created), 'minimize()');
  w.minimize();
  await sleep(4500);
  const ok = w.isMinimized();
  log(name, 'RESULT still minimized after the timer =', ok, st(w));
  w.destroy();
  return ok;
}

app.on('window-all-closed', () => {});
app.whenReady().then(async () => {
  const results = { current: '', fixed: '' };
  for (let i = 0; i < 3; i++) {
    results.current += (await scenario('current', false)) ? 'o' : 'X';
    results.fixed += (await scenario('fixed', true)) ? 'o' : 'X';
  }
  log('SUMMARY', JSON.stringify(results));
  app.quit();
});
