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

  // --- Notification bell (#89) ---------------------------------------------
  // Simple show/hide dropdown (no existing dropdown convention in this app to
  // mirror — the closest is the accessible-modal pattern below, which is
  // overkill for a small panel like this). The panel's own hx-get/hx-post
  // reload its content on every open/mark-read; this layer only owns
  // hidden/aria-expanded, ESC-to-close, click-outside-to-close, and syncing
  // the bell's badge from the swapped-in fragment's data-unread-count.
  function notifBellBtn() { return document.getElementById('notif-bell-btn'); }
  function notifPanel() { return document.getElementById('notif-panel'); }

  function notifPanelIsOpen() {
    var panel = notifPanel();
    return !!panel && !panel.hidden;
  }

  function openNotifPanel() {
    var panel = notifPanel(), btn = notifBellBtn();
    if (!panel || !btn) { return; }
    panel.hidden = false;
    btn.setAttribute('aria-expanded', 'true');
    // The panel now renders at <body> level (far from the bell in the DOM), so
    // forward-Tab from the bell would otherwise walk the whole sidebar/content
    // before reaching it. Move focus into the panel (tabindex="-1" container) on
    // open; ESC/close returns focus to the bell. Content loads async via HTMX —
    // focusing the container is stable across the innerHTML swap.
    panel.focus();
  }

  function closeNotifPanel() {
    var panel = notifPanel(), btn = notifBellBtn();
    if (!panel || !btn) { return; }
    panel.hidden = true;
    btn.setAttribute('aria-expanded', 'false');
  }

  function toggleNotifPanel() {
    if (notifPanelIsOpen()) { closeNotifPanel(); } else { openNotifPanel(); }
  }

  // The unread count on the bell badge lives OUTSIDE #notif-panel (in the
  // button itself), so a swap of the panel's content — from opening it or
  // from "Mark all read" — can't update it via normal HTMX targeting. The
  // panel fragment carries the fresh count in data-unread-count for exactly
  // this: read it after every swap and reflect it on the badge.
  function markNotifUnavailable() {
    var btn = notifBellBtn();
    if (!btn) { return; }
    btn.setAttribute('title', 'Notification state unavailable');
    btn.setAttribute('aria-label', 'Notifications — unread actions unavailable');
    var badge = btn.querySelector('.notif-badge');
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'badge notif-badge';
      btn.appendChild(badge);
    }
    // Keep any last-known number visibly stale. A previous zero has no badge,
    // so show '?' rather than leaving the bell looking currently empty.
    var lastKnown = (badge.textContent || '').replace(/\?$/, '');
    badge.textContent = lastKnown + '?';
    badge.setAttribute('aria-label', lastKnown
      ? 'Unread actions unavailable; last known count ' + lastKnown
      : 'Unread actions unavailable');
  }

  // Failed HTTP responses and network failures normally do not swap content.
  // Scope the failure indication to requests targeting this panel only.
  function notificationRequestFailed(evt) {
    var detail = evt.detail || {};
    var target = detail.target || (detail.requestConfig && detail.requestConfig.target);
    if (target && target.id === 'notif-panel') { markNotifUnavailable(); }
  }
  ['htmx:responseError', 'htmx:sendError', 'htmx:timeout'].forEach(function (name) {
    document.body.addEventListener(name, notificationRequestFailed);
  });

  function syncNotifBadge(panelElement) {
    var btn = notifBellBtn();
    if (!btn || !panelElement) { return; }
    var content = panelElement.querySelector('#notif-panel-content');
    var rawCount = content && content.getAttribute('data-unread-count');
    if (rawCount == null || !/^\d+$/.test(rawCount)) {
      markNotifUnavailable();
      return;
    }
    var count = Number(rawCount);
    btn.setAttribute('title', 'Unread actions');
    btn.setAttribute('aria-label', 'Notifications — unread actions');
    var badge = btn.querySelector('.notif-badge');
    if (count > 0) {
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'badge notif-badge';
        badge.setAttribute('aria-label', 'Unread actions');
        btn.appendChild(badge);
      }
      badge.textContent = String(count);
      badge.setAttribute('aria-label', 'Unread actions');
    } else if (badge) {
      badge.remove();
    }
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
    if (e.target.closest('#notif-bell-btn')) {
      // htmx's own click listener on this same button fires the hx-get
      // independently — this only owns the open/closed visual state.
      toggleNotifPanel();
      return;
    }
    // Click outside the bell/panel closes it. The panel now lives at <body> level
    // (outside .notif-bell-wrap), so it must be excluded explicitly — otherwise a
    // click inside the panel (e.g. a notification link) would self-close it.
    if (notifPanelIsOpen() && !e.target.closest('.notif-bell-wrap') && !e.target.closest('#notif-panel')) {
      closeNotifPanel();
    }
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && notifPanelIsOpen()) {
      closeNotifPanel();
      var btn = notifBellBtn();
      if (btn) { btn.focus(); }
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
      // Passing the row as the supported HTMX request source preserves its
      // inherited hx-target and hx-push-url behavior. HTMX 2.0.4 does not
      // support the later pushUrl ajax option.
      window.htmx.ajax('GET', href, { source: row, target: '#content', event: e });
    } else {
      window.location.href = href;
    }
  });

  // --- Per-file diff viewer (#85) ------------------------------------------
  // Diffs render as plain text inside [data-diff-viewer] containers (the raw
  // unified diff produced by GitOperationsService.diff via JGit's
  // DiffFormatter — "diff --git a/... b/..." headers, "--- a/x"/"+++ b/x" or
  // "/dev/null" for adds/deletes, "@@ ... @@" hunks). We parse that text into
  // per-file sections client-side and render each as a collapsible <details>
  // with a path + "+N -M" summary, reusing the original flat colorizer's line
  // classes so existing CSS carries over. All text is inserted via
  // textContent (never innerHTML) since diff content — file paths and line
  // bodies — comes from repo data and must be treated as untrusted.

  // Classify a single diff line the same way the original flat colorizer did,
  // plus a new "hunk" class for "@@" separators (previously left neutral).
  function diffLineExtraClass(lineText) {
    if (lineText.indexOf('+++') === 0 || lineText.indexOf('---') === 0) {
      return 'diff-header';
    }
    if (lineText.indexOf('@@') === 0) {
      return 'hunk';
    }
    if (lineText.charAt(0) === '+') {
      return 'added';
    }
    if (lineText.charAt(0) === '-') {
      return 'removed';
    }
    return '';
  }

  function buildDiffLineSpan(lineText) {
    var span = document.createElement('span');
    span.className = 'diff-line';
    var extra = diffLineExtraClass(lineText);
    if (extra) { span.className += ' ' + extra; }
    // Preserve empty lines as a row of height via a zero-width space.
    span.textContent = lineText.length ? lineText : '​';
    return span;
  }

  // Today's rendering: one flat colorized block, no per-file grouping. Used
  // both as the initial render path's fallback and directly when parsing
  // finds no file boundaries.
  function renderFlatDiff(el, raw) {
    el.textContent = '';
    var frag = document.createDocumentFragment();
    // .diff-line is display:block, so each line is its own row — no \n needed.
    splitDiffLines(raw).forEach(function (lineText) {
      frag.appendChild(buildDiffLineSpan(lineText));
    });
    el.appendChild(frag);
  }

  // Strips a leading "a/" or "b/" prefix from a diff path (e.g. "b/src/Foo.java").
  function stripDiffPathPrefix(path) {
    if (path.indexOf('a/') === 0 || path.indexOf('b/') === 0) {
      return path.slice(2);
    }
    return path;
  }

  // JGit quotes paths in diff output (QuotedString.GIT_PATH, on by default)
  // whenever they contain specials — parens, brackets, braces, !, #,
  // apostrophe, backtick, <>, quotes, backslashes — or any non-ASCII byte:
  //   diff --git "a/handler(v2).js" "b/handler(v2).js"
  //   +++ "b/caf\303\251.txt"
  // Inside the quotes, `"` and `\` are backslash-escaped and non-ASCII bytes
  // appear as octal \NNN escapes carrying raw UTF-8 bytes (é is \303\251).
  // Returns the decoded path; unquoted input is returned unchanged.
  function unquoteGitPath(s) {
    if (s.length < 2 || s.charAt(0) !== '"' || s.charAt(s.length - 1) !== '"') {
      return s;
    }
    var hasOctal = false;
    // Single left-to-right pass so an escaped backslash can never be re-read
    // as the start of an octal escape (in "\\303" the 303 is literal text).
    var bytes = s.slice(1, -1).replace(/\\([0-7]{1,3}|[\s\S])/g, function (all, seq) {
      if (seq.charAt(0) >= '0' && seq.charAt(0) <= '7') {
        hasOctal = true;
        return String.fromCharCode(parseInt(seq, 8) & 0xFF);
      }
      if (seq === 't') { return '\t'; }
      if (seq === 'n') { return '\n'; }
      if (seq === 'r') { return '\r'; }
      return seq; // \" -> ", \\ -> \, anything else kept literally
    });
    if (!hasOctal) { return bytes; }
    // Octal escapes are raw UTF-8 BYTES (every char here is <= 0xFF):
    // percent-encode each byte and let decodeURIComponent reassemble
    // multi-byte sequences into actual characters.
    try {
      return decodeURIComponent(bytes.replace(/[\s\S]/g, function (ch) {
        var code = ch.charCodeAt(0);
        return '%' + (code < 16 ? '0' : '') + code.toString(16).toUpperCase();
      }));
    } catch (e) {
      return bytes; // not valid UTF-8 — best effort: show the raw bytes
    }
  }

  // Splits raw diff text into lines, stripping the trailing \r that CRLF
  // repo content would otherwise leak into every rendered line (and into the
  // trailing path capture of the "diff --git" header parsing).
  function splitDiffLines(raw) {
    return raw.split('\n').map(function (l) { return l.replace(/\r$/, ''); });
  }

  // Splits the two path tokens out of a "diff --git <a> <b>" header line,
  // handling JGit's quoted-path form (each side is quoted independently).
  // Returns [tokenA, tokenB] (still quoted/prefixed) or null.
  function splitDiffGitHeaderPaths(lineText) {
    var rest = lineText.slice(11); // after 'diff --git '
    var m;
    if (rest.charAt(0) === '"') {
      // Quoted a-side ends at its first unescaped quote.
      m = /^("(?:[^"\\]|\\[\s\S])*") ([\s\S]+)$/.exec(rest);
      return m ? [m[1], m[2]] : null;
    }
    if (rest.charAt(rest.length - 1) === '"') {
      // Unquoted a-side, quoted b-side anchored at end of line. Unquoted git
      // paths never contain '"' (a quote triggers quoting), so the b token
      // starts at the first quote.
      m = /^([\s\S]+?) ("(?:[^"\\]|\\[\s\S])*")$/.exec(rest);
      return m ? [m[1], m[2]] : null;
    }
    // Both unquoted. The greedy split on the LAST " b/" is inherently
    // ambiguous when the a-path itself contains " b/" — this header is only
    // load-bearing for entries with no ---/+++ lines (binary, rename- or
    // mode-only), so a wrong split there is an accepted cosmetic risk.
    m = /^(a\/.+) (b\/.+)$/.exec(rest);
    return m ? [m[1], m[2]] : null;
  }

  // Parses a raw unified diff into per-file sections:
  // [{ path, adds, dels, binary, lines }].
  // Returns null if no "diff --git " boundaries are found (caller should fall
  // back to the flat renderer).
  function parseDiffFiles(raw) {
    var files = [];
    var current = null;

    function finishCurrent() {
      if (!current) { return; }
      var path, deleted = false;
      if (current.plusPath && current.plusPath !== '/dev/null') {
        path = stripDiffPathPrefix(current.plusPath);
      } else if (current.minusPath && current.minusPath !== '/dev/null') {
        path = stripDiffPathPrefix(current.minusPath);
        deleted = true;
      } else if (current.headerPathB) {
        path = current.headerPathB;
      } else if (current.headerPathA) {
        path = current.headerPathA;
      } else {
        path = 'unknown file';
      }
      files.push({
        path: deleted ? (path + ' (deleted)') : path,
        adds: current.adds,
        dels: current.dels,
        // "Binary files ... differ" with no hunks: counts are meaningless,
        // render a single neutral "binary" badge instead of "+0 −0".
        binary: current.sawBinaryLine && !current.sawHunk,
        lines: current.lines
      });
    }

    splitDiffLines(raw).forEach(function (lineText) {
      if (lineText.indexOf('diff --git ') === 0) {
        finishCurrent();
        current = { adds: 0, dels: 0, lines: [lineText], plusPath: null, minusPath: null,
                    headerPathA: null, headerPathB: null, sawBinaryLine: false, sawHunk: false };
        var tokens = splitDiffGitHeaderPaths(lineText);
        if (tokens) {
          current.headerPathA = stripDiffPathPrefix(unquoteGitPath(tokens[0]));
          current.headerPathB = stripDiffPathPrefix(unquoteGitPath(tokens[1]));
        }
        return;
      }
      if (!current) { return; } // content before any file header — shouldn't happen for JGit output
      current.lines.push(lineText);
      if (lineText.indexOf('+++') === 0) {
        current.plusPath = unquoteGitPath(lineText.slice(3).trim());
      } else if (lineText.indexOf('---') === 0) {
        current.minusPath = unquoteGitPath(lineText.slice(3).trim());
      } else if (lineText.indexOf('@@') === 0) {
        current.sawHunk = true;
      } else if (lineText.indexOf('Binary files ') === 0) {
        current.sawBinaryLine = true;
      } else if (lineText.charAt(0) === '+') {
        current.adds++;
      } else if (lineText.charAt(0) === '-') {
        current.dels++;
      }
    });
    finishCurrent();

    return files.length ? files : null;
  }

  function stableDiffIdentity(path) {
    var hash = 2166136261;
    for (var i = 0; i < path.length; i++) {
      hash ^= path.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36) + '-' + path.length;
  }

  function renderDiffFile(file, defaultOpen, statePrefix) {
    var details = document.createElement('details');
    details.className = 'diff-file';
    details.open = defaultOpen;
    if (statePrefix) {
      details.setAttribute('data-ui-state-key', statePrefix + ':file:' + stableDiffIdentity(file.path));
    }

    var summary = document.createElement('summary');
    summary.className = 'diff-file-summary';

    var pathSpan = document.createElement('span');
    pathSpan.className = 'diff-file-path';
    pathSpan.textContent = file.path;
    summary.appendChild(pathSpan);

    var counts = document.createElement('span');
    counts.className = 'diff-count-group';
    if (file.binary) {
      var binSpan = document.createElement('span');
      binSpan.className = 'diff-count diff-count-binary';
      binSpan.textContent = 'binary';
      counts.appendChild(binSpan);
    } else {
      var addSpan = document.createElement('span');
      addSpan.className = 'diff-count diff-count-add';
      addSpan.textContent = '+' + file.adds;
      var delSpan = document.createElement('span');
      delSpan.className = 'diff-count diff-count-del';
      delSpan.textContent = '−' + file.dels;
      counts.appendChild(addSpan);
      counts.appendChild(delSpan);
    }
    summary.appendChild(counts);

    details.appendChild(summary);

    var body = document.createElement('div');
    body.className = 'diff-viewer diff-file-body';
    var frag = document.createDocumentFragment();
    file.lines.forEach(function (lineText) {
      frag.appendChild(buildDiffLineSpan(lineText));
    });
    body.appendChild(frag);
    details.appendChild(body);

    return details;
  }

  // Builds the per-file view into `el`: an "expand/collapse all" toolbar row
  // plus one <details class="diff-file"> per file. Falls back to the flat
  // single-block rendering when there are no parseable file boundaries.
  function renderDiffViewer(el, raw) {
    var files = parseDiffFiles(raw);
    if (!files) {
      renderFlatDiff(el, raw);
      return;
    }

    var totalLines = raw.split('\n').length;
    var defaultOpen = files.length <= 5 && totalLines <= 800;

    el.textContent = '';

    var toolbar = document.createElement('div');
    toolbar.className = 'diff-viewer-toolbar';
    var expandBtn = document.createElement('button');
    expandBtn.type = 'button';
    expandBtn.className = 'btn btn-ghost btn-sm';
    expandBtn.textContent = 'Expand all';
    expandBtn.setAttribute('data-diff-expand-all', '');
    var collapseBtn = document.createElement('button');
    collapseBtn.type = 'button';
    collapseBtn.className = 'btn btn-ghost btn-sm';
    collapseBtn.textContent = 'Collapse all';
    collapseBtn.setAttribute('data-diff-collapse-all', '');
    toolbar.appendChild(expandBtn);
    toolbar.appendChild(collapseBtn);
    el.appendChild(toolbar);

    var list = document.createElement('div');
    list.className = 'diff-file-list';
    var owner = el.closest && el.closest('details[data-ui-state-key]');
    var statePrefix = owner && owner.getAttribute('data-ui-state-key');
    files.forEach(function (file) {
      list.appendChild(renderDiffFile(file, defaultOpen, statePrefix));
    });
    el.appendChild(list);
  }

  function initDiffViewer(el) {
    if (el.dataset.diffViewerInit === 'true') { return; }
    el.dataset.diffViewerInit = 'true';
    var raw = el.textContent || '';
    if (!raw) { return; }
    try {
      renderDiffViewer(el, raw);
      if (window.IssueBotUiState) { window.IssueBotUiState.restore(el); }
    } catch (e) {
      // Degrade to today's whole-blob rendering on any parse/render surprise.
      try { renderFlatDiff(el, raw); } catch (e2) { /* leave raw text as-is */ }
    }
  }

  function initDiffViewers() {
    document.querySelectorAll('[data-diff-viewer]').forEach(initDiffViewer);
  }

  function setDiffFilesOpen(container, open) {
    if (!container) { return; }
    container.querySelectorAll('.diff-file').forEach(function (details) { details.open = open; });
    if (window.IssueBotUiState) { window.IssueBotUiState.capture(container); }
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
        rejectToggle.setAttribute('aria-controls', panel.id);
        panel.hidden = !panel.hidden;
        rejectToggle.setAttribute('aria-expanded', String(!panel.hidden));
        if (!panel.hidden) {
          var ta = panel.querySelector('[data-reject-textarea]');
          if (ta) { ta.focus(); }
        }
      }
      return;
    }
    var rejectCancel = e.target.closest('[data-reject-cancel]');
    if (rejectCancel) {
      var rejectId = rejectCancel.getAttribute('data-reject-cancel');
      var cancelPanel = document.getElementById('reject-form-' + rejectId);
      var cancelToggle = document.querySelector('[data-reject-toggle="' + rejectId + '"]');
      if (cancelPanel) { cancelPanel.hidden = true; }
      if (cancelToggle) {
        cancelToggle.setAttribute('aria-expanded', 'false');
        cancelToggle.focus();
      }
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
    // Per-file diff viewer (#85): expand/collapse-all toggles scoped to the
    // enclosing [data-diff-viewer] container.
    var diffExpandAll = e.target.closest('[data-diff-expand-all]');
    if (diffExpandAll) {
      e.preventDefault();
      var expandContainer = diffExpandAll.closest('[data-diff-viewer]');
      setDiffFilesOpen(expandContainer, true);
      return;
    }
    var diffCollapseAll = e.target.closest('[data-diff-collapse-all]');
    if (diffCollapseAll) {
      e.preventDefault();
      var collapseContainer = diffCollapseAll.closest('[data-diff-viewer]');
      setDiffFilesOpen(collapseContainer, false);
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
    if (!el) return;
    var value = val == null ? '' : String(val);
    if (el.tagName === 'SELECT' && el.matches('[data-model-select], [data-reasoning-select]')
        && !Array.from(el.options).some(function (item) { return item.value === value; })) {
      var item = document.createElement('option');
      item.value = value;
      item.textContent = value;
      el.appendChild(item);
    }
    el.value = value;
  }

  function syncPlanFirstSubmission() {
    var checkbox = document.getElementById('plan-first');
    var optOut = document.getElementById('plan-first-opt-out');
    if (checkbox && optOut) { optOut.disabled = checkbox.checked; }
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
    configureRepositoryDisclosureKeys('new');
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
    setValue('implementation-model-reasoning', '');
    setValue('review-model-reasoning', '');
    syncReasoningPickers();
    setChecked('pre-screen-enabled', true);
    setChecked('security-review', false);
    setValue('allowed-paths', '');
    setValue('verification-commands', '');
    setValue('custom-instructions', '');
    setChecked('lessons-enabled', false);
    setChecked('ci-enabled', true);
    setValue('ci-timeout', '15');
    syncCiTimeout();
    setValue('mode', 'APPROVAL_GATED');
    setChecked('auto-start', true);
    setChecked('auto-merge', false);
    setValue('decomposition-mode', 'OFF');
    setValue('follow-up-mode', 'ROLLING_BACKLOG');
    setChecked('plan-first', true);
    syncPlanFirstSubmission();
    if (window.RepositoryWorkflow) { window.RepositoryWorkflow.load(document, 'LEGACY', 'PLANNING,IMPLEMENTATION,VERIFICATION,REVIEW,MERGE'); }
  }

  function editRepoFromDataset(ds) {
    configureRepositoryDisclosureKeys(ds.id || 'new');
    var title = document.getElementById('form-title');
    if (title) { title.textContent = ds.id ? 'Edit Repository' : 'Add Repository'; }
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
    setValue('implementation-model-reasoning', ds.implementationReasoningEffort);
    setValue('review-model-reasoning', ds.reviewReasoningEffort);
    syncReasoningPickers();
    setChecked('auto-start', ds.autoStart);
    setValue('follow-up-mode', ds.followUpMode);
    setValue('decomposition-mode', ds.decompositionMode);
    setChecked('pre-screen-enabled', ds.preScreenEnabled);
    setChecked('plan-first', ds.planFirst);
    syncPlanFirstSubmission();
    setChecked('auto-merge', ds.autoMerge);
    setChecked('security-review', ds.securityReviewEnabled);
    setValue('allowed-paths', allowedPathsToInput(ds.allowedPaths));
    setValue('verification-commands', ds.verificationCommands);
    setValue('custom-instructions', ds.customInstructions);
    setChecked('lessons-enabled', ds.lessonsEnabled);
    setChecked('ci-enabled', ds.ciEnabled);
    setValue('ci-timeout', ds.ciTimeoutMinutes);
    syncCiTimeout();
    if (window.RepositoryWorkflow) {
      window.RepositoryWorkflow.load(document, ds.workflowPolicy, ds.approvalStages);
    }
  }

  function restoreSubmittedRepoForm() {
    var form = document.querySelector('[data-repository-form-values]');
    if (!form || !form.dataset.repositoryFormValues) { return; }
    try {
      editRepoFromDataset(JSON.parse(form.dataset.repositoryFormValues));
      showRepoForm();
    } catch (e) { /* Server-generated JSON should be valid; leave the safe defaults if not. */ }
  }

  function configureRepositoryDisclosureKeys(repoId) {
    var form = document.getElementById('add-repo-form');
    if (!form) { return; }
    if (form.getAttribute('data-repository-editor-active') === 'true' && window.IssueBotUiState) {
      window.IssueBotUiState.capture(form);
    }
    Array.prototype.forEach.call(form.querySelectorAll('[data-repository-disclosure]'), function (details) {
      details.setAttribute('data-ui-state-key', 'editor:' + repoId + ':' + details.getAttribute('data-repository-disclosure'));
      details.open = details.getAttribute('data-ui-state-default-open') === 'true';
    });
    form.setAttribute('data-repository-editor-active', 'true');
    if (window.IssueBotUiState) { window.IssueBotUiState.restore(form); }
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

  document.addEventListener('change', function (e) {
    if (!e.target) { return; }
    if (e.target.id === 'plan-first') { syncPlanFirstSubmission(); }
    if (window.RepositoryWorkflow &&
        (e.target.name === 'workflowPolicy' || e.target.name === 'approvalStages' ||
         e.target.id === 'implementation-model' || e.target.id === 'review-model' ||
         e.target.id === 'implementation-model-reasoning' || e.target.id === 'review-model-reasoning')) {
      window.RepositoryWorkflow.sync(document);
    }
  });

  function revealQueueDependencies() {
    if (window.location.hash !== '#dependency-map') return;
    var section = document.getElementById('queue-dependencies');
    if (section) {
      section.open = true;
      if (window.IssueBotUiState) { window.IssueBotUiState.capture(section); }
    }
  }
  document.addEventListener('DOMContentLoaded', revealQueueDependencies);
  document.addEventListener('htmx:pushedIntoHistory', revealQueueDependencies);
  window.addEventListener('hashchange', revealQueueDependencies);
  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    var menu = document.getElementById('queue-control-menu');
    if (menu && menu.open) {
      menu.open = false;
      menu.querySelector('summary').focus();
    }
  });

  function syncHarnessSelection(group, changed) {
    var harnessSelect = group.querySelector('[data-harness-select]');
    var modelSelect = group.querySelector('[data-model-select]');
    var reasoningSelect = group.querySelector('[data-reasoning-select]');
    if (!harnessSelect || !modelSelect || !reasoningSelect) return;
    var catalog;
    try { catalog = JSON.parse(group.dataset.harnessCatalog || '[]'); } catch (error) { return; }
    var harness = catalog.find(function (entry) { return entry.id === harnessSelect.value; });
    var models = harness ? harness.models : [];
    var inherited = group.dataset.inherit === 'true';
    var model = modelSelect.value;
    var reasoning = reasoningSelect.value;
    if (changed === 'harness') model = inherited ? '' : (models.length ? models[0].id : '');

    function option(value, label) {
      var item = document.createElement('option');
      item.value = value;
      item.textContent = label;
      return item;
    }
    var modelOptions = [];
    if (inherited || !model) modelOptions.push(option('', inherited ? 'Use inherited model' : 'Choose a model'));
    models.forEach(function (entry) {
      var item = option(entry.id, entry.displayName);
      item.dataset.harnessId = harnessSelect.value;
      item.dataset.modelId = entry.id;
      item.dataset.reasoningLevels = entry.supportedReasoningLevels.join(',');
      item.dataset.defaultReasoning = entry.defaultReasoningLevel;
      modelOptions.push(item);
    });
    var info = models.find(function (entry) { return entry.id === model; });
    if (model && !info) modelOptions.push(option(model, model + ' (unavailable)'));
    modelSelect.replaceChildren.apply(modelSelect, modelOptions);
    modelSelect.value = model;

    if (!model && inherited) {
      info = models.find(function (entry) { return entry.id === group.dataset.inheritedModel; });
    }
    var levels = info ? info.supportedReasoningLevels : [];
    if (changed) {
      if (!model && inherited) reasoning = '';
      else if (info && (changed === 'harness' || levels.indexOf(reasoning) < 0)) reasoning = info.defaultReasoningLevel;
    }
    var reasoningOptions = [];
    if ((inherited && !model) || !reasoning) {
      reasoningOptions.push(option('', inherited ? 'Use inherited reasoning' : 'Use model default'));
    }
    levels.forEach(function (level) { reasoningOptions.push(option(level, level)); });
    // Preserve initial or server-rejected values verbatim; only an operator change resets them.
    if (reasoning && levels.indexOf(reasoning) < 0) {
      reasoningOptions.push(option(reasoning, reasoning + ' (unsupported)'));
    }
    reasoningSelect.replaceChildren.apply(reasoningSelect, reasoningOptions);
    reasoningSelect.value = reasoning;
    group.dataset.harnessInitialized = 'true';
  }

  // Harness picker events
  function syncReasoningPickers() {
    document.querySelectorAll('[data-reasoning-picker]').forEach(function (group) {
      syncHarnessSelection(group);
    });
  }
  function initializeHarnessPickers() {
    document.querySelectorAll('[data-reasoning-picker]').forEach(function (group) {
      if (group.dataset.harnessInitialized !== 'true') syncHarnessSelection(group);
    });
  }
  document.addEventListener('DOMContentLoaded', initializeHarnessPickers);
  document.addEventListener('htmx:afterSwap', initializeHarnessPickers);
  document.addEventListener('change', function (event) {
    var target = event.target;
    if (!target.matches('[data-harness-select], [data-model-select]')) return;
    var group = target.closest('[data-reasoning-picker]');
    if (!group) return;
    var changed = target.matches('[data-harness-select]') ? 'harness' : 'model';
    var sharedForm = group.closest('[data-shared-harness-form]');
    if (changed === 'harness' && sharedForm) {
      sharedForm.querySelectorAll('[data-reasoning-picker]').forEach(function (sibling) {
        sibling.querySelector('[data-harness-select]').value = target.value;
        var display = sibling.querySelector('[data-harness-display]');
        if (display) display.value = target.selectedOptions[0].textContent;
        syncHarnessSelection(sibling, 'harness');
      });
    } else syncHarnessSelection(group, changed);
    if (window.RepositoryWorkflow) window.RepositoryWorkflow.sync(document);
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

  function initNavigationContext(scope) {
    if (!window.IssueBotNavigation) { return; }
    // The navigation module owns only result-set identity and return scrolling.
    // IssueBotUiState continues to own drafts, disclosures, toasts, and native
    // history restoration; both modules initialize the same swapped subtree.
    window.IssueBotNavigation.decorateDetail(scope || document);
  }

  // Re-run enhanced widgets after HTMX swaps in new content. ui-state.js owns
  // disclosure restoration, ordinary SPA scroll, and toast lifetimes; the
  // navigation module adds only an explicit Back-to-results scroll restore.
  document.body.addEventListener('htmx:afterSwap', function (evt) {
    initDiffViewers();
    initSortableTables();
    initCostCharts();
    updateBulkActionBar();
    restoreSubmittedRepoForm();

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
    initNavigationContext(target);
    if (target && target.id === 'content') {
      // Re-trigger footgun (mirrors the fix already applied to issues.html's
      // #issue-table-body): htmx does not reliably wire up a *nested* self-morphing
      // polling element's own hx-trigger when it first arrives as part of this larger
      // #content swap — it only "just worked" when the same fragment (issue-detail's
      // #live-status) was rendered as part of a full page load. Left unprocessed, its
      // 5s poll never starts and Live Progress reads as stuck on SETUP forever even
      // though the workflow has moved on. Explicitly reprocessing the freshly-swapped
      // subtree is idempotent for everything already wired up correctly, so this is a
      // safe no-op everywhere else.
      if (window.htmx && typeof window.htmx.process === 'function') { window.htmx.process(target); }
      UpdateStamps.markAllVisible();
    } else if (target && target.id && SWAP_TARGET_STAMPS[target.id]) {
      markUpdated(SWAP_TARGET_STAMPS[target.id]);
    }
    if (target && target.id === 'notif-panel') {
      syncNotifBadge(target);
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

  // --- Issue queue: pagination + bulk selection (#87) ----------------------
  // Changing a filter/search field must reset to page 0 — the previous page
  // number belongs to a different filtered result set and could otherwise
  // land on an out-of-range/empty page. The tbody's own SSE-triggered
  // refresh (hx-trigger="sse:issue-update", triggered on the tbody itself,
  // not on a #filter-form field) and pager link clicks (which carry their
  // own explicit page param) are deliberately left alone.
  document.body.addEventListener('htmx:configRequest', function (evt) {
    // Thymeleaf-backed forms receive a hidden token automatically. HTMX request
    // attributes are not normal form actions, so forward the same token using
    // Spring Security's configured header for every HTMX mutation.
    var tokenMeta = document.querySelector('meta[name="_csrf"]');
    var headerMeta = document.querySelector('meta[name="_csrf_header"]');
    var token = tokenMeta && tokenMeta.getAttribute('content');
    var header = headerMeta && headerMeta.getAttribute('content');
    if (token && header) { evt.detail.headers[header] = token; }

    var triggerEl = evt.detail.elt;
    if (triggerEl && triggerEl.closest && triggerEl.closest('#filter-form') &&
        (triggerEl.tagName === 'SELECT' || triggerEl.tagName === 'INPUT')) {
      evt.detail.parameters.page = '0';
    }
  });

  // Checkbox column + action bar. Selection state is intentionally NOT
  // preserved across a tbody refresh (SSE-triggered or page navigation) —
  // the rows are freshly rendered, all unchecked, so the bar re-hides itself
  // via the afterSwap hook below. That's an accepted tradeoff: a live update
  // arriving mid-selection clears it rather than risk applying a bulk action
  // to a row the operator can no longer see checked.
  function bulkCheckboxes() {
    return Array.prototype.slice.call(document.querySelectorAll('.bulk-select'));
  }

  function updateBulkActionBar() {
    var bar = document.getElementById('bulk-action-bar');
    if (!bar) { return; }
    var boxes = bulkCheckboxes();
    var checked = boxes.filter(function (b) { return b.checked; });
    bar.hidden = checked.length === 0;
    var countEl = document.getElementById('bulk-selected-count');
    if (countEl) { countEl.textContent = checked.length + ' selected'; }
    var selectAll = document.getElementById('select-all-issues');
    if (selectAll) {
      selectAll.checked = boxes.length > 0 && checked.length === boxes.length;
      selectAll.indeterminate = checked.length > 0 && checked.length < boxes.length;
    }
  }
  window.updateBulkActionBar = updateBulkActionBar;

  document.addEventListener('change', function (e) {
    if (e.target && e.target.id === 'select-all-issues') {
      var checkedAll = e.target.checked;
      bulkCheckboxes().forEach(function (b) { b.checked = checkedAll; });
      updateBulkActionBar();
      return;
    }
    if (e.target && e.target.classList && e.target.classList.contains('bulk-select')) {
      updateBulkActionBar();
    }
  });

  // --- Plan Review desk ---------------------------------------------------
  // Event delegation keeps tabs working after htmx replaces #content. Each
  // review card owns its tab/panel state so a future page can safely host more
  // than one review fragment without id/query leakage between them.
  function activatePlanTab(tab, focusTab) {
    var root = tab && tab.closest('[data-plan-review]');
    if (!root) { return; }
    var tabs = Array.prototype.slice.call(root.querySelectorAll('[data-plan-tab]'));
    tabs.forEach(function (item) {
      var selected = item === tab;
      item.setAttribute('aria-selected', String(selected));
      item.setAttribute('tabindex', selected ? '0' : '-1');
      var panel = root.querySelector('#' + item.getAttribute('aria-controls'));
      if (panel) { panel.hidden = !selected; }
    });
    if (focusTab) { tab.focus(); }
  }

  document.addEventListener('click', function (event) {
    var tab = event.target.closest && event.target.closest('[data-plan-tab]');
    if (tab) { activatePlanTab(tab, false); }
  });

  document.addEventListener('keydown', function (event) {
    var tab = event.target.closest && event.target.closest('[data-plan-tab]');
    if (!tab || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { return; }
    var tabs = Array.prototype.slice.call(
      tab.closest('[data-plan-review]').querySelectorAll('[data-plan-tab]')
    );
    var index = tabs.indexOf(tab);
    if (event.key === 'Home') { index = 0; }
    else if (event.key === 'End') { index = tabs.length - 1; }
    else if (event.key === 'ArrowLeft') { index = (index - 1 + tabs.length) % tabs.length; }
    else { index = (index + 1) % tabs.length; }
    event.preventDefault();
    activatePlanTab(tabs[index], true);
  });

  function syncPlanRevisionButton(textarea) {
    var form = textarea && textarea.closest('.plan-revision-form');
    var button = form && form.querySelector('[data-plan-revise]');
    if (button) { button.disabled = textarea.value.trim().length === 0; }
  }

  document.addEventListener('input', function (event) {
    if (event.target.matches && event.target.matches('[data-plan-revision-guidance]')) {
      syncPlanRevisionButton(event.target);
    }
  });

  // --- Init ---------------------------------------------------------------
  function init() {
    syncThemeIcon();
    initDiffViewers();
    initSortableTables();
    initCostCharts();
    updateBulkActionBar();
    restoreSubmittedRepoForm();
    initNavigationContext(document);
    UpdateStamps.markAllVisible();
    document.querySelectorAll('[data-plan-revision-guidance]').forEach(syncPlanRevisionButton);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
