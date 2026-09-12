#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const LOOPBACK_HOSTS = new Set(['127.0.0.1', '::1', 'localhost']);
const CONTENT_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml']
]);

function safeFile(root, relative) {
  if (typeof relative !== 'string' || relative.length === 0 || path.isAbsolute(relative)) return null;
  const resolved = path.resolve(root, relative);
  return resolved.startsWith(`${root}${path.sep}`) ? resolved : null;
}

function loadManifest(root) {
  const manifestPath = path.join(root, 'fixture-manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (!manifest || typeof manifest.routes !== 'object' || Array.isArray(manifest.routes)) {
    throw new Error('fixture-manifest.json must contain a routes object');
  }
  for (const [route, variants] of Object.entries(manifest.routes)) {
    if (!route.startsWith('/') || !variants || typeof variants !== 'object') {
      throw new Error(`Invalid fixture route: ${route}`);
    }
    for (const relative of Object.values(variants)) {
      if (!safeFile(root, relative)) throw new Error(`Unsafe fixture path for ${route}`);
    }
  }
  return manifest;
}

function cacheBustAssets(html, root) {
  return html.replace(/((?:href|src)=['"])(\/(?:css|js)\/[^?'"#]+)(?:\?[^'"#]*)?(['"])/g,
    (match, prefix, assetUrl, suffix) => {
      const asset = safeFile(root, assetUrl.slice(1));
      if (!asset || !fs.existsSync(asset)) return match;
      const stat = fs.statSync(asset);
      const version = (stat.size + Math.trunc(stat.mtimeMs)).toString(16);
      return `${prefix}${assetUrl}?v=${version}${suffix}`;
    });
}

function responseHeaders(contentType) {
  return {
    'Cache-Control': 'no-store, max-age=0',
    'Content-Type': contentType,
    'Expires': '0',
    'Pragma': 'no-cache',
    'X-Content-Type-Options': 'nosniff'
  };
}

// Detail navigation generates a per-tab token at runtime. Strip only that bounded
// parameter on an already allowlisted detail route, never arbitrary application queries.
function fixtureRouteKey(pathname, rawSearch) {
  if (!/^\/issues\/[1-9][0-9]*$/.test(pathname)) return `${pathname}${rawSearch}`;
  const query = new URLSearchParams(rawSearch);
  const tokens = query.getAll('nav');
  if (tokens.length !== 1 || !/^[a-f0-9]{32}$/.test(tokens[0])) return `${pathname}${rawSearch}`;
  query.delete('nav');
  const remaining = query.toString();
  return `${pathname}${remaining ? `?${remaining}` : ''}`;
}

function send(res, req, status, body, contentType = 'text/plain; charset=utf-8', extra = {}) {
  const bytes = Buffer.from(body);
  res.writeHead(status, {
    ...responseHeaders(contentType),
    'Content-Length': bytes.length,
    ...extra
  });
  res.end(req.method === 'HEAD' ? undefined : bytes);
}

function createFixtureServer({ root = '/tmp/issuebot-ui-consistency', host = '127.0.0.1' } = {}) {
  if (!LOOPBACK_HOSTS.has(host)) throw new Error(`Fixture server host must be loopback, got: ${host}`);
  const fixtureRoot = path.resolve(root);
  const manifest = loadManifest(fixtureRoot);

  return http.createServer((req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      send(res, req, 405, 'Fixture server is read-only.\n', 'text/plain; charset=utf-8', {
        Allow: 'GET, HEAD'
      });
      return;
    }

    const rawTarget = req.url || '/';
    const queryIndex = rawTarget.indexOf('?');
    const rawPath = queryIndex === -1 ? rawTarget : rawTarget.slice(0, queryIndex);
    const rawSearch = queryIndex === -1 ? '' : rawTarget.slice(queryIndex);
    if (/%2e|%2f|%5c/i.test(rawPath) || rawPath.includes('\\')) {
      send(res, req, 400, 'Invalid path.\n');
      return;
    }

    let pathname;
    try {
      pathname = decodeURIComponent(rawPath);
    } catch {
      send(res, req, 400, 'Invalid path.\n');
      return;
    }
    if (pathname.split('/').some(segment => segment === '.' || segment === '..')) {
      send(res, req, 400, 'Invalid path.\n');
      return;
    }

    const route = manifest.routes[`${pathname}${rawSearch}`]
      || manifest.routes[fixtureRouteKey(pathname, rawSearch)];
    let relative;
    if (route) {
      const wantsFragment = req.headers['hx-request'] === 'true';
      relative = wantsFragment ? (route.fragment || route.full) : (route.full || route.fragment);
    } else if (/^\/(?:css|js|images)\/[A-Za-z0-9._/-]+$/.test(pathname)) {
      relative = pathname.slice(1);
    }

    const file = relative && safeFile(fixtureRoot, relative);
    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      send(res, req, 404, 'Fixture not found.\n');
      return;
    }

    const extension = path.extname(file).toLowerCase();
    const contentType = CONTENT_TYPES.get(extension) || 'application/octet-stream';
    let body = fs.readFileSync(file);
    if (extension === '.html') body = cacheBustAssets(body.toString('utf8'), fixtureRoot);
    send(res, req, 200, body, contentType);
  });
}

function parseArgs(argv) {
  const options = { root: '/tmp/issuebot-ui-consistency', host: '127.0.0.1', port: 8092 };
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    if (!['--root', '--host', '--port'].includes(flag) || argv[index + 1] == null) {
      throw new Error(`Usage: node scripts/ui-fixture-server.cjs [--root DIR] [--host 127.0.0.1] [--port 8092]`);
    }
    const value = argv[++index];
    if (flag === '--root') options.root = value;
    if (flag === '--host') options.host = value;
    if (flag === '--port') options.port = Number(value);
  }
  if (!Number.isInteger(options.port) || options.port < 0 || options.port > 65535) {
    throw new Error(`Invalid port: ${options.port}`);
  }
  return options;
}

if (require.main === module) {
  try {
    const options = parseArgs(process.argv.slice(2));
    const server = createFixtureServer(options);
    server.listen(options.port, options.host, () => {
      const address = server.address();
      process.stdout.write(`IssueBot fixtures: http://${options.host}:${address.port}\n`);
    });
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = { createFixtureServer };
