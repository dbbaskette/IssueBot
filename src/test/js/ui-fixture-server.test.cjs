'use strict';

const { after, before, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const { createFixtureServer } = require('../../../scripts/ui-fixture-server.cjs');

const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'issuebot-fixture-server-'));
fs.mkdirSync(path.join(fixtureRoot, 'css'));
fs.writeFileSync(path.join(fixtureRoot, 'dashboard.html'), '<link href="/css/style.css?v=old"><main>dashboard</main>');
fs.writeFileSync(path.join(fixtureRoot, 'dashboard.fragment.html'), '<main id="content">dashboard fragment</main>');
fs.writeFileSync(path.join(fixtureRoot, 'dashboard.live.html'), '<section id="dashboard-live">updated</section>');
fs.writeFileSync(path.join(fixtureRoot, 'dashboard.empty.html'), '<main>empty dashboard</main>');
fs.writeFileSync(path.join(fixtureRoot, 'css', 'style.css'), 'body { color: green; }');
fs.writeFileSync(path.join(fixtureRoot, 'fixture-manifest.json'), JSON.stringify({
  routes: {
    '/': { full: 'dashboard.html', fragment: 'dashboard.fragment.html' },
    '/dashboard?fixture=empty': { full: 'dashboard.empty.html' },
    '/fixtures/dashboard-empty': { full: 'dashboard.empty.html' },
    '/dashboard/live': { fragment: 'dashboard.live.html' }
  }
}));

let server;
let baseUrl;

function rawRequest(requestPath) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      host: '127.0.0.1', port: server.address().port, method: 'GET', path: requestPath
    }, response => {
      response.resume();
      response.once('end', () => resolve(response));
    });
    request.once('error', reject);
    request.end();
  });
}

before(async () => {
  server = createFixtureServer({ root: fixtureRoot, host: '127.0.0.1', port: 0 });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise(resolve => server.close(resolve));
  fs.rmSync(fixtureRoot, { recursive: true, force: true });
});

test('serves full and HX responses for the same application route without caching', async () => {
  const full = await fetch(`${baseUrl}/`);
  assert.equal(full.status, 200);
  assert.match(await full.text(), /<main>dashboard<\/main>/);
  assert.equal(full.headers.get('cache-control'), 'no-store, max-age=0');

  const fragment = await fetch(`${baseUrl}/`, { headers: { 'HX-Request': 'true' } });
  assert.equal(fragment.status, 200);
  assert.equal(await fragment.text(), '<main id="content">dashboard fragment</main>');

  const live = await fetch(`${baseUrl}/dashboard/live`);
  assert.equal(live.status, 200);
  assert.match(await live.text(), /dashboard-live/);
});

test('selects an explicitly exported query-string fixture without making arbitrary queries routes', async () => {
  const fixture = await fetch(`${baseUrl}/dashboard?fixture=empty`);
  assert.equal(fixture.status, 200);
  assert.match(await fixture.text(), /empty dashboard/);

  const unknownQuery = await fetch(`${baseUrl}/dashboard?fixture=other`);
  assert.equal(unknownQuery.status, 404);

  const alias = await fetch(`${baseUrl}/fixtures/dashboard-empty`);
  assert.equal(alias.status, 200);
  assert.match(await alias.text(), /empty dashboard/);
});

test('cache-busts local asset references and serves assets with no-store headers', async () => {
  const page = await (await fetch(`${baseUrl}/`)).text();
  assert.match(page, /\/css\/style\.css\?v=[a-f0-9]+/);
  assert.doesNotMatch(page, /v=old/);

  const asset = await fetch(`${baseUrl}/css/style.css?v=anything`);
  assert.equal(asset.status, 200);
  assert.equal(asset.headers.get('content-type'), 'text/css; charset=utf-8');
  assert.equal(asset.headers.get('cache-control'), 'no-store, max-age=0');
});

test('rejects mutation methods, traversal attempts, and routes absent from the manifest', async () => {
  const mutation = await fetch(`${baseUrl}/approvals/1/approve`, { method: 'POST' });
  assert.equal(mutation.status, 405);
  assert.equal(mutation.headers.get('allow'), 'GET, HEAD');

  const traversal = await rawRequest('/%2e%2e/pom.xml');
  assert.equal(traversal.statusCode, 400);

  const missing = await fetch(`${baseUrl}/not-exported`);
  assert.equal(missing.status, 404);
});

test('rejects literal dot segments before resolving allowlisted static paths', async () => {
  const traversal = await rawRequest('/css/../fixture-manifest.json');
  assert.equal(traversal.statusCode, 400);
});

test('HEAD returns the GET metadata without a response body', async () => {
  const response = await fetch(`${baseUrl}/dashboard/live`, { method: 'HEAD' });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.equal(await response.text(), '');
});
