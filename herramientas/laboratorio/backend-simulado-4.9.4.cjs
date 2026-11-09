'use strict';
/*
 * BACKEND SIMULADO con el motor de la 4.9.4.
 *
 * Responde a la API /api/v1 que usa la interfaz React, pero en lugar de usar
 * el backend Java ejecuta cada operación en la 4.9.4 original (oráculo) con el
 * mismo controlador de pasos del laboratorio. Sirve para:
 *   - probar la interfaz React sin Java (p. ej. en un equipo sin JDK),
 *   - comprobar que la interfaz envía exactamente las operaciones correctas:
 *     si la foto final coincide con la referencia, React ha hecho lo mismo que
 *     un usuario en la 4.9.4.
 *
 *   TZ=Europe/Madrid node backend-simulado-4.9.4.cjs [puerto] [carpeta_dist]
 *
 * No toca SharePoint ni ningún dato real: trabaja sobre los escenarios.
 */
const fs = require('fs');
const http = require('http');
const path = require('path');
const crypto = require('crypto');
const { abrirVersionOriginal, cerrarNavegador, leerEscenario, nombreExcel } = require('./oraculo-4.9.4.cjs');
const { ejecutarPaso } = require('./controlador-4.9.4.cjs');

const RAIZ = path.resolve(__dirname, '../..');
const PUERTO = Number(process.argv[2] || 5001);
const DIST = path.resolve(process.argv[3] || path.join(RAIZ, 'frontend/dist'));

// Correos del escenario anonimizado para cada rol (usuarios.json).
const CORREO_POR_ROL = { admin: 'usuario01@ejemplo.local', editor: 'usuario07@ejemplo.local', readonly: 'nadie@ejemplo.local' };

let sesion = null;          // página de la 4.9.4 abierta
let escenario = null;
let rol = 'admin';
let cola = Promise.resolve(); // las peticiones se atienden de una en una

let ultimaMatrizExcel = null;
let hoyLaboratorio = '2026-11-05';
async function reiniciar({ escenario: nombre = 'sharepoint-2026-11-05', fecha = '2026-11-05', email } = {}) {
  if (sesion) await sesion.cerrar();
  escenario = leerEscenario(path.join(RAIZ, 'datos/escenarios', nombre));
  const usuario = escenario.usuarios.find((u) => u.email === email);
  rol = usuario ? normalizarRol(usuario.role) : (email ? 'readonly' : 'admin');
  sesion = await abrirVersionOriginal({ escenario, fecha, rol, aceptarDialogos: true });
  hoyLaboratorio = fecha;
  return { ok: true, hoy: fecha };
}

function normalizarRol(r) {
  const s = String(r || '').toLowerCase().replace(/\s/g, '');
  return s === 'admin' || s === 'administrator' ? 'admin' : (s === 'editor' || s === 'edit' || s === 'write' ? 'editor' : 'readonly');
}

const huella = (texto) => (texto == null ? null : crypto.createHash('sha256').update(texto, 'utf8').digest('hex').slice(0, 16));

