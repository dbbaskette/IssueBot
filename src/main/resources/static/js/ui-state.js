(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module.exports) {
    module.exports = factory;
  }
  if (root && root.document && !root.IssueBotUiState) {
    root.IssueBotUiState = factory(root);
  }
}(typeof window !== 'undefined' ? window : null, function createIssueBotUiState(root) {
  'use strict';

  var document = root.document;
  var STORAGE_KEY = 'issuebot.ui-state.v1';
  var MAX_ENTRIES = 500;
  var choices = [];
  var storage = null;
  var pendingNavigation = null;
  var toastControllers = Object.create(null);

  function safeStorage() {
    if (storage) { return storage; }
    try {
      storage = root.sessionStorage;
      return storage;
    } catch (e) {
      return null;
    }
  }

  function validRecord(record) {
    return record && typeof record.key === 'string' && record.key.length > 0 &&
      record.key.length <= 1024 && typeof record.open === 'boolean';
  }

  function loadChoices() {
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      var raw = candidate.getItem(STORAGE_KEY);
      if (!raw) { return; }
      var parsed = JSON.parse(raw);
      var records = parsed && Array.isArray(parsed.entries) ? parsed.entries : [];
      records.forEach(function (record) {
        if (validRecord(record)) { choices.push({ key: record.key, open: record.open }); }
      });
      if (choices.length > MAX_ENTRIES) { choices = choices.slice(-MAX_ENTRIES); }
    } catch (e) {
      choices = [];
      try { candidate.removeItem(STORAGE_KEY); } catch (ignored) { /* storage may be denied */ }
    }
  }

  function saveChoices() {
    var candidate = safeStorage();
    if (!candidate) { return; }
    try {
      candidate.setItem(STORAGE_KEY, JSON.stringify({ version: 1, entries: choices }));
    } catch (e) { /* private/denied storage falls back to this tab's memory */ }
  }

  function pathname() {
    var value = root.location && root.location.pathname;
    if (!value || value.charAt(0) !== '/') { return '/'; }
    return value.length > 1 ? value.replace(/\/+$/, '') : value;
  }

  function semanticKey(details) {
    var value = details && details.getAttribute && details.getAttribute('data-ui-state-key');
    value = value && value.trim();
    return value ? pathname() + ':' + value : null;
  }

  function findChoice(key) {
    for (var i = choices.length - 1; i >= 0; i--) {
      if (choices[i].key === key) { return choices[i]; }
    }
    return null;
  }

  function remember(details) {
    var key = semanticKey(details);
    if (!key) { return; }
    for (var i = choices.length - 1; i >= 0; i--) {
      if (choices[i].key === key) { choices.splice(i, 1); }
    }
    choices.push({ key: key, open: !!details.open });
    if (choices.length > MAX_ENTRIES) {
      choices.splice(0, choices.length - MAX_ENTRIES);
    }
    saveChoices();
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

  function capture(scope) {
    elementsWithin(scope || document, 'details[data-ui-state-key]').forEach(remember);
  }

  // HTMX morphing may update an open attribute before it replaces the node.
  // Refresh only already-explicit choices here; server defaults must not become
  // choices merely because a polling target happened to be swapped.
  function captureKnown(scope) {
    var changed = false;
    elementsWithin(scope || document, 'details[data-ui-state-key]').forEach(function (details) {
      var record = findChoice(semanticKey(details));
      if (record && record.open !== !!details.open) {
        record.open = !!details.open;
        changed = true;
      }
    });
    if (changed) { saveChoices(); }
  }

  function restore(scope) {
    elementsWithin(scope || document, 'details[data-ui-state-key]').forEach(function (details) {
      var record = findChoice(semanticKey(details));
      if (record) { details.open = record.open; }
    });
  }

  function eventRoot(event) {
    var detail = event && event.detail;
    return (detail && (detail.target || detail.elt)) || (event && event.target) || document;
  }

  // outerHTML and OOB swaps may report the removed target in event.detail.
  // Resolve its replacement by stable id; without one, restoring the document
  // is the safe bounded fallback and also covers newly inserted siblings.
  function connectedEventRoot(event) {
    var candidate = eventRoot(event);
    if (connected(candidate)) { return candidate; }
    var replacement = candidate && candidate.id && document.getElementById(candidate.id);
    return connected(replacement) ? replacement : document;
  }

  function contentTarget(target) {
    return !!target && target.id === 'content';
  }

  function requestUrl(detail) {
    var elt = detail && detail.elt;
    var raw = detail && detail.requestConfig && detail.requestConfig.path;
    if (!raw && elt && elt.getAttribute) {
      raw = elt.getAttribute('hx-get') || elt.getAttribute('data-hx-get') || elt.getAttribute('href');
    }
    try { return new root.URL(raw || root.location.href, root.location.href); }
    catch (e) { return null; }
  }

  function navigationPath(url) {
    return url ? url.pathname : pathname();
  }

  function routeMatches(linkPath, currentPath) {
    if (linkPath === '/') { return currentPath === '/'; }
    return currentPath === linkPath || currentPath.indexOf(linkPath + '/') === 0;
  }

  function syncNavigation(path) {
    var currentPath = path || pathname();
    var links = document.querySelectorAll('.sidebar a[href]');
    var best = null;
    var bestLength = -1;
    Array.prototype.forEach.call(links, function (link) {
      var linkPath;
      try { linkPath = new root.URL(link.getAttribute('href'), root.location.href).pathname; }
      catch (e) { return; }
      if (routeMatches(linkPath, currentPath) && linkPath.length > bestLength) {
        best = link;
        bestLength = linkPath.length;
      }
    });
    Array.prototype.forEach.call(links, function (link) {
      var active = link === best;
      if (link.classList) { link.classList.toggle('active', active); }
      if (active) { link.setAttribute('aria-current', 'page'); }
      else { link.removeAttribute('aria-current'); }
    });
  }

  function scrollAfterNavigation(nav) {
    if (!nav) { return; }
    var destination = null;
    if (nav.hash) {
      try { destination = document.getElementById(decodeURIComponent(nav.hash.slice(1))); }
      catch (e) { destination = document.getElementById(nav.hash.slice(1)); }
    }
    if (!destination && document.querySelector) {
      destination = document.querySelector('#content h1, #content h2');
    }
    if (destination && destination.scrollIntoView) {
      destination.scrollIntoView({ behavior: 'auto', block: 'start' });
    } else if (root.scrollTo) {
      root.scrollTo(0, 0);
    }
  }

  function toastSeverity(toast) {
    var explicit = toast.getAttribute && toast.getAttribute('data-toast-severity');
    if (explicit) { return explicit.toLowerCase(); }
    if (toast.classList && toast.classList.contains('toast-danger')) { return 'error'; }
    if (toast.classList && (toast.classList.contains('toast-warning') || toast.classList.contains('toast-warn'))) {
      return 'warning';
    }
    return 'success';
  }

  function connected(element) {
    if (!element) { return false; }
    if (typeof element.isConnected === 'boolean') { return element.isConnected; }
    return !!(document.documentElement && document.documentElement.contains && document.documentElement.contains(element));
  }

  function now() {
    return root.Date && root.Date.now ? root.Date.now() : Date.now();
  }

  function clearControllerTimer(controller) {
    if (controller.timer != null) {
      root.clearTimeout(controller.timer);
      controller.timer = null;
    }
  }

  function removeToast(controller) {
    clearControllerTimer(controller);
    var element = controller.element;
    if (element) {
      element.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
      element.style.opacity = '0';
      element.style.transform = 'translateY(-6px)';
    }
    root.setTimeout(function () {
      if (element && element.parentNode) { element.parentNode.removeChild(element); }
      if (toastControllers[controller.key] === controller) { delete toastControllers[controller.key]; }
    }, 400);
  }

  function toastPaused(controller) {
    return controller.hovered || controller.focused;
  }

  function scheduleToast(controller) {
    if (controller.severity !== 'success' || controller.timer != null || toastPaused(controller)) { return; }
    controller.startedAt = now();
    controller.timer = root.setTimeout(function () {
      controller.timer = null;
      controller.remaining = 0;
      removeToast(controller);
    }, Math.max(0, controller.remaining));
  }

  function pauseToast(controller, reason) {
    if (controller.severity !== 'success') { return; }
    controller[reason] = true;
    if (controller.timer != null) {
      controller.remaining = Math.max(0, controller.remaining - (now() - controller.startedAt));
      clearControllerTimer(controller);
    }
  }

  function resumeToast(controller, reason) {
    if (controller.severity !== 'success') { return; }
    controller[reason] = false;
    if (toastPaused(controller)) { return; }
    if (controller.remaining <= 0) { removeToast(controller); }
    else { scheduleToast(controller); }
  }

  function wireToast(controller, toast) {
    if (toast.__issuebotToastInit) { return; }
    toast.__issuebotToastInit = true;
    toast.setAttribute('data-toast-message', controller.message);
    toast.setAttribute('data-toast-severity', controller.severity);
    toast.setAttribute('role', controller.severity === 'success' ? 'status' : 'alert');

    // HTMX history restores serialized markup, but not listeners or expandos.
    // Discard cached generated controls before wiring this DOM instance.
    elementsWithin(toast, '.toast-dismiss').forEach(function (button) {
      if (button.parentNode) { button.parentNode.removeChild(button); }
    });
    var dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.className = 'toast-dismiss';
    dismiss.setAttribute('aria-label', 'Dismiss notification');
    dismiss.textContent = '\u00d7';
    dismiss.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      removeToast(controller);
    });
    toast.appendChild(dismiss);

    toast.addEventListener('mouseenter', function () { pauseToast(controller, 'hovered'); });
    toast.addEventListener('mouseleave', function () { resumeToast(controller, 'hovered'); });
    toast.addEventListener('focusin', function () { pauseToast(controller, 'focused'); });
    toast.addEventListener('focusout', function (event) {
      if (!toast.contains || !toast.contains(event.relatedTarget)) { resumeToast(controller, 'focused'); }
    });
  }

  function initToasts(scope) {
    elementsWithin(scope || document, '.toast').forEach(function (toast) {
      if (toast.__issuebotToastInit) { return; }
      var message = (toast.getAttribute('data-toast-message') || toast.textContent || '').trim();
      var severity = toastSeverity(toast);
      var key = severity + ':' + message;
      var controller = toastControllers[key];

      if (controller && connected(controller.element)) {
        if (toast.parentNode) { toast.parentNode.removeChild(toast); }
        return;
      }
      if (!controller) {
        controller = {
          key: key, message: message, severity: severity, element: toast,
          remaining: 6000, startedAt: 0, timer: null, hovered: false, focused: false
        };
        toastControllers[key] = controller;
      } else {
        if (controller.timer != null) {
          controller.remaining = Math.max(0, controller.remaining - (now() - controller.startedAt));
        }
        clearControllerTimer(controller);
        controller.element = toast;
        controller.hovered = !!(toast.matches && toast.matches(':hover'));
        controller.focused = !!(document.activeElement && toast.contains && toast.contains(document.activeElement));
      }
      wireToast(controller, toast);
      scheduleToast(controller);
    });
  }

  function initialize() {
    restore(document);
    syncNavigation();
    initToasts(document);
  }

  loadChoices();

  document.addEventListener('click', function (event) {
    var summary = event.target && event.target.closest && event.target.closest('summary');
    var details = summary && summary.parentElement;
    if (!details || details.tagName !== 'DETAILS' || !details.hasAttribute('data-ui-state-key')) { return; }
    root.setTimeout(function () { remember(details); }, 0);
  }, true);

  document.addEventListener('htmx:beforeRequest', function (event) {
    var detail = event.detail || {};
    var method = detail.requestConfig && detail.requestConfig.verb;
    if (contentTarget(detail.target) && (!method || String(method).toUpperCase() === 'GET')) {
      pendingNavigation = requestUrl(detail);
    }
  });

  document.addEventListener('htmx:beforeSwap', function (event) { captureKnown(eventRoot(event)); });
  document.addEventListener('htmx:oobBeforeSwap', function (event) { captureKnown(eventRoot(event)); });
  document.addEventListener('htmx:beforeCleanupElement', function (event) { captureKnown(eventRoot(event)); });
  document.addEventListener('htmx:beforeHistorySave', function () { captureKnown(document); });

  function afterSwap(event) {
    var target = connectedEventRoot(event);
    restore(target);
    initToasts(target);
    if (contentTarget(target)) {
      var nav = pendingNavigation;
      pendingNavigation = null;
      syncNavigation(navigationPath(nav));
      scrollAfterNavigation(nav);
    }
  }

  document.addEventListener('htmx:afterSwap', afterSwap);
  document.addEventListener('htmx:oobAfterSwap', afterSwap);
  document.addEventListener('htmx:afterSettle', function (event) {
    var target = connectedEventRoot(event);
    restore(target);
    initToasts(target);
  });
  document.addEventListener('htmx:historyRestore', function () {
    pendingNavigation = null;
    restore(document);
    initToasts(document);
    syncNavigation();
  });
  document.addEventListener('htmx:responseError', function () { pendingNavigation = null; });
  document.addEventListener('htmx:sendError', function () { pendingNavigation = null; });
  root.addEventListener('popstate', function () { syncNavigation(); });

  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', initialize); }
  else { initialize(); }

  return {
    restore: restore,
    capture: capture,
    initToasts: initToasts,
    syncNavigation: syncNavigation
  };
}));
