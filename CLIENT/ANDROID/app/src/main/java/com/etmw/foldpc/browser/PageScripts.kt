package com.etmw.foldpc.browser

object PageScripts {

    const val HELPER = """
(function() {
  if (window.__etmwInstalled) return;
  window.__etmwInstalled = true;

  function deepActiveElement() {
    var active = document.activeElement;
    while (active) {
      if (active.tagName === 'IFRAME') {
        try {
          var inner = active.contentDocument;
          if (inner && inner.activeElement) {
            active = inner.activeElement;
            continue;
          }
        } catch (e) {}
      }
      if (active.shadowRoot && active.shadowRoot.activeElement) {
        active = active.shadowRoot.activeElement;
        continue;
      }
      break;
    }
    return active;
  }

  function isEditable(el) {
    if (!el) return false;
    var tag = (el.tagName || '').toLowerCase();
    return tag === 'input' || tag === 'textarea' || el.isContentEditable === true ||
      (el.getAttribute && el.getAttribute('role') === 'textbox');
  }

  document.addEventListener('focusin', function(event) {
    if (isEditable(event.target)) {
      try { window.EtmwNative.onEditableFocused(); } catch (e) {}
    }
  }, true);

  function notify(el, data, inputType) {
    try {
      el.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        cancelable: false,
        inputType: inputType || 'insertText',
        data: data == null ? null : data
      }));
    } catch (e) {
      try {
        var event = document.createEvent('Event');
        event.initEvent('input', true, false);
        el.dispatchEvent(event);
      } catch (e2) {}
    }
  }

  function nativeSetValue(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
    if (descriptor && descriptor.set) {
      descriptor.set.call(el, value);
    } else {
      el.value = value;
    }
  }

  function insertText(text) {
    var el = deepActiveElement();
    if (!isEditable(el)) {
      try { return document.execCommand('insertText', false, text) === true; }
      catch (e) { return false; }
    }
    if (el.isContentEditable) {
      try {
        if (document.execCommand('insertText', false, text)) return true;
      } catch (e) {}
      try {
        var selection = window.getSelection();
        if (!selection || selection.rangeCount === 0) return false;
        var range = selection.getRangeAt(0);
        range.deleteContents();
        var node = document.createTextNode(text);
        range.insertNode(node);
        range.setStartAfter(node);
        range.collapse(true);
        selection.removeAllRanges();
        selection.addRange(range);
        notify(el, text, 'insertText');
        return true;
      } catch (e) {
        return false;
      }
    }
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      var start = el.selectionStart;
      var end = el.selectionEnd;
      var value = el.value || '';
      if (start == null) start = value.length;
      if (end == null) end = start;
      var next = value.slice(0, start) + text + value.slice(end);
      nativeSetValue(el, next);
      try { el.setSelectionRange(start + text.length, start + text.length); } catch (e) {}
      notify(el, text, 'insertText');
      return true;
    }
    try { return document.execCommand('insertText', false, text) === true; }
    catch (e) { return false; }
  }

  function backspace() {
    var el = deepActiveElement();
    if (!isEditable(el)) return false;
    if (el.isContentEditable) {
      try { return document.execCommand('delete', false) === true; } catch (e) { return false; }
    }
    var tag = (el.tagName || '').toLowerCase();
    if (tag !== 'input' && tag !== 'textarea') return false;
    var value = el.value || '';
    var start = el.selectionStart;
    var end = el.selectionEnd;
    if (start == null) start = value.length;
    if (end == null) end = start;
    if (start === 0 && end === 0) return true;
    var from = start === end ? Math.max(0, start - 1) : start;
    var next = value.slice(0, from) + value.slice(end);
    nativeSetValue(el, next);
    try { el.setSelectionRange(from, from); } catch (e) {}
    notify(el, null, 'deleteContentBackward');
    return true;
  }

  function forwardDelete() {
    var el = deepActiveElement();
    if (!isEditable(el)) return false;
    if (el.isContentEditable) {
      try { return document.execCommand('forwardDelete', false) === true; } catch (e) { return false; }
    }
    var tag = (el.tagName || '').toLowerCase();
    if (tag !== 'input' && tag !== 'textarea') return false;
    var value = el.value || '';
    var start = el.selectionStart;
    var end = el.selectionEnd;
    if (start == null) start = 0;
    if (end == null) end = start;
    if (start === end && end < value.length) end = end + 1;
    var next = value.slice(0, start) + value.slice(end);
    nativeSetValue(el, next);
    try { el.setSelectionRange(start, start); } catch (e) {}
    notify(el, null, 'deleteContentForward');
    return true;
  }

  function enter() {
    var el = deepActiveElement();
    if (!isEditable(el)) return false;
    if (el.isContentEditable) return insertText('\n');
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return insertText('\n');
    try {
      if (el.form && typeof el.form.requestSubmit === 'function') {
        el.form.requestSubmit();
        return true;
      }
    } catch (e) {}
    try {
      el.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        bubbles: true, cancelable: true
      }));
      el.dispatchEvent(new KeyboardEvent('keyup', {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
        bubbles: true, cancelable: true
      }));
    } catch (e) {}
    return true;
  }

  function wordLeft(value, position) {
    var pos = position;
    while (pos > 0 && /\s/.test(value.charAt(pos - 1))) pos--;
    while (pos > 0 && !/\s/.test(value.charAt(pos - 1))) pos--;
    return pos;
  }

  function wordRight(value, position) {
    var pos = position;
    while (pos < value.length && /\s/.test(value.charAt(pos))) pos++;
    while (pos < value.length && !/\s/.test(value.charAt(pos))) pos++;
    return pos;
  }

  function arrow(direction, shift, word) {
    var el = deepActiveElement();
    if (!isEditable(el)) return false;
    if (el.isContentEditable) {
      try {
        var selection = window.getSelection();
        if (!selection || selection.rangeCount === 0) return false;
        var mode = shift ? 'extend' : 'move';
        var unit = word ? 'word' : (direction === 'up' || direction === 'down' ? 'line' : 'character');
        selection.modify(mode, direction, unit);
        return true;
      } catch (e) {
        return false;
      }
    }
    var tag = (el.tagName || '').toLowerCase();
    if (tag !== 'input' && tag !== 'textarea') return false;
    var value = el.value || '';
    var start = el.selectionStart;
    var end = el.selectionEnd;
    if (start == null) start = 0;
    if (end == null) end = start;
    if (direction === 'up' || direction === 'down') {
      try {
        if (direction === 'up') el.setSelectionRange(0, shift ? end : 0);
        else el.setSelectionRange(value.length, value.length);
        return true;
      } catch (e) { return false; }
    }
    var position;
    if (direction === 'left') {
      if (!shift && start !== end) position = start;
      else position = word ? wordLeft(value, start) : Math.max(0, start - 1);
      if (shift) el.setSelectionRange(position, end, 'backward');
      else el.setSelectionRange(position, position);
    } else {
      if (!shift && start !== end) position = end;
      else position = word ? wordRight(value, end) : Math.min(value.length, end + 1);
      if (shift) el.setSelectionRange(start, position, 'forward');
      else el.setSelectionRange(position, position);
    }
    return true;
  }

  function selectAll() {
    var el = deepActiveElement();
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
      try { el.select(); return true; } catch (e) {}
    }
    try { return document.execCommand('selectAll', false, null) === true; }
    catch (e) { return false; }
  }

  function exec(command) {
    try { return document.execCommand(command, false, null) === true; }
    catch (e) { return false; }
  }

  window.__etmw = {
    insertText: insertText,
    backspace: backspace,
    forwardDelete: forwardDelete,
    enter: enter,
    arrow: arrow,
    selectAll: selectAll,
    exec: exec,
    isEditable: function() { return isEditable(deepActiveElement()); }
  };
})();
"""

    const val COSMETIC = """
(function() {
  if (window.__etmwCosmeticInstalled) return;
  window.__etmwCosmeticInstalled = true;
  var css = [
    'ins.adsbygoogle',
    '[id^="google_ads_"]',
    '[id^="div-gpt-ad"]',
    '[id^="aswift_"]',
    '[id^="ad-slot-"]',
    '[data-ad-slot]',
    '[data-google-query-id]',
    '[class~="adsbygoogle"]',
    '[class~="advertisement"]',
    '[class~="ad-slot"]',
    '[class~="taboola"]',
    '[class~="outbrain"]',
    '[aria-label="Advertisement"]',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication.com"]',
    'iframe[src*="adservice.google"]'
  ].join(',');
  var style = document.getElementById('etmw-cosmetic-filter');
  if (!style) {
    style = document.createElement('style');
    style.id = 'etmw-cosmetic-filter';
    style.type = 'text/css';
    style.appendChild(document.createTextNode(css + '{display:none !important;visibility:hidden !important;}'));
    (document.head || document.documentElement).appendChild(style);
  }
})();
"""
}
