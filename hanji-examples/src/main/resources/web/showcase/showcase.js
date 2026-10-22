// Client-side helpers of the showcase: charts (data comes from Java via window.publish) and toasts
// (called from Java via window.call("showcase.toast", ...)). Everything else is done in Java.
(function () {
  'use strict';
  var revenue = null, channels = null;
  var canvas = document.getElementById('revenueChart');

  function css(name) { return getComputedStyle(document.body).getPropertyValue(name).trim(); }

  function drawRevenue() {
    if (!revenue || !canvas || !canvas.clientWidth) return;
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth, h = canvas.clientHeight;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var cur = revenue.current, prev = revenue.previous, labels = revenue.labels;
    var max = Math.max.apply(null, cur.concat(prev)) * 1.15;
    var step = Math.pow(10, Math.floor(Math.log10(max / 4)));
    var tick = Math.ceil(max / 4 / step) * step;
    max = tick * 4;
    var left = 46, right = w - 6, top = 10, bottom = h - 26;
    var x = function (i) { return left + (right - left) * (i + 0.5) / cur.length; };
    var y = function (v) { return bottom - (bottom - top) * v / max; };

    ctx.font = '11px -apple-system, "Apple SD Gothic Neo", system-ui, sans-serif';
    ctx.textBaseline = 'middle';
    for (var g = 0; g <= 4; g++) {
      var gy = y(tick * g);
      ctx.strokeStyle = css('--line');
      ctx.lineWidth = 1;
      ctx.setLineDash(g === 0 ? [] : [3, 4]);
      ctx.beginPath(); ctx.moveTo(left, gy); ctx.lineTo(right, gy); ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = css('--faint');
      ctx.textAlign = 'right';
      ctx.fillText((tick * g).toLocaleString('ko-KR'), left - 8, gy);
    }

    // bars: current period
    var slot = (right - left) / cur.length;
    var bw = Math.min(26, slot * 0.46);
    var accent = css('--accent');
    for (var i = 0; i < cur.length; i++) {
      var bx = x(i) - bw / 2, by = y(cur[i]);
      var grad = ctx.createLinearGradient(0, by, 0, bottom);
      grad.addColorStop(0, accent);
      grad.addColorStop(1, accent + '55');
      ctx.fillStyle = grad;
      roundRect(ctx, bx, by, bw, bottom - by, 6);
      ctx.fill();
      ctx.fillStyle = css('--faint');
      ctx.textAlign = 'center';
      ctx.fillText(labels[i], x(i), h - 10);
    }

    // line: previous period
    var prevColor = css('--prev');
    ctx.strokeStyle = prevColor;
    ctx.lineWidth = 2;
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    prev.forEach(function (v, k) { if (k === 0) ctx.moveTo(x(k), y(v)); else ctx.lineTo(x(k), y(v)); });
    ctx.stroke();
    ctx.setLineDash([]);
    prev.forEach(function (v, k) {
      ctx.beginPath(); ctx.arc(x(k), y(v), 3, 0, Math.PI * 2);
      ctx.fillStyle = css('--panel'); ctx.fill();
      ctx.strokeStyle = prevColor; ctx.lineWidth = 1.5; ctx.stroke();
    });

    // highlight the latest bar with a value badge
    var last = cur.length - 1, label = cur[last].toLocaleString('ko-KR');
    ctx.font = '600 11.5px -apple-system, "Apple SD Gothic Neo", system-ui, sans-serif';
    var tw = ctx.measureText(label).width + 14, tx = Math.min(x(last) - tw / 2, right - tw), ty = y(cur[last]) - 30;
    ctx.fillStyle = css('--fg');
    roundRect(ctx, tx, ty, tw, 22, 6); ctx.fill();
    ctx.fillStyle = css('--panel');
    ctx.textAlign = 'center';
    ctx.fillText(label, tx + tw / 2, ty + 11);
  }

  function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h); ctx.lineTo(x, y + h); ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

  function drawDonut() {
    var svg = document.getElementById('donut');
    if (!channels || !svg) return;
    var total = channels.reduce(function (s, c) { return s + c.value; }, 0);
    var r = 46, c = 2 * Math.PI * r, offset = 0, gap = 2.5;
    svg.innerHTML = '';
    var ns = 'http://www.w3.org/2000/svg';
    var bg = document.createElementNS(ns, 'circle');
    bg.setAttribute('cx', 60); bg.setAttribute('cy', 60); bg.setAttribute('r', r);
    bg.setAttribute('fill', 'none'); bg.setAttribute('stroke', css('--gray-soft')); bg.setAttribute('stroke-width', 14);
    svg.appendChild(bg);
    channels.forEach(function (ch) {
      var len = c * ch.value / total;
      var arc = document.createElementNS(ns, 'circle');
      arc.setAttribute('cx', 60); arc.setAttribute('cy', 60); arc.setAttribute('r', r);
      arc.setAttribute('fill', 'none'); arc.setAttribute('stroke', ch.color); arc.setAttribute('stroke-width', 14);
      arc.setAttribute('stroke-dasharray', Math.max(0, len - gap) + ' ' + (c - Math.max(0, len - gap)));
      arc.setAttribute('stroke-dashoffset', -offset);
      svg.appendChild(arc);
      offset += len;
    });
  }

  function redraw() { drawRevenue(); drawDonut(); }

  var ICONS = {
    success: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    info: '<path d="M12 11v6M12 7.5v.01"/>',
    warn: '<path d="M12 7v6M12 16.5v.01"/>'
  };

  // Java: window.call("showcase.toast", "주문이 등록되었습니다", "success", "HJ-24872")
  function toast(text, kind, detail) {
    kind = kind || 'success';
    var box = document.getElementById('toasts');
    if (!box) return;
    var el = document.createElement('div');
    el.className = 'toast ' + kind;
    var ic = document.createElement('span');
    ic.className = 'ic';
    ic.innerHTML = '<svg viewBox="0 0 24 24">' + (ICONS[kind] || ICONS.info) + '</svg>';
    var body = document.createElement('div');
    body.textContent = text;
    if (detail) {
      var small = document.createElement('small');
      small.textContent = detail;
      body.appendChild(small);
    }
    el.appendChild(ic);
    el.appendChild(body);
    box.appendChild(el);
    setTimeout(function () {
      el.classList.add('out');
      setTimeout(function () { el.remove(); }, 220);
    }, 3200);
  }

  window.showcase = { toast: toast, redraw: redraw };
  if (window.hanji) {
    hanji.on('revenue', function (data) { revenue = data; drawRevenue(); });
    hanji.on('channels', function (data) { channels = data; drawDonut(); });
  }
  window.addEventListener('resize', drawRevenue);
  // the OS theme can change while the app runs (theme "system")
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { setTimeout(redraw, 50); });

  // ⌘K / Ctrl+K focuses the search field (the native menu has the same accelerator)
  document.addEventListener('keydown', function (e) {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      var search = document.getElementById('search');
      if (search) { e.preventDefault(); search.focus(); search.select(); }
    }
  });
})();
