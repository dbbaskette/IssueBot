'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
const start = source.indexOf('  function syncHarnessSelection(');
const end = source.indexOf('  // Harness picker events', start);
const code = start < 0 ? '' : source.slice(start, end);

// Minimal select DOM; production creates real options, tests read their values and flags.
function select(value, options = []) {
  return { value, options, dataset: {}, replaceChildren(...children) { this.options = children; },
    appendChild(option) { this.options.push(option); } };
}
function picker(harnessId, model, reasoning, inherited = false) {
  const catalog = [
    { id: 'codex', models: [{ id: 'gpt-6-astra', displayName: 'Astra',
      supportedReasoningLevels: ['medium', 'high', 'ultra'], defaultReasoningLevel: 'medium' }] },
    { id: 'claude', models: [{ id: 'claude-haiku-4-5', displayName: 'Haiku',
      supportedReasoningLevels: ['default'], defaultReasoningLevel: 'default' },
      { id: 'claude-opus-4-8', displayName: 'Opus', supportedReasoningLevels: ['high', 'xhigh'], defaultReasoningLevel: 'high' }] }
  ];
  const harness = select(harnessId), models = select(model), effort = select(reasoning);
  const group = { dataset: { harnessCatalog: JSON.stringify(catalog), inherit: String(inherited), inheritedModel: 'claude-opus-4-8' },
    querySelector(selector) { return { '[data-harness-select]': harness, '[data-model-select]': models,
      '[data-reasoning-select]': effort }[selector]; } };
  const context = { document: { createElement: () => ({ dataset: {} }) } };
  vm.createContext(context);
  vm.runInContext(code, context);
  return { harness, models, effort, group, sync(change) {
    assert.equal(typeof context.syncHarnessSelection, 'function', 'generic harness picker is missing');
    context.syncHarnessSelection(group, change);
  } };
}

test('switching Astra/ultra to Claude Haiku resets to its declared default', () => {
  const p = picker('codex', 'gpt-6-astra', 'ultra');
  p.sync();
  assert.equal(p.effort.value, 'ultra');
  p.harness.value = 'claude';
  p.sync('harness');
  assert.equal(p.models.value, 'claude-haiku-4-5');
  assert.equal(p.effort.value, 'default');
  assert.deepEqual(Array.from(p.models.options, o => o.value), ['claude-haiku-4-5', 'claude-opus-4-8']);
  assert.deepEqual(Array.from(p.effort.options, o => o.value), ['default']);
});

test('one Settings harness change updates all three role pairs through the real change handler', () => {
  const roles = [picker('codex', 'gpt-6-astra', 'ultra'), picker('codex', 'gpt-6-astra', 'high'),
    picker('codex', 'gpt-6-astra', 'medium')];
  const groups = roles.map(p => p.group);
  const form = { querySelectorAll: () => groups };
  groups.forEach(group => { group.closest = () => form; });
  const listeners = {};
  const context = { document: { createElement: () => ({ dataset: {} }), querySelectorAll: () => groups,
    addEventListener(name, callback) { listeners[name] = callback; } }, window: {} };
  vm.createContext(context);
  vm.runInContext(source.slice(start, source.indexOf('  // --- Sortable tables', start)), context);
  const target = roles[0].harness;
  target.value = 'claude';
  target.matches = selector => selector.includes('[data-harness-select]');
  target.closest = () => groups[0];
  target.selectedOptions = [{ textContent: 'Claude' }];
  listeners.change({ target });
  roles.forEach(role => {
    assert.equal(role.harness.value, 'claude');
    assert.equal(role.models.value, 'claude-haiku-4-5');
    assert.equal(role.effort.value, 'default');
  });
});

test('model change preserves supported reasoning and resets unsupported reasoning', () => {
  const p = picker('claude', 'claude-opus-4-8', 'xhigh');
  p.sync('model');
  assert.equal(p.effort.value, 'xhigh');
  p.models.value = 'claude-haiku-4-5';
  p.sync('model');
  assert.equal(p.effort.value, 'default');
});

test('initialization preserves an invalid submitted tuple exactly', () => {
  const p = picker('claude', '<unknown>', 'ultra');
  p.sync();
  assert.equal(p.models.value, '<unknown>');
  assert.equal(p.effort.value, 'ultra');
  assert.equal(p.models.options.at(-1).textContent, '<unknown> (unavailable)');
});

test('whole-role inheritance stays blank, selecting an explicit model chooses its default', () => {
  const p = picker('claude', '', '', true);
  p.sync();
  assert.equal(p.models.value, '');
  assert.equal(p.effort.value, '');
  p.models.value = 'claude-haiku-4-5';
  p.sync('model');
  assert.equal(p.effort.value, 'default');
  p.models.value = '';
  p.sync('model');
  assert.equal(p.effort.value, '');
});

test('repository edit restores unavailable model and reasoning without losing their values', () => {
  const select = { tagName: 'SELECT', dataset: { modelSelect: '' }, options: [],
    matches: () => true, appendChild(option) { this.options.push(option); } };
  Object.defineProperty(select, 'value', {
    get() { return this.options.find(o => o.selected)?.value || ''; },
    set(value) { this.options.forEach(o => { o.selected = o.value === value; }); }
  });
  const code = source.slice(source.indexOf('  function setValue('), source.indexOf('  function syncPlanFirstSubmission('));
  const context = { document: { getElementById: () => select, createElement: () => ({}) } };
  vm.createContext(context);
  vm.runInContext(code, context);
  context.setValue('implementation-model', '<removed-model>');
  assert.equal(select.value, '<removed-model>');
  assert.equal(select.options[0].textContent, '<removed-model>');
});
