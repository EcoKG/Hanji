// The chart and tiles are plain JavaScript; the data comes from Java (MonitorApp publishes "sample").
(function () {
  var samples = [];
  var canvas = document.getElementById('chart');
  var MB = 1024 * 1024;

  function fmtMB(bytes) { return bytes < 0 ? 'n/a' : (bytes / MB).toFixed(1) + ' MB'; }

  hanji.on('sample', function (s) {
    samples.push(s);
    if (samples.length > 60) samples.shift();
    document.getElementById('heap').textContent = fmtMB(s.heapUsed);
    document.getElementById('heapMax').textContent = fmtMB(s.heapMax);
    document.getElementById('threadCount').textContent = s.threads;
    document.getElementById('cpu').textContent = s.processCpu < 0 ? 'n/a' : (s.processCpu * 100).toFixed(1) + ' %';
    document.getElementById('gcCount').textContent = s.gcCount;
    draw();
  });

  function draw() {
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) { canvas.width = w * dpr; canvas.height = h * dpr; }
    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    var css = getComputedStyle(document.documentElement);
    var line = css.getPropertyValue('--line').trim(), accent = css.getPropertyValue('--accent').trim();
    var fill = css.getPropertyValue('--fill').trim(), muted = css.getPropertyValue('--muted').trim();
    if (samples.length === 0) return;

    var max = 0;
    samples.forEach(function (s) { max = Math.max(max, s.heapCommitted, s.heapUsed); });
    max = Math.max(MB, max * 1.1);
    var left = 56, bottom = h - 20, top = 8, right = w - 8;
    var x = function (i) { return left + (right - left) * i / 59; };
    var y = function (v) { return bottom - (bottom - top) * v / max; };

    ctx.font = '11px system-ui, sans-serif';
    ctx.fillStyle = muted;
    ctx.strokeStyle = line;
    ctx.lineWidth = 1;
    for (var g = 0; g <= 4; g++) {
      var gy = top + (bottom - top) * g / 4;
      ctx.beginPath(); ctx.moveTo(left, gy); ctx.lineTo(right, gy); ctx.stroke();
      ctx.fillText(((max * (4 - g) / 4) / MB).toFixed(0) + ' MB', 4, gy + 4);
    }

    var offset = 60 - samples.length;
    ctx.beginPath();
    samples.forEach(function (s, i) { ctx.lineTo(x(i + offset), y(s.heapUsed)); });
    ctx.lineTo(x(59), bottom); ctx.lineTo(x(offset), bottom); ctx.closePath();
    ctx.fillStyle = fill; ctx.fill();

    ctx.beginPath();
    samples.forEach(function (s, i) { var px = x(i + offset), py = y(s.heapUsed); if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py); });
    ctx.strokeStyle = accent; ctx.lineWidth = 2; ctx.stroke();

    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    samples.forEach(function (s, i) { var px = x(i + offset), py = y(s.heapCommitted); if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py); });
    ctx.strokeStyle = muted; ctx.lineWidth = 1; ctx.stroke();
    ctx.setLineDash([]);
  }
  window.addEventListener('resize', draw);

  function toast(text) { document.getElementById('toast').textContent = text; }

  document.getElementById('gc').addEventListener('click', function () {
    hanji.call('gc').then(toast);
  });
  document.getElementById('alloc').addEventListener('click', function () {
    hanji.call('allocate', 64).then(toast, function (e) { toast('실패: ' + e.message); });
  });
  document.getElementById('threads').addEventListener('click', async function () {
    var rows = await hanji.call('threads');
    var body = document.getElementById('threadRows');
    body.replaceChildren();
    rows.forEach(function (t) {
      var tr = document.createElement('tr');
      [t.id, t.name, t.state, t.daemon ? '예' : ''].forEach(function (v) {
        var td = document.createElement('td'); td.textContent = v; tr.appendChild(td);
      });
      body.appendChild(tr);
    });
    document.getElementById('threadCard').hidden = false;
  });

  hanji.call('runtimeInfo').then(function (info) {
    document.getElementById('vm').textContent = info.vm + ' · ' + info.os + ' · CPU ' + info.processors + ' · PID ' + info.pid;
  });
})();
