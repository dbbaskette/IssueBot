'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const createUiState = require('../../main/resources/static/js/ui-state.js');

class EventTarget {
  constructor() { this.listeners = Object.create(null); }
  addEventListener(name, listener, options) {
    (this.listeners[name] ||= []).push({ listener, capture: options === true || !!(options && options.capture) });
  }
  emit(name, detail = {}, target = this) {
    const event = {
      type: name, detail, target, relatedTarget: detail.relatedTarget || null,
      preventDefault() {}, stopPropagation() {}
    };
    (this.listeners[name] || []).forEach(entry => entry.listener(event));
    return event;
  }
}

class ClassList {
  constructor(value = '') { this.values = new Set(value.split(/\s+/).filter(Boolean)); }
  contains(value) { return this.values.has(value); }
  toggle(value, force) {
    const enabled = force === undefined ? !this.values.has(value) : !!force;
    if (enabled) this.values.add(value); else this.values.delete(value);
    return enabled;
  }
  toString() { return [...this.values].join(' '); }
}

class Element extends EventTarget {
  constructor(tagName, attrs = {}, text = '') {
    super();
    this.tagName = tagName.toUpperCase();
    this.nodeName = this.tagName;
    this.attributes = Object.create(null);
    this.children = [];
    this.parentNode = null;
    this.parentElement = null;
    this.textContent = text;
    this.style = {};
    this.open = false;
    this.removed = false;
    this.scrollCalls = 0;
    this.hovered = false;
    this.classList = new ClassList(attrs.class || '');
    Object.entries(attrs).forEach(([key, value]) => this.setAttribute(key, value));
  }
  get id() { return this.getAttribute('id') || ''; }
  get className() { return this.classList.toString(); }
  set className(value) { this.setAttribute('class', value); }
  get isConnected() {
    let node = this;
    while (node) {
      if (node.tagName === '#DOCUMENT') return true;
      node = node.parentNode;
    }
    return false;
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'class') this.classList = new ClassList(String(value));
  }
  getAttribute(name) { return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  removeAttribute(name) { delete this.attributes[name]; }
  appendChild(child) {
    child.parentNode = this;
    child.parentElement = this.tagName === '#DOCUMENT' ? null : this;
    this.children.push(child);
    return child;
  }
  removeChild(child) {
    this.children = this.children.filter(value => value !== child);
    child.parentNode = null;
    child.parentElement = null;
    child.removed = true;
  }
  remove() { if (this.parentNode) this.parentNode.removeChild(this); }
  contains(candidate) {
    for (let node = candidate; node; node = node.parentNode) if (node === this) return true;
    return false;
  }
  matches(selector) {
    if (selector === 'details[data-ui-state-key]') {
      return this.tagName === 'DETAILS' && this.hasAttribute('data-ui-state-key');
    }
    if (selector === '.toast') return this.classList.contains('toast');
    if (selector === '.toast-dismiss') return this.classList.contains('toast-dismiss');
    if (selector === ':hover') return this.hovered;
    if (selector === 'summary') return this.tagName === 'SUMMARY';
    if (selector === 'details[data-ui-state-key]') return this.tagName === 'DETAILS' && this.hasAttribute('data-ui-state-key');
    return false;
  }
  closest(selector) {
    for (let node = this; node && node.tagName !== '#DOCUMENT'; node = node.parentNode) {
      if (node.matches(selector)) return node;
    }
    return null;
  }
  allDescendants() { return this.children.flatMap(child => [child, ...child.allDescendants()]); }
  querySelectorAll(selector) {
    const all = this.allDescendants();
    if (selector === '.sidebar a[href]') {
      return all.filter(node => node.tagName === 'A' && node.hasAttribute('href') &&
        node.closestByClass('sidebar'));
    }
    return all.filter(node => node.matches(selector));
  }
  querySelector(selector) {
    if (selector === '#content h1, #content h2') {
      return this.allDescendants().find(node => (node.tagName === 'H1' || node.tagName === 'H2') &&
        node.closestById('content')) || null;
    }
    return this.querySelectorAll(selector)[0] || null;
  }
  closestByClass(name) {
    for (let node = this.parentNode; node && node.tagName !== '#DOCUMENT'; node = node.parentNode) {
      if (node.classList.contains(name)) return node;
    }
    return null;
  }
  closestById(id) {
    for (let node = this.parentNode; node && node.tagName !== '#DOCUMENT'; node = node.parentNode) {
      if (node.id === id) return node;
    }
    return null;
  }
  scrollIntoView() { this.scrollCalls++; }
}

