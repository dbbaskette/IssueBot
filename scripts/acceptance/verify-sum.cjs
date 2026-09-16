'use strict';
// Trusted verifier: stays in IssueBot, never copied into the agent-editable fixture.
const assert = require('node:assert/strict');
const sum = require(require('node:path').resolve(process.argv[2]));
assert.equal(typeof sum, 'function');
assert.equal(sum([]), 0);
assert.equal(sum([1, 2, 3]), 6);
assert.equal(sum([-3, 8]), 5);
assert.equal(sum([0.25, 0.5]), 0.75);
