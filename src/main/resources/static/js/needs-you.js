/* Persistent Needs You refresh coordinator. One request supplies badge and cards. */
(function () {
  'use strict';
  if (window.__issuebotNeedsYou) return;
  var state = window.__issuebotNeedsYou = { inFlight: false, queued: false, epoch: 0 };

  function inboxRoot() {
    return window.location.pathname === '/inbox' ? document.getElementById('needs-you-content') : null;
  }

  function editing(root) {
    if (!root) return false;
    if (document.querySelector('[data-modal]:not([hidden])')) return true;
    var active = document.activeElement;
    if (active && root.contains(active) && active.matches('input, textarea, select, [contenteditable="true"]')) return true;
    return Array.prototype.some.call(root.querySelectorAll('input, textarea, select'), function (field) {
      if (!field.getClientRects().length || field.type === 'hidden') return false;
      if (field.type === 'checkbox' || field.type === 'radio') return field.checked !== field.defaultChecked;
      if (field.tagName === 'SELECT') {
        var defaults = Array.prototype.filter.call(field.options, function (option) { return option.defaultSelected; });
        if (!defaults.length && !field.multiple) return field.selectedIndex !== 0;
        return Array.prototype.some.call(field.options, function (option) { return option.selected !== option.defaultSelected; });
      }
      return field.value !== field.defaultValue;
    });
  }

  function refresh() {
    if (state.inFlight) { state.queued = true; return; }
    var root = inboxRoot();
    // Keep the badge equal to the retained cards while the operator is editing.
    if (root && editing(root)) return;
    state.inFlight = true;
    state.queued = false;
    var includeInbox = !!root;
    var epoch = state.epoch;
    var abort = new AbortController();
    var timeout = window.setTimeout(function () { abort.abort(); }, 10000);
    window.fetch('/inbox/live?includeInbox=' + includeInbox, {
      credentials: 'same-origin', cache: 'no-store', signal: abort.signal,
      headers: { 'HX-Request': 'true' }
    }).then(function (response) {
      if (!response.ok || response.redirected) throw new Error('Refresh unavailable');
      return response.text();
    }).then(function (html) {
      // A navigation/mutation response must not be overwritten by an older request.
      if (epoch !== state.epoch) return;
      if (includeInbox && (root !== inboxRoot() || editing(root))) return;
      var parsed = new DOMParser().parseFromString(html, 'text/html');
      var nextBadge = parsed.getElementById('needs-you-badge');
      var badge = document.getElementById('needs-you-badge');
      if (!nextBadge || !badge) return;
      var nextInbox = parsed.getElementById('needs-you-content');
      if (includeInbox && !nextInbox) return;
      if (includeInbox) {
        root.replaceWith(nextInbox);
        if (window.htmx) {
          window.htmx.process(nextInbox);
          window.htmx.trigger(nextInbox, 'htmx:afterSwap', { target: nextInbox });
        }
      }
      badge.replaceWith(nextBadge);
    }).catch(function () {
      // Keep the last good snapshot. EventSource reconnects and polling retries.
    }).finally(function () {
      window.clearTimeout(timeout);
      state.inFlight = false;
      if (state.queued) window.setTimeout(refresh, 0);
    });
  }

  document.addEventListener('htmx:beforeRequest', function (event) {
    var detail = event.detail || {};
    if (detail.target && detail.target.id === 'content') state.epoch++;
  });
  document.addEventListener('htmx:afterRequest', function (event) {
    var detail = event.detail || {};
    var verb = detail.requestConfig && detail.requestConfig.verb;
    if (verb && verb.toLowerCase() !== 'get') { state.epoch++; refresh(); }
  });
  document.addEventListener('htmx:afterSwap', function (event) {
    if (event.detail && event.detail.target && event.detail.target.id === 'content') {
      state.epoch++;
      refresh();
    }
  });
  document.addEventListener('focusout', function (event) {
    var root = inboxRoot();
    if (root && root.contains(event.target)) window.setTimeout(function () {
      if (!editing(inboxRoot())) refresh();
    }, 0);
  });
  document.addEventListener('click', function (event) {
    if (event.target.closest && event.target.closest('[data-modal-close], [data-reject-cancel]')) {
      window.setTimeout(refresh, 0);
    }
  });
  window.addEventListener('popstate', function () { state.epoch++; refresh(); });
  function connect() {
    if (window.EventSource) {
      if (state.events) state.events.close();
      try {
        state.events = new EventSource('/api/events/stream');
        state.events.addEventListener('issue-update', refresh);
      } catch (error) { state.events = null; }
    }
    window.clearInterval(state.poll);
    state.poll = window.setInterval(refresh, 15000);
  }
  connect();
  window.addEventListener('pageshow', function (event) {
    if (event.persisted) { connect(); refresh(); }
  });
  window.addEventListener('pagehide', function () {
    if (state.events) state.events.close();
    window.clearInterval(state.poll);
  });
}());
