// Hanji Electron shell: native windows, menus and tray, controlled by the Java process.
//
// Java <-> this process: one TCP connection (newline-delimited JSON), opened by Java on 127.0.0.1 and
// authenticated with a random token. Pages talk to Java through IPC (preload.js); this process forwards
// their traffic to Java's HTTP bridge, so pages need no HTTP connections of their own.
'use strict';

const { app, BrowserWindow, Menu, Tray, ipcMain, nativeImage, shell } = require('electron');
const net = require('node:net');
const path = require('node:path');

function arg(name) {
  const prefix = '--' + name + '=';
  const found = process.argv.find((a) => a.startsWith(prefix));
  return found ? found.slice(prefix.length) : null;
}

const control = arg('hanji-control');
const token = arg('hanji-token');
const appName = arg('hanji-name') || 'Hanji';
const devMode = process.argv.includes('--hanji-dev');
const isMac = process.platform === 'darwin';

if (!control || !token) {
  console.error('hanji: started without control arguments');
  app.exit(2);
}

app.setName(appName);
app.setPath('userData', path.join(app.getPath('appData'), appName.replace(/[\\/:*?"<>|]/g, '_')));
// Java decides when the application ends (implicit exit), not the last window.
app.on('window-all-closed', () => {});

const windows = new Map();   // window id (from Java) -> BrowserWindow
const windowMenus = new Map(); // window id -> Menu (macOS: becomes the application menu while focused)
const trays = new Map();     // tray id -> Tray
const trayMenus = new Map(); // tray id -> Menu
const origins = new Set();   // origins of Hanji pages; the only targets the IPC proxy will reach
const streams = new Map();   // webContents id -> AbortController of its event stream
let socket = null;

// ---------------------------------------------------------------- control channel

function send(message) {
  if (socket && !socket.destroyed) socket.write(JSON.stringify(message) + '\n');
}

function connectControl() {
  const sep = control.lastIndexOf(':');
  socket = net.connect({ host: control.slice(0, sep), port: Number(control.slice(sep + 1)) }, () => {
    send({ ev: 'hello', token, electron: process.versions.electron, platform: process.platform });
  });
  socket.setEncoding('utf8');
  socket.setNoDelay(true);
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk;
    let i;
    while ((i = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, i);
      buffer = buffer.slice(i + 1);
      if (!line) continue;
      let message;
      try { message = JSON.parse(line); } catch (e) { report(e); continue; }
      try { handle(message); } catch (e) { report(e); }
    }
  });
  // The Java process ended or dropped us: never outlive it.
  socket.on('close', () => app.exit(0));
  socket.on('error', () => app.exit(1));
}

function report(error) {
  send({ ev: 'error', message: String((error && error.stack) || error) });
}

function handle(m) {
  switch (m.cmd) {
    case 'open': return openWindow(m);
    case 'close': return closeWindow(m.win);
    case 'bounds': return withWindow(m.win, (w) => {
      const b = w.getBounds();
      w.setBounds({ x: m.x ?? b.x, y: m.y ?? b.y, width: m.width ?? b.width, height: m.height ?? b.height });
    });
    case 'center': return withWindow(m.win, (w) => w.center());
    case 'state': return withWindow(m.win, (w) => setState(w, m.state));
    case 'set': return withWindow(m.win, (w) => setProperty(w, m.prop, m.value));
    case 'menu': return setWindowMenu(m.win, m.menus);
    case 'tray': return trayCommand(m);
    case 'test': return testCommand(m);
    case 'quit': return app.exit(0);
    default: throw new Error('Unknown command ' + m.cmd);
  }
}

function withWindow(id, fn) {
  const w = windows.get(id);
  if (w && !w.isDestroyed()) fn(w);
}

// ---------------------------------------------------------------- windows

function stateOf(w) {
  if (w.isFullScreen()) return 'FULLSCREEN';
  if (w.isMinimized()) return 'MINIMIZED';
  if (w.isMaximized()) return 'MAXIMIZED';
  return 'NORMAL';
}

function openWindow(m) {
  const w = new BrowserWindow({
    width: m.width,
    height: m.height,
    x: m.x ?? undefined,
    y: m.y ?? undefined,
    title: m.title || appName,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false
    }
  });
  windows.set(m.win, w);
  origins.add(new URL(m.url).origin);
  if (!isMac) w.setMenu(null); // no menu bar until Java sets one

  const reveal = () => { if (!w.isDestroyed() && !w.isVisible()) w.show(); };
  w.once('ready-to-show', reveal);
  setTimeout(reveal, 3000); // slow pages still get a window

  let timer = null;
  const reportGeometry = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (w.isDestroyed()) return;
      send({ ev: 'window', win: m.win, bounds: w.getBounds(), state: stateOf(w), focused: w.isFocused() });
    }, 30);
  };
  for (const name of ['resize', 'move', 'minimize', 'maximize', 'unmaximize', 'restore',
                      'enter-full-screen', 'leave-full-screen', 'focus', 'blur', 'show']) {
    w.on(name, reportGeometry);
  }
  w.on('focus', () => applyApplicationMenu(m.win));
  w.on('close', (event) => {
    if (w.hanjiCloseGuard && !w.hanjiForceClose) {
      event.preventDefault();
      send({ ev: 'close-request', win: m.win });
    }
  });
  w.on('closed', () => {
    windows.delete(m.win);
    windowMenus.delete(m.win);
    send({ ev: 'closed', win: m.win });
  });

  // Links to other sites open in the user's browser; the window only shows Hanji pages.
  w.webContents.setWindowOpenHandler(({ url }) => { openExternal(url); return { action: 'deny' }; });
  w.webContents.on('will-navigate', (event, url) => {
    if (!isAppUrl(url)) { event.preventDefault(); openExternal(url); }
  });
  const contentsId = w.webContents.id;
  w.webContents.on('destroyed', () => stopStream(contentsId));
  if (devMode) {
    // reload and developer tools without a menu bar
    w.webContents.on('before-input-event', (event, input) => {
      if (input.type !== 'keyDown') return;
      const key = input.key.toLowerCase();
      const mod = input.control || input.meta;
      if (key === 'f5' || (mod && key === 'r')) { event.preventDefault(); w.webContents.reload(); }
      if (key === 'f12' || (mod && input.shift && key === 'i')) { event.preventDefault(); w.webContents.toggleDevTools(); }
    });
  }

  w.loadURL(m.url);
  reportGeometry();
}

