'use strict';
/*
 * Traduce los PASOS NEUTROS de un caso de prueba a acciones sobre la 4.9.4.
 *
 * Los casos (carpeta casos/) no saben nada de la implementación: hablan de
 * «editar el campo X del ensayo Y», «comprometer», «cerrar con estos valores»…
 * Este controlador los ejecuta en la versión original; el de la versión nueva
 * hará lo mismo contra React/Java, y las dos FOTOS resultantes deben coincidir.
 *
 * Criterio: las ediciones de celda se hacen escribiendo en el DOM real (así se
 * ejecutan los manejadores originales, con sus avisos y efectos secundarios).
 * Los botones se invocan llamando a la misma función que su onclick.
 */

/** Acciones de fila: nombre neutro -> función global de la 4.9.4 que llama el botón. */
const BOTONES_DE_FILA = {
  comprometer: 'confirmDatesRow',
  deshacerCompromiso: 'unconfirmDatesRow',
  borrar: 'delRow',
  aplicarSugerencia: 'applySuggestion',
  restaurar: 'restoreDone',
  reutilizar: 'reuseDone',
};

async function abrirBanco(sesion, banco) {
  await sesion.evaluar((bn) => {
    if (current !== bn) { current = bn; SEL.clear(); render(); }
  }, banco);
}

