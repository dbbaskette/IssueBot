const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/static/js/needs-you.js', 'utf8');

function harness(pathname = '/inbox') {
  const listeners = {};
  const requests = [];
  const timers = [];
  let modal = false;
  let badgeSwaps = 0;
  let inboxSwaps = 0;
  let processed = 0;
  let initialized = 0;
  const root = { contains: target => target === root.focus, querySelectorAll: () => [],
    replaceWith: () => inboxSwaps++ };
  const badge = { replaceWith: () => badgeSwaps++ };
  const document = {
    activeElement: null,
    getElementById: id => id === 'needs-you-badge' ? badge : root,
    querySelector: () => modal ? {} : null,
    addEventListener: (name, handler) => { (listeners[name] ||= []).push(handler); }
  };
  class Events {
    constructor() { this.listeners = {}; }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    close() { this.closed = true; }
  }
  const window = {
    location: { pathname }, EventSource: Events,
    addEventListener: document.addEventListener,
    setInterval: fn => { window.poll = fn; return 1; }, clearInterval() {},
    setTimeout: (fn, delay) => { if (delay !== 10000) timers.push(fn); return 1; }, clearTimeout() {},
    fetch: url => new Promise(resolve => requests.push({ url, resolve })),
    htmx: { process() { processed++; }, trigger(target, name) {
      if (name === 'htmx:afterSwap') initialized++;
    } }
  };
  const context = { window, document, EventSource: Events, AbortController,
    DOMParser: class { parseFromString() { return { getElementById: () => ({}) }; } } };
  vm.runInNewContext(source, context);
  return {
    window, document, root, requests,
    emit: (name, detail) => (listeners[name] || []).forEach(fn => fn({ detail })),
    emitWindow: (name, event) => (listeners[name] || []).forEach(fn => fn(event)),
    refresh: () => window.__issuebotNeedsYou.events.listeners['issue-update'](),
    modal: value => { modal = value; },
    swaps: () => [badgeSwaps, inboxSwaps],
    initialized: () => [processed, initialized],
    async resolve(index = 0) {
      requests[index].resolve({ ok: true, redirected: false, text: async () => '<snapshot>' });
      await new Promise(resolve => setImmediate(resolve));
      while (timers.length) timers.shift()();
    }
  };
}

test('coalesces events and swaps badge plus inbox from one response', async () => {
  const h = harness();
  h.refresh(); h.refresh(); h.refresh();
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, '/inbox/live?includeInbox=true');
  await h.resolve();
  assert.deepEqual(h.swaps(), [1, 1]);
  assert.deepEqual(h.initialized(), [1, 1]);
  assert.equal(h.requests.length, 2);
});

test('bfcache restoration reconnects SSE and refreshes the retained page', async () => {
  const h = harness();
  const original = h.window.__issuebotNeedsYou.events;
  h.emitWindow('pagehide', { persisted: true });
  assert.equal(original.closed, true);
  h.emitWindow('pageshow', { persisted: true });
  assert.notEqual(h.window.__issuebotNeedsYou.events, original);
  assert.equal(h.requests.length, 1);
  await h.resolve();
  assert.deepEqual(h.swaps(), [1, 1]);
});

test('modal and edited focus defer both cards and badge', async () => {
  const h = harness();
  h.modal(true); h.refresh();
  assert.equal(h.requests.length, 0);
  assert.deepEqual(h.swaps(), [0, 0]);
  h.modal(false);
  h.document.activeElement = h.root.focus = { matches: () => true };
  h.refresh();
  assert.equal(h.requests.length, 0);
});

test('opening a modal while fetching retains both prior cards and badge', async () => {
  const h = harness();
  h.refresh(); h.modal(true);
  await h.resolve();
  assert.deepEqual(h.swaps(), [0, 0]);
});

test('navigation invalidates in-flight response before it can overwrite another page', async () => {
  const h = harness();
  h.refresh();
  h.emit('htmx:beforeRequest', { target: { id: 'content' } });
  h.window.location.pathname = '/issues';
  await h.resolve();
  assert.deepEqual(h.swaps(), [0, 0]);
});

test('off-inbox polling fetches only badge and GET completion does not recurse', async () => {
  const h = harness('/issues');
  h.window.poll();
  h.emit('htmx:afterRequest', { requestConfig: { verb: 'get' } });
  await h.resolve();
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, '/inbox/live?includeInbox=false');
  assert.deepEqual(h.swaps(), [1, 0]);
});