function closeWindow(id) {
  withWindow(id, (w) => { w.hanjiForceClose = true; w.destroy(); });
}

function setState(w, state) {
  w.hanjiStateRequest = (w.hanjiStateRequest || 0) + 1; // cancels a pending minimize retry
  switch (state) {
    case 'MINIMIZED': minimize(w); break;
    case 'MAXIMIZED': if (w.isMinimized()) w.restore(); w.maximize(); break;
    case 'FULLSCREEN': w.setFullScreen(true); break;
    case 'NORMAL':
      if (w.isFullScreen()) w.setFullScreen(false);
      if (w.isMinimized()) w.restore();
      if (w.isMaximized()) w.unmaximize();
      break;
    case 'FRONT':
      if (w.isMinimized()) w.restore();
      w.show();
      w.focus();
      if (isMac) app.focus({ steal: true });
      break;
    default: throw new Error('Unknown window state ' + state);
  }
}

// macOS drops minimize() while a zoom animation runs. Right after unmaximize the window already reports NORMAL
// (the frame starts shrinking), so a minimize requested then is lost: repeat it until the window minimizes.
function minimize(w) {
  const request = w.hanjiStateRequest;
  let minimized = false;
  const onMinimize = () => { minimized = true; };
  w.once('minimize', onMinimize);
  let attempts = 0;
  const attempt = () => {
    if (w.isDestroyed()) return;
    if (minimized || w.isMinimized() || w.hanjiStateRequest !== request || attempts++ >= 20) {
      w.removeListener('minimize', onMinimize);
      return;
    }
    w.minimize();
    setTimeout(attempt, 250);
  };
  attempt();
}

function setProperty(w, prop, value) {
  switch (prop) {
    case 'resizable': w.setResizable(!!value); break;
    case 'alwaysOnTop': w.setAlwaysOnTop(!!value); break;
    case 'minimumSize': w.setMinimumSize(value[0], value[1]); break;
    case 'closeGuard': w.hanjiCloseGuard = !!value; break;
    default: throw new Error('Unknown window property ' + prop);
  }
}

function isAppUrl(url) {
  try { return origins.has(new URL(url).origin); } catch (e) { return false; }
}

function openExternal(url) {
  if (/^(https?|mailto):/i.test(url)) shell.openExternal(url);
}

// ---------------------------------------------------------------- menus & tray

function buildItems(items, source) {
  return (items || []).map((item) => {
    if (item.type === 'separator') return { type: 'separator' };
    const out = {};
    if (item.role) out.role = item.role;
    if (item.label != null) out.label = item.label;
    if (item.accelerator) out.accelerator = item.accelerator;
    if (item.enabled === false) out.enabled = false;
    if (item.type === 'checkbox') { out.type = 'checkbox'; out.checked = !!item.checked; }
    if (item.id) out.id = item.id;
    if (item.items) {
      out.submenu = buildItems(item.items, source);
    } else if (item.id && !item.role) {
      out.click = (menuItem) => send(Object.assign({ ev: 'menu', id: item.id, checked: menuItem.checked }, source));
    }
    return out;
  });
}

function hasRole(items, roles) {
  return (items || []).some((i) => roles.includes(i.role) || hasRole(i.items, roles) || hasRole(i.submenu, roles));
}

