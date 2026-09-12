'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');

// Execute the complete application script and dispatch its registered HTMX
// lifecycle listeners. Failure tests deliberately do not simulate a swap.
function harness(initialCount) {
  const listeners = {};
  let badge = null;
  const element = () => ({ attributes: {}, textContent: '',
    setAttribute(name, value) { this.attributes[name] = value; },
    remove() { badge = null; } });
  if (initialCount > 0) { badge = element(); badge.textContent = String(initialCount); }
  const button = { ...element(), querySelector: () => badge, appendChild: node => { badge = node; } };
  const panel = { id: 'notif-panel', querySelector: () => null };
  const addListener = (name, fn) => { (listeners[name] ||= []).push(fn); };
  const document = {
    documentElement: { getAttribute: () => null, setAttribute() {} },
    readyState: 'loading', activeElement: null,
    body: { appendChild() {}, removeChild() {}, addEventListener: addListener },
    addEventListener: addListener,
    getElementById: id => id === 'notif-bell-btn' ? button : id === 'notif-panel' ? panel : null,
    querySelector: () => null, querySelectorAll: () => [],
    createElement: element, createDocumentFragment: () => ({ appendChild() {} })
  };
  const window = { addEventListener: addListener, location: { pathname: '/notifications' } };
  vm.runInNewContext(source, { window, document, navigator: {}, localStorage: {},
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    EventSource: class {}, URL, Blob, console });
  const emit = (name, detail) => (listeners[name] || []).forEach(fn => fn({ detail }));
  return { button, panel, badge: () => badge, emit,
    swap(count) {
      panel.querySelector = () => count == null ? null : { getAttribute: () => String(count) };
      emit('htmx:afterSwap', { target: panel });
    }
  };
}

test('successful panel swaps supply the unread action badge including real zero', () => {
  const h = harness();
  h.swap(3);
  assert.equal(h.badge().textContent, '3');
  assert.equal(h.badge().attributes['aria-label'], 'Unread actions');
  h.swap(1);
  assert.equal(h.badge().textContent, '1');
  h.swap(0);
  assert.equal(h.badge(), null);
});

test('actual HTTP, network and timeout events mark retained counts unavailable without swapping', () => {
  for (const event of ['htmx:responseError', 'htmx:sendError', 'htmx:timeout']) {
    const h = harness(4);
    h.emit(event, { target: h.panel, xhr: { status: event === 'htmx:responseError' ? 500 : 0 } });
    assert.equal(h.badge().textContent, '4?');
    assert.equal(h.badge().attributes['aria-label'], 'Unread actions unavailable; last known count 4');
    assert.equal(h.button.attributes.title, 'Notification state unavailable');
    assert.equal(h.button.attributes['aria-label'], 'Notifications — unread actions unavailable');
    h.emit(event, { requestConfig: { target: h.panel } });
    assert.equal(h.badge().textContent, '4?', 'repeated errors do not duplicate the stale indicator');
  }
});

test('a failed initial or previously zero-count request shows unknown rather than apparent zero', () => {
  for (const initial of [undefined, 0]) {
    const h = harness(initial);
    h.emit('htmx:sendError', { target: h.panel });
    assert.equal(h.badge().textContent, '?');
    assert.equal(h.badge().attributes['aria-label'], 'Unread actions unavailable');
  }
});

test('unrelated request errors do not change notification state', () => {
  const h = harness(4);
  for (const event of ['htmx:responseError', 'htmx:sendError', 'htmx:timeout']) {
    h.emit(event, { target: { id: 'content' } });
    h.emit(event, {});
  }
  assert.equal(h.badge().textContent, '4');
  assert.equal(h.button.attributes.title, undefined);
});

test('a successful refresh replaces stale state with a current count or confirmed zero', () => {
  const h = harness(4);
  h.emit('htmx:responseError', { target: h.panel, xhr: { status: 500 } });
  h.swap(2);
  assert.equal(h.badge().textContent, '2');
  assert.equal(h.badge().attributes['aria-label'], 'Unread actions');
  assert.equal(h.button.attributes.title, 'Unread actions');
  assert.equal(h.button.attributes['aria-label'], 'Notifications — unread actions');
  h.emit('htmx:timeout', { target: h.panel });
  h.swap(0);
  assert.equal(h.badge(), null);
  assert.equal(h.button.attributes.title, 'Unread actions');
});

test('malformed swapped panel state also remains explicitly unavailable', () => {
  const h = harness(4);
  for (const state of [null, 'bad', '-1']) {
    h.swap(state);
    assert.equal(h.badge().textContent, '4?');
    assert.equal(h.button.attributes.title, 'Notification state unavailable');
  }
});
