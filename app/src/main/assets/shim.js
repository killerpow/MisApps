/* Puente entre las páginas web y Android: guardar archivos, copiar, compartir, imprimir
   y sustitutos de las funciones que solo existían dentro de Claude. */
(function () {
  var B = window.AndroidApp;
  if (!B) return;

  /* Copias de seguridad hechas con otras apps (web to app, WhatsApp, notas...) a veces llegan
     "estropeadas": codificadas como enlace (%7B...), con marcas invisibles o comillas tipográficas.
     Si el texto normal falla, se intenta limpiar antes de rendirse. */
  var origParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    try { return origParse.call(JSON, text, reviver); } catch (err) {
      if (typeof text !== 'string' || text.length < 2) throw err;
      var t = text.replace(/^﻿/, '').replace(/[​-‍⁠]/g, '').trim();
      if (/^data:/i.test(t)) t = t.replace(/^data:[^,]*,/i, '');
      if (/^%7B/i.test(t) || /^%5B/i.test(t)) { try { t = decodeURIComponent(t); } catch (e) {} }
      var cut = function (x) {
        var i = x.indexOf('{'), j = x.lastIndexOf('}'), k = x.indexOf('[');
        if (k >= 0 && (i < 0 || k < i)) { i = k; j = x.lastIndexOf(']'); }
        return i >= 0 && j > i ? x.slice(i, j + 1) : x;
      };
      var tries = [t, cut(t)];
      var q = t.replace(/[\u201C\u201D\u201E\u00AB\u00BB]/g, '"').replace(/[\u2018\u2019]/g, "'");
      tries.push(q, cut(q));
      for (var n = 0; n < tries.length; n++) {
        try { return origParse.call(JSON, tries[n], reviver); } catch (e2) {}
      }
      throw err;
    }
  };

  function toDataUrl(blob, cb) {
    var r = new FileReader();
    r.onload = function () { cb(r.result); };
    r.readAsDataURL(blob);
  }
  function saveAny(name, href) {
    if (href.indexOf('data:') === 0) { B.saveDataUrl(name, href); return; }
    fetch(href).then(function (r) { return r.blob(); })
      .then(function (b) { toDataUrl(b, function (d) { B.saveDataUrl(name, d); }); })
      .catch(function () { B.toast('No se pudo guardar el archivo'); });
  }
  window.__androidSave = saveAny;

  /* Guardar un archivo directamente (lo usan las apps para sus copias de seguridad) */
  window.androidSaveFile = function (name, text, mime) { B.saveText(name, String(text), mime || 'application/json'); };

  /* Almacenamiento persistente de Claude -> localStorage */
  if (!window.storage) {
    var P = '__st_';
    window.storage = {
      get: function (k) {
        var v = localStorage.getItem(P + k);
        return Promise.resolve(v === null ? null : { key: k, value: v, shared: false });
      },
      set: function (k, v) {
        localStorage.setItem(P + k, typeof v === 'string' ? v : JSON.stringify(v));
        return Promise.resolve({ key: k, value: v, shared: false });
      },
      'delete': function (k) {
        localStorage.removeItem(P + k);
        return Promise.resolve({ key: k, deleted: true, shared: false });
      },
      list: function (prefix) {
        prefix = prefix || '';
        var keys = [];
        for (var i = 0; i < localStorage.length; i++) {
          var k = localStorage.key(i);
          if (k && k.indexOf(P + prefix) === 0) keys.push(k.slice(P.length));
        }
        return Promise.resolve({ keys: keys, prefix: prefix, shared: false });
      }
    };
  }

  /* Portapapeles */
  try {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: function (t) { B.copy(String(t)); return Promise.resolve(); },
        readText: function () { return Promise.resolve(B.paste()); },
        write: function () { return Promise.reject(new Error('No disponible')); }
      }
    });
  } catch (e) {}
  var oldExec = document.execCommand ? document.execCommand.bind(document) : null;
  document.execCommand = function (cmd) {
    if (String(cmd).toLowerCase() === 'copy') {
      var s = window.getSelection ? String(window.getSelection()) : '';
      var a = document.activeElement;
      if (!s && a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT')) s = a.value.substring(a.selectionStart, a.selectionEnd) || a.value;
      B.copy(s);
      return true;
    }
    return oldExec ? oldExec.apply(document, arguments) : false;
  };

  /* Compartir e imprimir */
  navigator.share = function (d) {
    d = d || {};
    if (d.files && d.files.length) {
      toDataUrl(d.files[0], function (u) { B.shareFile(d.files[0].name || 'archivo', u, d.text || ''); });
      return Promise.resolve();
    }
    B.share(d.title || '', [d.text, d.url].filter(Boolean).join('\n'));
    return Promise.resolve();
  };
  navigator.canShare = function () { return true; };
  window.print = function () { B.print(); };

  /* Descargas con <a download> (copias de seguridad, CSV, PDF...) */
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.href || '';
    if (/^(blob:|data:)/.test(href)) { e.preventDefault(); saveAny(a.getAttribute('download') || 'archivo', href); }
  }, true);
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && /^(blob:|data:)/.test(this.href || '')) {
      saveAny(this.getAttribute('download') || 'archivo', this.href);
      return;
    }
    return origClick.call(this);
  };

  /* window.open de enlaces externos -> abrir fuera */
  var origOpen = window.open;
  window.open = function (url) {
    if (url && /^(https?:|tel:|mailto:|whatsapp:|geo:|intent:)/.test(String(url))) { B.openExternal(String(url)); return null; }
    if (url && /^(blob:|data:)/.test(String(url))) { saveAny('archivo', String(url)); return null; }
    return origOpen ? origOpen.apply(window, arguments) : null;
  };
})();
