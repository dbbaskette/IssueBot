(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) {
    module.exports = factory;
  }
  if (root && root.document && !root.IssueBotNavigation) {
    root.IssueBotNavigation = factory(root);
  }
}(typeof window !== 'undefined' ? window : null, function createIssueBotNavigation(root) {
  'use strict';

  var document = root.document;
  var STORAGE_KEY = 'issuebot.navigation-context.v1';
  var PENDING_KEY = 'issuebot.navigation-return.v1';
  var TOKEN_PARAM = 'nav';
  var MAX_CONTEXTS = 20;
  var MAX_ISSUE_IDS = 500;
  var MAX_SOURCE_LENGTH = 2048;
  var MAX_STORAGE_LENGTH = 256 * 1024;
  var MAX_AGE_MS = 30 * 60 * 1000;
  var STATUS_VALUES = new Set([
    'PENDING', 'QUEUED', 'BLOCKED', 'IN_PROGRESS', 'AWAITING_APPROVAL',
    'COMPLETED', 'FAILED', 'COOLDOWN', 'DECOMPOSED', 'AWAITING_DECOMPOSITION',
    'AWAITING_PLAN_APPROVAL', 'READY_TO_START', 'CANCELLED'
  ]);
  var ISSUE_QUERY_FIELDS = new Set(['status', 'repoId', 'q', 'page']);
  var contexts = [];
  var storage = null;
  var pendingToken = null;

  function now() {
    return root.Date && root.Date.now ? root.Date.now() : Date.now();
  }

  function safeStorage() {
    if (storage) { return storage; }
    try {
      storage = root.sessionStorage;
      return storage;
    } catch (error) {
      return null;
    }
  }

  function exactKeys(value, expected) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) { return false; }
    var keys = Object.keys(value).sort();
    var wanted = expected.slice().sort();
    return keys.length === wanted.length && keys.every(function (key, index) {
      return key === wanted[index];
    });
  }

  function positiveId(value) {
    if (typeof value === 'number') {
      return Number.isSafeInteger(value) && value > 0 ? value : null;
    }
    if (typeof value !== 'string' || !/^[1-9][0-9]{0,15}$/.test(value)) { return null; }
    var parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  function validToken(value) {
    return typeof value === 'string' && /^[a-f0-9]{32}$/.test(value);
  }

  function validSingleQueryValue(params, key) {
    return params.getAll(key).length <= 1;
  }

  function validIssueQuery(params) {
    var valid = true;
    params.forEach(function (value, key) {
      if (!ISSUE_QUERY_FIELDS.has(key) || !validSingleQueryValue(params, key)) { valid = false; }
    });
    if (!valid) { return false; }

    var status = params.get('status');
    if (status != null && status !== '' && !STATUS_VALUES.has(status)) { return false; }

    var repoId = params.get('repoId');
    if (repoId != null && repoId !== '' && positiveId(repoId) == null) { return false; }

    var page = params.get('page');
    if (page != null && page !== '') {
      if (!/^(0|[1-9][0-9]{0,9})$/.test(page) || Number(page) > 2147483647) { return false; }
    }

    var query = params.get('q');
    return query == null || !/[\u0000-\u001f\u007f]/.test(query);
  }

  function sourceUrl(raw) {
    if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_SOURCE_LENGTH) { return null; }
    if (raw.charAt(0) !== '/' || raw.indexOf('//') === 0) { return null; }
    var rawPath = raw.split(/[?#]/, 1)[0];
    if (rawPath.indexOf('\\') !== -1 || /%2e|%2f|%5c/i.test(rawPath)) { return null; }

    var parsed;
    try { parsed = new root.URL(raw, root.location.href); }
    catch (error) { return null; }
    var origin;
    try { origin = new root.URL(root.location.href).origin; }
    catch (error) { return null; }
    if (parsed.origin !== origin || parsed.hash || parsed.username || parsed.password) { return null; }
    if (parsed.pathname === '/' || parsed.pathname === '/inbox') {
      return parsed.search ? null : parsed.pathname;
    }
    if (parsed.pathname !== '/issues' || !validIssueQuery(parsed.searchParams)) { return null; }
    return parsed.pathname + parsed.search;
  }

  function returnTargetFor(raw) {
    return sourceUrl(raw) || '/issues';
  }

  function validContext(value, currentTime) {
    if (!exactKeys(value, ['version', 'token', 'createdAt', 'source', 'scrollY', 'issueIds'])) { return null; }
    if (value.version !== 1 || !validToken(value.token)) { return null; }
    if (!Number.isSafeInteger(value.createdAt) || value.createdAt < 0 || value.createdAt > currentTime) { return null; }
    if (currentTime - value.createdAt >= MAX_AGE_MS) { return null; }
    var source = sourceUrl(value.source);
    if (!source || source !== value.source) { return null; }
    if (!Number.isFinite(value.scrollY) || value.scrollY < 0 || !Number.isSafeInteger(value.scrollY)) { return null; }
    if (!Array.isArray(value.issueIds) || value.issueIds.length === 0 || value.issueIds.length > MAX_ISSUE_IDS) {
      return null;
    }
    var seen = new Set();
    for (var i = 0; i < value.issueIds.length; i++) {
      var id = positiveId(value.issueIds[i]);
      if (id == null || id !== value.issueIds[i] || seen.has(id)) { return null; }
      seen.add(id);
    }
    return {
      version: 1, token: value.token, createdAt: value.createdAt,
      source: source, scrollY: value.scrollY, issueIds: value.issueIds.slice()
    };
  }

  function clearStoredContexts(candidate) {
    try { candidate.removeItem(STORAGE_KEY); }
    catch (ignored) { /* storage can become unavailable at any time */ }
  }

  function loadContexts() {
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      var raw = candidate.getItem(STORAGE_KEY);
      if (!raw) { return; }
      if (raw.length > MAX_STORAGE_LENGTH) { throw new Error('oversize navigation storage'); }
      var parsed = JSON.parse(raw);
      if (!exactKeys(parsed, ['version', 'contexts']) || parsed.version !== 1 ||
          !Array.isArray(parsed.contexts) || parsed.contexts.length > MAX_CONTEXTS) {
        throw new Error('invalid navigation storage');
      }
      var currentTime = now();
      var loaded = [];
      var tokens = new Set();
      parsed.contexts.forEach(function (context) {
        // Expiry is an ordinary lifecycle event, not corruption of otherwise-valid snapshots.
        if (exactKeys(context, ['version', 'token', 'createdAt', 'source', 'scrollY', 'issueIds']) &&
            Number.isSafeInteger(context.createdAt) && context.createdAt >= 0 &&
            context.createdAt <= currentTime && currentTime - context.createdAt >= MAX_AGE_MS) {
          return;
        }
        var validated = validContext(context, currentTime);
        if (!validated) { throw new Error('invalid navigation context'); }
        if (tokens.has(validated.token)) { throw new Error('duplicate navigation token'); }
        tokens.add(validated.token);
        loaded.push(validated);
      });
      contexts = loaded;
      if (loaded.length !== parsed.contexts.length) { saveContexts(); }
    } catch (error) {
      contexts = [];
      clearStoredContexts(candidate);
    }
  }

  function saveContexts() {
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      var raw = JSON.stringify({ version: 1, contexts: contexts });
      if (raw.length > MAX_STORAGE_LENGTH) { return; }
      candidate.setItem(STORAGE_KEY, raw);
    } catch (error) { /* memory remains the denied-storage fallback for this page */ }
  }

  function loadPendingToken() {
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      var value = candidate.getItem(PENDING_KEY);
      if (validToken(value)) { pendingToken = value; }
      else if (value != null) { candidate.removeItem(PENDING_KEY); }
    } catch (error) { /* memory fallback only */ }
  }

  function storePendingToken(token) {
    pendingToken = validToken(token) ? token : null;
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      if (pendingToken) { candidate.setItem(PENDING_KEY, pendingToken); }
      else { candidate.removeItem(PENDING_KEY); }
    } catch (error) { /* memory fallback only */ }
  }

  function pruneExpired() {
    var currentTime = now();
    var retained = contexts.filter(function (context) { return validContext(context, currentTime) != null; });
    if (retained.length !== contexts.length) {
      contexts = retained;
      saveContexts();
    }
  }

  function contextForToken(token) {
    if (!validToken(token)) { return null; }
    pruneExpired();
    for (var i = contexts.length - 1; i >= 0; i--) {
      if (contexts[i].token === token) { return contexts[i]; }
    }
    return null;
  }

  function randomToken() {
    var bytes = new Uint8Array(16);
    if (root.crypto && typeof root.crypto.getRandomValues === 'function') {
      root.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < bytes.length; i++) { bytes[i] = Math.floor(Math.random() * 256); }
    }
    return Array.prototype.map.call(bytes, function (value) {
      return value.toString(16).padStart(2, '0');
    }).join('');
  }

  function newToken() {
    var token;
    do { token = randomToken(); } while (contextForToken(token));
    return token;
  }

  function elementsWithin(scope, selector) {
    var result = [];
    if (!scope) { return result; }
    if (scope.matches && scope.matches(selector)) { result.push(scope); }
    if (scope.querySelectorAll) {
      Array.prototype.push.apply(result, scope.querySelectorAll(selector));
    }
    return result;
  }

  function listRoot(scope) {
    return elementsWithin(scope || document, '[data-navigation-list]')[0] || null;
  }

  function locationSource() {
    var location = root.location || {};
    return sourceUrl((location.pathname || '') + (location.search || ''));
  }

  function currentScrollY() {
    var value = Number(root.scrollY || root.pageYOffset || 0);
    if (!Number.isFinite(value) || value < 0) { return 0; }
    return Math.min(Math.round(value), Number.MAX_SAFE_INTEGER);
  }

  function issueIdsWithin(list) {
    var seen = new Set();
    var ids = [];
    elementsWithin(list, '[data-navigation-issue]').forEach(function (element) {
      var id = positiveId(element.getAttribute && element.getAttribute('data-navigation-issue'));
      if (id != null && !seen.has(id) && ids.length < MAX_ISSUE_IDS) {
        seen.add(id);
        ids.push(id);
      }
    });
    return ids;
  }

  function captureList(scope) {
    var list = listRoot(scope);
    var source = locationSource();
    if (!list || !source) { return null; }
    var existingToken = list.getAttribute && list.getAttribute('data-navigation-token');
    var existing = contextForToken(existingToken);
    if (existing && existing.source === source) {
      existing.scrollY = currentScrollY();
      saveContexts();
      return existing;
    }

    var ids = issueIdsWithin(list);
    if (!ids.length) { return null; }
    var context = {
      version: 1, token: newToken(), createdAt: now(), source: source,
      scrollY: currentScrollY(), issueIds: ids
    };
    contexts.push(context);
    if (contexts.length > MAX_CONTEXTS) { contexts.splice(0, contexts.length - MAX_CONTEXTS); }
    if (list.setAttribute) { list.setAttribute('data-navigation-token', context.token); }
    saveContexts();
    return context;
  }

  function detailToken() {
    var parsed;
    try { parsed = new root.URL(root.location.href); }
    catch (error) { return null; }
    var values = parsed.searchParams.getAll(TOKEN_PARAM);
    return values.length === 1 && validToken(values[0]) ? values[0] : null;
  }

  function issueUrl(raw, token) {
    if (typeof raw !== 'string' || !validToken(token) || raw.indexOf('//') === 0) { return null; }
    var parsed;
    try { parsed = new root.URL(raw, root.location.href); }
    catch (error) { return null; }
    var origin = new root.URL(root.location.href).origin;
    if (parsed.origin !== origin || !/^\/issues\/[1-9][0-9]{0,15}$/.test(parsed.pathname)) { return null; }
    if (positiveId(parsed.pathname.slice('/issues/'.length)) == null) { return null; }
    parsed.searchParams.delete(TOKEN_PARAM);
    parsed.searchParams.set(TOKEN_PARAM, token);
    return parsed.pathname + parsed.search + parsed.hash;
  }

  function decorateIssueMarker(marker, token) {
    ['data-issue-href', 'hx-get', 'data-hx-get', 'href'].forEach(function (attribute) {
      if (!marker.hasAttribute || !marker.hasAttribute(attribute)) { return; }
      var decorated = issueUrl(marker.getAttribute(attribute), token);
      if (decorated) { marker.setAttribute(attribute, decorated); }
    });
  }

  function markerNavigatesToIssue(marker) {
    var markerId = positiveId(marker && marker.getAttribute && marker.getAttribute('data-navigation-issue'));
    if (markerId == null) { return false; }
    var attributes = ['data-issue-href', 'hx-get', 'data-hx-get', 'href'];
    for (var i = 0; i < attributes.length; i++) {
      if (!marker.hasAttribute || !marker.hasAttribute(attributes[i])) { continue; }
      var raw = marker.getAttribute(attributes[i]);
      var parsed;
      try { parsed = new root.URL(raw, root.location.href); }
      catch (error) { continue; }
      var origin = new root.URL(root.location.href).origin;
      var match = parsed.origin === origin && parsed.pathname.match(/^\/issues\/([1-9][0-9]{0,15})$/);
      if (match && positiveId(match[1]) === markerId) { return true; }
    }
    return false;
  }

  function prepareIssueNavigation(marker) {
    if (!markerNavigatesToIssue(marker)) { return null; }
    var list = marker && marker.closest && marker.closest('[data-navigation-list]');
    var context = captureList(list);
    if (context) { decorateIssueMarker(marker, context.token); }
    return context;
  }

  function setLink(link, href, hidden) {
    if (!link) { return; }
    link.hidden = !!hidden;
    if (!hidden && href) {
      link.setAttribute('href', href);
      if (link.hasAttribute('hx-get')) { link.setAttribute('hx-get', href); }
    }
  }

  function setButton(button, hidden) {
    if (button) { button.hidden = !!hidden; }
  }

  function currentIssueId(detailRoot) {
    var fromRoot = positiveId(detailRoot && detailRoot.getAttribute && detailRoot.getAttribute('data-current-issue-id'));
    if (fromRoot != null) { return fromRoot; }
    var match = (root.location.pathname || '').match(/^\/issues\/([1-9][0-9]{0,15})$/);
    return match ? positiveId(match[1]) : null;
  }

  function fallbackDetail(detailRoot) {
    var back = detailRoot.querySelector('[data-navigation-return]');
    if (back) {
      back.setAttribute('href', '/issues');
      if (back.hasAttribute('hx-get')) { back.setAttribute('hx-get', '/issues'); }
      back.textContent = detailRoot.hasAttribute('data-navigation-error') ? 'Back to the queue' : '\u2190 Back to queue';
    }
    var sequence = detailRoot.querySelector('[data-navigation-sequence]');
    if (sequence) { sequence.hidden = true; }
  }

  function decorateDetail(scope) {
    var roots = elementsWithin(scope || document, '[data-navigation-detail]')
      .concat(elementsWithin(scope || document, '[data-navigation-error]'));
    roots.forEach(function (detailRoot) {
      var token = detailToken();
      var context = contextForToken(token);
      var issueId = currentIssueId(detailRoot);
      if (issueId == null) {
        var genericSequence = detailRoot.querySelector('[data-navigation-sequence]');
        if (genericSequence) { genericSequence.hidden = true; }
        return;
      }
      var index = context && issueId != null ? context.issueIds.indexOf(issueId) : -1;
      if (!context || index < 0) {
        fallbackDetail(detailRoot);
        return;
      }

      var back = detailRoot.querySelector('[data-navigation-return]');
      if (back) {
        back.setAttribute('href', context.source);
        if (back.hasAttribute('hx-get')) { back.setAttribute('hx-get', context.source); }
        back.textContent = detailRoot.hasAttribute('data-navigation-error') ? 'Back to results' : '\u2190 Back to results';
      }

      var sequence = detailRoot.querySelector('[data-navigation-sequence]');
      if (!sequence) { return; }
      sequence.hidden = false;
      var sequenceLabel = sequence.querySelector('[data-navigation-sequence-label]');
      if (sequenceLabel) {
        sequenceLabel.textContent = context.source.indexOf('/issues') === 0
          ? 'In this result set (current page)' : 'In this result set';
      }
      var previousId = index > 0 ? context.issueIds[index - 1] : null;
      var nextId = index + 1 < context.issueIds.length ? context.issueIds[index + 1] : null;
      setLink(sequence.querySelector('[data-navigation-previous]'),
        previousId == null ? null : issueUrl('/issues/' + previousId, token), previousId == null);
      setButton(sequence.querySelector('[data-navigation-previous-disabled]'), previousId != null);
      setLink(sequence.querySelector('[data-navigation-next]'),
        nextId == null ? null : issueUrl('/issues/' + nextId, token), nextId == null);
      setButton(sequence.querySelector('[data-navigation-next-disabled]'), nextId != null);
    });
  }

  function restoreList(scope) {
    if (!pendingToken) { return false; }
    var context = contextForToken(pendingToken);
    if (!context || context.source !== locationSource() || !listRoot(scope)) {
      if (!context) { storePendingToken(null); }
      return false;
    }
    var scrollY = context.scrollY;
    storePendingToken(null);
    if (typeof root.scrollTo === 'function') { root.scrollTo(0, scrollY); }
    return true;
  }

  function initialize() {
    decorateDetail(document);
    restoreList(document);
  }

  loadContexts();
  loadPendingToken();

  document.addEventListener('click', function (event) {
    var target = event.target;
    var returning = target && target.closest && target.closest('[data-navigation-return]');
    if (returning) {
      var token = detailToken();
      if (contextForToken(token)) { storePendingToken(token); }
      return;
    }
    var marker = target && target.closest && target.closest('[data-navigation-issue]');
    var control = target && target.closest && target.closest('button, a, input, select, textarea');
    if (marker && (!control || control === marker)) { prepareIssueNavigation(marker); }
  }, true);

  document.addEventListener('auxclick', function (event) {
    var marker = event.target && event.target.closest && event.target.closest('[data-navigation-issue]');
    var control = event.target && event.target.closest && event.target.closest('button, a, input, select, textarea');
    if (marker && (!control || control === marker)) { prepareIssueNavigation(marker); }
  }, true);

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter' && event.key !== ' ' && event.key !== 'Spacebar') { return; }
    var marker = event.target && event.target.closest && event.target.closest('[data-navigation-issue]');
    if (!marker) { return; }
    var control = event.target.closest('button, a, input, select, textarea');
    if (control && control !== marker) { return; }
    prepareIssueNavigation(marker);
  }, true);

  document.addEventListener('htmx:afterSwap', function (event) {
    var target = event.detail && event.detail.target;
    decorateDetail(target || document);
  });
  document.addEventListener('htmx:afterSettle', function (event) {
    var target = event.detail && event.detail.target;
    decorateDetail(target || document);
    restoreList(target || document);
  });
  document.addEventListener('htmx:historyRestore', function () {
    storePendingToken(null);
    decorateDetail(document);
  });

  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', initialize); }
  else { initialize(); }

  return {
    captureList: captureList,
    decorateDetail: decorateDetail,
    restoreList: restoreList,
    returnTargetFor: returnTargetFor,
    contextForToken: contextForToken,
    limits: {
      contexts: MAX_CONTEXTS, issueIds: MAX_ISSUE_IDS, ageMs: MAX_AGE_MS,
      sourceLength: MAX_SOURCE_LENGTH, storageLength: MAX_STORAGE_LENGTH
    }
  };
}));