class Document extends Element {
  constructor() {
    super('#document');
    this.readyState = 'complete';
    this.activeElement = null;
    this.documentElement = this.appendChild(new Element('html'));
    this.body = this.documentElement.appendChild(new Element('body'));
  }
  createElement(tagName) { return new Element(tagName); }
  createDocumentFragment() { return new Element('fragment'); }
  getElementById(id) { return this.allDescendants().find(node => node.id === id) || null; }
}

function detail(key, open = false, text = '') {
  const element = new Element('details', { 'data-ui-state-key': key }, text);
  element.open = open;
  element.appendChild(new Element('summary', {}, 'Summary'));
  return element;
}

function storage(initial = null, denied = false) {
  const values = Object.create(null);
  if (initial != null) values['issuebot.ui-state.v1'] = initial;
  return {
    getItem(key) { if (denied) throw new Error('denied'); return values[key] ?? null; },
    setItem(key, value) { if (denied) throw new Error('denied'); values[key] = value; },
    removeItem(key) { if (denied) throw new Error('denied'); delete values[key]; },
    value(key) { return values[key]; }
  };
}

function harness({ pathname = '/', stored = null, denied = false } = {}) {
  const document = new Document();
  const sessionStorage = storage(stored, denied);
  let currentTime = 0;
  let nextTimer = 1;
  const timers = new Map();
  const windowEvents = new EventTarget();
  const location = { pathname, hash: '', href: `http://issuebot.test${pathname}` };
  const root = {
    document, sessionStorage, location, URL,
    Date: { now: () => currentTime },
    setTimeout(fn, delay) {
      const id = nextTimer++;
      timers.set(id, { fn, at: currentTime + delay, delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    addEventListener: windowEvents.addEventListener.bind(windowEvents),
    scrollTo() { root.scrollCalls++; },
    scrollCalls: 0
  };
  function advance(ms) {
    const end = currentTime + ms;
    while (true) {
      const due = [...timers.entries()].filter(([, timer]) => timer.at <= end)
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      currentTime = due[1].at;
      timers.delete(due[0]);
      due[1].fn();
    }
    currentTime = end;
  }
  return {
    root, document, sessionStorage, timers, advance,
    api: createUiState(root),
    emit(name, detail, target) { return document.emit(name, detail, target); },
    emitWindow(name, detail, target) { return windowEvents.emit(name, detail, target); }
  };
}

test('captures and restores explicit open and closed choices with independent issue, version, and nested keys', () => {
  const h = harness({ pathname: '/issues/142' });
  const root = h.document.body.appendChild(new Element('section'));
  const version3 = root.appendChild(detail('issue:142:plan:3:evidence', true));
  const nested = version3.appendChild(detail('issue:142:plan:3:evidence:raw-json', false));
  const version4 = root.appendChild(detail('issue:142:plan:4:evidence', false));

  h.api.capture(root);
  const records = JSON.parse(h.sessionStorage.value('issuebot.ui-state.v1')).entries;
  assert.deepEqual(records.map(record => [record.key, record.open]), [
    ['/issues/142:issue:142:plan:3:evidence', true],
    ['/issues/142:issue:142:plan:3:evidence:raw-json', false],
    ['/issues/142:issue:142:plan:4:evidence', false]
  ]);

  version3.open = false;
  nested.open = true;
  version4.open = true;
  h.api.restore(root);
  assert.equal(version3.open, true);
  assert.equal(nested.open, false);
  assert.equal(version4.open, false);

  h.root.location.pathname = '/issues/143';
  version3.open = false;
  h.api.restore(version3);
  assert.equal(version3.open, false, 'a different issue pathname keeps its server default');
});

test('summary activation records the resulting state through the delegated listener', () => {
  const h = harness({ pathname: '/issues/142' });
  const disclosure = h.document.body.appendChild(detail('issue:142:goal', false));
  disclosure.open = true;
  h.emit('click', {}, disclosure.children[0]);
  h.advance(0);
  const entries = JSON.parse(h.sessionStorage.value('issuebot.ui-state.v1')).entries;
  assert.deepEqual(entries.at(-1), { key: '/issues/142:issue:142:goal', open: true });
});

test('summary capture is registered in the capture phase so inline stopPropagation cannot suppress it', () => {
  const h = harness({ pathname: '/' });
  const disclosure = h.document.body.appendChild(detail('event:91:technical', true));
  const captureListener = h.document.listeners.click.find(entry => entry.capture);
  assert.ok(captureListener, 'delegated summary listener must run before target/bubble handlers');
  captureListener.listener({ target: disclosure.children[0] });
  h.advance(0);
  const entries = JSON.parse(h.sessionStorage.value('issuebot.ui-state.v1')).entries;
  assert.deepEqual(entries.at(-1), { key: '/:event:91:technical', open: true });
});

test('malformed or denied storage degrades to memory and storage remains bounded to 500 choices', () => {
  const malformed = harness({ pathname: '/issues/1', stored: '{not json' });
  const retained = malformed.document.body.appendChild(detail('retained', true));
  malformed.api.capture(retained);
  retained.open = false;
  malformed.api.restore(retained);
  assert.equal(retained.open, true);

  const denied = harness({ pathname: '/issues/1', denied: true });
  const memoryOnly = denied.document.body.appendChild(detail('memory', false));
  denied.api.capture(memoryOnly);
  memoryOnly.open = true;
  denied.api.restore(memoryOnly);
  assert.equal(memoryOnly.open, false);

  const bounded = harness({ pathname: '/issues/1' });
  const region = bounded.document.body.appendChild(new Element('section'));
  for (let i = 0; i < 505; i++) region.appendChild(detail(`item:${i}`, i % 2 === 0));
  bounded.api.capture(region);
  const entries = JSON.parse(bounded.sessionStorage.value('issuebot.ui-state.v1')).entries;
  assert.equal(entries.length, 500);
  assert.equal(entries[0].key, '/issues/1:item:5');
  assert.equal(entries.at(-1).key, '/issues/1:item:504');
});

test('HTMX replacement, morph settle, OOB, and history hooks restore state without replacing new content', () => {
  const h = harness({ pathname: '/issues/142' });
  const original = h.document.body.appendChild(detail('issue:142:activity', true, 'old content'));
  h.api.capture(original);

  const replacement = detail('issue:142:activity', false, 'new server content');
  original.setAttribute('id', 'activity-log');
  replacement.setAttribute('id', 'activity-log');
  h.document.body.removeChild(original);
  h.document.body.appendChild(replacement);
  h.emit('htmx:afterSwap', { target: original }, original);
  assert.equal(replacement.open, true);
  assert.equal(replacement.textContent, 'new server content');

  replacement.open = false;
  h.emit('htmx:afterSettle', { target: replacement }, replacement);
  assert.equal(replacement.open, true, 'morph defaults cannot replace the explicit choice');

  const detachedOob = detail('issue:142:activity', false, 'old OOB content');
  detachedOob.setAttribute('id', 'timeline-panel');
  const oob = h.document.body.appendChild(detail('issue:142:activity', false, 'OOB content'));
  oob.setAttribute('id', 'timeline-panel');
  h.emit('htmx:oobAfterSwap', { target: detachedOob }, detachedOob);
  assert.equal(oob.open, true);
  assert.equal(oob.textContent, 'OOB content');

  replacement.open = false;
  h.emit('htmx:historyRestore', {});
  assert.equal(replacement.open, true);
});

test('generated diff files receive stable semantic keys and expand/collapse-all explicitly capture them', () => {
  const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const code = source.slice(source.indexOf('  function stableDiffIdentity(path) {'),
    source.indexOf('  // Builds the per-file view'));
  const document = new Document();
  const context = {
    document,
    buildDiffLineSpan: text => new Element('span', {}, text)
  };
  vm.createContext(context);
  vm.runInContext(code, context);
  const first = context.renderDiffFile({ path: 'src/Foo.java', binary: false, adds: 1, dels: 0, lines: [] },
    false, 'issue:142:iteration:3:diff');
  const again = context.renderDiffFile({ path: 'src/Foo.java', binary: false, adds: 2, dels: 1, lines: [] },
    true, 'issue:142:iteration:3:diff');
  const other = context.renderDiffFile({ path: 'src/Bar.java', binary: false, adds: 1, dels: 0, lines: [] },
    false, 'issue:142:iteration:3:diff');
  assert.equal(first.getAttribute('data-ui-state-key'), again.getAttribute('data-ui-state-key'));
  assert.notEqual(first.getAttribute('data-ui-state-key'), other.getAttribute('data-ui-state-key'));
  const helperCode = source.slice(source.indexOf('  function setDiffFilesOpen(container, open) {'),
    source.indexOf('  // --- Live terminal controller'));
  const files = [detail('file:a', false), detail('file:b', false)];
  let captured = null;
  const container = { querySelectorAll: () => files };
  const helperContext = { window: { IssueBotUiState: { capture: value => { captured = value; } } } };
  vm.createContext(helperContext);
  vm.runInContext(helperCode, helperContext);
  helperContext.setDiffFilesOpen(container, true);
  assert.deepEqual(files.map(file => file.open), [true, true]);
  assert.equal(captured, container);
  helperContext.setDiffFilesOpen(container, false);
  assert.deepEqual(files.map(file => file.open), [false, false]);
});

test('successful navigation updates sidebar and scrolls, while polls and history preserve scroll', () => {
  const h = harness({ pathname: '/' });
  const sidebar = h.document.body.appendChild(new Element('nav', { class: 'sidebar' }));
  const dashboard = sidebar.appendChild(new Element('a', { href: '/', class: 'active', 'aria-current': 'page' }));
  const issues = sidebar.appendChild(new Element('a', { href: '/issues' }));
  const repositories = sidebar.appendChild(new Element('a', { href: '/repositories' }));
  const content = h.document.body.appendChild(new Element('main', { id: 'content' }));
  const heading = content.appendChild(new Element('h2', {}, 'Issue Queue'));
  const poll = h.document.body.appendChild(new Element('section', { id: 'live-status' }));

  h.emit('htmx:afterSwap', { target: poll }, poll);
  assert.equal(heading.scrollCalls, 0);

  const trigger = new Element('a', { href: '/issues/142', 'hx-get': '/issues/142' });
  h.emit('htmx:beforeRequest', { target: content, elt: trigger, requestConfig: { verb: 'get', path: '/issues/142' } }, trigger);
  h.emit('htmx:afterSwap', { target: content }, content);
  assert.equal(issues.classList.contains('active'), true);
  assert.equal(issues.getAttribute('aria-current'), 'page');
  assert.equal(dashboard.hasAttribute('aria-current'), false);
  assert.equal(heading.scrollCalls, 1);

  h.root.location.pathname = '/repositories';
  h.root.location.href = 'http://issuebot.test/repositories';
  h.emit('htmx:historyRestore', {});
  assert.equal(repositories.classList.contains('active'), true);
  assert.equal(heading.scrollCalls, 1, 'history restoration does not force a scroll');
});

test('errors and warnings persist while success dismissal pauses on hover/focus and duplicate messages do not reset it', () => {
  const document = new Document();
  const error = document.body.appendChild(new Element('div', { class: 'toast toast-danger' }, 'Needs attention'));
  const warning = document.body.appendChild(new Element('div', { class: 'toast toast-warning' }, 'Still waiting'));
  const success = document.body.appendChild(new Element('div', { class: 'toast toast-ok' }, 'Saved'));
  const h = harness();
  h.root.document = document;
  h.document = document;
  h.api = createUiState(h.root);

  assert.equal(error.getAttribute('role'), 'alert');
  assert.equal(warning.getAttribute('role'), 'alert');
  assert.equal(success.getAttribute('role'), 'status');
  assert.equal(error.children.at(-1).getAttribute('aria-label'), 'Dismiss notification');
  assert.equal([...h.timers.values()].filter(timer => timer.delay === 6000).length, 1);

  h.advance(2000);
  success.emit('mouseenter');
  success.emit('focusin');
  success.emit('mouseleave');
  h.advance(10000);
  assert.equal(success.removed, false);
  assert.equal(error.removed, false);
  assert.equal(warning.removed, false);

  success.emit('focusout', { relatedTarget: null });
  h.advance(1000);
  success.emit('mouseenter');
  success.emit('focusin');
  success.emit('focusout', { relatedTarget: null });
  h.advance(10000);
  assert.equal(success.removed, false);
  success.emit('mouseleave');

  const duplicate = document.body.appendChild(new Element('div', { class: 'toast toast-ok' }, 'Saved'));
  h.api.initToasts(duplicate);
  assert.equal(duplicate.removed, true);

  h.advance(2999);
  assert.equal(success.removed, false);
  h.advance(1);
  assert.equal(success.removed, false, 'fade begins at the remaining six-second timeout');
  h.advance(400);
  assert.equal(success.removed, true);
  assert.equal(error.removed, false);
  assert.equal(warning.removed, false);
});

test('a swapped success toast keeps elapsed time and recomputes stale pause reasons', () => {
  const document = new Document();
  const original = document.body.appendChild(new Element('div', { class: 'toast toast-ok' }, 'Updated'));
  const h = harness();
  h.root.document = document;
  h.document = document;
  h.api = createUiState(h.root);

  h.advance(2000);
  original.hovered = true;
  original.emit('mouseenter');
  document.body.removeChild(original);
  const replacement = document.body.appendChild(new Element('div', { class: 'toast toast-ok' }, 'Updated'));
  h.api.initToasts(replacement);
  assert.equal([...h.timers.values()].some(timer => timer.delay === 4000), true);
  h.advance(3999);
  assert.equal(replacement.removed, false);
  h.advance(1);
  h.advance(400);
  assert.equal(replacement.removed, true);
});

// A history snapshot contains only markup: attributes/text/children survive,
// while listeners, initialization expandos and controller references do not.
function serializeMarkup(element) {
  return JSON.stringify({ tag: element.tagName, attrs: element.attributes,
    text: element.textContent, children: element.children.map(serializeMarkup) });
}

function restoreMarkup(snapshot) {
  const markup = JSON.parse(snapshot);
  const element = new Element(markup.tag, markup.attrs, markup.text);
  markup.children.forEach(child => element.appendChild(restoreMarkup(child)));
  return element;
}

test('serialized history restores exactly one working dismiss control on every toast severity', () => {
  for (const severityClass of ['toast-danger', 'toast-warning', 'toast-ok']) {
    const h = harness();
    let toast = h.document.body.appendChild(new Element('div', { class: `toast ${severityClass}` }, 'History message'));
    h.api.initToasts(toast);
    for (let restore = 0; restore < 3; restore++) {
      const snapshot = serializeMarkup(toast);
      toast.remove();
      toast = h.document.body.appendChild(restoreMarkup(snapshot));
      assert.equal(toast.__issuebotToastInit, undefined);
      assert.equal(toast.querySelector('.toast-dismiss').listeners.click, undefined);
      h.emit('htmx:historyRestore');
      h.emit('htmx:historyRestore');
      const controls = toast.querySelectorAll('.toast-dismiss');
      assert.equal(controls.length, 1);
      assert.equal(controls[0].listeners.click.length, 1);
      assert.equal(controls[0].getAttribute('aria-label'), 'Dismiss notification');
      assert.equal(toast.getAttribute('role'), severityClass === 'toast-ok' ? 'status' : 'alert');
      toast.emit('mouseenter');
      toast.emit('focusin');
      toast.emit('mouseleave');
      h.advance(10000);
      assert.equal(toast.removed, false, 'errors persist and focused success stays paused');
    }
    toast.querySelector('.toast-dismiss').emit('click');
    h.advance(400);
    assert.equal(toast.removed, true, 'the sole control works after repeated serialized restores');
  }
});
