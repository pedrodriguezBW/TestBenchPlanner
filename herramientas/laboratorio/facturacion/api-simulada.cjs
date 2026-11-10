'use strict';
/*
 * API /api/v1/facturacion SIMULADA para probar la interfaz React sin Java.
 * Misma forma de peticiones y respuestas que FacturacionController /
 * ServicioFacturacion, con el motor JavaScript (motor.cjs) y las exportaciones
 * (exportaciones.cjs). Los documentos viven en memoria; DataJson.billing se
 * guarda en el ensayo de la 4.9.4 del backend simulado.
 */
const fs = require('fs');
const path = require('path');
const m = require('./motor.cjs');
const x = require('./exportaciones.cjs');

const MAESTROS = path.resolve(__dirname, '../../../backend/src/main/resources/facturacion/maestros-iniciales.json');

function crear(host) {
  let estado = nuevoEstado();
  function nuevoEstado() {
    return { docs: {}, versiones: {}, maestros: null, versionMaestros: '', auditoria: [], contador: 0 };
  }
  const ok = (cuerpo) => ({ estado: 200, cuerpo });
  const regla = (mensaje) => ({ estado: 422, cuerpo: { error: 'regla', mensaje } });
  const sinPermiso = (mensaje) => ({ estado: 403, cuerpo: { error: 'sin-permiso', mensaje } });
  const conflicto = () => ({ estado: 409, cuerpo: { error: 'conflicto-facturacion', mensaje: 'Otra persona ha cambiado el periodo. Vuelve a cargarlo.' } });
  const nuevaVersion = () => `v${++estado.contador}`;
  const maestros = () => estado.maestros || JSON.parse(fs.readFileSync(MAESTROS, 'utf8'));
  const usuario = () => ({ nombre: 'Laboratorio', rol: host.rol() });
  const ctx = () => ({ ahora: `${host.hoy()}T12:00` });

  async function ensayos(conRequesters = true) {
    const { vista } = await host.vista();
    const org = {};
    vista.grupos.forEach((g) => g.bancos.forEach((b) => { org[b.nombre] = b.organizacion; }));
    const req = {};
    if (conRequesters) (host.aprobaciones() || []).forEach((a) => { if (a.requester) req[String(a.id)] = a.requester; });
    const salida = [];
    Object.entries(vista.bancos).forEach(([banco, filas]) => filas.forEach((f) => salida.push(m.ensayoFacturable(banco, org[banco], f, req))));
    return { salida, vista };
  }
  const otros = (id) => Object.fromEntries(Object.entries(estado.docs).filter(([k]) => k !== id));
  function auditar(accion, periodo, linea, detalle, justificacion, antes, despues) {
    estado.auditoria.push({ id: `A${estado.auditoria.length + 1}`, en: ctx().ahora, por: 'Laboratorio', email: '', rol: host.rol(), accion, periodo,
      linea: linea || '', detalle: detalle || '', justificacion: justificacion || '', costeAntes: antes ?? null, costeDespues: despues ?? null });
  }
  function exigirEditor() {
    if (host.rol() === 'readonly') return sinPermiso('Tu usuario es de solo lectura: no se guardan cambios.');
    return null;
  }
  function exigirAdmin() {
    return exigirEditor() || (host.rol() !== 'admin' ? sinPermiso('Solo un Admin puede hacer esta operación.') : null);
  }
  function aplicarFoto(doc, ms) {
    const r = m.clonar(ms);
    const f = (doc.fotos || []).find((y) => y.version === doc.versionAprobada);
    if (f) { r.tarifas = f.tarifas; r.categorias = f.categorias; r.controlling = f.controlling; }
    return r;
  }
  async function vistaPeriodo(id) {
    const doc = estado.docs[id];
    if (!doc) return regla(`El periodo ${id} no existe.`);
    const aprobado = !!m.fotoVigente(doc);
    const ms = maestros();
    const efectivos = aprobado ? aplicarFoto(doc, ms) : ms;
    const ligero = m.clonar(doc);
    ligero.fotos = (doc.fotos || []).map((f) => ({ version: f.version, aprobadoPor: f.aprobadoPor, aprobadoEn: f.aprobadoEn, indicadores: f.indicadores }));
    let alertas = [];
    if (aprobado) alertas = m.alertasTrasAprobar(doc, (await ensayos()).salida, ms, ctx());
    return ok({ periodo: ligero, version: estado.versiones[id], resumen: m.resumen(doc, efectivos), comprobacionesEnvio: m.comprobacionesEnvio(doc),
      comprobacionesAprobacion: m.comprobacionesAprobacion(doc), alertas, anterior: m.anteriorId(id), siguiente: m.siguienteId(id) });
  }
  /** Lee, comprueba la versión, aplica, guarda y audita (como ServicioFacturacion.cambiar). */
  async function cambiar(id, version, fn) {
    const doc = estado.docs[id];
    if (!doc) return regla(`El periodo ${id} no existe.`);
    if (version && version !== estado.versiones[id]) return conflicto();
    const copia = m.clonar(doc);
    const auditoria = [];
    const ms = maestros();
    try {
      await fn(copia, ms, auditoria);
    } catch (e) {
      if (e instanceof m.ErrorRegla) return regla(e.message);
      throw e;
    }
    estado.docs[id] = copia;
    estado.versiones[id] = nuevaVersion();
    auditoria.forEach((a) => auditar(a.accion, id, a.linea, a.detalle, a.justificacion, a.costeAntes, a.costeDespues));
    return vistaPeriodo(id);
  }

  function combinar(anterior, recibido, esAdmin, ms, proyectoTexto, comprobar) {
    const n = Object.assign({}, anterior || {});
    const ahora = ctx().ahora;
    const firma = 'Laboratorio';
    n.startTime = String(recibido.startTime || '').trim();
    n.endTime = String(recibido.endTime || '').trim();
    n.stops = (Array.isArray(recibido.stops) ? recibido.stops : []).map((p, i) => ({ id: p.id || `p${Date.now()}-${i + 1}`, from: String(p.from || '').trim(),
      to: String(p.to || '').trim(), reason: p.reason || '', billable: p.billable === true, note: p.note || '' }));
    const num = (v) => {
      if (v === '' || v === null || v === undefined) return '';
      const d = Number(v);
      if (!Number.isFinite(d)) throw new m.ErrorRegla(`Valor numérico no válido: ${v}`);
      return d;
    };
    n.setupHours = num(recibido.setupHours);
    n.setupConfirmed = recibido.setupConfirmed === true;
    n.hv = recibido.hv === 'yes' || recibido.hv === 'no' ? recibido.hv : '';
    const facturable = recibido.billable !== false;
    const era = !(anterior && anterior.billable === false);
    n.billable = facturable;
    if (!facturable && era) { n.billableBy = firma; n.billableAt = ahora; } else if (facturable) { delete n.billableBy; delete n.billableAt; }
    const orden = String(recibido.order || '').trim();
    n.order = orden;
    const jr = String((recibido.orderException && recibido.orderException.justification) || '').trim();
    const ja = String((anterior && anterior.orderException && anterior.orderException.justification) || '').trim();
    if (jr !== ja && comprobar && !esAdmin) throw new m.ErrorRegla('Solo un Admin puede autorizar una orden que no está asociada al proyecto.');
    if (!jr) delete n.orderException; else if (jr !== ja) n.orderException = { justification: jr, by: firma, at: ahora };
    if (comprobar && orden) {
      const numero = m.proyectoDeTexto(proyectoTexto).numero;
      const proy = numero ? (ms.proyectos || []).find((p) => m.numeroCanonico(p.numero) === numero && p.activo !== false) : null;
      const ords = proy ? (proy.ordenes || []).filter((o) => o.activa !== false).map((o) => String(o.codigo).trim()) : [];
      if (proy && !ords.includes(orden) && !jr) {
        throw new m.ErrorRegla(esAdmin ? 'La orden no está asociada al proyecto: indica la justificación de la orden excepcional.'
          : 'La orden no está asociada al proyecto: solo un Admin puede usar otra, con justificación.');
      }
    }
    n.pieces = num(recibido.pieces);
    n.note = String(recibido.note || '');
    n.updatedBy = firma;
    n.updatedAt = ahora;
    return n;
  }

  async function ficha(banco, id, propuesta, fechas) {
    const { vista } = await host.vista();
    const fila = (vista.bancos[banco] || []).find((f) => f.id === id);
    if (!fila) return regla(`El ensayo ya no existe en ${banco}.`);
    const ms = maestros();
    const f2 = m.clonar(fila);
    if (propuesta) {
      try { f2.datos.billing = combinar(fila.datos.billing, propuesta, host.rol() === 'admin', ms, fila.datos.project, false); } catch (e) { return regla(e.message); }
    }
    const org = vista.grupos.flatMap((g) => g.bancos).find((b) => b.nombre === banco)?.organizacion || '';
    const req = {};
    (host.aprobaciones() || []).forEach((a) => { if (a.requester) req[String(a.id)] = a.requester; });
    const e = m.ensayoFacturable(banco, org, f2, req);
    if (fechas && fechas.fechaInicio) e.fechaInicio = fechas.fechaInicio;
    if (fechas && fechas.fechaFin) e.fechaFin = fechas.fechaFin;
    return ok(Object.assign(m.fichaEnsayo(e, ms, ctx()), { version: fila.version, puedeEditar: host.rol() !== 'readonly', esAdmin: host.rol() === 'admin' }));
  }

  async function atender(metodo, p, q, cuerpo) {
    const ruta = p.slice('/api/v1/facturacion'.length);
    let r;
    if (ruta === '/maestros' && metodo === 'GET') {
      const { vista } = await host.vista();
      const tipos = [...new Set(Object.values(vista.bancos).flatMap((fs2) => fs2.map((f) => f.datos.type || '')).filter(Boolean))].sort((a, b) => a.localeCompare(b));
      return ok({ maestros: maestros(), version: estado.versionMaestros, guardados: !!estado.maestros, reglas: m.REGLAS, motivosParada: m.MOTIVOS_PARADA,
        bancos: vista.grupos.flatMap((g) => g.bancos.map((b) => ({ nombre: b.nombre, organizacion: b.organizacion }))), tiposEnsayo: tipos });
    }
    if (ruta === '/maestros' && metodo === 'PUT') {
      if ((r = exigirAdmin())) return r;
      if ((cuerpo.version || '') !== estado.versionMaestros) return { estado: 409, cuerpo: { error: 'conflicto-facturacion', mensaje: 'Otra persona ha cambiado los maestros de facturación. Vuelve a cargarlos.' } };
      const nuevos = cuerpo.maestros;
      nuevos.revision = (maestros().revision || 0) + 1;
      estado.maestros = nuevos;
      estado.versionMaestros = nuevaVersion();
      auditar('MAESTROS_MODIFICADOS', '', '', 'Cambios en los maestros', '');
      return atender('GET', p, q, {});
    }
    if (ruta === '/periodos' && metodo === 'GET') {
      return ok(Object.values(estado.docs).sort((a, b) => b.id.localeCompare(a.id)).map((d) => ({ id: d.id, nombre: d.nombre, inicio: d.inicio, fin: d.fin,
        estado: d.estado, creadoPor: d.creadoPor, creadoEn: d.creadoEn, ultimoCalculo: d.ultimoCalculo, versionAprobada: d.versionAprobada,
        resumenRapido: d.resumenRapido || {}, exportaciones: (d.exportaciones || []).length })));
    }
    if (ruta === '/periodos' && metodo === 'POST') {
      if ((r = exigirEditor())) return r;
      const anio = Number(cuerpo.anio); const mes = Number(cuerpo.mes);
      if (!(mes >= 1 && mes <= 12 && anio >= 2000 && anio <= 2100)) return regla('Mes no válido.');
      const per = m.periodo(anio, mes);
      if (estado.docs[per.id]) return regla(`El periodo ${per.nombre} ya existe.`);
      const doc = m.nuevoPeriodo(anio, mes, usuario(), ctx());
      m.recalcular(doc, (await ensayos()).salida, maestros(), otros(per.id), usuario(), ctx());
      estado.docs[per.id] = doc;
      estado.versiones[per.id] = nuevaVersion();
      auditar('PERIODO_CREADO', per.id, '', `${per.nombre} (${per.inicio} – ${per.fin})`, '');
      return vistaPeriodo(per.id);
    }
    let mm;
    if ((mm = /^\/periodos\/([^/]+)$/.exec(ruta)) && metodo === 'GET') return vistaPeriodo(decodeURIComponent(mm[1]));
    if ((mm = /^\/periodos\/([^/]+)\/(recalcular|enviar|aprobar|reabrir)$/.exec(ruta)) && metodo === 'POST') {
      const id = decodeURIComponent(mm[1]);
      const op = mm[2];
      if ((r = (op === 'aprobar' || op === 'reabrir') ? exigirAdmin() : exigirEditor())) return r;
      const lista = (await ensayos()).salida;
      return cambiar(id, cuerpo.version, async (doc, ms, auditoria) => {
        if (op === 'recalcular') { m.recalcular(doc, lista, ms, otros(id), usuario(), ctx()); auditoria.push({ accion: 'PERIODO_RECALCULADO', detalle: `Importe: ${doc.resumenRapido.importe}` }); }
        if (op === 'enviar') { m.enviarRevision(doc, usuario(), ctx()); auditoria.push({ accion: 'ENVIADO_A_REVISION', detalle: `Importe: ${doc.resumenRapido.importe}` }); }
        if (op === 'aprobar') { const v = m.aprobar(doc, usuario(), ctx(), cuerpo.confirmacion === true, ms); auditoria.push({ accion: 'PERIODO_APROBADO', detalle: `Versión ${v} · importe ${doc.resumenRapido.importe} €` }); }
        if (op === 'reabrir') { const v = doc.versionAprobada; m.reabrir(doc, usuario(), ctx(), cuerpo.justificacion || ''); auditoria.push({ accion: 'PERIODO_REABIERTO', detalle: `Versión ${v} reabierta; exportaciones marcadas como obsoletas`, justificacion: String(cuerpo.justificacion || '').trim() }); }
      });
    }
    if ((mm = /^\/periodos\/([^/]+)\/lineas\/([^/]+)$/.exec(ruta)) && metodo === 'POST') {
      if ((r = exigirEditor())) return r;
      const id = decodeURIComponent(mm[1]);
      const lineaId = decodeURIComponent(mm[2]);
      const lista = (await ensayos()).salida;
      return cambiar(id, cuerpo.version, async (doc, ms, auditoria) => {
        const res = m.ejecutarAccionLinea(doc, lineaId, cuerpo.accion, cuerpo.datos || {}, usuario(), lista, ms, otros(id), ctx());
        res.auditoria.forEach((a) => auditoria.push(Object.assign({ linea: lineaId }, a)));
        if (res.maestrosCambiados) {
          estado.maestros = Object.assign(m.clonar(ms), { revision: (ms.revision || 0) + 1 });
          estado.versionMaestros = nuevaVersion();
        }
      });
    }
    if ((mm = /^\/periodos\/([^/]+)\/exportar$/.exec(ruta)) && metodo === 'POST') {
      if ((r = exigirAdmin())) return r;
      const id = decodeURIComponent(mm[1]);
      let tipos = (cuerpo.tipos || []);
      if (tipos.includes('todos')) tipos = ['excel', 'controlling', 'html'];
      if (!tipos.length) return regla('Elige al menos un tipo de exportación.');
      const archivos = [];
      const res = await cambiar(id, cuerpo.version, async (doc, ms) => {
        if (doc.estado !== 'APROBADO' && doc.estado !== 'EXPORTADO') throw new m.ErrorRegla('Solo se exporta un periodo aprobado.');
        const v = doc.versionAprobada;
        const foto = doc.fotos.find((f) => f.version === v);
        const base = Object.assign(m.clonar(doc), { lineas: m.clonar(foto.lineas) });
        const ef = aplicarFoto(doc, ms);
        for (const tipo of tipos) {
          const exportId = `EXP-${id}-v${v}-${doc.exportaciones.length + 1}`;
          const meta = { id: exportId, version: v, aprobadoPor: foto.aprobadoPor, aprobadoEn: foto.aprobadoEn, exportadoPor: 'Laboratorio', exportadoEn: ctx().ahora };
          const a = tipo === 'excel' ? x.excelCompleto(base, ef, meta) : tipo === 'controlling' ? x.excelControlling(base, ef, meta) : x.htmlPresentacion(base, ef, meta);
          doc.exportaciones.push({ id: exportId, version: v, tipo, nombre: a.nombre, por: 'Laboratorio', en: ctx().ahora, estado: 'VALIDA' });
          archivos.push({ id: exportId, tipo, nombre: a.nombre, estado: 'VALIDA', b64: Buffer.from(a.contenido).toString('base64') });
          auditar('EXPORTACION', id, '', `${exportId} · ${a.nombre}`, '');
        }
        doc.estado = 'EXPORTADO';
      });
      if (res.estado !== 200) return res;
      return ok({ exportaciones: archivos, periodo: res.cuerpo });
    }
    if ((mm = /^\/periodos\/([^/]+)\/exportaciones\/([^/]+)$/.exec(ruta)) && metodo === 'GET') {
      if ((r = exigirAdmin())) return r;
      const doc = estado.docs[decodeURIComponent(mm[1])];
      const e = doc && doc.exportaciones.find((y) => y.id === decodeURIComponent(mm[2]));
      if (!e) return regla('La exportación no existe.');
      const foto = doc.fotos.find((f) => f.version === e.version);
      const base = Object.assign(m.clonar(doc), { lineas: m.clonar(foto.lineas), versionAprobada: e.version });
      const ef = Object.assign(m.clonar(maestros()), { tarifas: foto.tarifas, categorias: foto.categorias, controlling: foto.controlling });
      const meta = { id: e.id, version: e.version, aprobadoPor: foto.aprobadoPor, aprobadoEn: foto.aprobadoEn, exportadoPor: e.por, exportadoEn: e.en };
      const a = e.tipo === 'excel' ? x.excelCompleto(base, ef, meta) : e.tipo === 'controlling' ? x.excelControlling(base, ef, meta) : x.htmlPresentacion(base, ef, meta);
      return ok({ id: e.id, tipo: e.tipo, nombre: a.nombre, estado: e.estado, b64: Buffer.from(a.contenido).toString('base64') });
    }
    if (ruta === '/auditoria' && metodo === 'GET') {
      const per = q.get('periodo');
      return ok(estado.auditoria.filter((a) => !per || a.periodo === per).slice().reverse());
    }
    if (ruta === '/ensayos/estados' && metodo === 'GET') {
      const ms = maestros();
      const salida = {};
      (await ensayos(false)).salida.forEach((e) => { salida[e.id] = m.estadoDatosEnsayo(e, ms); });
      return ok(salida);
    }
    if ((mm = /^\/bancos\/([^/]+)\/ensayos\/([^/]+)$/.exec(ruta)) && metodo === 'GET') return ficha(decodeURIComponent(mm[1]), decodeURIComponent(mm[2]), null);
    if ((mm = /^\/bancos\/([^/]+)\/ensayos\/([^/]+)\/vista-previa$/.exec(ruta)) && metodo === 'POST') return ficha(decodeURIComponent(mm[1]), decodeURIComponent(mm[2]), cuerpo.billing || {}, cuerpo);
    if ((mm = /^\/bancos\/([^/]+)\/ensayos\/([^/]+)$/.exec(ruta)) && metodo === 'PUT') {
      if ((r = exigirEditor())) return r;
      const banco = decodeURIComponent(mm[1]);
      const id = decodeURIComponent(mm[2]);
      if (!cuerpo.forzar && cuerpo.version) {
        const c = await host.comprobarVersiones({ [id]: cuerpo.version });
        if (c) return c;
      }
      const { vista } = await host.vista();
      const fila = (vista.bancos[banco] || []).find((f) => f.id === id);
      if (!fila) return regla(`El ensayo ya no existe en ${banco}.`);
      const ms = maestros();
      let nuevo;
      try { nuevo = combinar(fila.datos.billing, cuerpo.billing || {}, host.rol() === 'admin', ms, fila.datos.project, true); } catch (e) { return regla(e.message); }
      const org = vista.grupos.flatMap((g) => g.bancos).find((b) => b.nombre === banco)?.organizacion || '';
      const e = m.ensayoFacturable(banco, org, Object.assign(m.clonar(fila), { datos: Object.assign(m.clonar(fila.datos), { billing: nuevo }) }), {});
      const errores = m.validarDatosEnsayo(e);
      if (errores.length) return regla(errores.join(' '));
      await host.ponerBilling(banco, id, nuevo);
      auditar('DATOS_ENSAYO', '', id, `${banco} · ${id}`, '');
      return ok({ filasGuardadas: 1, vista: (await host.vista()).vista });
    }
    return { estado: 404, cuerpo: { error: 'sin ruta', mensaje: p } };
  }

  return { atender, reiniciar: () => { estado = nuevoEstado(); }, estado: () => estado };
}

module.exports = { crear };
