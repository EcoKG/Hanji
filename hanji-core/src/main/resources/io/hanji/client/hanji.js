/*
 * Hanji client runtime. Injected into every page served by Hanji.
 *
 * Java -> page : Server-Sent Events (__hanji/events), each message is a batch of commands.
 * page -> Java : POST (__hanji/send), batches of messages sent strictly one at a time to keep order.
 *
 * Public API (window.hanji):
 *   hanji.call(name, ...args)  -> Promise    call a Java function (@JsCallable / Window.expose)
 *   hanji.emit(name, payload)                send a message (Window.onMessage)
 *   hanji.on(topic, fn)        -> unsubscribe receive Window.publish(topic, data)
 *   hanji.sync(elementOrId)                  push a form field value changed by script to Java
 */

// Evaluates Java-provided scripts outside strict mode; `el` (and `this`) is the target element.
var __hanjiEval = function (el, __script) { return eval(__script); };

(function () {
  'use strict';
  if (window.hanji) return;

  var script = document.currentScript;
  var src = script ? script.src : '';
  var base = src.substring(0, src.lastIndexOf('/') + 1);
  var dev = /[?&]dev=1/.test(src);
  var pageId = Date.now().toString(36) + Math.random().toString(36).slice(2, 10);

  var created = new Map();    // id -> element created by Java (found even while not in the document)
  var listeners = new Map();  // listener id -> { target, type, fn, capture, timer }
  var topics = new Map();     // topic -> [fn]
  var calls = new Map();      // call id -> { resolve, reject }
  var callSeq = 0, querySeq = 0;

  var queue = [];
  var started = false, sending = false, closing = false;
  var clientSeq = 0, lastReceived = 0, ackDue = false, ackTimer = 0;
  var eventSource = null;

  // ------------------------------------------------------------------ helpers

  function find(id) {
    switch (id) {
      case '#document': return document;
      case '#window': return window;
      case '#body': return document.body;
      case '#head': return document.head;
    }
    return document.getElementById(id) || created.get(id) || null;
  }

  // Drops Java-created descendants of `root` from the registry (their subtree is being discarded).
  function forget(root) {
    if (!root.querySelectorAll || created.size === 0) return;
    var nodes = root.querySelectorAll('[id]');
    for (var i = 0; i < nodes.length; i++) created.delete(nodes[i].id);
  }

  function isField(el) {
    if (!el || !el.tagName) return false;
    var t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT';
  }

  function fieldState(el) {
    var s = { value: el.value };
    if (el.type === 'checkbox' || el.type === 'radio') s.checked = el.checked;
    return s;
  }

  function errorText(err) {
    if (err == null) return 'Unknown error';
    if (err instanceof Error) return err.name + ': ' + err.message;
    return String(err);
  }

  function toJson(v) {
    if (v === undefined || typeof v === 'function') return null;
    if (typeof Node !== 'undefined' && v instanceof Node) return v.id ? v.id : null;
    try { return JSON.parse(JSON.stringify(v)); } catch (e) { return String(v); }
  }

  // ------------------------------------------------------------------ page -> Java

  function post(msg) {
    queue.push(msg);
    pump();
  }

  function pump() {
    if (!started || sending || closing) return;
    if (queue.length === 0 && !ackDue) return;
    var batch = queue;
    queue = [];
    ackDue = false;
    var body = JSON.stringify({ page: pageId, seq: ++clientSeq, ack: lastReceived, messages: batch });
    sending = true;
    transmit(body, 0);
  }

  function transmit(body, attempt) {
    fetch(base + 'send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body,
      cache: 'no-store'
    }).then(function (r) {
      if (r.status >= 500) throw new Error('HTTP ' + r.status);
      done();
    }).catch(function (err) {
      if (closing) return;
      if (attempt < 8) {
        setTimeout(function () { transmit(body, attempt + 1); }, Math.min(2000, 100 * Math.pow(2, attempt)));
      } else {
        console.error('[hanji] failed to reach Java', err);
        done();
      }
    });
    function done() { sending = false; pump(); }
  }

  function scheduleAck() {
    if (ackTimer) return;
    ackTimer = setTimeout(function () { ackTimer = 0; ackDue = true; pump(); }, 1000);
  }

  function report(err, context) {
    console.error('[hanji]', err, context || '');
    post({ type: 'log', level: 'error', message: errorText(err) + (context ? ' in ' + JSON.stringify(context).slice(0, 300) : ''),
           stack: err && err.stack ? String(err.stack) : null });
  }

  // ------------------------------------------------------------------ Java -> page

  function connect() {
    eventSource = new EventSource(base + 'events?page=' + encodeURIComponent(pageId));
    eventSource.onmessage = function (e) {
      if (e.lastEventId) lastReceived = Number(e.lastEventId);
      var cmds;
      try { cmds = JSON.parse(e.data); } catch (err) { report(err); return; }
      for (var i = 0; i < cmds.length; i++) {
        try { apply(cmds[i]); } catch (err) { report(err, cmds[i]); }
      }
      scheduleAck();
    };
  }

  var needsTarget = { append: 1, prepend: 1, insert: 1, remove: 1, dispose: 1, clear: 1, set: 1, attr: 1,
                      cls: 1, style: 1, html: 1, invoke: 1, listen: 1 };

  function apply(c) {
    var op = ops[c.op];
    if (!op) throw new Error('Unknown command ' + c.op);
    if (needsTarget[c.op]) {
      var el = find(c.id);
      if (!el) {
        if (dev) console.warn('[hanji] element #' + c.id + ' not found for', c);
        return;
      }
      op(c, el);
    } else {
      op(c);
    }
  }

  function childOf(c) {
    var child = find(c.child);
    if (!child) throw new Error('Element #' + c.child + ' not found');
    return child;
  }

  var ops = {
    create: function (c) {
      var el = c.ns ? document.createElementNS(c.ns, c.tag) : document.createElement(c.tag);
      el.id = c.id;
      created.set(c.id, el);
    },
    append: function (c, el) { el.appendChild(childOf(c)); },
    prepend: function (c, el) { el.insertBefore(childOf(c), el.firstChild); },
    insert: function (c, el) { var ref = find(c.ref); el.insertBefore(childOf(c), ref && ref.parentNode === el ? ref : null); },
    remove: function (c, el) { if (el.parentNode) el.parentNode.removeChild(el); },
    dispose: function (c, el) {
      if (el.parentNode) el.parentNode.removeChild(el);
      forget(el);
      created.delete(c.id);
      listeners.forEach(function (l, lid) {
        if (l.target === el) { el.removeEventListener(l.type, l.fn, l.capture); listeners.delete(lid); }
      });
    },
    clear: function (c, el) { forget(el); while (el.firstChild) el.removeChild(el.firstChild); },
    set: function (c, el) {
      if (c.prop === 'textContent' || c.prop === 'innerHTML') forget(el);
      el[c.prop] = c.value;
    },
    attr: function (c, el) {
      if (c.value === null || c.value === undefined) el.removeAttribute(c.name);
      else el.setAttribute(c.name, c.value);
    },
    cls: function (c, el) { if (c.on) el.classList.add(c.name); else el.classList.remove(c.name); },
    style: function (c, el) {
      if (c.value === null || c.value === undefined) el.style.removeProperty(c.name);
      else el.style.setProperty(c.name, c.value);
    },
    html: function (c, el) { el.insertAdjacentHTML(c.pos || 'beforeend', c.html); },
    invoke: function (c, el) {
      var fn = el[c.method];
      if (typeof fn !== 'function') throw new Error('#' + c.id + '.' + c.method + ' is not a function');
      fn.apply(el, c.args || []);
    },
    listen: listen,
    unlisten: function (c) {
      var l = listeners.get(c.lid);
      if (!l) return;
      clearTimeout(l.timer);
      l.target.removeEventListener(l.type, l.fn, l.capture);
      listeners.delete(c.lid);
    },
    eval: function (c) {
      var el = c.id ? find(c.id) : null;
      var value;
      try {
        value = __hanjiEval.call(el || window, el, c.script);
      } catch (err) {
        post({ type: 'result', rid: c.rid, error: errorText(err) });
        return;
      }
      Promise.resolve(value).then(function (v) {
        post({ type: 'result', rid: c.rid, value: toJson(v) });
      }, function (err) {
        post({ type: 'result', rid: c.rid, error: errorText(err) });
      });
    },
    query: function (c) {
      try {
        var nodes = document.querySelectorAll(c.selector), ids = [];
        for (var i = 0; i < nodes.length; i++) {
          if (!nodes[i].id) nodes[i].id = 'hjq-' + pageId.slice(-4) + '-' + (++querySeq);
          ids.push(nodes[i].id);
        }
        post({ type: 'result', rid: c.rid, value: ids });
      } catch (err) {
        post({ type: 'result', rid: c.rid, error: errorText(err) });
      }
    },
    dialog: showDialog,
    reply: function (c) {
      var p = calls.get(c.cid);
      if (!p) return;
      calls.delete(c.cid);
      if (c.error !== undefined) p.reject(new Error(c.error));
      else p.resolve(c.value);
    },
    publish: function (c) {
      var handlers = topics.get(c.topic);
      if (handlers) {
        handlers.slice().forEach(function (h) {
          try { h(c.data); } catch (err) { report(err); }
        });
      }
      window.dispatchEvent(new CustomEvent('hanji:' + c.topic, { detail: c.data }));
    },
    call: function (c) {
      var parts = c.fn.split('.'), owner = window, fn = window;
      for (var i = 0; i < parts.length; i++) {
        owner = fn;
        fn = fn == null ? undefined : fn[parts[i]];
      }
      if (typeof fn !== 'function') throw new Error(c.fn + ' is not a function');
      fn.apply(owner, c.args || []);
    },
    title: function (c) { document.title = c.value; },
    navigate: function (c) { location.href = c.url; },
    close: function (c) {
      closing = true;
      if (eventSource) eventSource.close();
      window.close();
      setTimeout(function () {
        if (window.closed) return;
        document.body.innerHTML = '';
        var note = document.createElement('div');
        note.className = 'hj-closed';
        note.textContent = c.text || 'This window can be closed.';
        document.body.appendChild(note);
      }, 300);
    }
  };

  // ------------------------------------------------------------------ events

  function listen(c, target) {
    var o = c.opts || {};
    var entry = { target: target, type: c.type, fn: null, capture: !!o.capture, timer: 0 };
    entry.fn = function (ev) {
      var cur = target;
      if (o.delegate) {
        var t = ev.target && ev.target.closest ? ev.target.closest(o.delegate) : null;
        if (!t) return;
        if (target !== window && target !== document && !target.contains(t)) return;
        cur = t;
      }
      if (o.keys && o.keys.indexOf(ev.key) < 0) return;
      if (o.prevent) ev.preventDefault();
      if (o.stop) ev.stopPropagation();
      var data = eventData(ev, cur);
      var send = function () { post({ type: 'event', lid: c.lid, data: data }); };
      if (o.debounce > 0) {
        clearTimeout(entry.timer);
        entry.timer = setTimeout(send, o.debounce);
      } else {
        send();
      }
    };
    target.addEventListener(c.type, entry.fn, entry.capture);
    listeners.set(c.lid, entry);
  }

  function eventData(ev, cur) {
    var d = { type: ev.type };
    var t = ev.target;
    if (t && t.id) d.targetId = t.id;
    if (cur && cur.id) d.currentId = cur.id;
    if (typeof ev.key === 'string') { d.key = ev.key; d.code = ev.code; d.repeat = !!ev.repeat; }
    if (typeof ev.button === 'number') {
      d.button = ev.button; d.clientX = ev.clientX; d.clientY = ev.clientY;
    }
    if (typeof ev.deltaY === 'number') { d.deltaX = ev.deltaX; d.deltaY = ev.deltaY; }
    d.altKey = !!ev.altKey; d.ctrlKey = !!ev.ctrlKey; d.shiftKey = !!ev.shiftKey; d.metaKey = !!ev.metaKey;
    var f = isField(cur) ? cur : (isField(t) ? t : null);
    if (f) {
      d.value = f.value;
      if (f.type === 'checkbox' || f.type === 'radio') d.checked = f.checked;
      if (f.id) d.valueId = f.id;
    }
    if (cur && cur.dataset) {
      var ds = {};
      for (var k in cur.dataset) ds[k] = cur.dataset[k];
      d.dataset = ds;
    }
    if (ev.type === 'submit' && cur && cur.tagName === 'FORM') d.form = formData(cur);
    if (typeof CustomEvent !== 'undefined' && ev instanceof CustomEvent) d.detail = toJson(ev.detail);
    return d;
  }

  function formData(form) {
    var out = {};
    new FormData(form).forEach(function (v, k) {
      if (typeof v !== 'string') v = v.name;
      if (Object.prototype.hasOwnProperty.call(out, k)) {
        if (!Array.isArray(out[k])) out[k] = [out[k]];
        out[k].push(v);
      } else {
        out[k] = v;
      }
    });
    return out;
  }

  // Mirror form field values to Java so Element.getValue() is synchronous there.
  function syncField(el) {
    if (!el || !el.id || !isField(el)) return;
    var s = fieldState(el);
    s.type = 'sync';
    s.id = el.id;
    post(s);
  }

  document.addEventListener('input', function (e) { syncField(e.target); }, true);
  document.addEventListener('change', function (e) {
    var t = e.target;
    syncField(t);
    if (t && t.type === 'radio' && t.name) {
      var group = document.getElementsByName(t.name);
      for (var i = 0; i < group.length; i++) if (group[i] !== t) syncField(group[i]);
    }
  }, true);

  // Forms never navigate in a desktop app (<form method="dialog"> keeps closing its dialog).
  window.addEventListener('submit', function (e) {
    if (e.target && e.target.method === 'dialog') return;
    e.preventDefault();
  }, true);

  if (!dev) {
    document.addEventListener('keydown', function (e) {
      var k = e.key;
      if (k === 'F5' || ((e.ctrlKey || e.metaKey) && (k === 'r' || k === 'R'))) e.preventDefault();
    }, true);
  }

  window.addEventListener('error', function (e) {
    post({ type: 'log', level: 'error', message: e.message,
           source: (e.filename || '') + ':' + (e.lineno || 0),
           stack: e.error && e.error.stack ? String(e.error.stack) : null });
  });
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    post({ type: 'log', level: 'error', message: 'Unhandled rejection: ' + errorText(r),
           stack: r && r.stack ? String(r.stack) : null });
  });

  window.addEventListener('pagehide', function () {
    if (closing || !navigator.sendBeacon) return;
    navigator.sendBeacon(base + 'send', JSON.stringify({ page: pageId, seq: -1, messages: [{ type: 'unload' }] }));
  });

  // ------------------------------------------------------------------ dialogs

  function showDialog(c) {
    var dlg = document.createElement('dialog');
    dlg.className = 'hj-dialog hj-dialog-' + c.kind;
    var form = document.createElement('form');
    form.method = 'dialog';
    if (c.title) {
      var h = document.createElement('div');
      h.className = 'hj-dialog-title';
      h.textContent = c.title;
      form.appendChild(h);
    }
    var msg = document.createElement('div');
    msg.className = 'hj-dialog-message';
    msg.textContent = c.message || '';
    form.appendChild(msg);
    var input = null;
    if (c.kind === 'prompt') {
      input = document.createElement('input');
      input.className = 'hj-input hj-dialog-input';
      input.value = c.value || '';
      form.appendChild(input);
    }
    var bar = document.createElement('div');
    bar.className = 'hj-dialog-buttons';
    var cancel = null;
    if (c.kind !== 'alert') {
      cancel = document.createElement('button');
      cancel.type = 'button';
      cancel.className = 'hj-button';
      cancel.textContent = c.cancelText || 'Cancel';
      bar.appendChild(cancel);
    }
    var ok = document.createElement('button');
    ok.type = 'submit';
    ok.className = 'hj-button hj-primary';
    ok.textContent = c.okText || 'OK';
    bar.appendChild(ok);
    form.appendChild(bar);
    dlg.appendChild(form);
    document.body.appendChild(dlg);

    var finished = false;
    function finish(accepted) {
      if (finished) return;
      finished = true;
      var value = null;
      if (c.kind === 'confirm') value = accepted;
      else if (c.kind === 'prompt') value = accepted ? input.value : null;
      try { if (dlg.open && dlg.close) dlg.close(); } catch (e) { /* ignore */ }
      if (dlg.parentNode) dlg.parentNode.removeChild(dlg);
      post({ type: 'result', rid: c.rid, value: value });
    }
    form.addEventListener('submit', function (e) { e.preventDefault(); finish(true); });
    if (cancel) cancel.addEventListener('click', function () { finish(false); });
    dlg.addEventListener('cancel', function (e) { e.preventDefault(); finish(c.kind === 'alert'); });
    if (typeof dlg.showModal === 'function') dlg.showModal(); else dlg.setAttribute('open', '');
    (input || ok).focus();
  }

  // ------------------------------------------------------------------ public API

  window.hanji = {
    pageId: pageId,
    call: function (name) {
      var args = Array.prototype.slice.call(arguments, 1).map(toJson);
      return new Promise(function (resolve, reject) {
        var cid = ++callSeq;
        calls.set(cid, { resolve: resolve, reject: reject });
        post({ type: 'call', cid: cid, name: name, args: args });
      });
    },
    emit: function (name, payload) {
      post({ type: 'emit', name: name, payload: payload === undefined ? null : toJson(payload) });
    },
    on: function (topic, fn) {
      var list = topics.get(topic);
      if (!list) { list = []; topics.set(topic, list); }
      list.push(fn);
      return function () {
        var i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
      };
    },
    sync: function (elementOrId) {
      syncField(typeof elementOrId === 'string' ? find(elementOrId) : elementOrId);
    }
  };

  // ------------------------------------------------------------------ start

  function snapshot() {
    var values = {};
    var fields = document.querySelectorAll('input[id],textarea[id],select[id]');
    for (var i = 0; i < fields.length; i++) values[fields[i].id] = fieldState(fields[i]);
    return values;
  }

  function init() {
    connect();
    // 'ready' must be the first message Java sees from this page
    queue.unshift({ type: 'ready', values: snapshot(), url: location.pathname, title: document.title });
    started = true;
    pump();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
