'use strict';
/*
 * API /api/v1/facturacion SIMULADA para probar la interfaz React sin Java.
 * Misma forma de peticiones y respuestas que FacturacionController /
 * ServicioFacturacion, con la réplica hoja.cjs. Los Equipment Types
 * (lista TestBenchEquipmentTypes) y las columnas BillingOrder / EquipmentType
 * de los catálogos viven en memoria y empiezan VACÍOS (sin datos precargados).
 */
const h = require('./hoja.cjs');

/** Campos de facturación de la tarea (EdicionEnsayo.java): valor normalizado o error. */
function normalizarCampo(campo, valor) {
  const texto = valor === null || valor === undefined ? '' : String(valor);
  if (campo === 'setupHours') {
    if (texto === '') return { valor: '' };
    const n = Number(texto);
    if (Number.isNaN(n) || n < 0) return { error: 'Setup: indica un número mayor o igual que 0.' };
    return { valor: n };
  }
  if (campo === 'hv') return { valor: texto === 'yes' || texto === 'no' ? texto : '' };
  return { valor: texto.trim() };
}
const CAMPOS_TAREA = ['setupHours', 'hv', 'productLine'];

function crear(host) {
  let estado = nuevoEstado();
  function nuevoEstado() { return { tipos: [], ordenes: {}, tipoBanco: {} }; }
  const ok = (cuerpo) => ({ estado: 200, cuerpo });
  const regla = (mensaje) => ({ estado: 422, cuerpo: { error: 'regla', mensaje } });
  const sinPermiso = (mensaje) => ({ estado: 403, cuerpo: { error: 'sin-permiso', mensaje } });

  /** Catálogo de la 4.9.4 con las columnas BillingOrder (projectOrders) y EquipmentType (benches[].equipmentType). */
  function mezclarCatalogo(cat) {
    const salida = JSON.parse(JSON.stringify(cat));
    salida.projectOrders = Object.fromEntries((cat.projects || []).filter((t) => estado.ordenes[t]).map((t) => [t, estado.ordenes[t]]));
    salida.benches = (cat.benches || []).map((b) => Object.assign({}, b, { equipmentType: estado.tipoBanco[b.name] || '' }));
    return salida;
  }
  /** Guarda las columnas que lleguen (si no llegan, se conservan). Devuelve un error 403 o null. */
  function guardarCatalogo(cat, cuerpo, renombrados) {
    if (cuerpo.projectOrders && typeof cuerpo.projectOrders === 'object') {
      const cambia = (cuerpo.projects || []).some((t) => String(cuerpo.projectOrders[t] || '').trim() !== String(estado.ordenes[t] || '').trim());
      if (cambia && host.rol() !== 'admin') return sinPermiso('Solo un Admin puede cambiar la orden contable de un proyecto.');
      estado.ordenes = Object.fromEntries((cuerpo.projects || []).filter((t) => String(cuerpo.projectOrders[t] || '').trim())
        .map((t) => [t, String(cuerpo.projectOrders[t]).trim()]));
    }
    Object.entries(renombrados || {}).forEach(([a, n]) => { if (estado.tipoBanco[a] !== undefined) estado.tipoBanco[n] = estado.tipoBanco[a]; });
    (cuerpo.benches || []).forEach((b) => { if (b && 'equipmentType' in b) estado.tipoBanco[b.name] = String(b.equipmentType || '').trim(); });
    return null;
  }

  async function configuracion() {
    const { vista } = await host.vista();
    const bancos = [];
    vista.grupos.forEach((g) => g.bancos.forEach((b) => bancos.push({ nombre: b.nombre, organizacion: b.organizacion, equipmentType: estado.tipoBanco[b.nombre] || '' })));
    return { tipos: estado.tipos.map((t) => Object.assign({}, t)), reglas: h.REGLAS, grupos: h.GRUPOS_EXCEL, productLines: h.PRODUCT_LINES, bancos };
  }

  async function calcular(mes, nvs) {
    const { vista } = await host.vista();
    const entradas = [];
    Object.entries(vista.bancos).forEach(([banco, filas]) => filas.forEach((f) => {
      const e = h.entradaDeFila(banco, f, vista.hoy);
      if (e) entradas.push(e);
    }));
    return h.calcular(entradas, mes, estado.tipoBanco, estado.tipos, estado.ordenes, nvs);
  }

  async function atender(metodo, p, q) {
    const ruta = p.replace('/api/v1/facturacion', '');
    if (ruta === '/tipos-equipo' && metodo === 'GET') return ok(await configuracion());
    const mes = q.get('mes') || '';
    if ((ruta === '/hoja' || ruta === '/hoja/exportar') && !/^\d{4}-(0[1-9]|1[0-2])$/.test(mes)) return regla(`Mes no válido (aaaa-mm): ${mes}`);
    let nvs;
    try { nvs = h.niveles(q.get('niveles')); } catch (e) { return regla(e.message); }
    if (ruta === '/hoja' && metodo === 'GET') {
      return ok(Object.assign(h.json(await calcular(mes, nvs)), { nivelesDisponibles: h.NIVELES.map((n) => ({ id: n.id, nombre: n.nombre })) }));
    }
    if (ruta === '/hoja/exportar' && metodo === 'GET') {
      const hoja = await calcular(mes, nvs);
      const html = q.get('formato') === 'html';
      const bytes = html ? Buffer.from(h.html(mes, hoja.exportable), 'utf8') : h.excel(mes, hoja.exportable);
      return ok({ ok: true, filename: `${h.nombreArchivo(mes)}.${html ? 'html' : 'xlsx'}`, b64: bytes.toString('base64'),
        excluidas: hoja.lineas.filter((l) => !l.completa).length });
    }
    return { estado: 404, cuerpo: { error: 'sin ruta', mensaje: p } };
  }

  async function atenderConCuerpo(metodo, p, q, cuerpo) {
    if (p === '/api/v1/facturacion/tipos-equipo' && metodo === 'PUT') {
      if (host.rol() === 'readonly') return sinPermiso('Tu usuario es de solo lectura: no se guardan cambios.');
      if (host.rol() !== 'admin') return sinPermiso('Solo un Admin puede hacer esta operación.');
      const errores = h.validarTipos(cuerpo.tipos);
      if (errores.length) return regla(errores.join(' '));
      estado.tipos = cuerpo.tipos.map(h.tipoEquipo);
      return ok(await configuracion());
    }
    return atender(metodo, p, q);
  }

  return {
    atender: atenderConCuerpo, reiniciar: () => { estado = nuevoEstado(); }, estado: () => estado, mezclarCatalogo, guardarCatalogo,
    catalogosActuales: () => host.catalogos(),
  };
}

module.exports = { crear, normalizarCampo, CAMPOS_TAREA };
