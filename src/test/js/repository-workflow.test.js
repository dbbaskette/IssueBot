'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const workflow = require('../../main/resources/static/js/repository-workflow.js');

function domFixture() {
  const policies = ['AUTOMATED', 'STAGED', 'LEGACY'].map(value => ({ name: 'workflowPolicy', value, checked: value === 'LEGACY' }));
  const stages = ['PLANNING', 'IMPLEMENTATION', 'VERIFICATION', 'REVIEW', 'MERGE']
    .map(value => ({ name: 'approvalStages', value, checked: true }));
  const elements = {
    checkpoints: { hidden: false }, existing: { hidden: false }, summary: { textContent: '' },
    implementationSummary: { textContent: '' }, reviewSummary: { textContent: '' },
    'implementation-model': { value: 'impl-default' }, 'review-model': { value: 'review-default' },
    mode: { value: 'APPROVAL_GATED' }, 'auto-start': { checked: false },
    'auto-merge': { checked: true }, 'plan-first': { checked: false }
  };
  return {
    policies, stages, elements,
    getElementById(id) { return elements[id] || null; },
    querySelector(selector) {
      if (selector === 'input[name="workflowPolicy"]:checked') return policies.find(input => input.checked) || null;
      const policy = selector.match(/^input\[name="workflowPolicy"\]\[value="(.+)"\]$/);
      if (policy) return policies.find(input => input.value === policy[1]) || null;
      return ({
        '[data-workflow-checkpoints]': elements.checkpoints,
        '[data-workflow-existing-settings]': elements.existing,
        '[data-workflow-summary]': elements.summary,
        '[data-implementation-model-summary]': elements.implementationSummary,
        '[data-review-model-summary]': elements.reviewSummary
      })[selector] || null;
    },
    querySelectorAll(selector) {
      if (selector === 'input[name="workflowPolicy"]') return policies;
      if (selector === 'input[name="approvalStages"]') return stages;
      if (selector === 'input[name="approvalStages"]:checked') return stages.filter(input => input.checked);
      return [];
    }
  };
}

test('automatic policy summary describes uninterrupted workflow', () => {
  assert.equal(workflow.summary('AUTOMATED', []),
    'IssueBot runs planning, implementation, verification, review and merge without routine approval checkpoints.');
});

test('staged policy summary names selected checkpoints in workflow order', () => {
  assert.equal(workflow.summary('STAGED', ['MERGE', 'PLANNING', 'REVIEW']),
    'IssueBot pauses for approval before planning, review, merge. Other stages continue automatically.');
});

test('staged policy with no selections explains automatic behavior', () => {
  assert.equal(workflow.summary('STAGED', []),
    'No approval checkpoints are selected; the workflow continues automatically through every stage.');
});

test('legacy policy summary points to existing settings', () => {
  assert.equal(workflow.summary('LEGACY', []),
    'Existing settings control when IssueBot pauses, starts work and merges.');
});

test('load restores exact staged checkpoint selection and model information', () => {
  const doc = domFixture();

  workflow.load(doc, 'STAGED', 'PLANNING,REVIEW');

  assert.equal(doc.policies.find(input => input.value === 'STAGED').checked, true);
  assert.deepEqual(doc.stages.filter(input => input.checked).map(input => input.value), ['PLANNING', 'REVIEW']);
  assert.equal(doc.elements.checkpoints.hidden, false);
  assert.equal(doc.elements.existing.hidden, true);
  assert.equal(doc.elements.implementationSummary.textContent, 'impl-default');
  assert.equal(doc.elements.reviewSummary.textContent, 'review-default');
});

test('automatic and legacy policies switch disclosures without changing hidden legacy controls', () => {
  const doc = domFixture();
  const legacyBefore = {
    mode: doc.elements.mode.value,
    autoStart: doc.elements['auto-start'].checked,
    autoMerge: doc.elements['auto-merge'].checked,
    planFirst: doc.elements['plan-first'].checked
  };

  workflow.load(doc, 'AUTOMATED', 'PLANNING,MERGE');
  assert.equal(doc.elements.checkpoints.hidden, true);
  assert.equal(doc.elements.existing.hidden, true);
  assert.deepEqual(legacyBefore, {
    mode: doc.elements.mode.value,
    autoStart: doc.elements['auto-start'].checked,
    autoMerge: doc.elements['auto-merge'].checked,
    planFirst: doc.elements['plan-first'].checked
  });

  workflow.load(doc, 'LEGACY', 'PLANNING,MERGE');
  assert.equal(doc.elements.checkpoints.hidden, true);
  assert.equal(doc.elements.existing.hidden, false);
});

test('edit load leaves the staged values that the form will submit on save', () => {
  const doc = domFixture();

  workflow.load(doc, 'STAGED', 'IMPLEMENTATION,VERIFICATION,MERGE');

  const submittedPolicy = doc.policies.find(input => input.checked).value;
  const submittedStages = doc.stages.filter(input => input.checked).map(input => input.value);
  assert.equal(submittedPolicy, 'STAGED');
  assert.deepEqual(submittedStages, ['IMPLEMENTATION', 'VERIFICATION', 'MERGE']);
});