/** Vista con el mismo formato que RepresentacionVista (Java), calculada por la 4.9.4. */
async function vista() {
  const v = await sesion.evaluar(() => {
    recomputeAll();
    const textos = lbBuild();
    const salida = {
      hoy: iso(TODAY), semanaHoy: cw(TODAY),
      calendario: { anio: state.settings.year, saltarFestivos: !!state.settings.skipHolidays, festivos: state.holidays.map((h) => ({ fecha: h.date, nombre: h.name })) },
      grupos: GROUPS.map((g) => ({ clave: g.key, nombre: g.name, color: g.dot, bancos: g.benches.map((b) => ({ nombre: b, organizacion: benchOrg(b) })) })),
      bancos: {}, textos,
    };
    const fechaIso = (d) => (d ? iso(d) : null);
    GROUPS.forEach((g) => g.benches.forEach((bn) => {
      let prioridad = 0;
      salida.bancos[bn] = (state.benches[bn] || []).map((r) => {
        const datos = JSON.parse(JSON.stringify(stripRT(r)));
        const c = {
          estado: statusOf(r), estadoDeclarado: manualCallOf(r), diasNominales: durDays(r), diasPlanificados: plannedDays(r),
          fraccionAvance: progFrac(r), tipoEspecial: isSpecialType(r),
        };
        if (r.done) {
          c.estadoFinal = finalStatusOf(r);
          c.cierreConfirmado = isConfirmedClose(r);
        } else {
          prioridad++;
          Object.assign(c, {
            inicio: fechaIso(r._start), fin: fechaIso(r._end), criterioInicio: r._startWhy || '', arranqueEfectivo: effStartOf(r) || '',
            arranqueAsumido: startAssumed(r), finTolerado: fechaIso(tolEndOf(r)), finPlanificado: fechaIso(plannedEndOf(r)),
            diasSinAvance: idleDays(r),
            solapes: (r._ovl || []).map((o) => ({ etiqueta: o.label, inicio: o.s, fin: o.e })),
            sugerencias: suggestionsFor(r).map((s) => ({ clave: s.k, dias: s.k === 'noprog' ? idleDays(r) : undefined, fecha: null, referencia: null })),
            sugerenciaAbierta: suggestionOpen(r),
          });
        }
        return { id: r.id, version: null, cerrado: !!r.done, prioridad: r.done ? undefined : prioridad, datos, calculo: c };
      });
    }));
    return salida;
  });
  Object.values(v.bancos).forEach((filas) => filas.forEach((f) => { f.version = huella(v.textos[f.id]); }));
  const textos = v.textos;
  delete v.textos;
  return { vista: v, textos };
}

/** 409 si alguna fila que el usuario vio ha cambiado desde entonces. */
async function comprobarVersiones(versiones) {
  const { textos } = await vista();
  const filas = Object.entries(versiones || {})
    .filter(([id, version]) => version !== null && version !== undefined && huella(textos[id]) !== version)
    .map(([id]) => ({ id, version: huella(textos[id]), borrado: !textos[id], datos: textos[id] ? JSON.parse(textos[id]) : null }));
  return filas.length ? { estado: 409, cuerpo: { error: 'conflicto', mensaje: 'Otra persona ha cambiado este ensayo', filas } } : null;
}

/** Ejecuta pasos y devuelve 422 si la 4.9.4 mostró un aviso (alert) de regla. */
async function pasos(lista) {
  const avisosAntes = sesion.dialogos.length;
  for (const paso of lista) await ejecutarPaso(sesion, paso);
  const avisos = sesion.dialogos.slice(avisosAntes).filter((d) => d.tipo === 'alert').map((d) => d.texto);
  return avisos;
}

const REGLAS = [/no puede ser anterior/, /no computed dates/, /Select at least/, /Choose a destination/, /Solo un Admin/];

async function operacion(banco, cuerpo) {
  const conflicto = !cuerpo.forzar && await comprobarVersiones(cuerpo.versiones);
  if (conflicto) return conflicto;
  const antes = Object.keys((await vista()).textos);
  const ids = cuerpo.ids || [];
  const sel = { op: 'seleccionar', banco, ensayos: ids };
  const mapa = {
    comprometer: [{ op: 'comprometer', banco, ensayo: cuerpo.id }],
    deshacerCompromiso: [{ op: 'deshacerCompromiso', banco, ensayo: cuerpo.id }],
    comprometerTodos: [{ op: 'confirmarTodas', banco }],
    estado: [{ op: 'estado', banco, ensayo: cuerpo.id, valor: cuerpo.estado }],
    aplicarSugerencia: [{ op: 'aplicarSugerencia', banco, ensayo: cuerpo.id }],
    reordenar: [...(ids.length ? [sel] : []), { op: 'arrastrar', banco, origen: cuerpo.id, destino: cuerpo.destino }],
    duplicar: [sel, { op: 'duplicar', banco }],
    borrarSeleccion: [sel, { op: 'borrarSeleccion', banco }],
    alternarRecurso: [{ op: 'recurso', banco, ensayo: cuerpo.id, recurso: cuerpo.recurso }],
    borrar: [{ op: 'borrar', banco, ensayo: cuerpo.id }],
    mover: [sel, { op: 'mover', banco, destino: cuerpo.bancoDestino, posicion: cuerpo.posicion ?? 99999 }],
    anadir: [{ op: 'anadir', banco }],
    restaurar: [{ op: 'restaurar', banco, ensayo: cuerpo.id }],
    reutilizar: [{ op: 'reutilizar', banco, ensayo: cuerpo.id }],
  };
  if (!mapa[cuerpo.tipo]) return { estado: 400, cuerpo: { error: 'peticion', mensaje: 'Operación desconocida: ' + cuerpo.tipo } };
  if (rol === 'readonly') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Tu usuario es de solo lectura: no se guardan cambios.' } };
  const avisos = await pasos(mapa[cuerpo.tipo]);
  const regla = avisos.find((a) => REGLAS.some((r) => r.test(a)));
  if (regla) return { estado: 422, cuerpo: { error: 'regla', mensaje: regla } };
  const nueva = await vista();
  let resultado;
  if (cuerpo.tipo === 'duplicar') resultado = Object.keys(nueva.textos).filter((id) => !antes.includes(id)).join(',');
  if (cuerpo.tipo === 'mover') resultado = avisos[avisos.length - 1];
  if (cuerpo.tipo === 'anadir' || cuerpo.tipo === 'reutilizar') resultado = Object.keys(nueva.textos).find((id) => !antes.includes(id));
  return { estado: 200, cuerpo: { resultado, filasGuardadas: 1, vista: nueva.vista } };
}