async function ejecutarPaso(sesion, paso) {
  const { op } = paso;
  if (paso.banco) await abrirBanco(sesion, paso.banco);
  switch (op) {
    case 'editar': {
      // Celda de la tabla: input / select / checkbox con data-f="campo".
      const celda = sesion.pagina.locator(`#tb tr[data-id="${paso.ensayo}"] [data-f="${paso.campo}"]`).first();
      if (await celda.count() === 0) throw new Error(`No hay celda ${paso.campo} para ${paso.ensayo} en ${paso.banco}`);
      const etiqueta = await celda.evaluate((el) => el.tagName + ':' + (el.type || '') + ':' + el.className);
      if (etiqueta.includes('datecal')) {
        // Fecha: la 4.9.4 usa su propio calendario (solo lectura + calPick/calClear).
        await sesion.evaluar(([bn, id, campo, valor]) => {
          const el = document.querySelector(`#tb tr[data-id="${id}"] [data-f="${campo}"]`);
          openDateCal(el);
          if (valor) calPick(valor); else calClear();
        }, [paso.banco, paso.ensayo, paso.campo, paso.valor]);
      } else if (etiqueta.startsWith('SELECT')) await celda.selectOption(String(paso.valor));
      else if (etiqueta.includes(':checkbox:')) await celda.setChecked(!!paso.valor);
      else await celda.fill(paso.valor === null ? '' : String(paso.valor));
      break;
    }
    case 'estado':
      await sesion.evaluar(([bn, id, v]) => setManualState(bn, id, v), [paso.banco, paso.ensayo, paso.valor]);
      break;
    case 'confirmarTodas':
      await sesion.evaluar((bn) => confirmAll(bn), paso.banco);
      break;
    case 'anadir':
      await sesion.evaluar((bn) => addRow(bn), paso.banco);
      break;
    case 'seleccionar':
      await sesion.evaluar(([bn, ids]) => { SEL.clear(); ids.forEach((i) => SEL.add(i)); renderBench(bn); }, [paso.banco, paso.ensayos]);
      break;
    case 'duplicar':
      await sesion.evaluar((bn) => duplicateSel(bn), paso.banco);
      break;
    case 'borrarSeleccion':
      await sesion.evaluar((bn) => deleteSel(bn), paso.banco);
      break;
    case 'mover':
      // El panel de selección tiene un desplegable de destino y la prioridad.
      await sesion.evaluar(([bn, destino, posicion]) => {
        const sel = document.getElementById('move-target'); const pos = document.getElementById('move-pos');
        if (!sel) throw new Error('No aparece el panel de mover (¿no hay selección?)');
        sel.value = destino; if (pos) pos.value = String(posicion);
        moveSel(bn);
      }, [paso.banco, paso.destino, paso.posicion]);
      break;
    case 'arrastrar': {
      // Soltar `origen` encima de `destino` (con la selección actual si la hay).
      const origen = sesion.pagina.locator(`#tb tr[data-id="${paso.origen}"] [data-drag]`);
      const destino = sesion.pagina.locator(`#tb tr[data-id="${paso.destino}"]`);
      await origen.dragTo(destino);
      break;
    }
    case 'cerrar':
    case 'corregir': {
      const modoCorreccion = op === 'corregir';
      await sesion.evaluar(([bn, id, ed]) => openCloseModal(bn, id, ed), [paso.banco, paso.ensayo, modoCorreccion]);
      const v = paso.valores || {};
      const pagina = sesion.pagina;
      const visible = await pagina.evaluate(() => document.getElementById('closem') && document.getElementById('closem').classList.contains('show'));
      if (!visible) break; // p. ej. un Editor intentando corregir: la 4.9.4 avisa y no abre
      const campos = { arranqueReal: 'cl-ts', finReal: 'cl-te', diasParada: 'cl-dd', notaParada: 'cl-dn', explicacion: 'cl-cm' };
      for (const [clave, idCampo] of Object.entries(campos)) {
        if (v[clave] !== undefined) await pagina.locator('#' + idCampo).fill(v[clave] === null ? '' : String(v[clave]));
      }
      if (v.estadoFinal !== undefined) await pagina.locator('#cl-st').selectOption(v.estadoFinal);
      if (v.motivo !== undefined) await pagina.locator('#cl-rs').selectOption(v.motivo);
      if (paso.confirmar !== false) await pagina.locator('#cl-ok').click();
      break;
    }
    case 'recurso':
      // ✚ / ✓ Quitar en el selector de recursos de la fila (toggleResource).
      await sesion.evaluar(async ([bn, id, nombre]) => {
        if (!RES_LOADED) await resLoad();
        RES_PICK = { bn, id, tab: 'free', q: '' };
        ensureResModals();
        toggleResource(nombre);
      }, [paso.banco, paso.ensayo, paso.recurso]);
      break;
    case 'renombrarRecurso':
      // Equipments List: cambiar el nombre de un equipo (renameResource).
      await sesion.evaluar(async ([anterior, nuevo]) => {
        if (!RES_LOADED) await resLoad();
        renameResource(anterior, nuevo);
      }, [paso.anterior, paso.nuevo]);
      break;
    case 'fotoKpi':
      await sesion.evaluar(() => updateYearlyStatus(true));
      break;
    case 'esperar':
      await sesion.avanzar(paso.ms);
      break;
    default: {
      const funcion = BOTONES_DE_FILA[op];
      if (!funcion) throw new Error('Paso desconocido: ' + op);
      await sesion.evaluar(([f, bn, id]) => window[f](bn, id), [funcion, paso.banco, paso.ensayo]);
    }
  }
  // Tras cada paso dejamos pasar el guardado diferido (1,2 s) para capturar lo enviado.
  await sesion.avanzar(1500);
}

/**
 * FOTO del estado observable, en un formato neutro y estable.
 * Es lo que se compara entre la 4.9.4 y la versión nueva.
 */
