// Page-side behaviour that is simplest in JavaScript. The todo logic lives in TodoController.java.

// Java -> JS: TodoController publishes "progress" after every render.
hanji.on('progress', function (p) {
  var pct = p.total === 0 ? 0 : Math.round(p.done * 100 / p.total);
  document.getElementById('progressBar').style.width = pct + '%';
});

// JS -> Java: call a @JsCallable method and show the result.
document.getElementById('export').addEventListener('click', async function () {
  var todos = await hanji.call('exportTodos');
  document.getElementById('exportJson').textContent = JSON.stringify(todos, null, 2);
  document.getElementById('exportDialog').showModal();
});

document.addEventListener('keydown', function (e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    document.getElementById('search').focus();
  }
});
