'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');
const code = source.slice(source.indexOf('  function syncReasoningPickers() {'),
  source.indexOf("  document.addEventListener('DOMContentLoaded', syncReasoningPickers);"));

function harness(model, effort, codexActive = true) {
  const models = { value: model };
  const select = { value: effort, dataset: { forModel: 'model' },
    options: ['', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].map(value => ({ value })) };
  Object.defineProperty(select, 'selectedOptions', { get: () => select.options.filter(o => o.value === select.value) });
  const group = { dataset: { codexActive: String(codexActive), reasoningCatalog: JSON.stringify([
    { id: 'gpt-6-astra', supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'] },
    { id: 'gpt-5.5', supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh'] }
  ]) }, querySelector: () => select };
  const context = { document: { querySelectorAll: () => [group], getElementById: () => models } };
  vm.createContext(context);
  vm.runInContext(code, context);
  return { select, group, models, sync: () => context.syncReasoningPickers() };
}

test('Astra supports ultra; changing model clears an unsupported effort', () => {
  const h = harness('gpt-6-astra', 'ultra');
  h.sync();
  assert.equal(h.select.value, 'ultra');
  h.models.value = 'gpt-5.5';
  h.sync();
  assert.equal(h.select.value, '');
  assert.equal(h.select.options.find(o => o.value === 'ultra').disabled, true);
});
test('stage provider overrides the global provider and hides Claude reasoning', () => {
  const h = harness('CODEX:gpt-6-astra', 'max', false);
  h.sync();
  assert.equal(h.group.hidden, false);
  h.models.value = 'CLAUDE_CODE:claude-sonnet';
  h.sync();
  assert.equal(h.group.hidden, true);
  assert.equal(h.select.disabled, true);
});
test('blank model keeps inherited reasoning selectable for Codex', () => {
  const h = harness('', 'high');
  h.sync();
  assert.equal(h.select.value, 'high');
  assert.equal(h.select.disabled, false);
});