function setWindowMenu(winId, menus) {
  const template = (menus || []).map((m) => (
    m.role ? { role: m.role, label: m.label || undefined } : { label: m.label, submenu: buildItems(m.items, { win: winId }) }
  ));
  if (isMac) {
    // macOS has one menu bar per application: keep the app menu, and Edit/Window so Cmd+C/V/W/M keep working.
    // A first menu with macOS app-menu items (about, hide, ...) is the application's own app menu; a File menu
    // that merely ends with Quit is not.
    const ownAppMenu = menus && menus.length && !menus[0].role
        && hasRole(menus[0].items, ['about', 'hide', 'hideOthers', 'unhide']);
    const full = ownAppMenu ? [...template] : [{ role: 'appMenu' }, ...template];
    if (!hasRole(menus, ['editMenu', 'copy', 'paste'])) full.splice(1, 0, { role: 'editMenu' });
    if (!hasRole(menus, ['windowMenu'])) full.push({ role: 'windowMenu' });
    windowMenus.set(winId, Menu.buildFromTemplate(full));
    const w = windows.get(winId);
    if (w && w.isFocused()) applyApplicationMenu(winId);
  } else {
    const menu = template.length ? Menu.buildFromTemplate(template) : null;
    if (menu) windowMenus.set(winId, menu); else windowMenus.delete(winId);
    withWindow(winId, (w) => w.setMenu(menu));
  }
}

function defaultMacMenu() {
  const template = [{ role: 'appMenu' }, { role: 'editMenu' }];
  if (devMode) template.push({ role: 'viewMenu' });
  template.push({ role: 'windowMenu' });
  return Menu.buildFromTemplate(template);
}

function applyApplicationMenu(winId) {
  if (isMac) Menu.setApplicationMenu(windowMenus.get(winId) || defaultMacMenu());
}

function trayCommand(m) {
  if (m.action === 'remove') {
    const t = trays.get(m.id);
    if (t) { t.destroy(); trays.delete(m.id); trayMenus.delete(m.id); }
    return;
  }
  let t = trays.get(m.id);
  const image = nativeImage.createFromPath(m.icon || path.join(__dirname, 'tray.png'));
  if (!t) {
    t = new Tray(image);
    trays.set(m.id, t);
    t.on('click', () => send({ ev: 'tray-click', tray: m.id }));
  } else {
    t.setImage(image);
  }
  t.setToolTip(m.tooltip || '');
  const menu = m.items && m.items.length ? Menu.buildFromTemplate(buildItems(m.items, { tray: m.id })) : null;
  if (menu) trayMenus.set(m.id, menu); else trayMenus.delete(m.id);
  t.setContextMenu(menu);
}

// Lets automated tests trigger native UI that cannot be clicked from a page.
function testCommand(m) {
  switch (m.action) {
    case 'click-menu': {
      for (const menu of [...windowMenus.values(), ...trayMenus.values()]) {
        const item = menu.getMenuItemById(m.id);
        if (item) { item.click(); return; }
      }
      throw new Error('No menu item ' + m.id);
    }
    case 'click-tray': {
      const t = m.tray ? trays.get(m.tray) : trays.values().next().value;
      if (!t) throw new Error('No tray icon');
      t.emit('click');
      return;
    }
    case 'user-close': // as if the user pressed the window's close button
      for (const w of windows.values()) if (w.webContents.getURL().startsWith(m.url)) w.close();
      return;
    default: throw new Error('Unknown test action ' + m.action);
  }
}

// ---------------------------------------------------------------- page transport (IPC <-> Java HTTP bridge)

function stopStream(id) {
  const previous = streams.get(id);
  if (previous) { previous.abort(); streams.delete(id); }
}

ipcMain.on('hanji:connect', (event, base, pageId) => {
  if (!isAppUrl(base)) return;
  const wc = event.sender;
  stopStream(wc.id);
  const controller = new AbortController();
  streams.set(wc.id, controller);
  pumpEvents(wc, base, pageId, controller.signal);
});

ipcMain.handle('hanji:send', async (event, base, body) => {
  if (!isAppUrl(base)) throw new Error('Blocked request to ' + base);
  const response = await fetch(base + 'send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body
  });
  return response.status;
});

async function pumpEvents(wc, base, pageId, signal) {
  let lastId = null;
  while (!signal.aborted && !wc.isDestroyed()) {
    try {
      const response = await fetch(base + 'events?page=' + encodeURIComponent(pageId), {
        headers: lastId ? { 'Last-Event-ID': lastId } : {},
        signal
      });
      if (response.status !== 200) return; // 204/404: window closed or page replaced
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
        let i;
        while ((i = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, i);
          buffer = buffer.slice(i + 2);
          let id = null;
          const data = [];
          for (const line of block.split('\n')) {
            if (line.startsWith('id:')) id = line.slice(3).trim();
            else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
          }
          if (data.length === 0) continue; // heartbeat or retry hint
          if (id) lastId = id;
          if (!wc.isDestroyed()) wc.send('hanji:batch', id, data.join('\n'));
        }
      }
    } catch (error) {
      if (signal.aborted) return;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

// ---------------------------------------------------------------- start

app.whenReady().then(() => {
  if (isMac) Menu.setApplicationMenu(defaultMacMenu());
  connectControl();
});
