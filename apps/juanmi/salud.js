/* Conexión con Health Connect (Samsung Health, Google Fit…) en la app de Android.
   - Lee los pasos de hoy y de ayer y los pone en la app.
   - Envía cada entrenamiento nuevo (fuerza, spinning, deportes) con su duración y calorías. */
(function () {
  if (!window.Salud) return;
  var SK = 'jb-salud-v1', pend = {}, avisado = false, manual = false;
  function cfg() { try { return JSON.parse(localStorage.getItem(SK)) || {}; } catch (e) { return {}; } }
  function put(o) { try { localStorage.setItem(SK, JSON.stringify(o)); } catch (e) { } }
  function estado() { try { return Salud.estado(); } catch (e) { return 'no'; } }
  function listo() {
    try { return typeof S !== 'undefined' && S && S.profile && typeof render === 'function' && typeof save === 'function' && typeof stepsCard === 'function'; }
    catch (e) { return false; }
  }
  function aviso(m) { try { toast(m); } catch (e) { } }
  function dia(off) { var d = new Date(); d.setDate(d.getDate() - off); return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2); }
  function idDe(h) { return String(h.ts || (h.date + '|' + h.day + '|' + h.min)); }
  function hojaAbierta() { var s = document.getElementById('sh'); return !!(s && s.classList.contains('on')); }
  function pintar() {
    try { if (typeof W !== 'undefined' && W) return; } catch (e) { }
    if (hojaAbierta()) return;
    var y = window.scrollY; render(); window.scrollTo(0, y);
  }

  function enviar() {
    if (estado() !== 'ok') return;
    var c = cfg(); c.sent = c.sent || {};
    (S.history || []).forEach(function (h) {
      var id = idDe(h);
      if (c.sent[id] || pend[id]) return;
      pend[id] = 1;
      var fin = h.ts || new Date(h.date + 'T20:00').getTime();
      Salud.guardarEjercicio(JSON.stringify({
        id: id, fin: fin, min: h.min || 1, kcal: h.kcal || 0, titulo: h.day || 'Entrenamiento',
        tipo: h.type === 'spin' ? 'spin' : h.type === 'sport' ? (h.sport || 'otro') : 'fuerza'
      }));
    });
  }
  function sincro() {
    if (estado() !== 'ok') return;
    try { Salud.leerPasos(dia(0)); Salud.leerPasos(dia(1)); } catch (e) { }
    enviar();
  }

  window.saludPasos = function (d, n) {
    n = Math.round(+n || 0);
    if (manual && d === dia(0)) {
      manual = false;
      aviso(n > 0 ? '👣 Samsung Health: ' + n.toLocaleString('es-ES') + ' pasos hoy' : 'Samsung Health aún no ha enviado pasos de hoy');
    }
    if (!listo() || n <= 0) return;
    S.steps = S.steps || {};
    if ((S.steps[d] || 0) >= n) return;
    S.steps[d] = n;
    save();
    pintar();
  };
  window.saludGuardado = function (id, ok, err) {
    delete pend[id];
    if (ok) {
      var c = cfg(); c.sent = c.sent || {}; c.sent[id] = 1; put(c);
      aviso('Enviado a Samsung Health ✓');
    } else if (!avisado) {
      avisado = true;
      aviso('No se pudo enviar a Samsung Health' + (err ? ': ' + err : ''));
    }
  };
  window.saludPermisos = function (e) {
    if (e === 'ok') { aviso('¡Conectado con Samsung Health! 💪'); sincro(); pintar(); }
    else if (e === 'no') aviso('Este móvil no tiene Health Connect (hace falta Android 14 o superior)');
    else { aviso('Activa los permisos de Juanmi Booster en Health Connect'); try { Salud.abrirAjustes(); } catch (x) { } }
  };
  window.saludError = function (m) { if (manual) { manual = false; aviso('No se pudieron leer los pasos' + (m ? ': ' + m : '')); } };
  window.saludSincronizar = function () {
    if (estado() !== 'ok') { saludConectar(); return; }
    manual = true; aviso('Sincronizando…'); sincro();
  };
  window.saludConectar = function () { try { Salud.pedirPermisos(); } catch (e) { } };

  function iniciar() {
    if (estado() === 'no') return;
    // Lo que ya estaba en el historial antes de conectar no se envía (para no duplicar).
    var c = cfg();
    if (!c.inicio) {
      c.inicio = Date.now(); c.sent = c.sent || {};
      (S.history || []).forEach(function (h) { c.sent[idDe(h)] = 1; });
      put(c);
    }
    var tarjeta = stepsCard;
    stepsCard = function () {
      var h = tarjeta.apply(this, arguments);
      if (estado() === 'ok') return h + '<button class="card actbtn" onclick="saludSincronizar()" style="padding:10px 14px"><span style="font-size:20px">🔄</span><span style="flex:1;text-align:left"><b>Sincronizar con Samsung Health</b><small class="mut" style="display:block">Coge ahora los pasos y envía los entrenos pendientes</small></span></button>';
      return h + '<button class="card actbtn" onclick="saludConectar()"><span style="font-size:24px">🔗</span><span style="flex:1;text-align:left"><b>Conectar con Samsung Health</b><small class="mut" style="display:block">Tus pasos se pondrán solos y tus entrenos aparecerán en Samsung Health</small></span><span class="mut">›</span></button>';
    };
    var guardar = save;
    save = function () { var r = guardar.apply(this, arguments); setTimeout(enviar, 400); return r; };
    document.addEventListener('visibilitychange', function () { if (!document.hidden) sincro(); });
    pintar();
    sincro();
  }
  var n = 0, t = setInterval(function () {
    if (listo()) { clearInterval(t); iniciar(); }
    else if (++n > 120) clearInterval(t);
  }, 500);
})();
