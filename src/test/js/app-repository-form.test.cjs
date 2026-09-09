'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const workflow = require('../../main/resources/static/js/repository-workflow.js');

const source = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');

function repositoryFormHarness() {
  const listeners = {};
  const elements = {};
  const add = {
    closest: selector => selector === '[data-show-add-form]' ? add : null,
    matches: () => false,
    classList: { contains: () => false },
    getAttribute: () => null
  };
  const values = [
    ['edit-id'], ['owner'], ['repo-name'], ['branch'], ['max-iterations'],
    ['max-review-iterations'], ['review-pass-threshold'], ['issue-budget-usd'],
    ['implementation-model'], ['review-model'], ['allowed-paths'],
    ['verification-commands'], ['custom-instructions'], ['ci-timeout'],
    ['mode', 'mode'], ['decomposition-mode', 'decompositionMode'],
    ['follow-up-mode', 'followUpMode']
  ];
  const checks = [
    ['pre-screen-enabled'], ['security-review'], ['lessons-enabled'], ['ci-enabled'],
    ['auto-start', 'autoStart'], ['auto-merge', 'autoMerge'], ['plan-first', 'planFirst']
  ];
  values.forEach(([id, name]) => { elements[id] = { id, name, value: 'edited' }; });
  checks.forEach(([id, name]) => { elements[id] = { id, name, checked: false }; });
  elements['plan-first-opt-out'] = { disabled: false };
  elements['ci-timeout-group'] = { style: {} };
  elements['form-title'] = { textContent: 'Edit Repository' };
  elements['add-repo-form'] = { hidden: true, scrollIntoView() {} };
  elements.owner.focus = () => {};

  const policies = ['AUTOMATED', 'STAGED', 'LEGACY'].map(value => ({
    name: 'workflowPolicy', value, checked: value === 'STAGED'
  }));
  const stages = ['PLANNING', 'IMPLEMENTATION', 'VERIFICATION', 'REVIEW', 'MERGE'].map(value => ({
    name: 'approvalStages', value, checked: false
  }));
  const document = {
    documentElement: { getAttribute: () => null, setAttribute() {} },
    readyState: 'loading',
    activeElement: null,
    body: { appendChild() {}, removeChild() {}, addEventListener(name, fn) { (listeners[name] ||= []).push(fn); } },
    addEventListener(name, fn) { (listeners[name] ||= []).push(fn); },
    getElementById(id) { return elements[id] || null; },
    querySelector(selector) {
      if (selector === 'input[name="workflowPolicy"]:checked') return policies.find(input => input.checked) || null;
      const match = selector.match(/^input\[name="workflowPolicy"\]\[value="(.+)"\]$/);
      if (match) return policies.find(input => input.value === match[1]) || null;
      return null;
    },
    querySelectorAll(selector) {
      if (selector === 'input[name="workflowPolicy"]') return policies;
      if (selector === 'input[name="approvalStages"]') return stages;
      if (selector === 'input[name="approvalStages"]:checked') return stages.filter(input => input.checked);
      return [];
    },
    createElement: () => ({}),
    createDocumentFragment: () => ({ appendChild() {} })
  };
  const window = {
    RepositoryWorkflow: workflow,
    addEventListener: document.addEventListener.bind(document),
    location: { pathname: '/repositories' }
  };
  const context = {
    window, document, navigator: {}, localStorage: {},
    setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    EventSource: class {}, URL, Blob, console
  };
  vm.runInNewContext(source, context);

  return {
    clickAdd() {
      (listeners.click || []).forEach(handler => handler({ target: add, preventDefault() {} }));
    },
    submittedDefaults() {
      return Object.fromEntries([
        ...values.filter(([, name]) => name).map(([id, name]) => [name, elements[id].value]),
        ...checks.filter(([, name]) => name).map(([id, name]) => [name, elements[id].checked]),
        ['workflowPolicy', policies.find(input => input.checked).value],
        ['approvalStages', stages.filter(input => input.checked).map(input => input.value)]
      ]);
    },
    form: elements['add-repo-form'],
    title: elements['form-title']
  };
}

test('Add Repository resets the actual submitted workflow fields to the former Assist defaults', () => {
  const harness = repositoryFormHarness();

  harness.clickAdd();

  assert.equal(harness.form.hidden, false);
  assert.equal(harness.title.textContent, 'Add Repository');
  assert.deepEqual(harness.submittedDefaults(), {
    mode: 'APPROVAL_GATED',
    decompositionMode: 'PROPOSE',
    followUpMode: 'ROLLING_BACKLOG',
    autoStart: true,
    autoMerge: false,
    planFirst: true,
    workflowPolicy: 'LEGACY',
    approvalStages: ['PLANNING', 'IMPLEMENTATION', 'VERIFICATION', 'REVIEW', 'MERGE']
  });
});