async function campos(banco, id, cuerpo) {
  const conflicto = !cuerpo.forzar && await comprobarVersiones({ [id]: cuerpo.version });
  if (conflicto) return conflicto;
  if (rol === 'readonly') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Tu usuario es de solo lectura: no se guardan cambios.' } };
  for (const c of cuerpo.cambios || []) {
    const avisos = await pasos([{ op: 'editar', banco, ensayo: id, campo: c.campo, valor: c.valor }]);
    if (avisos.length) return { estado: 422, cuerpo: { error: 'regla', mensaje: avisos[0] } };
  }
  return { estado: 200, cuerpo: { filasGuardadas: 1, vista: (await vista()).vista } };
}

/** Rellena la ventana de cierre de la 4.9.4 y lee lo que calcula. */
async function ventanaCierre(banco, id, correccion, valores) {
  return sesion.evaluar(([bn, rid, ed, v]) => {
    const g = (k) => document.getElementById(k);
    if (!g('closem') || !g('closem').classList.contains('show') || !CLOSE_CTX || CLOSE_CTX.id !== rid) {
      openCloseModal(bn, rid, ed);
    }
    if (!g('closem') || !g('closem').classList.contains('show')) return null;
    if (v) {
      g('cl-ts').value = v.arranqueReal; g('cl-te').value = v.finReal; g('cl-st').value = v.estadoFinal; g('cl-rs').value = v.motivo;
      g('cl-cm').value = v.explicacion; g('cl-dd').value = v.diasParada; g('cl-dn').value = v.notaParada;
    }
    const error = closeRecalc();
    const dev = g('cl-dev').textContent;
    const bill = g('cl-bill').textContent;
    const m = /^(\d+) d +\( (\d+) d de calendario/.exec(bill);
    return {
      valores: { arranqueReal: g('cl-ts').value, finReal: g('cl-te').value, estadoFinal: g('cl-st').value, motivo: g('cl-rs').value,
        explicacion: g('cl-cm').value, diasParada: g('cl-dd').value, notaParada: g('cl-dn').value },
      validacion: {
        error, sugerirRetraso: !!g('cl-sug').textContent,
        desviacion: /tarde/.test(dev) ? parseInt(dev.replace('+', ''), 10) : /antes/.test(dev) ? -parseInt(dev, 10) : /En la fecha/.test(dev) ? 0 : null,
        diasCalendario: m ? Number(m[2]) : 0, diasFacturables: m ? Number(m[1]) : null,
      },
    };
  }, [banco, id, correccion, valores || null]);
}

/** Indicadores del Dashboard calculados con las funciones de la 4.9.4. */
async function indicadores(que, q, metodo) {
  const org = q.get('org') || 'ALL';
  if (que === 'foto-semanal' && metodo === 'POST') {
    const fotos = await sesion.evaluar(() => { const s = snapshotKPIs(); updateYearlyStatus(true); return s; });
    return { estado: 200, cuerpo: fotos };
  }
  if (que === 'bloques') {
    const cuerpo = await sesion.evaluar(([lunes, o, resp]) => {
      recomputeAll();
      const ff = o === 'ALL' ? null : (bn) => benchOrg(bn) === o;
      const filas = resp === null ? null : (r) => (r.resp || '') === resp;
      const l = lunes ? weekStart(d0(lunes)) : weekStart(TODAY);
      return { estaSemana: blockKPIs(l, filas, ff), siguiente: blockKPIs(addDays(l, 7), filas, ff) };
    }, [q.get('lunes'), org, q.has('responsable') ? q.get('responsable') : null]);
    return { estado: 200, cuerpo };
  }
  if (que === 'tabla-ocupacion') {
    const cuerpo = await sesion.evaluar(([desde, hasta, o]) => {
      recomputeAll();
      const a = d0(desde), b = d0(hasta);
      const ff = o === 'ALL' ? null : (bn) => benchOrg(bn) === o;
      let TB = 0, TC = 0;
      const grupos = [];
      GROUPS.forEach((g) => {
        const gb = g.benches.filter((bn) => !ff || ff(bn));
        if (!gb.length) return;
        let bw = 0, s2 = 0, e2 = 0;
        gb.forEach((bn) => {
          const rs = activeRows(bn);
          if (rs.some((r) => overlaps(r, a, b))) bw++;
          rs.forEach((r) => { if (inWeek(r._start, a, b)) s2++; if (inWeek(r._end, a, b)) e2++; });
        });
        const oc = occupancyOf(gb, a, b);
        TB += oc.busy; TC += oc.cap;
        grupos.push({ clave: g.key, nombre: g.name, color: g.dot, bancos: gb.length, trabajando: bw, inicia: s2, acaba: e2, busy: oc.busy, cap: oc.cap, pct: oc.pct });
      });
      return { grupos, busy: TB, cap: TC, pct: TC ? Math.round(100 * TB / TC) : 0 };
    }, [q.get('desde'), q.get('hasta'), org]);
    return { estado: 200, cuerpo };
  }
  if (que === 'tendencia') {
    const cuerpo = await sesion.evaluar(([desde, hasta, o]) => {
      recomputeAll();
      const prefs = Object.assign({}, VIEWPREF), trend = Object.assign({}, TREND);
      VIEWPREF.trendOrg = o; TREND.cwFrom = desde; TREND.cwTo = hasta;
      VIEWPREF.trendMode = 'pct'; const pct = trendData();
      VIEWPREF.trendMode = 'hours'; const horas = trendData();
      Object.assign(VIEWPREF, prefs); Object.assign(TREND, trend);
      return {
        anio: pct.yr, semanasDelAnio: pct.nW, desde: pct.cf, hasta: pct.ct, lunes: pct.weeks.map(iso), cw: pct.weeks.map(cw),
        series: pct.series.map((s, i) => ({ clave: s.key, nombre: s.name, color: s.color, bancos: s.n, busy: horas.series[i].data, pct: s.data })),
      };
    }, [Number(q.get('cwDesde') || 0), Number(q.get('cwHasta') || 0), org]);
    return { estado: 200, cuerpo };
  }
  if (que === 'historico') {
    const cuerpo = await sesion.evaluar(([o, filasEscenario]) => {
      if (KPIHIST === null) KPIHIST = filasEscenario;
      const antes = KPIHIST_ORG;
      KPIHIST_ORG = o;
      const semanas = kpiHistRows();
      KPIHIST_ORG = antes;
      return { filas: KPIHIST.length, semanas };
    }, [org, escenario.kpis]);
    return { estado: 200, cuerpo };
  }
  if (que === 'estadisticas') {
    const cuerpo = await sesion.evaluar(([desde, hasta, o, resp]) => {
      recomputeAll();
      const guardado = [STAT_FROM, STAT_TO, STAT_RESP, KPIHIST_ORG];
      statDefaults();
      if (desde) STAT_FROM = desde;
      if (hasta) STAT_TO = hasta;
      STAT_RESP = resp || '';
      KPIHIST_ORG = o;
      const filas = statRows().map((x) => ({ banco: x.bn, id: x.r.id, fecha: iso(x.dt), estadoFinal: x.fin, motivo: x.reason, confirmado: x.conf,
        datos: JSON.parse(JSON.stringify(stripRT(x.r))) }));
      [STAT_FROM, STAT_TO, STAT_RESP, KPIHIST_ORG] = guardado;
      return filas;
    }, [q.get('desde'), q.get('hasta'), org, q.get('responsable')]);
    return { estado: 200, cuerpo };
  }
  return { estado: 404, cuerpo: { error: 'sin ruta' } };
}

async function atender(metodo, ruta, cuerpo) {
  const url = new URL(ruta, 'http://local');
  const p = url.pathname;
  let m;
  if (p === '/api/v1/local/reinicio' && metodo === 'POST') return { estado: 200, cuerpo: await reiniciar(cuerpo || {}) };
  if (!sesion) await reiniciar({});
  if (p === '/api/v1/sesion') {
    const u = escenario.usuarios.find((x) => normalizarRol(x.role) === rol) || {};
    return { estado: 200, cuerpo: { email: CORREO_POR_ROL[rol], nombre: 'Laboratorio', responsable: rol === 'admin' ? 'Laboratorio' : (u.responsibleName || ''), rol, esAdmin: rol === 'admin', soloLectura: rol === 'readonly' } };
  }
  if (p === '/api/v1/planificacion') return { estado: 200, cuerpo: (await vista()).vista };
  // Recursos (bloque D): el catálogo y los bloqueos viven en RES_CACHE de la 4.9.4.
  if (p === '/api/v1/recursos' && metodo === 'GET') {
    return { estado: 200, cuerpo: await sesion.evaluar(async () => { if (!RES_LOADED) await resLoad(); return JSON.parse(JSON.stringify(RES_CACHE)); }) };
  }
  if (p === '/api/v1/recursos' && metodo === 'PUT') {
    if (rol === 'readonly') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Tu usuario es de solo lectura: no se guardan cambios.' } };
    await sesion.evaluar((r) => { RES_CACHE.resources = r; resSaveLocal(); }, cuerpo.resources || []);
    return { estado: 200, cuerpo: { ok: true } };
  }
  if (p === '/api/v1/recursos/renombrar' && metodo === 'POST') {
    if (rol !== 'admin') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Solo un Admin puede cambiar el catálogo de equipos.' } };
    const antes = (await vista()).textos;
    await sesion.evaluar(([a, n]) => {
      // Solo la parte de los ensayos de renameResource; el catálogo llega luego con PUT /recursos.
      GROUPS.forEach((g) => g.benches.forEach((bn) => (state.benches[bn] || []).forEach((t) => {
        if (Array.isArray(t.resources)) { const i = t.resources.indexOf(a); if (i >= 0) t.resources[i] = n; }
      })));
      save();
    }, [cuerpo.anterior, String(cuerpo.nuevo || '').trim()]);
    await sesion.avanzar(1500);
    const nueva = await vista();
    const cambiadas = Object.keys(nueva.textos).filter((k) => nueva.textos[k] !== antes[k]).length;
    return { estado: 200, cuerpo: { resultado: String(cambiadas), filasGuardadas: cambiadas, vista: nueva.vista } };
  }
  if (p === '/api/v1/recursos/bloqueos' && metodo === 'POST') {
    if (rol === 'readonly') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Tu usuario es de solo lectura: no se guardan cambios.' } };
    const id = 'b' + Date.now().toString(36);
    await sesion.evaluar(([b, i]) => { RES_CACHE.blocks = RES_CACHE.blocks || []; RES_CACHE.blocks.push({ id: i, resource: b.resource, start: b.start, end: b.end, reason: b.reason, note: b.note }); resSaveLocal(); }, [cuerpo, id]);
    return { estado: 200, cuerpo: { ok: true, id } };
  }
  if (p === '/api/v1/recursos/bloqueos/borrar' && metodo === 'POST') {
    await sesion.evaluar((i) => { RES_CACHE.blocks = (RES_CACHE.blocks || []).filter((b) => String(b.id) !== String(i)); resSaveLocal(); }, cuerpo.id);
    return { estado: 200, cuerpo: { ok: true } };
  }
  // Configuración (bloque E): se lee y se cambia el estado de la 4.9.4.
  const protegida = (lista) => ({ estado: 412, cuerpo: { error: 'lista-protegida', mensaje: `No se guarda: la lista ${lista} quedaría vacía (protección de listas, D11).` } });
  if (p === '/api/v1/catalogos' && metodo === 'GET') {
    // Responsables: los de la lista compartida (como el backend), no la lista fija de la 4.9.4 (D24).
    return { estado: 200, cuerpo: await sesion.evaluar((r) => Object.assign({ responsibles: r }, JSON.parse(JSON.stringify(cfgSnapshot()))), escenario.configuracion.responsibles || []) };
  }
  if (p === '/api/v1/catalogos' && metodo === 'PUT') {
    if (rol === 'readonly') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Tu usuario es de solo lectura: no se guardan cambios.' } };
    if (!(cuerpo.projects || []).length) return protegida('TestBenchProjects');
    await sesion.evaluar((pr) => { state.projects = pr; save(); refreshDL(); }, cuerpo.projects);
    await sesion.avanzar(1500);
    return { estado: 200, cuerpo: { ok: true } };
  }
  if (p === '/api/v1/configuracion/bancos' && metodo === 'PUT') {
    if (rol !== 'admin') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Solo un Admin puede hacer esta operación.' } };
    if (!(cuerpo.benches || []).length) return protegida('TestBenchBenches');
    const antes = (await vista()).textos;
    await sesion.evaluar(([cfg, ren]) => {
      // Lo mismo que renameBench / delBench / delCategory de la 4.9.4 sobre state.benches.
      Object.entries(ren || {}).forEach(([a, n]) => {
        state.benches[n] = state.benches[a] || [];
        delete state.benches[a];
        if (state.benchOrg && state.benchOrg[a]) { state.benchOrg[n] = state.benchOrg[a]; delete state.benchOrg[a]; }
        if (current === a) current = n;
      });
      const nombres = new Set(cfg.benches.map((b) => b.name));
      GROUPS.forEach((g) => g.benches.forEach((b) => { if (!nombres.has(b) && !Object.values(ren || {}).includes(b)) { delete state.benches[b]; if (state.benchOrg) delete state.benchOrg[b]; } }));
      state.projects = cfg.projects;
      applyConfig({ projects: cfg.projects, benches: cfg.benches });
      cfg.benches.forEach((b) => { state.benchOrg[b.name] = b.org; });
      persistGroups();
    }, [cuerpo, cuerpo.renombrados]);
    await sesion.avanzar(1500);
    const nueva = await vista();
    const cambiadas = new Set([...Object.keys(nueva.textos), ...Object.keys(antes)]);
    const filas = [...cambiadas].filter((k) => nueva.textos[k] !== antes[k]);
    const borradas = filas.filter((k) => nueva.textos[k] === undefined).length;
    return { estado: 200, cuerpo: { filasGuardadas: filas.length, ensayosBorrados: borradas, vista: nueva.vista } };
  }
  // Exportaciones (bloque F). El Excel lo genera el backend Java (probado aparte con
  // referencias-excel); aquí se guarda la matriz recibida para compararla con la de la 4.9.4.
  if (p === '/api/v1/exportaciones/gantt' && metodo === 'POST') {
    ultimaMatrizExcel = cuerpo;
    return { estado: 200, cuerpo: { ok: true, b64: Buffer.from('simulado').toString('base64'), filename: nombreExcel(cuerpo.title, hoyLaboratorio) } };
  }
  if (p === '/api/v1/exportaciones/gantt-recibida' && metodo === 'GET') return { estado: 200, cuerpo: ultimaMatrizExcel };
  if (p === '/api/v1/exportaciones/copia' && metodo === 'GET') {
    return { estado: 200, cuerpo: await sesion.evaluar(() => stripState(state)) };
  }
  if (p === '/api/v1/importaciones/copia' && metodo === 'POST') {
    if (rol !== 'admin') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Solo un Admin puede hacer esta operación.' } };
    if (!cuerpo || !cuerpo.benches || typeof cuerpo.benches !== 'object') return { estado: 400, cuerpo: { error: 'fichero-no-valido', mensaje: 'Invalid file' } };
    if (Array.isArray(cuerpo.projects) && !cuerpo.projects.length) return protegida('TestBenchProjects');
    await sesion.evaluar((d) => {
      // El cuerpo de importJSON de la 4.9.4, sin el FileReader ni el alert.
      state = d; if (!state.settings) state.settings = { year: TODAY.getFullYear(), skipHolidays: true };
      if (!state.holidays) state.holidays = computeHolidays(state.settings.year);
      if (!Array.isArray(state.groups) || !state.groups.length) state.groups = GROUPS_SEED.map((g) => ({ key: g.key, name: g.name, dot: g.dot, benches: g.benches.slice() }));
      GROUPS = state.groups; rebuildDotOf(); GROUPS.forEach((g) => g.benches.forEach((b) => { if (!state.benches[b]) state.benches[b] = []; }));
      if (!Array.isArray(state.projects)) state.projects = PROJECTS_SEED.slice();
      if (!Array.isArray(state.responsibles)) state.responsibles = RESPS_SEED.slice();
      HOLSET = new Set(state.holidays.map((h) => h.date)); save(); render();
    }, cuerpo);
    await sesion.avanzar(1500);
    return { estado: 200, cuerpo: { ok: true } };
  }
  if (p === '/api/v1/usuarios' && metodo === 'GET') {
    return { estado: 200, cuerpo: await sesion.evaluar((u0) => (Array.isArray(USERS_CACHE) && USERS_CACHE.length ? USERS_CACHE : u0)
      .map((u) => ({ title: u.title, email: u.email, role: u.role, responsibleName: u.responsibleName || '' })), escenario.usuarios) };
  }
  if (p === '/api/v1/usuarios' && metodo === 'PUT') {
    if (rol !== 'admin') return { estado: 403, cuerpo: { error: 'sin-permiso', mensaje: 'Solo un Admin puede hacer esta operación.' } };
    if (!(cuerpo.users || []).length) return protegida('TestBenchUsers');
    await sesion.evaluar((us) => { USERS_CACHE = us.map((u) => ({ title: u.title, email: u.email, role: String(u.role).toLowerCase().replace(' ', ''), responsibleName: u.responsibleName })); mergeRespsFromUsers(); }, cuerpo.users);
    return { estado: 200, cuerpo: { ok: true } };
  }
  const calendario = () => sesion.evaluar(() => ({ anio: state.settings.year, saltarFestivos: !!state.settings.skipHolidays, festivos: state.holidays.map((h) => ({ fecha: h.date, nombre: h.name })) }));
  if (p === '/api/v1/calendario' && metodo === 'GET') return { estado: 200, cuerpo: await calendario() };
  if (p === '/api/v1/calendario' && metodo === 'PUT') {
    await sesion.evaluar((c) => {
      state.settings.year = c.anio; state.settings.skipHolidays = !!c.saltarFestivos;
      state.holidays = c.festivos.map((f) => ({ date: f.fecha, name: f.nombre })); HOLSET = new Set(state.holidays.map((h) => h.date)); save();
    }, cuerpo);
    return { estado: 200, cuerpo: await calendario() };
  }
  if (p === '/api/v1/calendario/anio' && metodo === 'POST') {
    await sesion.evaluar((y) => { state.settings.year = y; state.holidays = computeHolidays(y); HOLSET = new Set(state.holidays.map((h) => h.date)); save(); }, cuerpo.anio);
    return { estado: 200, cuerpo: await calendario() };
  }
  if (p === '/api/v1/actividad') return { estado: 200, cuerpo: { rows: [], columns: [] } };
  if (p.startsWith('/api/v1/indicadores/')) return indicadores(p.slice('/api/v1/indicadores/'.length), url.searchParams, metodo);
  if (p === '/api/v1/aprobaciones') return { estado: 200, cuerpo: escenario.aprobaciones };
  if (p === '/api/v1/aprobaciones/estados') {
    const salida = {};
    (cuerpo.ids || []).forEach((id) => {
      const n = (String(id).match(/\d+/) || [])[0];
      const a = n && escenario.aprobaciones.find((x) => String(x.id) === n);
      salida[id] = { status: a ? a.status : 'not_created' };
    });
    return { estado: 200, cuerpo: salida };
  }
  if ((m = /^\/api\/v1\/bancos\/([^/]+)\/operaciones$/.exec(p))) return operacion(decodeURIComponent(m[1]), cuerpo);
  if ((m = /^\/api\/v1\/bancos\/([^/]+)\/ensayos\/([^/]+)\/campos$/.exec(p))) return campos(decodeURIComponent(m[1]), decodeURIComponent(m[2]), cuerpo);
  if ((m = /^\/api\/v1\/bancos\/([^/]+)\/ensayos\/([^/]+)\/cierre(\/validacion)?$/.exec(p))) {
    const banco = decodeURIComponent(m[1]), id = decodeURIComponent(m[2]);
    if (metodo === 'GET') {
      const correccion = url.searchParams.get('correccion') === 'true';
      if (correccion && rol !== 'admin') return { estado: 422, cuerpo: { error: 'regla', mensaje: 'Solo un Admin puede corregir un ensayo cerrado.' } };
      const r = await ventanaCierre(banco, id, correccion, null);
      const datos = (await vista()).vista.bancos[banco].find((f) => f.id === id).datos;
      return { estado: 200, cuerpo: { ...r, comprometidoInicio: datos.confirmedStart || '', comprometidoFin: datos.confirmedEnd || '' } };
    }
    if (m[3]) return { estado: 200, cuerpo: (await ventanaCierre(banco, id, null, cuerpo.valores)).validacion };
    const conflicto = !cuerpo.forzar && await comprobarVersiones(cuerpo.versiones);
    if (conflicto) return conflicto;
    const r = await ventanaCierre(banco, id, !!cuerpo.correccion, cuerpo.valores);
    if (r.validacion.error) return { estado: 422, cuerpo: { error: 'regla', mensaje: r.validacion.error } };
    await sesion.evaluar(() => confirmClose());
    await sesion.avanzar(1500);
    return { estado: 200, cuerpo: { filasGuardadas: 1, vista: (await vista()).vista } };
  }
  if (p === '/api/load') {
    const { textos } = await vista();
    return { estado: 200, cuerpo: Object.entries(textos).map(([uid, data]) => ({ uid, data })) };
  }
  if (p === '/api/heartbeat' || p === '/api/shutdown' || p === '/api/v1/presencia') return { estado: 200, cuerpo: { ok: true } };
  return { estado: 404, cuerpo: { error: 'sin ruta' } };
}

const TIPOS = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml' };

const servidor = http.createServer((req, res) => {
  let datos = '';
  req.on('data', (t) => { datos += t; });
  req.on('end', () => {
    if (req.url.split('?')[0] === '/clasico') {
      // La interfaz 4.9.4 original, como InterfazClasicaController del backend Java.
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(fs.readFileSync(path.join(RAIZ, 'legacy/BW_TestBench_Planner_31_Local.html')));
      return;
    }
    if (!req.url.startsWith('/api/')) {
      const ruta = path.join(DIST, req.url.split('?')[0] === '/' ? 'index.html' : decodeURIComponent(req.url.split('?')[0]));
      if (ruta.startsWith(DIST) && fs.existsSync(ruta) && fs.statSync(ruta).isFile()) {
        res.writeHead(200, { 'Content-Type': TIPOS[path.extname(ruta)] || 'application/octet-stream' });
        res.end(fs.readFileSync(ruta));
      } else {
        res.writeHead(404); res.end();
      }
      return;
    }
    cola = cola.then(async () => {
      try {
        const r = await atender(req.method, req.url, datos ? JSON.parse(datos) : null);
        res.writeHead(r.estado, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(r.cuerpo ?? null));
      } catch (e) {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: String(e && e.message || e) }));
      }
    });
  });
});

if (process.env.TZ !== 'Europe/Madrid') throw new Error('Lanza con TZ=Europe/Madrid');
servidor.listen(PUERTO, '127.0.0.1', () => console.log(`Backend simulado (4.9.4) en http://127.0.0.1:${PUERTO}`));
process.on('SIGTERM', async () => { await cerrarNavegador(); process.exit(0); });
