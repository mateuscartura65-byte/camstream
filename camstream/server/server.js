const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const TOKEN = process.env.TOKEN || '';

const devices = new Map();

const ensure = (id) => {
  if (!devices.has(id)) {
    devices.set(id, { last: null, at: 0, viewers: new Set(), ws: null });
  }
  return devices.get(id);
};

const isOnline = (d) => d && Date.now() - d.at < 15000;
const authOk = (u) => !TOKEN || u.searchParams.get('token') === TOKEN;

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;

  if (p === '/') return dashboard(res);
  if (p === '/api/devices') return apiDevices(res);

  let m = p.match(/^\/stream\/([\w.-]+)$/);
  if (m) return stream(m[1], req, res, u);

  m = p.match(/^\/frame\/([\w.-]+)$/);
  if (m) return frame(m[1], res);

  res.writeHead(404);
  res.end('not found');
});

function stream(id, req, res, u) {
  if (!authOk(u)) {
    res.writeHead(401);
    return res.end('token invalido');
  }
  const d = ensure(id);
  res.writeHead(200, {
    'Content-Type': 'multipart/x-mixed-replace; boundary=--frameboundary',
    'Cache-Control': 'no-cache',
    'Connection': 'close'
  });
  d.viewers.add(res);
  req.on('close', () => d.viewers.delete(res));
}

function frame(id, res) {
  const d = devices.get(id);
  if (!d || !d.last) {
    res.writeHead(404);
    return res.end();
  }
  res.writeHead(200, {
    'Content-Type': 'image/jpeg',
    'Cache-Control': 'no-cache'
  });
  res.end(d.last);
}

function apiDevices(res) {
  const out = [...devices.entries()].map(([id, d]) => ({
    id,
    online: isOnline(d),
    at: d.at
  }));
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(out));
}

function dashboard(res) {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(`<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>CamStream</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{font-family:system-ui,sans-serif;background:#0f0f0f;color:#eee;margin:0;padding:24px}
  h1{font-size:20px;margin:0 0 4px}
  .sub{color:#888;font-size:13px;margin-bottom:20px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:16px}
  .card{background:#1a1a1a;border:1px solid #2e2e2e;border-radius:10px;overflow:hidden}
  .card img{width:100%;display:block;background:#000;aspect-ratio:16/9;object-fit:contain}
  .meta{padding:10px 12px;display:flex;justify-content:space-between;align-items:center;font-size:14px}
  .dot{width:9px;height:9px;border-radius:50%;display:inline-block;margin-right:7px;background:#555}
  .dot.on{background:#3fbf6f}
  a{color:#7cc4ff;text-decoration:none}
  .empty{color:#888}
</style>
</head>
<body>
<h1>CamStream</h1>
<div class="sub">painel de cameras ao vivo</div>
<div class="grid" id="grid"><p class="empty">Aguardando dispositivos...</p></div>
<script>
const known = new Set();

async function tick(){
  const list = await (await fetch('/api/devices')).json();
  const g = document.getElementById('grid');

  if (!list.length){
    g.innerHTML = '<p class="empty">Aguardando dispositivos...</p>';
    known.clear();
    return;
  }

  const emptyEl = g.querySelector('.empty');
  if (emptyEl) emptyEl.remove();

  for (const d of list){
    if (!known.has(d.id)){
      known.add(d.id);
      const c = document.createElement('div');
      c.className = 'card';
      c.dataset.id = d.id;
      c.innerHTML =
        '<img src="/stream/' + d.id + '">' +
        '<div class="meta">' +
          '<span><span class="dot"></span>' + d.id + '</span>' +
          '<a href="/stream/' + d.id + '" target="_blank">abrir</a>' +
        '</div>';
      g.appendChild(c);
    }
    const c = g.querySelector('.card[data-id="' + d.id + '"]');
    if (c) c.querySelector('.dot').className = 'dot' + (d.online ? ' on' : '');
  }

  g.querySelectorAll('.card').forEach(c => {
    if (!list.find(d => d.id === c.dataset.id)){
      c.remove();
      known.delete(c.dataset.id);
    }
  });
}

setInterval(tick, 3000);
tick();
</script>
</body>
</html>`);
}

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const u = new URL(req.url, 'http://x');
  const id = u.searchParams.get('id');

  if (!id) return ws.close(4000, 'sem id');
  if (!authOk(u)) return ws.close(4001, 'token invalido');

  const d = ensure(id);
  d.ws = ws;
  d.at = Date.now();
  console.log('[+] conectado:', id);

  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;

    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
    d.last = buf;
    d.at = Date.now();

    if (!d.viewers.size) return;

    const head = Buffer.from(
      '--frameboundary\r\n' +
      'Content-Type: image/jpeg\r\n' +
      'Content-Length: ' + buf.length + '\r\n\r\n'
    );
    const tail = Buffer.from('\r\n');

    for (const v of d.viewers) {
      try {
        v.write(head);
        v.write(buf);
        v.write(tail);
      } catch (e) {
        d.viewers.delete(v);
      }
    }
  });

  ws.on('close', () => {
    console.log('[-] saiu:', id);
    if (d.ws === ws) d.ws = null;
  });

  ws.on('error', () => {});
});

server.listen(PORT, () => {
  console.log('relay ouvindo na porta ' + PORT);
});