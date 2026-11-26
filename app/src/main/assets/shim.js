/* Puente entre las páginas web y Android: guardar archivos, copiar, compartir, imprimir
   y sustitutos de las funciones que solo existían dentro de Claude. */
(function () {
  var B = window.AndroidApp;
  if (!B) return;

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

  /* Funciones de Claude: descargas sí; guardado en la nube no (los datos quedan en el móvil) */
  window.claude = window.claude || {};
  window.claude.use = function (cap) {
    if (cap === 'downloads') {
      return Promise.resolve({
        save: function (o) {
          return new Promise(function (res, rej) {
            o = o || {};
            var name = o.filename || o.name || 'archivo';
            var data = o.data != null ? o.data : (o.content != null ? o.content : o.text);
            try {
              if (typeof Blob !== 'undefined' && data instanceof Blob) {
                toDataUrl(data, function (d) { B.saveDataUrl(name, d); res({ status: 'delivered' }); });
              } else {
                B.saveText(name, typeof data === 'string' ? data : JSON.stringify(data, null, 1),
                  o.mimeType || o.type || 'application/octet-stream');
                res({ status: 'delivered' });
              }
            } catch (e) { rej({ code: 'error', message: String(e) }); }
          });
        }
      });
    }
    return Promise.resolve(null);
  };

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
