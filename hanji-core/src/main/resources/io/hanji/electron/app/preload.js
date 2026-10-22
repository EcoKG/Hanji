// Exposes the IPC transport used by hanji.js inside Electron windows (context isolation stays on).
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('hanjiNative', {
  // Java -> page: batches arrive over IPC; the main process keeps the event stream to Java.
  connect(base, pageId, onBatch) {
    ipcRenderer.removeAllListeners('hanji:batch');
    ipcRenderer.on('hanji:batch', (_event, id, data) => onBatch(id, data));
    ipcRenderer.send('hanji:connect', base, pageId);
  },
  // page -> Java: resolves with the HTTP status of the forwarded request.
  send(base, body) {
    return ipcRenderer.invoke('hanji:send', base, body);
  }
});
