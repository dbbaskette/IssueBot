(function () {
  'use strict';
  const selections = new Map();
  const planSelections = new Map();
  const names = { intake: 'Intake', plan: 'Plan', work: 'Work', verify: 'Verify', review: 'Review', done: 'Done' };

  function root() { return document.querySelector('[data-stage-browser]'); }
  function sync() {
    const page = root();
    if (!page) return;
    const progress = page.querySelector('#live-status');
    if (!progress) return;
    const current = progress.dataset.currentStage;
    const selected = selections.get(page.dataset.currentIssueId) || current;
    page.dataset.selectedStage = selected;
    page.querySelectorAll('[data-stage-output]').forEach(panel => {
      panel.hidden = panel.dataset.stageOutput !== selected;
    });
    page.querySelectorAll('[data-select-stage]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.selectStage === selected));
    });
    page.querySelectorAll('[data-stage-terminal]').forEach(panel => {
      panel.hidden = selected !== current || progress.dataset.issueRunning !== 'true';
    });
    page.querySelectorAll('[data-stage-current-action]').forEach(panel => {
      panel.hidden = selected !== current;
    });
    const following = !selections.has(page.dataset.currentIssueId);
    const label = page.querySelector('[data-stage-selection-label]');
    if (label) label.textContent = following ? 'Following current stage · ' + names[selected] : 'Viewing ' + names[selected];
    const follow = page.querySelector('[data-follow-stage]');
    if (follow) follow.hidden = following;
    const plan = page.querySelector('[data-plan-review]');
    if (plan) {
      const chosen = planSelections.get(plan.dataset.uiStateKey);
      if (chosen) plan.querySelectorAll('[data-plan-tab]').forEach(tab => {
        const selectedTab = tab.dataset.planTab === chosen;
        tab.setAttribute('aria-selected', String(selectedTab));
        tab.setAttribute('tabindex', selectedTab ? '0' : '-1');
        const panel = document.getElementById(tab.getAttribute('aria-controls'));
        if (panel) panel.hidden = !selectedTab;
      });
    }
  }

  function rememberPlan() {
    const page = root();
    const plan = page && page.querySelector('[data-plan-review]');
    const tab = plan && plan.querySelector('[data-plan-tab][aria-selected="true"]');
    if (tab) planSelections.set(plan.dataset.uiStateKey, tab.dataset.planTab);
  }

  function select(stage) {
    const page = root();
    if (!page || !names[stage]) return;
    selections.set(page.dataset.currentIssueId, stage);
    sync();
  }

  function revealHash() {
    const page = root();
    if (!page || !window.location.hash) return;
    let id;
    try { id = decodeURIComponent(window.location.hash.slice(1)); } catch (_) { return; }
    const target = document.getElementById(id);
    if (!target || !page.contains(target)) return;
    const panel = target.closest('[data-stage-output]');
    if (panel) {
      select(panel.dataset.stageOutput);
      let ancestor = target;
      while (ancestor && ancestor !== page) {
        if (ancestor.tagName === 'DETAILS') ancestor.open = true;
        ancestor = ancestor.parentElement;
      }
    }
  }

  document.addEventListener('click', function (event) {
    const button = event.target.closest('[data-select-stage]');
    if (button) select(button.dataset.selectStage);
    const follow = event.target.closest('[data-follow-stage]');
    if (follow && root()) {
      selections.delete(root().dataset.currentIssueId);
      sync();
    }
    const link = event.target.closest('a[href^="#"]');
    if (link) {
      const target = document.getElementById(link.getAttribute('href').slice(1));
      const panel = target && target.closest('[data-stage-output]');
      if (panel) select(panel.dataset.stageOutput);
    }
  });
  document.addEventListener('htmx:beforeSwap', rememberPlan);
  document.addEventListener('htmx:oobBeforeSwap', rememberPlan);
  document.addEventListener('htmx:afterSwap', sync);
  document.addEventListener('htmx:oobAfterSwap', sync);
  document.addEventListener('htmx:afterSettle', sync);
  window.addEventListener('hashchange', revealHash);
  document.addEventListener('htmx:afterSwap', function (event) {
    if (event.detail && event.detail.target && event.detail.target.id === 'content') revealHash();
  });
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { sync(); revealHash(); });
  } else { sync(); revealHash(); }
})();
