'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('terminal subscribes to one issue, replays lines, and shows connection and output state', () => {
  const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const start = source.indexOf('  var IssueBotTerminal = {');
  const end = source.indexOf('  window.IssueBotTerminal = IssueBotTerminal;', start);
  assert.ok(start >= 0 && end > start);

  const listeners = new Map();
  let stream;
  function EventSource(url) {
    this.url = url;
    this.addEventListener = (type, callback) => listeners.set(type, callback);
    this.close = () => {};
    stream = this;
  }
  const context = {
    window: {}, EventSource, encodeURIComponent,
    SseStatus: { set() {}, clear() {} },
    document: { querySelector: () => null, getElementById: () => null }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end + '  window.IssueBotTerminal = IssueBotTerminal;'.length), context);
  const terminal = context.window.IssueBotTerminal;
  terminal.issueId = 42;
  const states = [];
  const lines = [];
  terminal._setConnection = state => states.push(state);
  terminal._setEmptyMessage = () => {};
  terminal._appendLine = line => lines.push(line);
  terminal._markOutput = at => states.push(at);

  terminal._openStream();
  assert.equal(stream.url, '/api/events/stream?issueId=42');
  listeners.get('open')();
  listeners.get('claude-log')({ data: JSON.stringify({ issueId: 42, text: 'Checking tests', at: '2026-09-12T16:00:00Z' }) });
  listeners.get('claude-log')({ data: JSON.stringify({ issueId: 43, text: 'Different issue' }) });
  listeners.get('error')();

  assert.deepEqual(lines, ['Checking tests']);
  assert.deepEqual(states, ['connected', '2026-09-12T16:00:00Z', 'reconnecting']);
});
