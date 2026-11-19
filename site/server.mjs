// The Weave website: a handful of static pages under /weave, the two install scripts, and
// /weave/apk, which sends a phone to the newest release's APK. No dependencies.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), 'public');
const port = Number(process.env.PORT || 8080);
const base = '/weave';
const releases = 'https://github.com/T3lluz/Weave/releases/latest';
const types = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml',
  '.webp': 'image/webp', '.png': 'image/png', '.sh': 'text/plain; charset=utf-8', '.ps1': 'text/plain; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
};
// pages by their address, without the .html
const pages = { '/': 'index.html', '/pair': 'pair.html' };

let apk = { url: releases, at: 0 };
// The newest release's APK, asked of GitHub at most every ten minutes. Its releases page when
// GitHub won't say: a person can still find the file there.
async function newestApk() {
  if (Date.now() - apk.at < 600_000) return apk.url;
  try {
    const reply = await fetch('https://api.github.com/repos/T3lluz/Weave/releases/latest', {
      headers: { 'User-Agent': 'weave-site', Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(6000),
    });
    const found = reply.ok ? (await reply.json()).assets?.find((a) => a.name.endsWith('.apk')) : null;
    apk = { url: found?.browser_download_url || releases, at: Date.now() };
  } catch {
    apk = { url: releases, at: Date.now() - 540_000 };   // try again in a minute
  }
  return apk.url;
}

function send(res, status, headers, body) {
  res.writeHead(status, { 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', ...headers });
  res.end(body);
}

async function answer(req, res) {
  let url;
  try {
    url = new URL(req.url, 'http://x');
  } catch {
    return send(res, 400, { 'Content-Type': 'text/plain' }, 'Bad request');
  }
  let path = url.pathname;
  // any case leads here (/Weave, /WEAVE); the pages live at the lower-case one
  if (path.toLowerCase().startsWith(base) && !path.startsWith(base)) {
    return send(res, 302, { Location: base + path.slice(base.length) + url.search });
  }
  if (path === base) return send(res, 302, { Location: base + '/' });
  if (!path.startsWith(base + '/')) return send(res, 404, { 'Content-Type': 'text/plain' }, 'Not found');
  path = path.slice(base.length);
  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, { Allow: 'GET, HEAD' });
  if (path === '/api/health') return send(res, 200, { 'Content-Type': 'application/json' }, '{"ok":true}');
  if (path === '/apk') return send(res, 302, { Location: await newestApk(), 'Cache-Control': 'no-store' });
  const file = pages[path] || normalize(path).replace(/^[/\\]+/, '');
  if (file.includes('..')) return send(res, 404, { 'Content-Type': 'text/plain' }, 'Not found');
  try {
    const body = await readFile(join(root, file));
    const html = file.endsWith('.html');
    send(res, 200, {
      'Content-Type': types[extname(file)] || 'application/octet-stream',
      // pages and scripts are always the newest; pictures may be kept for a day
      'Cache-Control': html || /\.(sh|ps1)$/.test(file) ? 'no-cache' : 'public, max-age=86400',
      ...(html ? { 'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'" } : {}),
    }, req.method === 'HEAD' ? undefined : body);
  } catch {
    send(res, 404, { 'Content-Type': 'text/plain' }, 'Not found');
  }
}

// Whatever a request does, the server stays up: one that throws gets a 500 and the next is served.
createServer((req, res) => {
  answer(req, res).catch(() => {
    try {
      if (res.headersSent) res.destroy();
      else send(res, 500, { 'Content-Type': 'text/plain' }, 'Something went wrong');
    } catch {
      res.destroy();
    }
  });
}).listen(port, () => console.log(`weave site on :${port}${base}`));