async function fotografiar(sesion, apartados) {
  return sesion.evaluar(async (ap) => {
    const quiere = (k) => !ap || ap.includes(k);
    if ((quiere('historicoKpi') || quiere('kpiHistorico')) && KPIHIST === null) KPIHIST = (await loadKpiHistory()) || [];
    recomputeAll();
    const foto = {};
    const todosLosBancos = []; GROUPS.forEach((g) => g.benches.forEach((b) => todosLosBancos.push(b)));
    const fechaIso = (d) => (d ? iso(d) : null);
    if (quiere('planificacion')) {
      foto.planificacion = {};
      todosLosBancos.forEach((bn) => {
        foto.planificacion[bn] = (state.benches[bn] || []).filter((r) => !r.done).map((r) => ({
          id: r.id,
          inicio: fechaIso(r._start), fin: fechaIso(r._end), criterioInicio: r._startWhy || '',
          estado: statusOf(r), sugerencias: suggestionsFor(r).map((s) => s.k), sugerenciaAbierta: suggestionOpen(r),
          solapes: (r._ovl || []).map((o) => o.s + '..' + o.e), diasPlanificados: plannedDays(r),
          finTolerado: fechaIso(tolEndOf(r)), finPlanificado: fechaIso(plannedEndOf(r)),
          arranqueEfectivo: effStartOf(r) || '', diasSinAvance: idleDays(r),
        }));
      });
    }
    if (quiere('persistido')) {
      // Exactamente lo que se escribiría en DataJson para cada ensayo.
      foto.persistido = lbBuild();
    }
    if (quiere('kpi')) {
      const lunes = weekStart(TODAY), siguiente = addDays(lunes, 7);
      const filtros = { ALL: null, BW: (bn) => benchOrg(bn) === 'BW', CTAG: (bn) => benchOrg(bn) === 'CTAG' };
      foto.kpi = { bloques: {}, semana: {}, ocupacion: {} };
      Object.entries(filtros).forEach(([org, ff]) => {
        foto.kpi.bloques[org] = { estaSemana: blockKPIs(lunes, null, ff), siguiente: blockKPIs(siguiente, null, ff) };
        foto.kpi.semana[org] = weekKPIs(null, ff, lunes);
      });
      foto.kpi.fotoSemanal = snapshotKPIs();
      const anio = TODAY.getFullYear(), ini = new Date(anio, 0, 1), fin = new Date(anio, 11, 31);
      GROUPS.forEach((g) => {
        foto.kpi.ocupacion[g.key] = { semana: occupancyOf(g.benches, lunes, addDays(lunes, 6)), anio: occupancyOf(g.benches, ini, fin) };
      });
    }
    if (quiere('panel')) {
      // Dashboard (bloque B): tabla de ocupación y gráfica por CW tal y como las
      // pinta la 4.9.4, y los bloques KPI filtrados por responsable.
      const lunes = weekStart(TODAY), domingo = addDays(lunes, 6);
      const anio = state.settings.year, nW = cw(new Date(anio, 11, 28));
      const prefs = Object.assign({}, VIEWPREF), trend = Object.assign({}, TREND);
      const numero = (t) => Number(String(t).replace(/[^0-9-]/g, ''));
      const leerTabla = () => {
        const caja = document.createElement('div');
        caja.innerHTML = utilCardHTML(lunes, domingo);
        const filas = [...caja.querySelectorAll('table.util-full tbody tr')].map((tr) => [...tr.children].map((td) => td.textContent.trim()));
        const total = filas.pop();
        return {
          grupos: filas.map((c) => ({ nombre: c[0], bancos: numero(c[1]), trabajando: numero(c[2]), busy: numero(c[3].split('/')[0]),
            cap: numero(c[3].split('/')[1]), pct: numero(c[4]), inicia: numero(c[5]), acaba: numero(c[6]) })),
          busy: numero(total[3].split('/')[0]), cap: numero(total[3].split('/')[1]), pct: numero(total[4]),
        };
      };
      foto.panel = { tabla: {}, tendencia: {}, responsables: {} };
      ['ALL', 'BW', 'CTAG'].forEach((org) => {
        VIEWPREF.utilOrg = org; VIEWPREF.utilMode = 'pct';
        VIEWPREF.utilFrom = null; VIEWPREF.utilTo = null;
        const semana = leerTabla();
        VIEWPREF.utilFrom = 1; VIEWPREF.utilTo = nW;
        const anual = leerTabla();
        VIEWPREF.utilFrom = 10; VIEWPREF.utilTo = 14;
        const rango = leerTabla();
        foto.panel.tabla[org] = { semana, anual, rango };
        VIEWPREF.trendOrg = org;
        TREND.cwFrom = 1; TREND.cwTo = 53;
        VIEWPREF.trendMode = 'pct';
        const pct = trendData();
        VIEWPREF.trendMode = 'hours';
        const horas = trendData();
        foto.panel.tendencia[org] = {
          desde: pct.cf, hasta: pct.ct, semanas: pct.nW, lunes: pct.weeks.map(iso),
          series: pct.series.map((s, i) => ({ clave: s.key, bancos: s.n, pct: s.data, busy: horas.series[i].data })),
        };
      });
      Object.assign(VIEWPREF, prefs); Object.assign(TREND, trend);
      const resps = [...new Set(GROUPS.flatMap((g) => g.benches.flatMap((bn) => activeRows(bn).map((r) => r.resp || ''))))].filter(Boolean).sort();
      resps.forEach((rp) => {
        const ff = (r) => (r.resp || '') === rp;
        foto.panel.responsables[rp] = { estaSemana: blockKPIs(lunes, ff, null), siguiente: blockKPIs(addDays(lunes, 7), ff, null) };
      });
    }
    if (quiere('kpiHistorico')) {
      // KPI History (bloque C): fotos por organización y estadísticas con filtros.
      const guardado = [KPIHIST_ORG, STAT_FROM, STAT_TO, STAT_RESP];
      foto.kpiHistorico = { historico: {}, estadisticas: {} };
      ['ALL', 'BW', 'CTAG'].forEach((o) => { KPIHIST_ORG = o; foto.kpiHistorico.historico[o] = Array.isArray(KPIHIST) ? kpiHistRows() : []; });
      const anioCal = state.settings.year;
      const filtros = [
        ['ALL', '', `${anioCal}-01-01`, `${anioCal}-12-31`], ['BW', '', `${anioCal}-01-01`, `${anioCal}-12-31`],
        ['CTAG', '', `${anioCal}-01-01`, `${anioCal}-12-31`], ['ALL', 'Responsable 27', `${anioCal}-01-01`, `${anioCal}-12-31`],
        ['ALL', '', `${anioCal}-09-01`, `${anioCal}-10-15`],
      ];
      filtros.forEach(([o, rp, a, b]) => {
        KPIHIST_ORG = o; STAT_RESP = rp; STAT_FROM = a; STAT_TO = b;
        foto.kpiHistorico.estadisticas[[o, rp, a, b].join('|')] = statRows().map((x) => ({ banco: x.bn, id: x.r.id, fecha: iso(x.dt), estadoFinal: x.fin, motivo: x.reason, confirmado: x.conf }));
      });
      [KPIHIST_ORG, STAT_FROM, STAT_TO, STAT_RESP] = guardado;
    }
    if (quiere('estadisticas')) {
      STAT_FROM = iso(new Date(TODAY.getFullYear(), 0, 1)); STAT_TO = iso(new Date(TODAY.getFullYear(), 11, 31));
      foto.estadisticas = statRows().map((x) => ({ banco: x.bn, id: x.r.id, fecha: iso(x.dt), estadoFinal: x.fin, motivo: x.reason, confirmado: x.conf }));
    }
    if (quiere('historicoKpi') && Array.isArray(KPIHIST)) {
      foto.historicoKpi = kpiHistRows();
    }
    if (quiere('cierre')) {
      const g = (k) => document.getElementById(k);
      const abierta = g('closem') && g('closem').classList.contains('show');
      foto.cierre = abierta ? {
        abierta: true, arranqueReal: g('cl-ts').value, finReal: g('cl-te').value, estadoFinal: g('cl-st').value,
        motivo: g('cl-rs').value, diasParada: g('cl-dd').value, error: g('cl-err').textContent,
        desviacion: g('cl-dev').textContent, sugerencia: g('cl-sug').textContent, facturables: g('cl-bill').textContent,
      } : { abierta: false };
    }
    foto.orden = {}; todosLosBancos.forEach((bn) => { foto.orden[bn] = (state.benches[bn] || []).map((r) => r.id); });
    return foto;
  }, apartados || null);
}

module.exports = { ejecutarPaso, fotografiar };
