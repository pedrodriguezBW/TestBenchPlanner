'use strict';
/*
 * Ejecuta un caso de facturación paso a paso con el motor JavaScript y devuelve
 * lo observado tras cada paso, normalizado. La prueba Java ParidadFacturacionTest
 * ejecuta los mismos pasos con el dominio Java y compara con lo guardado aquí.
 */
const m = require('./motor.cjs');

const USUARIOS = {
  admin: { nombre: 'Admin Pruebas', rol: 'admin' },
  editor: { nombre: 'Editor Pruebas', rol: 'editor' },
  readonly: { nombre: 'Lector Pruebas', rol: 'readonly' },
};

function aplicarParches(maestros, parches) {
  const r = m.clonar(maestros);
  (parches || []).forEach((p) => {
    if (p.anadir) r[p.anadir] = [...(r[p.anadir] || []), ...p.valores];
    if (p.poner) r[p.poner] = p.valor;
  });
  return r;
}

/** Ensayo de caso con los valores por defecto (formato ensayoFacturable). */
function ensayo(e) {
  return Object.assign({ id: '', banco: '', organizacion: 'BW', cerrado: true, tipoEnsayo: '', responsable: 'Responsable 01', proyecto: '',
    approvalId: '', requester: '', fechaInicio: '', fechaFin: '', finDeSemana: false, inicioAsumido: false, inicioPlan: '', finPlan: '',
    tipoEspecial: false }, e, { billing: m.datosFacturacion({ billing: e.billing || {} }) });
}

function normalizarLinea(l) {
  const n = (x) => (x === null || x === undefined ? null : Math.round(x * 1e6) / 1e6);
  const c = l.calculado || {};
  const a = l.aplicado || {};
  return {
    id: l.id, estado: l.estado, origen: l.origen, incidencias: (l.incidencias || []).map((i) => i.codigo).sort(),
    tramoInicio: l.tramoInicio || '', tramoFin: l.tramoFin || '',
    minutosBrutos: c.minutosBrutos ?? null, minutosFinDeSemana: c.minutosFinDeSemana ?? null, minutosParadas: c.minutosParadas ?? null,
    horasNetas: n(c.horasNetas), horasSetup: n(c.horasSetup), categoria: c.categoria ?? null, orden: c.orden ?? null, hv: c.hv ?? null,
    tarifa: n(c.tarifa), coste: n(c.coste),
    aplicado: l.aplicado ? { horasNetas: n(a.horasNetas), horasSetup: n(a.horasSetup), tarifa: n(a.tarifa), coste: n(a.coste), orden: a.orden, categoria: a.categoria } : null,
    ajustes: Object.keys(l.ajustes || {}).sort().map((k) => `${k}:${l.ajustes[k].validado ? 'validado' : 'pendiente'}${l.ajustes[k].conflicto ? ':conflicto' : ''}`),
    propuesta: l.propuestaTarifa ? `${l.propuestaTarifa.estado}:${l.propuestaTarifa.alcance || ''}` : '',
  };
}

function observar(mundo, id, maestros) {
  const doc = mundo.docs[id];
  const r = m.resumen(doc, maestros);
  const n = (x) => Math.round(x * 1e6) / 1e6;
  return {
    periodo: id, estado: doc.estado, versionAprobada: doc.versionAprobada, exportaciones: (doc.exportaciones || []).map((x) => x.estado),
    lineas: doc.lineas.map(normalizarLinea),
    indicadores: { coste: n(r.indicadores.coste), horasNetas: n(r.indicadores.horasNetas), horasSetup: n(r.indicadores.horasSetup), lineas: r.indicadores.lineas,
      bloqueadas: r.indicadores.bloqueadas, excluidas: r.indicadores.excluidas, aplazadas: r.indicadores.aplazadas, pendientes: r.indicadores.pendientes },
    controlling: r.controlling.map((f) => ({ equipo: f.equipo, orden: f.orden, amount: f.amount, totalQty: n(f.totalQty), um: f.um, lineas: f.lineas.length })),
  };
}

