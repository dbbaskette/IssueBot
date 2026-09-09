'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const workflow = require('../../main/resources/static/js/repository-workflow.js');

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
