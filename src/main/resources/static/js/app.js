/* IssueBot — liquid-glass UI behaviors.
   Vanilla JS, no framework. Uses event delegation on document so it
   survives HTMX content swaps. Guarded against double-init. */
(function () {
  'use strict';
  if (window.__issuebotAppInit) { return; }
  window.__issuebotAppInit = true;

  var root = document.documentElement;

  // --- Theme toggle -------------------------------------------------------
  function currentTheme() {
    return root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
  }

  function syncThemeIcon() {
    var btn = document.getElementById('theme-toggle');
    if (!btn) { return; }
    var icon = btn.querySelector('i');
    if (!icon) { return; }
    var dark = currentTheme() === 'dark';
    icon.classList.toggle('ti-sun', dark);
    icon.classList.toggle('ti-moon', !dark);
  }

  function toggleTheme() {
    var next = currentTheme() === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('theme', next); } catch (e) { /* ignore */ }
    syncThemeIcon();
  }

  // --- Mobile menu --------------------------------------------------------
  function sidebar() { return document.querySelector('.sidebar'); }
  function overlay() { return document.querySelector('.sidebar-overlay'); }
  function menuBtn() { return document.querySelector('.mobile-menu-btn'); }

  function setMenu(open) {
    var sb = sidebar(), ov = overlay(), btn = menuBtn();
    if (sb) { sb.classList.toggle('open', open); }
    if (ov) { ov.classList.toggle('open', open); }
    if (btn) { btn.setAttribute('aria-expanded', open ? 'true' : 'false'); }
  }

  function menuIsOpen() {
    var sb = sidebar();
    return !!sb && sb.classList.contains('open');
  }

  // --- Clipboard helper ---------------------------------------------------
  // Copies `text` to the clipboard and, if `btn` is given, briefly flips its
  // contents to a "Copied" confirmation before restoring the original markup.
  function copyText(text, btn) {
    var done = function () {
      if (!btn) { return; }
      if (btn.__copyResetTimer) { clearTimeout(btn.__copyResetTimer); }
      else { btn.__copyOrig = btn.innerHTML; }
      btn.innerHTML = '<i class="ti ti-check" aria-hidden="true"></i> Copied';
      btn.__copyResetTimer = setTimeout(function () {
        btn.innerHTML = btn.__copyOrig;
        btn.__copyResetTimer = null;
      }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(function () {});
    } else {
      try {
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        done();
      } catch (e) { /* ignore */ }
    }
  }
  window.copyText = copyText;

  // --- Toast auto-dismiss -------------------------------------------------
  function dismissToasts() {
    var toasts = document.querySelectorAll('.toast');
    toasts.forEach(function (toast) {
      if (toast.__dismissScheduled) { return; }
      toast.__dismissScheduled = true;
      setTimeout(function () {
        toast.style.transition = 'opacity 0.4s ease, transform 0.4s ease';
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(-6px)';
        setTimeout(function () {
          if (toast.parentNode) { toast.parentNode.removeChild(toast); }
        }, 400);
      }, 4000);
    });
  }

  // --- SSE health indicator (#83) ------------------------------------------
  // A single header dot (#sse-status, in layout.html) reflects whether live
  // updates are actually flowing on the current page. Multiple independent
  // streams can exist across the app (the queue's htmx-sse connection, the
  // issue-detail terminal's raw EventSource) — never both at once today, but
  // tracked per-stream by name so that could change without a rewrite here.
  // Aggregate rule: reconnecting beats connected beats none (worst state wins),
  // and "none" is the state when no stream is registered at all (e.g. Settings,
  // Dashboard, Repositories — pages with no live stream on them).
  var SseStatus = {
    states: {},
    TITLES: {
      connected: 'Live updates connected',
      reconnecting: 'Reconnecting to live updates…',
      none: 'No live stream on this page'
    },

    set: function (name, state) {
      this.states[name] = state;
      this._render();
    },

    clear: function (name) {
      delete this.states[name];
      this._render();
    },

    _aggregate: function () {
      var names = Object.keys(this.states);
      if (names.length === 0) { return 'none'; }
      for (var i = 0; i < names.length; i++) {
        if (this.states[names[i]] === 'reconnecting') { return 'reconnecting'; }
      }
      return 'connected';
    },

    _render: function () {
      var dot = document.getElementById('sse-status');
      if (!dot) { return; }
      var state = this._aggregate();
      dot.setAttribute('data-state', state);
      dot.setAttribute('title', this.TITLES[state]);
    }
  };
  window.SseStatus = SseStatus;

  // --- Last-updated stamps (#83) -------------------------------------------
  // markUpdated(key) records "now" for a named live region; a single 1s tick
  // renders "updated Xs ago" (relative, human-friendly) into every element
  // carrying a matching [data-updated-stamp="key"]. Keys in use: "queue"
  // (issues.html table), "dashboard" (dashboard.html metrics), "pipeline"
  // (issue-detail.html live-status section).
  var UpdateStamps = {
    times: {},

    mark: function (key) {
      this.times[key] = Date.now();
      this._renderOne(key);
    },

    _renderOne: function (key) {
      var ts = this.times[key];
      if (ts == null) { return; }
      var text = UpdateStamps.formatAgo(Date.now() - ts);
      var els = document.querySelectorAll('[data-updated-stamp="' + key + '"]');
      Array.prototype.forEach.call(els, function (el) { el.textContent = text; });
    },

    renderAll: function () {
      var self = this;
      Object.keys(this.times).forEach(function (key) { self._renderOne(key); });
    },

    // Marks every [data-updated-stamp] element currently in the DOM. Called on
    // initial page load and on every #content swap (SPA navigation): a freshly
    // rendered region is by definition fresh data, so its stamp starts at
    // "just now" — deliberately including keys already tracked from an earlier
    // visit (Dashboard → Issues → Dashboard), whose stale per-session times
    // must not be shown against freshly-rendered data.
    markAllVisible: function () {
      var self = this;
      document.querySelectorAll('[data-updated-stamp]').forEach(function (el) {
        var key = el.getAttribute('data-updated-stamp');
        if (key) { self.mark(key); }
      });
    },

    formatAgo: function (deltaMs) {
      var s = Math.floor(deltaMs / 1000);
      if (s < 5) { return 'updated just now'; }
      if (s < 60) { return 'updated ' + s + 's ago'; }
      var m = Math.floor(s / 60);
      return 'updated ' + m + 'm ago';
    }
  };
  window.markUpdated = function (key) { UpdateStamps.mark(key); };

  setInterval(function () { UpdateStamps.renderAll(); }, 1000);

  // Maps an htmx swap target's element id to the stamp key for that live
  // region — the actual hx-target ids used by the dashboard/pipeline polls
  // and the queue's SSE-triggered refresh (see issues.html, dashboard.html,
  // issue-detail.html).
  var SWAP_TARGET_STAMPS = {
    'issue-table-body': 'queue',
    'dashboard-live': 'dashboard',
    'live-status': 'pipeline'
  };

  // --- Event delegation ---------------------------------------------------
  document.addEventListener('click', function (e) {
    if (e.target.closest('#theme-toggle')) {
      toggleTheme();
      return;
    }
    if (e.target.closest('.mobile-menu-btn')) {
      setMenu(!menuIsOpen());
      return;
    }
    if (e.target.closest('.sidebar-overlay')) {
      setMenu(false);
      return;
    }
    // Close the mobile menu when a nav link is clicked.
    if (e.target.closest('.sidebar ul li a') && menuIsOpen()) {
      setMenu(false);
    }
  });

  // --- Double-submit guard for plain (non-HTMX) form submits --------------
  // HTMX-driven mutations are guarded with hx-disabled-elt. Plain forms that
  // POST a full page navigation (e.g. the Retry / Mark Complete modals) are not,
  // so disable their submit button on first submit to prevent a double-fire.
  // We do NOT touch forms that htmx owns (they carry hx-post/hx-get/etc.) — htmx
  // handles those and disabling here could interfere with its lifecycle.
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.nodeName !== 'FORM') { return; }
    // Skip htmx-managed forms — identified by any hx-* request attribute.
    if (form.hasAttribute('hx-post') || form.hasAttribute('hx-get') ||
        form.hasAttribute('hx-put') || form.hasAttribute('hx-delete') ||
        form.hasAttribute('hx-patch') || form.hasAttribute('data-hx-post')) {
      return;
    }
    var btn = form.querySelector('button[type="submit"], input[type="submit"]');
    if (!btn || btn.disabled) { return; }
    // Let the browser collect the value/submit first, then disable on next tick
    // so the button's name/value is still included in the POST body.
    setTimeout(function () { btn.disabled = true; }, 0);
  });

  // --- Keyboard-accessible navigable rows ---------------------------------
  // Table rows are click-to-open; mirror that for keyboard users. Rows carry
  // a [data-issue-href]; Enter or Space navigates to the issue detail.
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') { return; }
    var row = e.target.closest('[data-issue-href]');
    if (!row) { return; }
    // Don't hijack keys aimed at a control inside the row (e.g. Start button).
    if (e.target !== row && e.target.closest('button, a, input, select, textarea')) {
      return;
    }
    e.preventDefault();
    var href = row.getAttribute('data-issue-href');
    if (!href) { return; }
    if (window.htmx && typeof window.htmx.ajax === 'function') {
      window.htmx.ajax('GET', href, { target: '#content', pushUrl: true });
    } else {
      window.location.href = href;
    }
  });

  // --- Diff coloring ------------------------------------------------------
  // Diffs render as plain text inside [data-diff] containers. Split into lines
  // and wrap +/- lines in colored spans. textContent (never innerHTML) is used
  // per line so content is HTML-escaped safely.
  function colorizeDiff(el) {
    if (el.__diffColorized) { return; }
    el.__diffColorized = true;
    var raw = el.textContent || '';
    el.textContent = '';
    var lines = raw.split('\n');
    var frag = document.createDocumentFragment();
    // .diff-line is display:block, so each line is its own row — no \n needed.
    lines.forEach(function (lineText) {
      var span = document.createElement('span');
      span.className = 'diff-line';
      if (lineText.indexOf('+++') === 0 || lineText.indexOf('---') === 0) {
        span.className += ' diff-header';
      } else if (lineText.indexOf('@@') === 0) {
        // hunk header — leave neutral
      } else if (lineText.charAt(0) === '+') {
        span.className += ' added';
      } else if (lineText.charAt(0) === '-') {
        span.className += ' removed';
      }
      // Preserve empty lines as a row of height via a zero-width space.
      span.textContent = lineText.length ? lineText : '​';
      frag.appendChild(span);
    });
    el.appendChild(frag);
  }

  function colorizeDiffs() {
    document.querySelectorAll('[data-diff]').forEach(colorizeDiff);
  }

  // --- Live terminal controller ------------------------------------------
  // Hardened singleton: closes any existing EventSource before opening a new
  // one so a partial HTMX swap re-running the inline init script cannot leak
  // duplicate connections. Tracks auto-scroll (follow) state and surfaces a
  // "lines trimmed" note + "jump to bottom" affordance.
  // Maps a line's recognized prefix to a CSS modifier class (#84). Checked in
  // order against the start of the line; first match wins. Lines with no
  // recognized prefix (e.g. plain assistant text) get no extra class.
  var TERMINAL_PREFIX_CLASSES = [
    ['[local-check]', 'term-localcheck'],
    ['[stderr]', 'term-stderr'],
    ['[system]', 'term-system'],
    ['[tool_use]', 'term-tool'],
    ['[tool_result]', 'term-tool'],
    ['[tool]', 'term-tool'],
    ['[result]', 'term-result'],
    ['[raw]', 'term-raw']
  ];

  function classifyTerminalLine(text) {
    for (var i = 0; i < TERMINAL_PREFIX_CLASSES.length; i++) {
      if (text.indexOf(TERMINAL_PREFIX_CLASSES[i][0]) === 0) { return TERMINAL_PREFIX_CLASSES[i][1]; }
    }
    return '';
  }

  var IssueBotTerminal = {
    es: null,
    issueId: null,
    follow: true,
    lineCount: 0,
    trimmed: 0,
    trimMarkerInserted: false,
    maxLines: 5000,
    longLineThreshold: 500,

    init: function (issueId) {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      this.issueId = issueId;
      this.lineCount = 0;
      this.trimmed = 0;
      this.trimMarkerInserted = false;
      this.follow = true;
      this._wireControls(terminal);
      this._openStream();
    },

    _openStream: function () {
      var self = this;
      // Harden the singleton: close any pre-existing connection first.
      if (window.__issueBotES) {
        try { window.__issueBotES.close(); } catch (e) { /* ignore */ }
        window.__issueBotES = null;
        SseStatus.clear('terminal');
      }
      var es = new EventSource('/api/events/stream');
      window.__issueBotES = es;
      self.es = es;

      // EventSource auto-reconnects on drop, firing 'error' then 'open' again —
      // the dot mirrors that lifecycle directly, no extra retry bookkeeping needed.
      es.addEventListener('open', function () { SseStatus.set('terminal', 'connected'); });
      es.addEventListener('error', function () { SseStatus.set('terminal', 'reconnecting'); });

      es.addEventListener('claude-log', function (e) {
        try {
          var data = JSON.parse(e.data);
          if (data.issueId !== self.issueId) { return; }
          self._appendLine(data.text || '');
        } catch (err) { /* ignore malformed payloads */ }
      });
    },

    _wireControls: function (terminal) {
      var self = this;
      // Detect manual scroll-up to disable follow; re-enable when back at bottom.
      if (!terminal.__scrollWired) {
        terminal.__scrollWired = true;
        terminal.addEventListener('scroll', function () {
          var atBottom = (terminal.scrollHeight - terminal.scrollTop - terminal.clientHeight) < 24;
          self._setFollow(atBottom);
        });
      }
      this._setFollow(true);
    },

    _setFollow: function (follow) {
      this.follow = follow;
      var lock = document.querySelector('[data-terminal-scroll-lock]');
      if (lock) {
        lock.setAttribute('aria-pressed', follow ? 'false' : 'true');
        var label = follow ? '<i class="ti ti-arrow-down-circle" aria-hidden="true"></i> Following'
                           : '<i class="ti ti-lock" aria-hidden="true"></i> Paused';
        lock.innerHTML = label;
      }
      var jump = document.querySelector('[data-terminal-jump]');
      if (jump) { jump.hidden = follow; }
    },

    // --- Filter (#84) --------------------------------------------------
    // Case-insensitive substring match against each line's full (unclamped)
    // text, stored in dataset.raw. Applied to existing lines on every
    // keystroke and to each new line as it arrives.
    _currentFilter: function () {
      var input = document.querySelector('[data-terminal-filter]');
      return input ? input.value.trim().toLowerCase() : '';
    },

    _matchesFilter: function (raw, filter) {
      if (!filter) { return true; }
      return raw.toLowerCase().indexOf(filter) !== -1;
    },

    applyFilter: function (value) {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      var filter = (value || '').trim().toLowerCase();
      var self = this;
      Array.prototype.forEach.call(terminal.children, function (line) {
        if (line.classList.contains('term-trim-marker')) { return; } // filter-exempt
        var raw = (line.dataset && line.dataset.raw) || '';
        line.classList.toggle('hidden', !self._matchesFilter(raw, filter));
      });
    },

    clearFilter: function () {
      var input = document.querySelector('[data-terminal-filter]');
      if (input) { input.value = ''; }
      this.applyFilter('');
    },

    // --- Buffered raw text (#84) ----------------------------------------
    // Shared by Copy and Download so both always agree: the full text of
    // every buffered line (including ones currently hidden by the filter),
    // joined with newlines. Only covers what's still in the DOM buffer —
    // lines evicted by the FIFO cap are gone (see the trim marker below).
    _bufferedText: function () {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return ''; }
      return Array.prototype.map.call(terminal.children, function (line) {
        return (line.dataset && line.dataset.raw) || '';
      }).join('\n');
    },

    // Removes the oldest buffered line for the FIFO cap, but never the
    // one-off trim marker (identified by .term-trim-marker) once inserted —
    // it always occupies position 0 from then on, so the victim is its next
    // sibling instead. Returns false if there was nothing left to remove.
    _trimOldest: function (terminal) {
      var victim = terminal.firstChild;
      if (victim && victim.classList && victim.classList.contains('term-trim-marker')) {
        victim = victim.nextSibling;
      }
      if (!victim) { return false; }
      terminal.removeChild(victim);
      this.trimmed++;
      return true;
    },

    _appendLine: function (text) {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      if (this.lineCount === 0) { terminal.innerHTML = ''; }

      var line = document.createElement('div');
      line.className = 'terminal-line';
      var prefixClass = classifyTerminalLine(text);
      if (prefixClass) { line.className += ' ' + prefixClass; }
      line.dataset.raw = text;

      var textSpan = document.createElement('span');
      textSpan.className = 'term-line-text';
      textSpan.textContent = text;
      line.appendChild(textSpan);

      // Long-line collapse (#84): lines over the threshold render clamped
      // (CSS max-height + ellipsis) with an inline show more/less toggle.
      // dataset.raw above already carries the untruncated text regardless.
      if (text.length > this.longLineThreshold) {
        line.classList.add('clamped');
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'term-line-toggle';
        toggle.textContent = 'show more';
        toggle.setAttribute('aria-expanded', 'false');
        line.appendChild(toggle);
      }

      if (!this._matchesFilter(text, this._currentFilter())) {
        line.classList.add('hidden');
      }

      terminal.appendChild(line);
      this.lineCount++;

      // FIFO cap (#84): raised from 200 to 5,000 rendered lines. The first
      // time a trim happens, a one-off marker line is inserted at the front
      // of the buffer so the gap is visible in both the live view and any
      // Copy/Download taken afterward. _trimOldest skips the marker itself
      // once it exists (it always sits at position 0) — otherwise the very
      // next trim would immediately evict the marker it just inserted.
      while (terminal.children.length > this.maxLines) {
        if (!this._trimOldest(terminal)) { break; }
      }
      if (this.trimmed > 0) {
        var note = document.getElementById('terminal-trim-note');
        if (note) {
          note.hidden = false;
          note.textContent = this.trimmed + ' earlier line' + (this.trimmed === 1 ? '' : 's') + ' trimmed';
        }
        if (!this.trimMarkerInserted) {
          this.trimMarkerInserted = true;
          var marker = document.createElement('div');
          // Deliberately filter-exempt: the truncation notice must stay visible
          // even when a filter is active, or filtered views look complete.
          marker.className = 'terminal-line term-system term-trim-marker';
          var markerText = '… earlier output trimmed';
          marker.textContent = markerText;
          marker.dataset.raw = markerText;
          terminal.insertBefore(marker, terminal.firstChild);
          // Inserting the marker pushes the total one past the cap — trim
          // exactly one more real line (never the marker) to compensate.
          while (terminal.children.length > this.maxLines) {
            if (!this._trimOldest(terminal)) { break; }
          }
        }
      }

      if (this.follow) {
        terminal.scrollTop = terminal.scrollHeight;
      }
    },

    jumpToBottom: function () {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      this._setFollow(true);
      terminal.scrollTop = terminal.scrollHeight;
    },

    // Copies the same buffered raw text as Download (#84) — previously this
    // copied the terminal's rendered innerText, which would have included
    // "show more" button labels and excluded clamped overflow.
    copy: function () {
      copyText(this._bufferedText(), document.querySelector('[data-terminal-copy]'));
    },

    // Downloads the full buffered log as a text file. Covers only what's
    // still in the DOM buffer (see the FIFO cap above) — not the complete
    // server-side history if lines have been trimmed since the run started.
    download: function () {
      var text = this._bufferedText();
      var blob = new Blob([text], { type: 'text/plain' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = 'issue-' + this.issueId + '-terminal.txt';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    }
  };
  window.IssueBotTerminal = IssueBotTerminal;

  // --- Accessible modal dialogs ------------------------------------------
  var FOCUSABLE = 'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])';
  var activeModal = null;
  var lastFocused = null;

  function openModal(id) {
    var modal = document.getElementById(id);
    if (!modal) { return; }
    lastFocused = document.activeElement;
    modal.hidden = false;
    modal.classList.add('open');
    activeModal = modal;
    var focusTarget = modal.querySelector('[data-modal-autofocus]') || modal.querySelector(FOCUSABLE);
    if (focusTarget) { focusTarget.focus(); }
  }

  function closeModal(modal) {
    if (!modal) { return; }
    modal.hidden = true;
    modal.classList.remove('open');
    if (activeModal === modal) { activeModal = null; }
    if (lastFocused && typeof lastFocused.focus === 'function') {
      lastFocused.focus();
      lastFocused = null;
    }
  }

  function trapFocus(e) {
    if (!activeModal || e.key !== 'Tab') { return; }
    var nodes = Array.prototype.slice.call(activeModal.querySelectorAll(FOCUSABLE))
      .filter(function (n) { return n.offsetParent !== null; });
    if (!nodes.length) { return; }
    var first = nodes[0];
    var last = nodes[nodes.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }

  // --- Repositories: shared Remove-repository modal ------------------------
  // The Remove button on each row carries [data-remove-repo] plus data-repo-name /
  // data-issue-count / data-delete-url (Thymeleaf HTML-escapes these). Rather than render
  // one modal per row, a single shared #remove-repo-modal is populated from whichever
  // button was clicked, then its form's hx-delete target is (re)pointed at that repo and
  // handed to htmx.process so the dynamically-set attribute takes effect (#81).
  function populateRemoveRepoModal(ds) {
    var nameEl = document.getElementById('remove-repo-name');
    if (nameEl) { nameEl.textContent = ds.repoName || 'this repository'; }
    var countEl = document.getElementById('remove-repo-issue-count');
    if (countEl) { countEl.textContent = ds.issueCount || '0'; }
    var form = document.getElementById('remove-repo-form');
    if (form && ds.deleteUrl) {
      form.setAttribute('hx-delete', ds.deleteUrl);
      if (window.htmx && typeof window.htmx.process === 'function') { window.htmx.process(form); }
    }
  }

  // Delegated modal controls.
  document.addEventListener('click', function (e) {
    var removeRepoBtn = e.target.closest('[data-remove-repo]');
    if (removeRepoBtn) {
      populateRemoveRepoModal(removeRepoBtn.dataset);
      // Falls through — the same button also carries [data-modal-open] to open it.
    }
    var opener = e.target.closest('[data-modal-open]');
    if (opener) {
      e.preventDefault();
      openModal(opener.getAttribute('data-modal-open'));
      return;
    }
    if (e.target.closest('[data-modal-close]')) {
      e.preventDefault();
      closeModal(e.target.closest('[data-modal]'));
      return;
    }
    // Backdrop click (outside the .modal) closes.
    if (e.target.matches('[data-modal]')) {
      closeModal(e.target);
      return;
    }
    if (e.target.closest('[data-terminal-scroll-lock]')) {
      e.preventDefault();
      IssueBotTerminal._setFollow(!IssueBotTerminal.follow);
      if (IssueBotTerminal.follow) { IssueBotTerminal.jumpToBottom(); }
      return;
    }
    if (e.target.closest('[data-terminal-jump]')) {
      e.preventDefault();
      IssueBotTerminal.jumpToBottom();
      return;
    }
    if (e.target.closest('[data-terminal-copy]')) {
      e.preventDefault();
      IssueBotTerminal.copy();
      return;
    }
    if (e.target.closest('[data-terminal-download]')) {
      e.preventDefault();
      IssueBotTerminal.download();
      return;
    }
    if (e.target.closest('[data-terminal-filter-clear]')) {
      e.preventDefault();
      IssueBotTerminal.clearFilter();
      return;
    }
    var lineToggle = e.target.closest('.term-line-toggle');
    if (lineToggle) {
      e.preventDefault();
      var toggleLine = lineToggle.closest('.terminal-line');
      if (toggleLine) {
        var expanded = toggleLine.classList.toggle('expanded');
        toggleLine.classList.toggle('clamped', !expanded);
        lineToggle.textContent = expanded ? 'show less' : 'show more';
        lineToggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
      }
      return;
    }
    // Approvals reject inline panel. The toggle button carries
    // [data-reject-toggle]=<issueId>; the panel has id "reject-form-<issueId>"
    // and contains a [data-reject-textarea]. Cancel carries [data-reject-cancel].
    // Delegated here (loaded once) so it survives HTMX content swaps — the panel
    // markup is re-rendered by the approvals fragment but this listener is not.
    var rejectToggle = e.target.closest('[data-reject-toggle]');
    if (rejectToggle) {
      var panel = document.getElementById('reject-form-' + rejectToggle.getAttribute('data-reject-toggle'));
      if (panel) {
        panel.hidden = !panel.hidden;
        if (!panel.hidden) {
          var ta = panel.querySelector('[data-reject-textarea]');
          if (ta) { ta.focus(); }
        }
      }
      return;
    }
    var rejectCancel = e.target.closest('[data-reject-cancel]');
    if (rejectCancel) {
      var cancelPanel = document.getElementById('reject-form-' + rejectCancel.getAttribute('data-reject-cancel'));
      if (cancelPanel) { cancelPanel.hidden = true; }
      return;
    }
    // Generic copy-to-clipboard: copies the textContent of the element
    // referenced by the button's [data-copy-target] selector.
    var copyBtn = e.target.closest('[data-copy-target]');
    if (copyBtn) {
      e.preventDefault();
      var target = document.querySelector(copyBtn.getAttribute('data-copy-target'));
      if (target) { copyText(target.innerText || target.textContent || '', copyBtn); }
      return;
    }
  });

  // Live terminal filter box (#84): substring-hides non-matching lines as
  // the operator types, both for lines already rendered and (via _appendLine
  // consulting the same input) for lines that arrive afterward.
  // Debounced: a full-buffer rescan per keystroke is wasteful at 5,000 lines.
  var terminalFilterDebounce = null;
  document.addEventListener('input', function (e) {
    if (e.target && e.target.matches && e.target.matches('[data-terminal-filter]')) {
      var value = e.target.value;
      clearTimeout(terminalFilterDebounce);
      terminalFilterDebounce = setTimeout(function () {
        IssueBotTerminal.applyFilter(value);
      }, 150);
    }
  });

  document.addEventListener('keydown', function (e) {
    if (!activeModal) { return; }
    if (e.key === 'Escape') {
      closeModal(activeModal);
    } else if (e.key === 'Tab') {
      trapFocus(e);
    }
  });

  // --- Repositories add/edit form ----------------------------------------
  // The Edit button carries every repo field as a data-* attribute (Thymeleaf
  // HTML-escapes these, so no injection is possible — unlike the old inline
  // onclick that string-concatenated owner/name/branch into a JS call). On
  // click we read the dataset, populate the form by element id, then reveal +
  // scroll + focus.
  function setChecked(id, val) {
    var el = document.getElementById(id);
    if (el) { el.checked = (val === 'true' || val === true); }
  }
  function setValue(id, val) {
    var el = document.getElementById(id);
    if (el) { el.value = (val == null ? '' : val); }
  }

  // --- Autonomy presets -----------------------------------------------
  // "Observe / Assist / Autonomous" are pure client-side sugar over six
  // existing fields — nothing new is posted (the <select> has no [name]).
  // AUTONOMY_FIELD_META maps each mapped field to its element id + how to
  // read/write it, so the preset-application and preset-derivation code
  // below share one source of truth for "which fields are mapped."
  var AUTONOMY_FIELD_META = {
    mode: { id: 'mode', type: 'value' },
    autoStart: { id: 'auto-start', type: 'checked' },
    autoMerge: { id: 'auto-merge', type: 'checked' },
    decompositionMode: { id: 'decomposition-mode', type: 'value' },
    followUpMode: { id: 'follow-up-mode', type: 'value' },
    planFirst: { id: 'plan-first', type: 'checked' }
  };
  var AUTONOMY_FIELD_IDS = Object.keys(AUTONOMY_FIELD_META).map(function (key) {
    return AUTONOMY_FIELD_META[key].id;
  });

  var AUTONOMY_PRESETS = {
    OBSERVE: {
      mode: 'APPROVAL_GATED', autoStart: false, autoMerge: false,
      decompositionMode: 'PROPOSE', followUpMode: 'COMMENT_ONLY', planFirst: true
    },
    ASSIST: {
      mode: 'APPROVAL_GATED', autoStart: true, autoMerge: false,
      decompositionMode: 'PROPOSE', followUpMode: 'ROLLING_BACKLOG', planFirst: false
    },
    AUTONOMOUS: {
      mode: 'AUTONOMOUS', autoStart: true, autoMerge: true,
      decompositionMode: 'AUTO', followUpMode: 'ROLLING_BACKLOG', planFirst: false
    }
  };

  var AUTONOMY_PRESET_DESCRIPTIONS = {
    OBSERVE: 'Nothing happens without your approval — plans, splits, and merges all wait for you.',
    ASSIST: 'IssueBot works automatically but PRs wait for your approval.',
    AUTONOMOUS: 'Full autopilot — auto-start, auto-merge, automatic splitting.',
    CUSTOM: 'Your own combination of the advanced settings below.'
  };

  function updatePresetDescription(name) {
    var el = document.getElementById('preset-description');
    if (el) { el.textContent = AUTONOMY_PRESET_DESCRIPTIONS[name] || AUTONOMY_PRESET_DESCRIPTIONS.CUSTOM; }
  }

  function setAdvancedOpen(open) {
    var details = document.getElementById('advanced-settings');
    if (details) { details.open = !!open; }
  }

  // Reads the six mapped fields' current values off the DOM.
  function readAutonomyValues() {
    var values = {};
    Object.keys(AUTONOMY_FIELD_META).forEach(function (key) {
      var meta = AUTONOMY_FIELD_META[key];
      var el = document.getElementById(meta.id);
      values[key] = el ? (meta.type === 'checked' ? el.checked : el.value) : null;
    });
    return values;
  }

  function matchesPreset(values, preset) {
    return Object.keys(AUTONOMY_FIELD_META).every(function (key) {
      return values[key] === preset[key];
    });
  }

  // Returns 'OBSERVE' | 'ASSIST' | 'AUTONOMOUS' if the six mapped fields
  // exactly match a known preset, else 'CUSTOM'.
  function derivePreset() {
    var current = readAutonomyValues();
    var names = ['OBSERVE', 'ASSIST', 'AUTONOMOUS'];
    for (var i = 0; i < names.length; i++) {
      if (matchesPreset(current, AUTONOMY_PRESETS[names[i]])) { return names[i]; }
    }
    return 'CUSTOM';
  }

  // Updates the preset <select> + description text, and — only when
  // `allowCollapse` is true — the Advanced-settings open/closed state.
  // Manual edits to a mapped field must flip the selector to Custom
  // WITHOUT collapsing the section the user is actively editing, so that
  // path calls this with allowCollapse=false.
  function syncPresetUi(name, allowCollapse) {
    var select = document.getElementById('autonomy-preset');
    if (select) { select.value = name; }
    updatePresetDescription(name);
    if (allowCollapse) { setAdvancedOpen(name === 'CUSTOM'); }
  }

  // Applies a named preset's field values (Custom is a no-op on the fields —
  // it just opens Advanced so the user can see what they're working with).
  function applyPreset(name) {
    var preset = AUTONOMY_PRESETS[name];
    if (preset) {
      Object.keys(AUTONOMY_FIELD_META).forEach(function (key) {
        var meta = AUTONOMY_FIELD_META[key];
        if (meta.type === 'checked') { setChecked(meta.id, preset[key]); }
        else { setValue(meta.id, preset[key]); }
      });
    }
    syncPresetUi(name, true);
  }

  // Stored allowedPaths is JSON (e.g. ["src/","test/"]); the form input is a
  // comma-separated string. Convert back, tolerating non-JSON/blank values.
  function allowedPathsToInput(raw) {
    if (!raw) { return ''; }
    try {
      var parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) { return parsed.join(', '); }
    } catch (e) { /* not JSON — fall through */ }
    return raw;
  }

  function showRepoForm() {
    var form = document.getElementById('add-repo-form');
    if (!form) { return; }
    form.hidden = false;
    form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    var first = document.getElementById('owner');
    if (first) { first.focus(); }
  }

  function resetRepoForm() {
    var title = document.getElementById('form-title');
    if (title) { title.textContent = 'Add Repository'; }
    setValue('edit-id', '');
    setValue('owner', '');
    setValue('repo-name', '');
    setValue('branch', 'main');
    setValue('max-iterations', '5');
    setValue('max-review-iterations', '2');
    setValue('review-pass-threshold', '0.70');
    setValue('issue-budget-usd', '');
    setValue('implementation-model', '');
    setValue('review-model', '');
    setChecked('pre-screen-enabled', true);
    setChecked('security-review', false);
    setValue('allowed-paths', '');
    setValue('verification-commands', '');
    setValue('custom-instructions', '');
    setChecked('lessons-enabled', false);
    setChecked('ci-enabled', true);
    setValue('ci-timeout', '15');
    syncCiTimeout();
    // The six autonomy-mapped fields (mode/autoStart/autoMerge/decompositionMode/
    // followUpMode/planFirst) are set via the Assist preset — the blank-form
    // default is "Assist", per #65. This makes the blank form's effective
    // default mode APPROVAL_GATED (previously AUTONOMOUS, the <select>'s first
    // option) — an intentional behavior change, since Assist maps mode to
    // APPROVAL_GATED and Assist is now the documented default preset.
    applyPreset('ASSIST');
  }

  function editRepoFromDataset(ds) {
    var title = document.getElementById('form-title');
    if (title) { title.textContent = 'Edit Repository'; }
    setValue('edit-id', ds.id);
    setValue('owner', ds.owner);
    setValue('repo-name', ds.name);
    setValue('branch', ds.branch);
    setValue('mode', ds.mode);
    setValue('max-iterations', ds.maxIterations);
    setValue('max-review-iterations', ds.maxReviewIterations);
    setValue('review-pass-threshold', ds.reviewPassThreshold);
    setValue('issue-budget-usd', ds.issueBudgetUsd);
    setValue('implementation-model', ds.implementationModel);
    setValue('review-model', ds.reviewModel);
    setChecked('auto-start', ds.autoStart);
    setValue('follow-up-mode', ds.followUpMode);
    setValue('decomposition-mode', ds.decompositionMode);
    setChecked('pre-screen-enabled', ds.preScreenEnabled);
    setChecked('plan-first', ds.planFirst);
    setChecked('auto-merge', ds.autoMerge);
    setChecked('security-review', ds.securityReviewEnabled);
    setValue('allowed-paths', allowedPathsToInput(ds.allowedPaths));
    setValue('verification-commands', ds.verificationCommands);
    setValue('custom-instructions', ds.customInstructions);
    setChecked('lessons-enabled', ds.lessonsEnabled);
    setChecked('ci-enabled', ds.ciEnabled);
    setValue('ci-timeout', ds.ciTimeoutMinutes);
    syncCiTimeout();
    // Derive which preset (if any) the loaded repo's values match, so editing
    // a repo whose config is exactly Assist/Observe/Autonomous shows that
    // preset selected (and Advanced collapsed); a mixed config shows Custom
    // (and Advanced expanded so the mismatched fields are visible).
    syncPresetUi(derivePreset(), true);
  }

  // Show/hide the CI timeout field based on the CI-enabled checkbox.
  function syncCiTimeout() {
    var cb = document.getElementById('ci-enabled');
    var group = document.getElementById('ci-timeout-group');
    if (cb && group) { group.style.display = cb.checked ? '' : 'none'; }
  }

  document.addEventListener('click', function (e) {
    var addBtn = e.target.closest('[data-show-add-form]');
    if (addBtn) {
      resetRepoForm();
      showRepoForm();
      return;
    }
    var cancelBtn = e.target.closest('[data-cancel-form]');
    if (cancelBtn) {
      var form = document.getElementById('add-repo-form');
      if (form) { form.hidden = true; }
      return;
    }
    var editBtn = e.target.closest('[data-edit-repo]');
    if (editBtn) {
      editRepoFromDataset(editBtn.dataset);
      showRepoForm();
      return;
    }
  });

  // Delegated change handler for the CI-enabled toggle.
  document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'ci-enabled') {
      syncCiTimeout();
    }
  });

  // Delegated change handler for the autonomy preset select + its six mapped
  // fields. Choosing a preset applies it (Custom is a no-op on the fields —
  // just opens Advanced). Manually editing a mapped field re-derives the
  // preset and flips the selector to Custom (or back) without touching the
  // Advanced open/closed state, since the user is actively looking at it.
  document.addEventListener('change', function (e) {
    if (!e.target || !e.target.id) { return; }
    if (e.target.id === 'autonomy-preset') {
      applyPreset(e.target.value);
      return;
    }
    if (AUTONOMY_FIELD_IDS.indexOf(e.target.id) !== -1) {
      syncPresetUi(derivePreset(), false);
    }
  });

  // --- Models card (custom model select) -----------------------------------
  // The Implementation/Review selects on the Settings page offer a
  // "Custom…" option; picking it reveals a sibling .custom-model-input text
  // field (scoped to the select's .field-group container). On submit, any
  // .model-select still set to "__custom__" gets a new <option> appended
  // whose value is the custom input's text, so the posted <select> param
  // carries the real model ID. (The Utility select has no custom option and
  // is untouched by either handler.)
  function customModelInputFor(select) {
    var group = select.closest('.field-group');
    return group ? group.querySelector('.custom-model-input') : null;
  }

  document.addEventListener('change', function (e) {
    var select = e.target;
    if (!select.classList || !select.classList.contains('model-select')) { return; }
    var input = customModelInputFor(select);
    if (!input) { return; }
    var custom = (select.value === '__custom__');
    input.hidden = !custom;
    // Required only while visible: blocks submitting an empty custom ID, but a
    // hidden required input would invisibly wedge the form.
    input.required = custom;
  });

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.nodeName !== 'FORM') { return; }
    var selects = form.querySelectorAll('.model-select');
    Array.prototype.forEach.call(selects, function (select) {
      if (select.value !== '__custom__') { return; }
      var input = customModelInputFor(select);
      var value = input ? input.value.trim() : '';
      if (!value) { return; }
      var opt = document.createElement('option');
      opt.value = value;
      opt.textContent = value;
      opt.selected = true;
      select.appendChild(opt);
      select.value = value;
    });
  });

  // --- Sortable tables ----------------------------------------------------
  // Generic helper: any <table data-sortable> whose <th data-sort="number|text">
  // headers become click-to-sort. Cells may carry a data-value attribute that
  // overrides their text for sort purposes (used for currency/number columns).
  function cellSortValue(row, index, type) {
    var cell = row.children[index];
    if (!cell) { return type === 'number' ? 0 : ''; }
    var raw = cell.hasAttribute('data-value')
      ? cell.getAttribute('data-value')
      : cell.textContent;
    raw = (raw || '').trim();
    if (type === 'number') {
      var num = parseFloat(raw.replace(/[^0-9.eE+-]/g, ''));
      return isNaN(num) ? 0 : num;
    }
    return raw.toLowerCase();
  }

  function sortTableBy(table, index, dir) {
    var headers = table.tHead ? table.tHead.rows[0].cells : [];
    var th = headers[index];
    var type = (th && th.getAttribute('data-sort')) || 'text';
    var tbody = table.tBodies[0];
    if (!tbody) { return; }
    var rows = Array.prototype.slice.call(tbody.rows);
    var sign = dir === 'desc' ? -1 : 1;
    rows.sort(function (a, b) {
      var va = cellSortValue(a, index, type);
      var vb = cellSortValue(b, index, type);
      if (va < vb) { return -1 * sign; }
      if (va > vb) { return 1 * sign; }
      return 0;
    });
    rows.forEach(function (r) { tbody.appendChild(r); });
    for (var i = 0; i < headers.length; i++) {
      headers[i].removeAttribute('aria-sort');
      headers[i].classList.remove('sort-asc', 'sort-desc');
    }
    if (th) {
      th.setAttribute('aria-sort', dir === 'desc' ? 'descending' : 'ascending');
      th.classList.add(dir === 'desc' ? 'sort-desc' : 'sort-asc');
    }
  }

  function initSortableTables() {
    var tables = document.querySelectorAll('table[data-sortable]');
    Array.prototype.forEach.call(tables, function (table) {
      if (table.__sortInit) { return; }
      table.__sortInit = true;
      var headers = table.tHead ? table.tHead.rows[0].cells : [];
      var defaultIndex = -1, defaultDir = 'desc';
      Array.prototype.forEach.call(headers, function (th, index) {
        if (!th.hasAttribute('data-sort')) { return; }
        th.classList.add('is-sortable');
        th.setAttribute('role', 'button');
        th.setAttribute('tabindex', '0');
        if (th.hasAttribute('data-sort-default')) {
          defaultIndex = index;
          defaultDir = th.getAttribute('data-sort-default') || 'desc';
        }
        function activate() {
          var asc = th.classList.contains('sort-asc');
          sortTableBy(table, index, asc ? 'desc' : 'asc');
        }
        th.addEventListener('click', activate);
        th.addEventListener('keydown', function (e) {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); }
        });
      });
      if (defaultIndex >= 0) { sortTableBy(table, defaultIndex, defaultDir); }
    });
  }

  // --- Cost report charts -------------------------------------------------
  function cssVar(name, fallback) {
    var v = getComputedStyle(root).getPropertyValue(name);
    return (v && v.trim()) || fallback;
  }

  function initCostTimeChart() {
    var dataEl = document.getElementById('cost-series');
    var canvas = document.getElementById('cost-over-time-chart');
    if (!dataEl || !canvas) { return; }
    if (canvas.__chart) { canvas.__chart.destroy(); }

    var parsed;
    try { parsed = JSON.parse(dataEl.textContent); } catch (e) { return; }
    var series = (parsed && parsed.series) || [];
    if (!series.length) { return; }

    var accent = cssVar('--accent', '#7c63ff');
    var grid = cssVar('--hairline', 'rgba(128,128,128,.15)');
    var textColor = cssVar('--text-secondary', '#64748b');

    canvas.__chart = new Chart(canvas, {
      type: 'line',
      data: {
        labels: series.map(function (p) { return p.label; }),
        datasets: [{
          label: 'Daily Cost ($)',
          data: series.map(function (p) { return Math.round((Number(p.cost) || 0) * 10000) / 10000; }),
          borderColor: accent,
          backgroundColor: accent,
          tension: 0.25,
          fill: false,
          pointRadius: 3
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: { label: function (ctx) { return '$' + Number(ctx.parsed.y).toFixed(4); } }
          }
        },
        scales: {
          x: { ticks: { color: textColor }, grid: { display: false } },
          y: {
            beginAtZero: true,
            ticks: { color: textColor, callback: function (v) { return '$' + Number(v).toFixed(2); } },
            grid: { color: grid }
          }
        }
      }
    });
  }

  function initCostCharts() {
    if (typeof Chart === 'undefined') { return; }
    initCostTimeChart();

    var dataEl = document.getElementById('cost-data');
    var canvas = document.getElementById('cost-by-repo-chart');
    if (!dataEl || !canvas) { return; }
    if (canvas.__chart) { canvas.__chart.destroy(); }

    var data;
    try { data = JSON.parse(dataEl.textContent); } catch (e) { return; }
    var repos = (data && data.repos) || [];
    if (!repos.length) { return; }

    var accent = cssVar('--accent', '#7c63ff');
    var grid = cssVar('--hairline', 'rgba(128,128,128,.15)');
    var textColor = cssVar('--text-secondary', '#64748b');

    var labels = repos.map(function (r) { return r.repoName; });
    var values = repos.map(function (r) {
      return Math.round((Number(r.totalCost) || 0) * 10000) / 10000;
    });

    canvas.__chart = new Chart(canvas, {
      type: 'bar',
      data: {
        labels: labels,
        datasets: [{
          label: 'Total Cost ($)',
          data: values,
          backgroundColor: accent,
          borderRadius: 6,
          maxBarThickness: 56
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: function (ctx) { return '$' + Number(ctx.parsed.y).toFixed(4); }
            }
          }
        },
        scales: {
          x: { ticks: { color: textColor }, grid: { display: false } },
          y: {
            beginAtZero: true,
            ticks: { color: textColor, callback: function (v) { return '$' + Number(v).toFixed(2); } },
            grid: { color: grid }
          }
        }
      }
    });
  }

  // Re-run toast handling + diff coloring after HTMX swaps in new content.
  document.body.addEventListener('htmx:afterSwap', function (evt) {
    dismissToasts();
    colorizeDiffs();
    initSortableTables();
    initCostCharts();

    // Last-updated stamps (#83): a #content swap is an SPA navigation — the
    // whole page region (and every stamp on it) was just freshly rendered by
    // the server, so ALL visible stamps reset to "just now". This includes
    // revisits to a page whose key was already tracked from an earlier visit;
    // without the reset, the previous visit's stale time would be shown
    // against fresh data until the next live tick. Otherwise, a swap into one
    // of the tracked live regions (queue table, dashboard metrics, issue-detail
    // pipeline — including the queue's SSE-triggered refresh, which flows
    // through htmx's normal fetch+swap cycle) marks just that region's key.
    var target = evt.detail && evt.detail.target;
    if (target && target.id === 'content') {
      UpdateStamps.markAllVisible();
    } else if (target && target.id && SWAP_TARGET_STAMPS[target.id]) {
      markUpdated(SWAP_TARGET_STAMPS[target.id]);
    }
  });

  // Close the live-terminal EventSource when navigating away (registered once).
  document.addEventListener('htmx:beforeSwap', function (evt) {
    if (evt.detail.target && evt.detail.target.id === 'content' && window.__issueBotES) {
      try { window.__issueBotES.close(); } catch (e) { /* ignore */ }
      window.__issueBotES = null;
      if (window.IssueBotTerminal) { window.IssueBotTerminal.es = null; }
      SseStatus.clear('terminal');
    }
    // htmx suppresses swaps on 4xx by default; the server renders a friendly
    // not-found page for 404s, so let it through instead of doing nothing.
    if (evt.detail.xhr && evt.detail.xhr.status === 404) {
      evt.detail.shouldSwap = true;
      evt.detail.isError = false;
    }
  });

  // Queue's htmx-sse connection (issues.html: hx-ext="sse" sse-connect="...").
  // The extension dispatches these on the sse-connect element (bubbles to
  // body) for the EventSource's open/error lifecycle and on cleanup (e.g. the
  // element being removed from the DOM when navigating to another page).
  document.body.addEventListener('htmx:sseOpen', function () { SseStatus.set('queue', 'connected'); });
  document.body.addEventListener('htmx:sseError', function () { SseStatus.set('queue', 'reconnecting'); });
  document.body.addEventListener('htmx:sseClose', function () { SseStatus.clear('queue'); });

  // --- Init ---------------------------------------------------------------
  function init() {
    syncThemeIcon();
    dismissToasts();
    colorizeDiffs();
    initSortableTables();
    initCostCharts();
    UpdateStamps.markAllVisible();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
