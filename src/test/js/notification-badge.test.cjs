'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
const start = source.indexOf('  function syncNotifBadge(');
const end = source.indexOf('\n  // --- Clipboard helper', start);

function harness(initialCount) {
  let badge = initialCount == null ? null : { textContent: String(initialCount), remove() { badge = null; } };
  const button = { attributes: {}, setAttribute(name, value) { this.attributes[name] = value; },
    querySelector: () => badge, appendChild: node => { badge = node; } };
  const document = { createElement: () => ({ attributes: {}, setAttribute(name, value) { this.attributes[name] = value; },
    remove() { badge = null; } }) };
  const context = { notifBellBtn: () => button, document };
  vm.runInNewContext(source.slice(start, end), context);
  return { button, badge: () => badge, sync: count => context.syncNotifBadge({
    querySelector: () => count == null ? null : { getAttribute: () => String(count) }
  }) };
}
test('panel snapshot supplies the unread action badge including real zero', () => {
  const h = harness();
  h.sync(3);
  assert.equal(h.badge().textContent, '3');
  assert.equal(h.badge().attributes['aria-label'], 'Unread actions');
  h.sync(1);
  assert.equal(h.badge().textContent, '1');
  h.sync(0);
  assert.equal(h.badge(), null);
});
test('missing or malformed panel state does not invent a zero count', () => {
  const h = harness(4);
  for (const state of [null, 'bad', '-1']) {
    h.sync(state);
    assert.equal(h.badge().textContent, '4');
    assert.equal(h.button.attributes.title, 'Notification state unavailable');
  }
});
