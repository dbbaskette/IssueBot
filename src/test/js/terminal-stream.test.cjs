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

test('direct page initialization starts one stream, while polls do not restart it', () => {
  const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
  const start = source.indexOf('  var IssueBotTerminal = {');
  const end = source.indexOf('  // --- Accessible modal dialogs', start);
  const terminalElement = {
    dataset: { issueId: '121' },
    addEventListener() {},
    querySelector() { return null; }
  };
  const urls = [];
  function EventSource(url) {
    urls.push(url);
    this.addEventListener = () => {};
    this.close = () => {};
  }
  const context = {
    window: {}, EventSource, encodeURIComponent, Number,
    SseStatus: { set() {}, clear() {} },
    document: {
      getElementById: id => id === 'live-terminal' ? terminalElement : null,
      querySelector: () => null
    }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end), context);
  const terminal = context.window.IssueBotTerminal;
  terminal._wireControls = () => {};
  terminal._setConnection = () => {};

  context.initLiveTerminal(); // DOM ready on a direct visit
  context.initLiveTerminal(); // an unrelated live poll
  assert.deepEqual(urls, ['/api/events/stream?issueId=121']);
  assert.match(source, /function init\(\)\s*\{[\s\S]*?initLiveTerminal\(\);/);
  assert.match(source, /target\.id === 'content'[\s\S]*?initLiveTerminal\(\);/);

  terminal.es = null; // the prior page was swapped away
  context.initLiveTerminal(); // restored issue page
  assert.equal(urls.length, 2);
});
