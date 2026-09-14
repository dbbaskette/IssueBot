const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture() {
  const handlers = {};
  const stages = ['intake', 'plan', 'work', 'verify', 'review', 'done'];
  const panels = stages.map(stage => ({ dataset: { stageOutput: stage }, hidden: false }));
  const buttons = stages.map(stage => ({ dataset: { selectStage: stage }, setAttribute(key, value) { this[key] = value; } }));
  const progress = { dataset: { currentStage: 'work', issueRunning: 'true' } };
  const label = {};
  const follow = {};
  const terminal = {};
  const page = {
    dataset: { currentIssueId: '127' },
    querySelector: selector => ({ '#live-status': progress, '[data-stage-selection-label]': label, '[data-follow-stage]': follow }[selector]),
    querySelectorAll: selector => ({ '[data-stage-output]': panels, '[data-select-stage]': buttons, '[data-stage-terminal]': [terminal] }[selector] || [])
  };
  const document = {
    readyState: 'complete', querySelector: () => page,
    addEventListener: (name, handler) => (handlers[name] ||= []).push(handler)
  };
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/issue-stages.js', 'utf8'), {
    document, window: { location: { hash: '' }, addEventListener() {} }
  });
  const emit = (name, event = {}) => (handlers[name] || []).forEach(handler => handler(event));
  const click = (selector, element) => emit('click', { target: { closest: value => value === selector ? element : null } });
  return { panels, buttons, progress, terminal, follow, page, emit, click };
}

test('current stage follows workflow transitions and hides unrelated output', () => {
  const f = fixture();
  assert.deepEqual(f.panels.filter(p => !p.hidden).map(p => p.dataset.stageOutput), ['work']);
  assert.equal(f.terminal.hidden, false);
  f.progress.dataset.currentStage = 'review';
  f.emit('htmx:afterSwap');
  assert.deepEqual(f.panels.filter(p => !p.hidden).map(p => p.dataset.stageOutput), ['review']);
  f.progress.dataset.issueRunning = 'false';
  f.emit('htmx:afterSwap');
  assert.equal(f.terminal.hidden, true);
});

test('manual stage survives OOB replacement and follow returns to the latest stage', () => {
  const f = fixture();
  f.click('[data-select-stage]', f.buttons[1]);
  f.progress.dataset.currentStage = 'verify';
  f.panels[1] = { dataset: { stageOutput: 'plan' }, hidden: true };
  f.emit('htmx:oobAfterSwap');
  assert.equal(f.panels[1].hidden, false);
  assert.equal(f.buttons[1]['aria-pressed'], 'true');
  assert.equal(f.terminal.hidden, true);
  assert.equal(f.follow.hidden, false);
  f.click('[data-follow-stage]', f.follow);
  assert.deepEqual(f.panels.filter(p => !p.hidden).map(p => p.dataset.stageOutput), ['verify']);
  assert.equal(f.follow.hidden, true);
});

test('a different issue defaults to its own current stage', () => {
  const f = fixture();
  f.click('[data-select-stage]', f.buttons[1]);
  f.page.dataset.currentIssueId = '128';
  f.progress.dataset.currentStage = 'done';
  f.emit('htmx:afterSettle');
  assert.equal(f.page.dataset.selectedStage, 'done');
});
