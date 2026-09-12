'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const createNavigation = require('../../main/resources/static/js/navigation-context.js');

class EventTarget {
  constructor() { this.listeners = Object.create(null); }
  addEventListener(name, listener, options) {
    (this.listeners[name] ||= []).push({ listener, capture: options === true || !!(options && options.capture) });
  }
  emit(name, detail = {}, target = this) {
    const event = { type: name, detail, target, key: detail.key, preventDefault() {}, stopPropagation() {} };
    (this.listeners[name] || []).forEach(entry => entry.listener(event));
    return event;
  }
}

class Element {
  constructor(tagName, attrs = {}, text = '') {
    this.tagName = tagName.toUpperCase();
    this.nodeName = this.tagName;
    this.attributes = Object.create(null);
    this.children = [];
    this.parentNode = null;
    this.parentElement = null;
    this.textContent = text;
    this.hidden = false;
    this.disabled = false;
    this.open = false;
    this.value = '';
    Object.entries(attrs).forEach(([key, value]) => this.setAttribute(key, value));
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  appendChild(child) {
    child.parentNode = this;
    child.parentElement = this.tagName === '#DOCUMENT' ? null : this;
    this.children.push(child);
    return child;
  }
  remove() {
    if (!this.parentNode) return;
    this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
    this.parentElement = null;
  }
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  matches(selector) {
    if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
    const attribute = selector.match(/^\[([^\]]+)\]$/);
    if (attribute) return this.hasAttribute(attribute[1]);
    return this.tagName === selector.toUpperCase();
  }
  closest(selector) {
    for (let node = this; node && node.tagName !== '#DOCUMENT'; node = node.parentNode) {
      if (node.matches(selector)) return node;
    }
    return null;
  }
  querySelectorAll(selector) { return this.descendants().filter(node => node.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

class Document extends EventTarget {
  constructor() {
    super();
    this.tagName = '#DOCUMENT';
    this.readyState = 'complete';
    this.children = [];
    this.documentElement = this.appendChild(new Element('html'));
    this.body = this.documentElement.appendChild(new Element('body'));
  }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  matches() { return false; }
  querySelectorAll(selector) { return this.descendants().filter(node => node.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

function storage(initial, denied = false) {
  const values = Object.assign(Object.create(null), initial || {});
  return {
    getItem(key) { if (denied) throw new Error('denied'); return values[key] ?? null; },
    setItem(key, value) { if (denied) throw new Error('denied'); values[key] = String(value); },
    removeItem(key) { if (denied) throw new Error('denied'); delete values[key]; },
    value(key) { return values[key]; }
  };
}

function harness({ url = '/issues', initialStorage, denied = false, initialTime = 0 } = {}) {
  const document = new Document();
  const sessionStorage = storage(initialStorage, denied);
  let time = initialTime;
  let tokenSeed = 0;
  const scrollCalls = [];
  const root = {
    document,
    sessionStorage,
    URL,
    Uint8Array,
    Date: { now: () => time },
    crypto: {
      getRandomValues(bytes) {
        tokenSeed++;
        bytes.fill(0);
        bytes[bytes.length - 1] = tokenSeed;
        return bytes;
      }
    },
    scrollY: 0,
    pageYOffset: 0,
    scrollTo(x, y) { scrollCalls.push([x, y]); }
  };
  function setLocation(relative) {
    const parsed = new URL(relative, 'http://issuebot.test');
    root.location = {
      href: parsed.href, origin: parsed.origin, pathname: parsed.pathname,
      search: parsed.search, hash: parsed.hash
    };
  }
  setLocation(url);
  const api = createNavigation(root);
  return {
    api, root, document, sessionStorage, scrollCalls, setLocation,
    setTime(value) { time = value; },
    emit(name, detail, target) { return document.emit(name, detail, target); }
  };
}

function list(document, ids) {
  const result = document.body.appendChild(new Element('section', { 'data-navigation-list': '' }));
  ids.forEach(id => result.appendChild(new Element('a', {
    href: `/issues/${id}`, 'hx-get': `/issues/${id}`, 'data-navigation-issue': String(id)
  })));
  return result;
}

function detail(document, id, error = false) {
  const attrs = error ? { 'data-navigation-error': '' } : {
    'data-navigation-detail': '', 'data-current-issue-id': String(id)
  };
  const root = document.body.appendChild(new Element('section', attrs));
  root.appendChild(new Element('a', { href: '/issues', 'hx-get': '/issues', 'data-navigation-return': '' }, 'Back to queue'));
  const sequence = root.appendChild(new Element('nav', { 'data-navigation-sequence': '' }));
  sequence.hidden = true;
  const sequenceLabel = sequence.appendChild(new Element('span', { 'data-navigation-sequence-label': '' }, 'In this result set'));
  const previous = sequence.appendChild(new Element('a', { href: '/issues', 'hx-get': '/issues', 'data-navigation-previous': '' }));
  previous.hidden = true;
  const previousDisabled = sequence.appendChild(new Element('button', { 'data-navigation-previous-disabled': '' }));
  previousDisabled.hidden = true;
  const next = sequence.appendChild(new Element('a', { href: '/issues', 'hx-get': '/issues', 'data-navigation-next': '' }));
  next.hidden = true;
  const nextDisabled = sequence.appendChild(new Element('button', { 'data-navigation-next-disabled': '' }));
  nextDisabled.hidden = true;
  return { root, back: root.querySelector('[data-navigation-return]'), sequence, sequenceLabel,
    previous, previousDisabled, next, nextDisabled };
}

test('accepts only same-origin allowlisted list routes and actual issue query fields', () => {
  const h = harness();
  assert.equal(h.api.returnTargetFor('/'), '/');
  assert.equal(h.api.returnTargetFor('/inbox'), '/inbox');
  assert.equal(h.api.returnTargetFor('/issues?status=FAILED&repoId=7&q=build%20failure&page=2'),
    '/issues?status=FAILED&repoId=7&q=build%20failure&page=2');

  for (const invalid of [
    '//evil.example', 'https://evil.example/issues', '/issues/../settings',
    '/issues/%2e%2e/settings', '/issues?returnTo=%2Fsettings', '/?q=secret',
    '/inbox?page=2', '/issues?status=NOT_A_STATUS', '/issues?repoId=-1',
    '/issues?page=-1', '/issues?page=Infinity', '/issues?page=1&page=2'
  ]) {
    assert.equal(h.api.returnTargetFor(invalid), '/issues', invalid);
  }
});

test('captures source, scroll, and rendered order; a live poll cannot replace the original sequence', () => {
  const h = harness({ url: '/issues?status=FAILED&q=timeout&page=1' });
  h.root.scrollY = 640;
  const result = list(h.document, [12, 19, 12]);

  const context = h.api.captureList(result);
  assert.deepEqual(context.issueIds, [12, 19]);
  assert.equal(context.source, '/issues?status=FAILED&q=timeout&page=1');
  assert.equal(context.scrollY, 640);

  result.children = [];
  result.appendChild(new Element('a', { href: '/issues/19', 'data-navigation-issue': '19' }));
  result.appendChild(new Element('a', { href: '/issues/12', 'data-navigation-issue': '12' }));
  h.root.scrollY = 800;
  const sequenceAfterLivePoll = h.api.captureList(result);
  assert.deepEqual(sequenceAfterLivePoll.issueIds, [12, 19]);
  assert.equal(sequenceAfterLivePoll.scrollY, 800);
  assert.equal(sequenceAfterLivePoll.createdAt, 0, 'updates do not slide fixed expiry');
});

test('bounds snapshots to 20 and each visible sequence to 500 IDs', () => {
  const h = harness();
  let firstToken;
  for (let visit = 0; visit < 21; visit++) {
    const result = list(h.document, Array.from({ length: 510 }, (_, index) => index + 1));
    const context = h.api.captureList(result);
    if (visit === 0) firstToken = context.token;
    assert.equal(context.issueIds.length, 500);
    result.remove();
  }
  const stored = JSON.parse(h.sessionStorage.value('issuebot.navigation-context.v1'));
  assert.equal(stored.contexts.length, 20);
  assert.equal(h.api.contextForToken(firstToken), null, 'oldest snapshot is evicted');
});

test('context expires at exactly 30 minutes without a sliding refresh', () => {
  const h = harness();
  const context = h.api.captureList(list(h.document, [12, 19]));
  h.setTime(30 * 60 * 1000 - 1);
  assert.ok(h.api.contextForToken(context.token));
  h.setTime(30 * 60 * 1000);
  assert.equal(h.api.contextForToken(context.token), null);
});

test('deliberate detail navigation decorates only the issue URL with the context token', () => {
  const h = harness({ url: '/inbox' });
  const result = list(h.document, [12, 19]);
  const marker = result.children[1];
  h.emit('click', {}, marker);
  const href = marker.getAttribute('href');
  assert.match(href, /^\/issues\/19\?nav=[a-f0-9]{32}$/);
  assert.equal(marker.getAttribute('hx-get'), href);
  const stored = JSON.parse(h.sessionStorage.value('issuebot.navigation-context.v1')).contexts[0];
  assert.deepEqual(Object.keys(stored).sort(), ['createdAt', 'issueIds', 'scrollY', 'source', 'token', 'version']);
  assert.equal(stored.source, '/inbox');
});

test('HTMX config hook replaces its initialized request path with the bounded detail URL', () => {
  const h = harness({ url: '/issues?status=FAILED' });
  const result = list(h.document, [12, 19]);
  const marker = result.children[1];
  const initializedPath = marker.getAttribute('hx-get');

  // This is the order used by HTMX: its listener has already captured the
  // initialized path when our capture-phase click decorates the DOM.
  h.emit('click', {}, marker);
  const request = { elt: marker, path: initializedPath, headers: {}, parameters: {} };
  h.emit('htmx:configRequest', request, marker);

  assert.match(request.path, /^\/issues\/19\?nav=[a-f0-9]{32}$/);
  assert.equal(request.path, marker.getAttribute('hx-get'));
  assert.notEqual(request.path, initializedPath);
});

test('HTMX config hook does not decorate a mismatched or cross-origin request path', () => {
  const h = harness();
  const marker = list(h.document, [12]).children[0];

  const mismatch = { elt: marker, path: '/issues/99', headers: {}, parameters: {} };
  h.emit('htmx:configRequest', mismatch, marker);
  assert.equal(mismatch.path, '/issues/99');

  const crossOrigin = { elt: marker, path: 'https://evil.example/issues/12', headers: {}, parameters: {} };
  h.emit('htmx:configRequest', crossOrigin, marker);
  assert.equal(crossOrigin.path, 'https://evil.example/issues/12');
  assert.equal(h.sessionStorage.value('issuebot.navigation-context.v1'), undefined);
});

test('row controls do not create a snapshot when they do not navigate to detail', () => {
  const h = harness();
  const result = h.document.body.appendChild(new Element('section', { 'data-navigation-list': '' }));
  const row = result.appendChild(new Element('tr', {
    'data-navigation-issue': '12', 'data-issue-href': '/issues/12', 'hx-get': '/issues/12'
  }));
  const checkbox = row.appendChild(new Element('input'));

  h.emit('click', {}, checkbox);

  assert.equal(h.sessionStorage.value('issuebot.navigation-context.v1'), undefined);
  assert.equal(row.getAttribute('data-issue-href'), '/issues/12');
});

test('identity-only cards contribute to order without turning blank card clicks into navigation', () => {
  const h = harness({ url: '/inbox' });
  const result = h.document.body.appendChild(new Element('section', { 'data-navigation-list': '' }));
  const identityOnly = result.appendChild(new Element('article', { 'data-navigation-issue': '12' }));
  const link = result.appendChild(new Element('a', {
    href: '/issues/19', 'data-navigation-issue': '19'
  }));

  h.emit('click', {}, identityOnly);
  assert.equal(h.sessionStorage.value('issuebot.navigation-context.v1'), undefined);

  h.emit('click', {}, link);
  const context = h.api.contextForToken(result.getAttribute('data-navigation-token'));
  assert.deepEqual(context.issueIds, [12, 19]);
});

test('detail previous and next retain the original token and disable result-set boundaries', () => {
  const h = harness();
  const context = h.api.captureList(list(h.document, [12, 19, 27]));
  h.document.body.children = [];
  const middle = detail(h.document, 19);
  h.setLocation(`/issues/19?nav=${context.token}`);
  h.api.decorateDetail(middle.root);

  assert.equal(middle.back.textContent, '\u2190 Back to results');
  assert.equal(middle.back.getAttribute('href'), '/issues');
  assert.equal(middle.sequence.hidden, false);
  assert.equal(middle.sequenceLabel.textContent, 'In this result set (current page)');
  assert.equal(middle.previous.getAttribute('href'), `/issues/12?nav=${context.token}`);
  assert.equal(middle.next.getAttribute('href'), `/issues/27?nav=${context.token}`);

  middle.root.remove();
  const first = detail(h.document, 12);
  h.setLocation(`/issues/12?nav=${context.token}`);
  h.api.decorateDetail(first.root);
  assert.equal(first.previous.hidden, true);
  assert.equal(first.previousDisabled.hidden, false);
  assert.equal(first.next.hidden, false);
  assert.equal(first.nextDisabled.hidden, true);
});

test('missing issue keeps recoverable result context while direct, expired, and spoofed links fall back', () => {
  const h = harness({ url: '/issues?status=FAILED' });
  const context = h.api.captureList(list(h.document, [12, 19]));
  h.document.body.children = [];
  const missing = detail(h.document, 19, true);
  h.setLocation(`/issues/19?nav=${context.token}`);
  h.api.decorateDetail(missing.root);
  assert.equal(missing.back.textContent, 'Back to results');
  assert.equal(missing.back.getAttribute('href'), '/issues?status=FAILED');
  assert.equal(missing.previous.getAttribute('href'), `/issues/12?nav=${context.token}`);

  missing.root.remove();
  const direct = detail(h.document, 99);
  h.setLocation('/issues/99');
  h.api.decorateDetail(direct.root);
  assert.equal(direct.back.getAttribute('href'), '/issues');
  assert.equal(direct.sequence.hidden, true);

  h.setLocation(`/issues/99?nav=${context.token}`);
  h.api.decorateDetail(direct.root);
  assert.equal(direct.back.getAttribute('href'), '/issues', 'a token cannot be replayed for an issue outside its result set');
});

test('generic error pages retain their server-selected recovery target', () => {
  const h = harness({ url: '/settings' });
  const error = detail(h.document, 1, true);
  error.back.setAttribute('href', '/');
  error.back.textContent = 'Back to Dashboard';

  h.api.decorateDetail(error.root);

  assert.equal(error.back.getAttribute('href'), '/');
  assert.equal(error.back.textContent, 'Back to Dashboard');
  assert.equal(error.sequence.hidden, true);
});

test('Back to results restores query first and scroll only after HTMX settles', () => {
  const h = harness({ url: '/issues?status=FAILED&repoId=7&q=timeout&page=2' });
  h.root.scrollY = 733;
  const result = list(h.document, [12, 19]);
  const context = h.api.captureList(result);
  result.remove();
  const issue = detail(h.document, 19);
  h.setLocation(`/issues/19?nav=${context.token}`);
  h.api.decorateDetail(issue.root);

  h.emit('click', {}, issue.back);
  assert.deepEqual(h.scrollCalls, []);
  issue.root.remove();
  const restored = list(h.document, [12, 19]);
  h.setLocation('/issues?status=FAILED&repoId=7&q=timeout&page=2');
  h.emit('htmx:afterSwap', { target: restored }, restored);
  assert.deepEqual(h.scrollCalls, [], 'afterSwap is too early');
  h.emit('htmx:afterSettle', { target: restored }, restored);
  assert.deepEqual(h.scrollCalls, [[0, 733]]);
});

test('native history restoration does not force scroll or disturb drafts and disclosures', () => {
  const h = harness();
  const context = h.api.captureList(list(h.document, [12]));
  h.document.body.children = [];
  const issue = detail(h.document, 12);
  const disclosure = issue.root.appendChild(new Element('details', { 'data-ui-state-key': 'issue:12:history' }));
  disclosure.open = true;
  const draft = issue.root.appendChild(new Element('textarea'));
  draft.value = 'operator draft';
  h.setLocation(`/issues/12?nav=${context.token}`);

  h.emit('htmx:historyRestore', {}, h.document);
  assert.deepEqual(h.scrollCalls, []);
  assert.equal(disclosure.open, true);
  assert.equal(draft.value, 'operator draft');
});

test('two separate list visits get independent snapshots and denied storage stays functional in memory', () => {
  const h = harness({ denied: true, url: '/issues?status=FAILED' });
  const firstList = list(h.document, [12, 19]);
  const first = h.api.captureList(firstList);
  firstList.remove();
  h.setLocation('/inbox');
  const second = h.api.captureList(list(h.document, [27, 31]));

  assert.notEqual(first.token, second.token);
  assert.deepEqual(h.api.contextForToken(first.token).issueIds, [12, 19]);
  assert.deepEqual(h.api.contextForToken(second.token).issueIds, [27, 31]);
});

test('malformed, secret-bearing, negative-scroll, and oversize storage is rejected wholesale', () => {
  const token = 'a'.repeat(32);
  const base = { version: 1, token, createdAt: 0, source: '/issues', scrollY: 0, issueIds: [12] };
  for (const context of [
    { ...base, source: '//evil.example' },
    { ...base, scrollY: -1 },
    { ...base, issueIds: [0] },
    { ...base, secret: 'must not be retained' }
  ]) {
    const raw = JSON.stringify({ version: 1, contexts: [context] });
    const h = harness({ initialStorage: { 'issuebot.navigation-context.v1': raw } });
    assert.equal(h.api.contextForToken(token), null);
    assert.equal(h.sessionStorage.value('issuebot.navigation-context.v1'), undefined);
  }

  const oversize = harness({ initialStorage: { 'issuebot.navigation-context.v1': 'x'.repeat(256 * 1024 + 1) } });
  assert.equal(oversize.sessionStorage.value('issuebot.navigation-context.v1'), undefined);
});

test('expired snapshots are pruned without discarding a separate valid snapshot', () => {
  const currentTime = 30 * 60 * 1000;
  const expired = {
    version: 1, token: 'a'.repeat(32), createdAt: 0,
    source: '/issues', scrollY: 0, issueIds: [12]
  };
  const valid = {
    version: 1, token: 'b'.repeat(32), createdAt: currentTime - 1,
    source: '/inbox', scrollY: 10, issueIds: [19]
  };
  const raw = JSON.stringify({ version: 1, contexts: [expired, valid] });
  const h = harness({
    initialTime: currentTime,
    initialStorage: { 'issuebot.navigation-context.v1': raw }
  });

  assert.equal(h.api.contextForToken(expired.token), null);
  assert.deepEqual(h.api.contextForToken(valid.token).issueIds, [19]);
  assert.equal(JSON.parse(h.sessionStorage.value('issuebot.navigation-context.v1')).contexts.length, 1);
});