function ejecutar(caso, maestrosBase) {
  const maestros = aplicarParches(maestrosBase, caso.maestros);
  const ensayos = caso.ensayos.map(ensayo);
  const mundo = { docs: {} };
  const salida = [];
  for (const paso of caso.pasos) {
    const ctx = { ahora: paso.ahora || caso.ahora };
    const usuario = USUARIOS[paso.usuario || 'editor'];
    const otros = (id) => Object.fromEntries(Object.entries(mundo.docs).filter(([k]) => k !== id));
    let error = '';
    let extra = null;
    try {
      switch (paso.tipo) {
        case 'crear': {
          const p = m.periodoDeId(paso.periodo);
          const doc = m.nuevoPeriodo(p.anio, p.mes, usuario, ctx);
          m.recalcular(doc, ensayos, maestros, otros(paso.periodo), usuario, ctx);
          mundo.docs[paso.periodo] = doc;
          break;
        }
        case 'recalcular': m.recalcular(mundo.docs[paso.periodo], ensayos, maestros, otros(paso.periodo), usuario, ctx); break;
        case 'accion': {
          const r = m.ejecutarAccionLinea(mundo.docs[paso.periodo], paso.linea, paso.accion, paso.datos || {}, usuario, ensayos, maestros, otros(paso.periodo), ctx);
          extra = { auditoria: r.auditoria.map((a) => a.accion), maestrosCambiados: r.maestrosCambiados };
          break;
        }
        case 'enviar': m.enviarRevision(mundo.docs[paso.periodo], usuario, ctx); break;
        case 'aprobar': m.aprobar(mundo.docs[paso.periodo], usuario, ctx, paso.confirmacion !== false, maestros); break;
        case 'reabrir': m.reabrir(mundo.docs[paso.periodo], usuario, ctx, paso.justificacion || ''); break;
        case 'exportar': mundo.docs[paso.periodo].exportaciones.push({ id: `EXP-${paso.periodo}-${mundo.docs[paso.periodo].versionAprobada}`, version: mundo.docs[paso.periodo].versionAprobada, tipo: 'excel', por: usuario.nombre, en: ctx.ahora, estado: 'VALIDA', nombre: 'x.xlsx' }); mundo.docs[paso.periodo].estado = 'EXPORTADO'; break;
        case 'cambiarEnsayo': {
          const e = ensayos.find((x) => x.id === paso.ensayo);
          Object.assign(e, paso.campos || {});
          if (paso.billing) e.billing = m.datosFacturacion({ billing: Object.assign({}, e.billing, paso.billing) });
          break;
        }
        case 'alertas': extra = { alertas: m.alertasTrasAprobar(mundo.docs[paso.periodo], ensayos, maestros, ctx).map((a) => a.lineaId) }; break;
        case 'ficha': {
          const e = ensayos.find((x) => x.id === paso.ensayo);
          const f = m.fichaEnsayo(e, maestros, ctx);
          extra = { ficha: { estado: f.estado.estado, faltan: f.estado.faltan, errores: f.errores, setupRecomendado: f.setupRecomendado,
            tramos: f.tramos.map((t) => `${t.periodo}:${t.horasNetas}`) } };
          break;
        }
        default: throw new Error(`Paso desconocido ${paso.tipo}`);
      }
    } catch (e) {
      if (!(e instanceof m.ErrorRegla)) throw e;
      error = e.message;
    }
    const obs = { paso: paso.tipo, error };
    if (extra) Object.assign(obs, extra);
    if (paso.periodo && mundo.docs[paso.periodo] && paso.tipo !== 'ficha') obs.resultado = observar(mundo, paso.periodo, maestros);
    salida.push(obs);
  }
  return salida;
}

module.exports = { ejecutar, ensayo, aplicarParches, USUARIOS, normalizarLinea };
