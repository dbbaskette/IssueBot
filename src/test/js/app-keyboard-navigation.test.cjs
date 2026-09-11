'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const createNavigation = require('../../main/resources/static/js/navigation-context.js');

const appSource = fs.readFileSync('src/main/resources/static/js/app.js', 'utf8');

class EventTarget {
  constructor() { this.listeners = Object.create(null); }
  addEventListener(name, listener) { (this.listeners[name] ||= []).push(listener); }
  emit(name, detail, target) {
    const event = {
      type: name,
      detail: detail || {},
      target: target || this,
      key: detail && detail.key,
      defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() {}
    };
    (this.listeners[name] || []).forEach(listener => listener(event));
    return event;
  }
}

class Element extends EventTarget {
  constructor(tagName, attrs = {}) {
    super();
    this.tagName = tagName.toUpperCase();
    this.nodeName = this.tagName;
    this.attributes = Object.create(null);
    this.children = [];
    this.parentNode = null;
    Object.entries(attrs).forEach(([name, value]) => this.setAttribute(name, value));
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return Object.hasOwn(this.attributes, name) ? this.attributes[name] : null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  matches(selector) {
    if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
    const attr = selector.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
    if (attr) return this.hasAttribute(attr[1]) && (attr[2] === undefined || this.getAttribute(attr[1]) === attr[2]);
    return this.tagName === selector.toUpperCase();
  }
  closest(selector) {
    for (let node = this; node && node.tagName !== '#DOCUMENT'; node = node.parentNode) {
      if (node.matches(selector)) return node;
    }
    return null;
  }
  querySelectorAll(selector) { return this.descendants().filter(node => node.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

class Document extends EventTarget {
  constructor() {
    super();
    this.tagName = '#DOCUMENT';
    this.readyState = 'loading';
    this.documentElement = new Element('html');
    this.documentElement.parentNode = this;
    this.body = this.documentElement.appendChild(new Element('body'));
  }
  descendants() { return [this.documentElement, ...this.documentElement.descendants()]; }
  querySelectorAll(selector) { return this.descendants().filter(node => node.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  getElementById(id) { return this.descendants().find(node => node.getAttribute('id') === id) || null; }
  createElement(tagName) { return new Element(tagName); }
}

function keyboardHarness() {
  const document = new Document();
  const values = Object.create(null);
  const storage = {
    getItem(key) { return values[key] ?? null; },
    setItem(key, value) { values[key] = String(value); },
    removeItem(key) { delete values[key]; }
  };
  const root = {
    document,
    sessionStorage: storage,
    URL,
    Uint8Array,
    Date,
    crypto: { getRandomValues(bytes) { bytes.fill(7); return bytes; } },
    scrollY: 0,
    pageYOffset: 0,
    scrollTo() {},
    location: {},
    addEventListener: document.addEventListener.bind(document)
  };
  function setLocation(relative) {
    const parsed = new URL(relative, 'http://issuebot.test');
    Object.assign(root.location, {
      href: parsed.href,
      origin: parsed.origin,
      pathname: parsed.pathname,
      search: parsed.search,
      hash: parsed.hash
    });
  }
  setLocation('/issues?status=FAILED');

  const list = document.body.appendChild(new Element('table', { 'data-navigation-list': '' }));
  const row = list.appendChild(new Element('tr', {
    'data-navigation-issue': '19',
    'data-issue-href': '/issues/19',
    'hx-get': '/issues/19',
    'hx-target': '#content',
    'hx-push-url': 'true',
    tabindex: '0'
  }));
  const ajaxCalls = [];
  const pushedUrls = [];
  root.history = {
    pushState(_state, _title, path) {
      pushedUrls.push(path);
      setLocation(path);
    }
  };
  root.htmx = {
    ajax(method, path, options) {
      ajaxCalls.push({ method, path, options });
      const request = { elt: options.source, path, headers: {}, parameters: {} };
      document.emit('htmx:configRequest', request, options.source);
      if (options.source.getAttribute('hx-push-url') === 'true') {
        root.history.pushState({}, '', request.path);
      }
    }
  };

  createNavigation(root);
  const context = {
    window: root,
    document,
    navigator: {},
    localStorage: {},
    setTimeout: () => 1,
    clearTimeout() {},
    setInterval: () => 1,
    clearInterval() {},
    EventSource: class {},
    URL,
    Blob,
    console
  };
  vm.runInNewContext(appSource, context);
  return { document, root, row, ajaxCalls, pushedUrls };
}

test('keyboard detail navigation uses the row as HTMX source and pushes the tokenized request URL', () => {
  const h = keyboardHarness();

  const event = h.document.emit('keydown', { key: 'Enter' }, h.row);

  assert.equal(event.defaultPrevented, true);
  assert.equal(h.ajaxCalls.length, 1);
  assert.equal(h.ajaxCalls[0].method, 'GET');
  assert.equal(h.ajaxCalls[0].options.source, h.row);
  assert.equal(h.ajaxCalls[0].options.target, '#content');
  assert.equal(h.ajaxCalls[0].options.event, event);
  assert.equal(Object.hasOwn(h.ajaxCalls[0].options, 'pushUrl'), false);
  assert.match(h.ajaxCalls[0].path, /^\/issues\/19\?nav=[a-f0-9]{32}$/);
  assert.deepEqual(h.pushedUrls, [h.ajaxCalls[0].path]);
  assert.equal(h.root.location.pathname + h.root.location.search, h.ajaxCalls[0].path);
});
