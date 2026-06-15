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
  var IssueBotTerminal = {
    es: null,
    issueId: null,
    follow: true,
    lineCount: 0,
    trimmed: 0,
    maxLines: 200,

    init: function (issueId) {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      this.issueId = issueId;
      this.lineCount = 0;
      this.trimmed = 0;
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
      }
      var es = new EventSource('/api/events/stream');
      window.__issueBotES = es;
      self.es = es;

      es.addEventListener('claude-log', function (e) {
        try {
          var data = JSON.parse(e.data);
          if (data.issueId !== self.issueId) { return; }
          self._appendLine(data.text || '');
        } catch (err) { /* ignore malformed payloads */ }
      });

      // Close on navigation away from this page.
      document.addEventListener('htmx:beforeSwap', function (evt) {
        if (evt.detail.target && evt.detail.target.id === 'content') {
          try { es.close(); } catch (e) { /* ignore */ }
          window.__issueBotES = null;
          self.es = null;
        }
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

    _appendLine: function (text) {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      if (this.lineCount === 0) { terminal.innerHTML = ''; }

      var line = document.createElement('div');
      line.className = 'terminal-line';
      if (text.indexOf('[tool]') === 0) {
        line.className += ' terminal-tool';
      } else if (text.indexOf('[result]') === 0) {
        line.className += ' terminal-result';
      }
      line.textContent = text;
      terminal.appendChild(line);
      this.lineCount++;

      while (terminal.children.length > this.maxLines) {
        terminal.removeChild(terminal.firstChild);
        this.trimmed++;
      }
      if (this.trimmed > 0) {
        var note = document.getElementById('terminal-trim-note');
        if (note) {
          note.hidden = false;
          note.textContent = this.trimmed + ' earlier line' + (this.trimmed === 1 ? '' : 's') + ' trimmed';
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

    copy: function () {
      var terminal = document.getElementById('live-terminal');
      if (!terminal) { return; }
      var text = terminal.innerText || terminal.textContent || '';
      var done = function () {
        var btn = document.querySelector('[data-terminal-copy]');
        if (!btn) { return; }
        var orig = btn.innerHTML;
        btn.innerHTML = '<i class="ti ti-check" aria-hidden="true"></i> Copied';
        setTimeout(function () { btn.innerHTML = orig; }, 1500);
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

  // Delegated modal controls.
  document.addEventListener('click', function (e) {
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
    setValue('mode', 'AUTONOMOUS');
    setValue('max-iterations', '5');
    setValue('max-review-iterations', '2');
    setChecked('auto-start', true);
    setChecked('follow-up-enabled', true);
    setChecked('auto-merge', false);
    setChecked('security-review', false);
    setValue('allowed-paths', '');
    setChecked('ci-enabled', true);
    setValue('ci-timeout', '15');
    syncCiTimeout();
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
    setChecked('auto-start', ds.autoStart);
    setChecked('follow-up-enabled', ds.followUpEnabled);
    setChecked('auto-merge', ds.autoMerge);
    setChecked('security-review', ds.securityReviewEnabled);
    setValue('allowed-paths', allowedPathsToInput(ds.allowedPaths));
    setChecked('ci-enabled', ds.ciEnabled);
    setValue('ci-timeout', ds.ciTimeoutMinutes);
    syncCiTimeout();
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

  // Re-run toast handling + diff coloring after HTMX swaps in new content.
  document.body.addEventListener('htmx:afterSwap', function () {
    dismissToasts();
    colorizeDiffs();
  });

  // --- Init ---------------------------------------------------------------
  function init() {
    syncThemeIcon();
    dismissToasts();
    colorizeDiffs();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
