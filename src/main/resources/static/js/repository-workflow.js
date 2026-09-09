(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) { module.exports = api; }
  if (root) { root.RepositoryWorkflow = api; }
}(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  var ORDER = ['PLANNING', 'IMPLEMENTATION', 'VERIFICATION', 'REVIEW', 'MERGE'];
  var LABELS = {
    PLANNING: 'planning', IMPLEMENTATION: 'implementation', VERIFICATION: 'verification',
    REVIEW: 'review', MERGE: 'merge'
  };

  function summary(policy, stages) {
    if (policy === 'AUTOMATED') {
      return 'IssueBot runs planning, implementation, verification, review and merge without routine approval checkpoints.';
    }
    if (policy === 'STAGED') {
      var selected = ORDER.filter(function (stage) { return stages.indexOf(stage) !== -1; });
      if (!selected.length) {
        return 'No approval checkpoints are selected; the workflow continues automatically through every stage.';
      }
      return 'IssueBot pauses for approval before ' + selected.map(function (stage) {
        return LABELS[stage];
      }).join(', ') + '. Other stages continue automatically.';
    }
    return 'Existing settings control when IssueBot pauses, starts work and merges.';
  }

  function selectedPolicy(doc) {
    var selected = doc.querySelector('input[name="workflowPolicy"]:checked');
    return selected ? selected.value : 'LEGACY';
  }

  function selectedStages(doc) {
    return Array.prototype.map.call(
      doc.querySelectorAll('input[name="approvalStages"]:checked'),
      function (input) { return input.value; }
    );
  }

  function modelLabel(value) {
    return value ? value : 'global default';
  }

  function sync(doc) {
    var policy = selectedPolicy(doc);
    var checkpoints = doc.querySelector('[data-workflow-checkpoints]');
    var existing = doc.querySelector('[data-workflow-existing-settings]');
    var output = doc.querySelector('[data-workflow-summary]');
    if (checkpoints) { checkpoints.hidden = policy !== 'STAGED'; }
    if (existing) { existing.hidden = policy !== 'LEGACY'; }
    if (output) { output.textContent = summary(policy, selectedStages(doc)); }

    var implementation = doc.getElementById('implementation-model');
    var review = doc.getElementById('review-model');
    var implementationOutput = doc.querySelector('[data-implementation-model-summary]');
    var reviewOutput = doc.querySelector('[data-review-model-summary]');
    if (implementationOutput) { implementationOutput.textContent = modelLabel(implementation && implementation.value); }
    if (reviewOutput) { reviewOutput.textContent = modelLabel(review && review.value); }
  }

  function load(doc, policy, csv) {
    Array.prototype.forEach.call(doc.querySelectorAll('input[name="workflowPolicy"]'), function (input) {
      input.checked = false;
    });
    var selected = doc.querySelector('input[name="workflowPolicy"][value="' + (policy || 'LEGACY') + '"]');
    if (!selected) {
      selected = doc.querySelector('input[name="workflowPolicy"][value="LEGACY"]');
    }
    if (selected) { selected.checked = true; }
    var stages = (csv || '').split(',').map(function (stage) { return stage.trim(); });
    Array.prototype.forEach.call(doc.querySelectorAll('input[name="approvalStages"]'), function (input) {
      input.checked = stages.indexOf(input.value) !== -1;
    });
    sync(doc);
  }

  return { summary: summary, sync: sync, load: load };
}));
