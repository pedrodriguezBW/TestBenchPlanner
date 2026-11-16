'use strict';
/*
 * Réplica JavaScript de la facturación Java para el laboratorio:
 *   - dominio/facturacion/Regla, TipoEquipo y HojaMensual (líneas de cada tarea en
 *     un mes y tabla Product Line → Billing Order → Requester × Group),
 *   - infraestructura/facturacion/HojaExcel y LibroExcel (exportación Excel y HTML).
 * Se usa en el backend simulado (api-simulada.cjs) y en pruebas-facturacion.cjs.
 */
const zlib = require('zlib');

const PRODUCT_LINES = ['Vigo', 'Viana', 'Heaters', 'eCoolers', 'Recuperador', 'STC'];
const GRUPOS_EXCEL = ['Shakers', 'HVCH (STC)', 'HVCH (CTAG)', 'Climático', 'Caudal', 'RACS'];
const PLANTA_SIN_COSTE = 'STC';
const EN_BLANCO = '(en blanco)';
const SIN_TIPO = '(sin Equipment Type)';
const TIPOS_ESPECIALES = ['Calibration', 'Maintenance', 'Breakdown', 'Not available'];

const REGLAS = [
  { codigo: 'LINEAR', nombre: 'Lineal', calculo: 'Coste = horas totales × tarifa', unidad: 'HORAS' },
  { codigo: 'ASINH_100', nombre: 'Reductora /100', calculo: 'Coste = tarifa × ASINH(horas totales / 100) × 100', unidad: 'HORAS' },
  { codigo: 'ASINH_200', nombre: 'Reductora /200', calculo: 'Coste = tarifa × ASINH(horas totales / 200) × 200', unidad: 'HORAS' },
  { codigo: 'PER_PIECE', nombre: 'Por pieza', calculo: 'Coste = piezas × tarifa (RACS)', unidad: 'PIEZAS' },
  { codigo: 'NOT_BILLABLE', nombre: 'No facturable', calculo: 'La línea se excluye: no suma horas ni importe', unidad: 'HORAS' },
];
const asinh = (x) => Math.log(x + Math.sqrt(x * x + 1));
/** Regla.coste: mismo orden de operaciones que el Excel. */
function coste(regla, cantidad, tarifa) {
  if (cantidad === 0) return 0;
  switch (regla) {
    case 'ASINH_100': return cantidad * tarifa * asinh(cantidad / 100) * 100 / cantidad;
    case 'ASINH_200': return cantidad * tarifa * asinh(cantidad / 200) * 200 / cantidad;
    case 'NOT_BILLABLE': return 0;
    default: return cantidad * tarifa;
  }
}
const reglaValida = (c) => REGLAS.some((r) => r.codigo === String(c || '').trim().toUpperCase());

// ---- TipoEquipo --------------------------------------------------------------------------
function numero(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  const t = String(v).trim().replace(',', '.');
  if (t === '' || t === '-') return null;
  const n = Number(t);
  return Number.isNaN(n) ? null : n;
}
function tipoEquipo(n) {
  const anio = numero(n.anio);
  const regla = String(n.regla || '').trim().toUpperCase();
  return {
    titulo: String(n.titulo || '').trim(), grupo: String(n.grupo || '').trim(), anio: anio === null ? 0 : Math.round(anio),
    sinHv: numero(n.sinHv), conHv: numero(n.conHv), unidad: String(n.unidad || '').trim().toUpperCase() === 'PIEZAS' ? 'PIEZAS' : 'HORAS',
    regla: reglaValida(regla) ? regla : 'LINEAR',
  };
}
const clave = (titulo, anio) => `${String(titulo || '').trim().toLowerCase()}|${anio}`;
const tarifaDe = (t, hv) => (hv === 'yes' ? t.conHv : hv === 'no' ? t.sinHv : null);

/** ServicioFacturacion.validar: lista de errores (vacía si todo es correcto). */
function validarTipos(lista) {
  if (!Array.isArray(lista)) return ['Faltan los Equipment Types.'];
  const errores = [];
  const claves = new Set();
  lista.forEach((n, i) => {
    const titulo = String(n.titulo || '').trim();
    const nombre = titulo ? `«${titulo}»` : `Fila ${i + 1}`;
    if (!titulo) errores.push(`${nombre}: falta el Equipment Type.`);
    if (!reglaValida(n.regla)) errores.push(`${nombre}: regla de cálculo no válida («${n.regla ?? ''}»).`);
    const t = tipoEquipo(n);
    if (t.anio < 2000 || t.anio > 2100) errores.push(`${nombre}: año no válido.`);
    [['sinHv', 'Without HV'], ['conHv', 'With HV']].forEach(([c, txt]) => {
      const vacio = n[c] === null || n[c] === undefined || String(n[c]).trim() === '';
      if (!vacio && (t[c] === null || t[c] < 0)) errores.push(`${nombre}: la tarifa ${txt} debe ser un número ≥ 0 o quedar vacía.`);
    });
    if (titulo && claves.has(clave(t.titulo, t.anio))) errores.push(`${nombre} está repetido en ${t.anio}.`);
    claves.add(clave(t.titulo, t.anio));
  });
  return errores;
}

// ---- fechas ------------------------------------------------------------------------------
const DIA_MS = 86400000;
const diaNum = (iso) => Math.round(Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)) / DIA_MS);
const diaIso = (n) => new Date(n * DIA_MS).toISOString().slice(0, 10);
const esDia = (t) => typeof t === 'string' && /^\d{4}-\d{2}-\d{2}/.test(t);
const mesDe = (iso) => iso.slice(0, 7);
const sumarMes = (mes) => { const a = +mes.slice(0, 4); const m = +mes.slice(5, 7); return m === 12 ? `${a + 1}-01` : `${a}-${String(m + 1).padStart(2, '0')}`; };
const r2 = (v) => Math.round(v * 100) / 100;
const restarMes = (mes) => { const a = +mes.slice(0, 4); const m = +mes.slice(5, 7); return m === 1 ? `${a - 1}-12` : `${a}-${String(m - 1).padStart(2, '0')}`; };
/** Periodo de facturación: del día 21 del mes anterior al día 20 del mes, ambos incluidos (HojaMensual.DIA_CORTE). */
const DIA_CORTE = 20;
const inicioPeriodo = (mes) => `${restarMes(mes)}-${DIA_CORTE + 1}`;
const finPeriodo = (mes) => `${mes}-${DIA_CORTE}`;
const periodoDe = (iso) => (+iso.slice(8, 10) > DIA_CORTE ? sumarMes(iso.slice(0, 7)) : iso.slice(0, 7));
const ddmmaaaa = (iso) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`;
const periodo = (mes) => `Periodo ${ddmmaaaa(inicioPeriodo(mes))} \u2013 ${ddmmaaaa(finPeriodo(mes))}`;

function productLine(texto) {
  const t = String(texto ?? '').trim();
  return PRODUCT_LINES.find((p) => p.toLowerCase() === t.toLowerCase()) || t;
}
function proyectoCero(proyecto) {
  const m = /^\s*(\d+)/.exec(String(proyecto || ''));
  return !!m && /^0+$/.test(m[1]);
}
function ordenDe(proyecto, ordenes) {
  if (!proyecto || !ordenes) return '';
  let o = ordenes[proyecto];
  if (o === undefined) {
    const k = Object.keys(ordenes).find((x) => x.trim().toLowerCase() === proyecto.trim().toLowerCase());
    o = k === undefined ? undefined : ordenes[k];
  }
  return o === undefined || o === null ? '' : String(o).trim();
}

/** Entrada normalizada (record HojaMensual.Entrada). */
function entradaNormal(e) {
  const hv = String(e.hv ?? '').trim().toLowerCase();
  return Object.assign({}, e, { proyecto: String(e.proyecto ?? '').trim(), requester: String(e.requester ?? '').trim(),
    productLine: productLine(e.productLine), hv: hv === 'yes' || hv === 'no' ? hv : '' });
}

/**
 * HojaMensual.entrada desde una fila de la vista ({datos, calculo, cerrado}).
 * Abiertas: solo si han arrancado (arranque efectivo ≤ hoy).
 */
function entradaDeFila(banco, fila, hoy) {
  const d = fila.datos || {};
  if (TIPOS_ESPECIALES.includes(d.type)) return null;
  const primero = (...c) => { for (const k of c) if (d[k]) return String(d[k]); return ''; };
  let inicio; let fin;
  const c = fila.calculo || {};
  if (fila.cerrado) {
    inicio = primero('finalTrueStart', 'finalActualStart', 'histStart', 'actualStart');
    fin = primero('finalTrueEnd', 'histEnd', 'trueEnd');
  } else {
    const efectivo = c.arranqueEfectivo || '';
    if (!efectivo || efectivo.slice(0, 10) > hoy) return null;
    inicio = d.actualStart ? String(d.actualStart) : (c.inicio || efectivo);
    fin = d.trueEnd ? String(d.trueEnd) : hoy;
  }
  if (!esDia(inicio) || !esDia(fin) || fin.slice(0, 10) < inicio.slice(0, 10)) return null;
  const setupTexto = d.setupHours === null || d.setupHours === undefined ? '' : String(d.setupHours);
  let setup = setupTexto === '' ? null : Number(setupTexto);
  if (setup !== null && (Number.isNaN(setup) || setup < 0)) setup = null;
  let parada = Number(d.downtimeDays) || 0;
  if (parada < 0) parada = 0;
  return entradaNormal({ banco, ensayoId: fila.id, proyecto: d.project, tipoEnsayo: d.type || '', requester: d.resp, productLine: d.productLine,
    hv: d.hv, setup, paradaDias: parada, inicio: inicio.slice(0, 10), fin: fin.slice(0, 10) });
}

/** HojaMensual.linea: la tarea en el mes, o null. */
function linea(en, mes, equipmentType, tipos, ordenes) {
  en = entradaNormal(en);
  const desde = diaNum(inicioPeriodo(mes));
  const hasta = diaNum(finPeriodo(mes)) + 1;
  const ini = diaNum(en.inicio);
  const finEx = diaNum(en.fin) + 1;
  const diasMes = Math.max(0, Math.min(finEx, hasta) - Math.max(ini, desde));
  if (diasMes === 0) return null;
  const diasTotales = finEx - ini;
  const paradaMes = en.paradaDias * 24 * diasMes / diasTotales;
  const netas = r2(Math.max(0, diasMes * 24 - paradaMes));
  const setup = periodoDe(en.inicio) === mes && en.setup !== null && en.setup !== undefined ? en.setup : 0;
  const et = String(equipmentType || '').trim();
  const anio = +mes.slice(0, 4);
  const tipo = et ? tipos[clave(et, anio)] : undefined;
  const faltas = []; const avisos = [];
  if (!et) faltas.push(`El banco ${en.banco} no tiene Equipment Type`);
  else if (!tipo) faltas.push(`El Equipment Type «${et}» no está configurado para ${anio}`);
  if (tipo && tipo.regla === 'NOT_BILLABLE') return null;
  const porPiezas = !!tipo && tipo.unidad === 'PIEZAS';
  const cantidad = porPiezas ? setup : r2(netas + setup);
  if (cantidad === 0) return null;
  let tarifa = null;
  if (en.productLine === PLANTA_SIN_COSTE) tarifa = 0;
  else if (!en.hv) faltas.push('Falta indicar HV en la tarea');
  else if (tipo) {
    tarifa = tarifaDe(tipo, en.hv);
    if (tarifa === null || tarifa === undefined) {
      tarifa = null;
      faltas.push(`«${tipo.titulo}» no tiene tarifa ${en.hv === 'yes' ? 'With HV' : 'Without HV'} en ${anio}`);
    }
  }
  const orden = ordenDe(en.proyecto, ordenes);
  if (!en.productLine) avisos.push('Sin Product Line');
  if (!orden) avisos.push(en.proyecto ? 'El proyecto no tiene Billing Order' : 'Sin proyecto');
  if (!en.requester) avisos.push('Sin Responsible');
  let c = null;
  if (!faltas.length && tipo && tarifa !== null) c = proyectoCero(en.proyecto) ? 0 : coste(tipo.regla, cantidad, tarifa);
  return {
    entrada: en, desde: diaIso(Math.max(ini, desde)), hasta: diaIso(Math.min(finEx, hasta) - 1), orden, equipmentType: tipo ? tipo.titulo : et, grupo: tipo && tipo.grupo ? tipo.grupo : SIN_TIPO,
    unidad: tipo ? tipo.unidad : 'HORAS', regla: tipo ? tipo.regla : '', horasNetas: netas, horasSetup: setup, cantidad, tarifa, coste: c,
    faltas, avisos, completa: faltas.length === 0,
  };
}

// ---- tabla dinámica (HojaMensual.NIVELES) ----------------------------------------------
const enBlanco = (v) => v === null || v === undefined || v === '' || v === SIN_TIPO;
function ordenTexto(conocidos) {
  const clase = (v) => (enBlanco(v) ? 2 : conocidos.includes(v) ? 0 : 1);
  const pos = (v) => { const i = conocidos.indexOf(v); return i < 0 ? conocidos.length : i; };
  return (a, b) => clase(a) - clase(b) || pos(a) - pos(b) || (a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : 0)
    || (a < b ? -1 : a > b ? 1 : 0);
}
const ordenFecha = (a, b) => (enBlanco(a) ? 1 : 0) - (enBlanco(b) ? 1 : 0) || (a < b ? -1 : a > b ? 1 : 0);
const ordenNumero = (a, b) => (enBlanco(a) ? 1 : 0) - (enBlanco(b) ? 1 : 0) || (enBlanco(a) ? 0 : Number(a) - Number(b));
const DIAS = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
const NOMBRES_MES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
/** «miércoles, 5 de agosto de 2026» */
function fechaLarga(iso) {
  if (!iso) return EN_BLANCO;
  const d = new Date(`${iso}T00:00:00Z`);
  return `${DIAS[d.getUTCDay()]}, ${d.getUTCDate()} de ${NOMBRES_MES[d.getUTCMonth()]} de ${d.getUTCFullYear()}`;
}
/** 5.78 → «5,78»; 24 → «24» */
function numeroEs(t) {
  if (t === null || t === undefined || t === '') return EN_BLANCO;
  return String(Number(Number(t).toFixed(4))).replace('.', ',');
}
const etiquetaTexto = (v) => (v ? v : EN_BLANCO);
const NIVELES = [
  { id: 'orden', nombre: 'Billing Order', valor: (l) => l.orden, orden: ordenTexto([]), etiqueta: etiquetaTexto },
  { id: 'planta', nombre: 'Planta', valor: (l) => l.entrada.productLine, orden: ordenTexto(PRODUCT_LINES), etiqueta: etiquetaTexto },
  { id: 'tipo', nombre: 'Tipo', valor: (l) => l.grupo, orden: ordenTexto(GRUPOS_EXCEL), etiqueta: etiquetaTexto },
  { id: 'equipo', nombre: 'Equipo', valor: (l) => l.equipmentType, orden: ordenTexto([]), etiqueta: etiquetaTexto },
  { id: 'hv', nombre: 'HV', valor: (l) => (l.entrada.hv === 'yes' ? 'Sí' : l.entrada.hv === 'no' ? 'No' : ''), orden: ordenTexto([]), etiqueta: etiquetaTexto },
  { id: 'inicio', nombre: 'Inicio', valor: (l) => l.desde, orden: ordenFecha, etiqueta: fechaLarga },
  { id: 'fin', nombre: 'Fin', valor: (l) => l.hasta, orden: ordenFecha, etiqueta: fechaLarga },
  { id: 'tarifa', nombre: 'Coste/hora', valor: (l) => (l.tarifa === null || l.tarifa === undefined ? '' : String(l.tarifa)), orden: ordenNumero, etiqueta: numeroEs },
];
/** HojaMensual.niveles / ServicioFacturacion.niveles: «orden,planta» → niveles en el orden fijo; vacío = todos; «-» = ninguno. */
function niveles(texto) {
  if (texto === null || texto === undefined || String(texto).trim() === '') return NIVELES;
  if (String(texto).trim() === '-') return [];
  const ids = String(texto).split(',').map((x) => x.trim()).filter(Boolean);
  const malo = ids.find((id) => !NIVELES.some((n) => n.id === id));
  if (malo) { const e = new Error(`Campo de fila desconocido: ${malo}`); e.regla = true; throw e; }
  return NIVELES.filter((n) => ids.includes(n.id));
}
function nodo() { return { valores: {}, total: 0, incompleta: false, hijos: new Map() }; }
function sumar(n, l) {
  const c = l.coste === null || l.coste === undefined ? 0 : l.coste;
  n.valores[l.grupo] = (n.valores[l.grupo] || 0) + c;
  n.total += c;
  n.incompleta = n.incompleta || !l.completa;
}
function tabla(lineas, nvs = NIVELES) {
  const raiz = nodo();
  const columnas = [];
  for (const l of lineas) {
    if (!columnas.includes(l.grupo)) columnas.push(l.grupo);
    sumar(raiz, l);
    let n = raiz;
    nvs.forEach((nv) => {
      const v = nv.valor(l) || '';
      if (!n.hijos.has(v)) n.hijos.set(v, nodo());
      n = n.hijos.get(v);
      sumar(n, l);
    });
  }
  columnas.sort(ordenTexto(GRUPOS_EXCEL));
  const fila = (nivel, et, n) => ({ nivel, etiqueta: et, valores: Object.assign({}, n.valores), total: n.total, incompleta: n.incompleta });
  const filas = [];
  const recorrer = (n, nivel) => {
    if (nivel >= nvs.length) return;
    [...n.hijos.keys()].sort(nvs[nivel].orden).forEach((v) => {
      const h = n.hijos.get(v);
      filas.push(fila(nivel, nvs[nivel].etiqueta(v), h));
      recorrer(h, nivel + 1);
    });
  };
  recorrer(raiz, 0);
  if (lineas.length) filas.push(fila(nvs.length, 'Total general', raiz));
  return { columnas, niveles: nvs.map((n) => n.nombre), filas };
}

function calcular(entradas, mes, tipoPorBanco, tipos, ordenes, nvs = NIVELES) {
  const porClave = {};
  tipos.map(tipoEquipo).forEach((t) => { porClave[clave(t.titulo, t.anio)] = t; });
  const lineas = entradas.map((en) => linea(en, mes, tipoPorBanco[en.banco] || '', porClave, ordenes)).filter(Boolean);
  lineas.sort((a, b) => (a.entrada.banco < b.entrada.banco ? -1 : a.entrada.banco > b.entrada.banco ? 1 : 0)
    || (a.desde < b.desde ? -1 : a.desde > b.desde ? 1 : 0));
  return { mes, lineas, vistaPrevia: tabla(lineas, nvs), exportable: tabla(lineas.filter((l) => l.completa), nvs) };
}

/** HojaMensual.json */
function json(h) {
  const t = (x) => ({ columnas: x.columnas, niveles: x.niveles, filas: x.filas.map((f) => ({ nivel: f.nivel, etiqueta: f.etiqueta,
    valores: Object.fromEntries(x.columnas.filter((c) => f.valores[c] !== undefined).map((c) => [c, f.valores[c]])), total: f.total, incompleta: f.incompleta })) });
  return {
    mes: h.mes, periodoInicio: inicioPeriodo(h.mes), periodoFin: finPeriodo(h.mes), vistaPrevia: t(h.vistaPrevia), exportable: t(h.exportable),
    lineas: h.lineas.map((l) => ({ banco: l.entrada.banco, ensayoId: l.entrada.ensayoId, proyecto: l.entrada.proyecto, tipoEnsayo: l.entrada.tipoEnsayo,
      requester: l.entrada.requester, productLine: l.entrada.productLine, orden: l.orden, equipmentType: l.equipmentType, grupo: l.grupo, hv: l.entrada.hv,
      inicio: l.desde, fin: l.hasta, inicioTarea: l.entrada.inicio, finTarea: l.entrada.fin, unidad: l.unidad, regla: l.regla, horasNetas: l.horasNetas, horasSetup: l.horasSetup, cantidad: l.cantidad,
      tarifa: l.tarifa, coste: l.coste, completa: l.completa, faltas: l.faltas, avisos: l.avisos })),
    incompletas: h.lineas.filter((l) => !l.completa).length,
  };
}

// ---- exportación (HojaExcel.java) ---------------------------------------------------------
const TABLA_CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(buf) { let c = 0xFFFFFFFF; for (const b of buf) c = TABLA_CRC[(c ^ b) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; }
function zip(ficheros) {
  const partes = []; const central = []; let offset = 0;
  for (const [nombre, texto] of ficheros) {
    const datos = Buffer.from(texto, 'utf8');
    const comp = zlib.deflateRawSync(datos);
    const n = Buffer.from(nombre, 'utf8');
    const crc = crc32(datos);
    const cab = Buffer.alloc(30); cab.writeUInt32LE(0x04034b50, 0); cab.writeUInt16LE(20, 4); cab.writeUInt16LE(0x0800, 6); cab.writeUInt16LE(8, 8);
    cab.writeUInt16LE(0, 10); cab.writeUInt16LE(0x21, 12); cab.writeUInt32LE(crc, 14); cab.writeUInt32LE(comp.length, 18); cab.writeUInt32LE(datos.length, 22);
    cab.writeUInt16LE(n.length, 26); cab.writeUInt16LE(0, 28);
    partes.push(cab, n, comp);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(20, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt16LE(0, 12); c.writeUInt16LE(0x21, 14); c.writeUInt32LE(crc, 16); c.writeUInt32LE(comp.length, 20); c.writeUInt32LE(datos.length, 24);
    c.writeUInt16LE(n.length, 28); c.writeUInt32LE(offset, 42);
    central.push(c, n);
    offset += 30 + n.length + comp.length;
  }
  const cd = Buffer.concat(central);
  const fin = Buffer.alloc(22); fin.writeUInt32LE(0x06054b50, 0); fin.writeUInt16LE(ficheros.length, 8); fin.writeUInt16LE(ficheros.length, 10);
  fin.writeUInt32LE(cd.length, 12); fin.writeUInt32LE(offset, 16);
  return Buffer.concat([...partes, cd, fin]);
}


// ---- libro de Excel sencillo (réplica de LibroExcel.java) ----
// Estilos: 0 normal · 1 cabecera · 2 importe € · 3 importe € en negrita · 7 negrita · 8 mes · 10/11 sangría
const ESTILOS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="3"><numFmt numFmtId="164" formatCode="#,##0.00\\ &quot;€&quot;"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/><numFmt numFmtId="166" formatCode="mmm\\-yy"/></numFmts><fonts count="4"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font><font><b/><sz val="14"/><color rgb="FF0C1D2C"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0C1D2C"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9D9D9"/></left><right style="thin"><color rgb="FFD9D9D9"/></right><top style="thin"><color rgb="FFD9D9D9"/></top><bottom style="thin"><color rgb="FFD9D9D9"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="17"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment wrapText="1" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="3" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="2" fontId="3" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="2"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="3"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="4"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="5"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="6"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment indent="7"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;

const esc = (t) => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
function columna(c) { let s = ''; let n = c + 1; while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26); } return s; }

class Hoja {
  constructor(nombre) { this.nombre = nombre; this.filas = []; this.esquema = []; this.anchos = []; this.fijarFila = 0; }
  /** celdas: [valor, estilo] o valor; null = vacía. esquema: nivel de agrupación de la fila (0 a 7). */
  fila(celdas, esquema = 0) { this.filas.push(celdas); this.esquema.push(Math.max(0, Math.min(7, esquema))); return this; }
  vacia() { return this.fila([]); }
  xml() {
    const filas = this.filas.map((celdas, i) => {
      const cs = celdas.map((c, j) => {
        if (c === null || c === undefined) return '';
        const [v, s] = Array.isArray(c) ? c : [c, 0];
        if (v === null || v === undefined || v === '') return s ? `<c r="${columna(j)}${i + 1}" s="${s}"/>` : '';
        const r = `${columna(j)}${i + 1}`;
        if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${r}" s="${s}"><v>${numeroXml(v)}</v></c>`;
        return `<c r="${r}" s="${s}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`;
      }).join('');
      return `<row r="${i + 1}"${this.esquema[i] > 0 ? ` outlineLevel="${this.esquema[i]}"` : ''}>${cs}</row>`;
    }).join('');
    const cols = this.anchos.length ? `<cols>${this.anchos.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('')}</cols>` : '';
    const panel = this.fijarFila ? `<sheetViews><sheetView workbookViewId="0"><pane ySplit="${this.fijarFila}" topLeftCell="A${this.fijarFila + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>` : '<sheetViews><sheetView workbookViewId="0"/></sheetViews>';
    const maxEsquema = Math.max(0, ...this.esquema);
    const pr = maxEsquema > 0 ? '<sheetPr><outlinePr summaryBelow="0"/></sheetPr>' : '';
    const formato = `<sheetFormatPr defaultRowHeight="13.2"${maxEsquema > 0 ? ` outlineLevelRow="${maxEsquema}"` : ''}/>`;
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">${pr}${panel}${formato}${cols}<sheetData>${filas}</sheetData></worksheet>`;
  }
}
/** Número con el texto más corto que lo representa (como Double.toString sin «E» ni «.0»). */
function numeroXml(v) { return String(v); }

function libro(hojas) {
  const ficheros = [
    ['[Content_Types].xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${hojas.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`],
    ['_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
    ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets>${hojas.map((h, i) => `<sheet name="${esc(h.nombre)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`],
    ['xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${hojas.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId${hojas.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
    ['xl/styles.xml', ESTILOS],
    ...hojas.map((h, i) => [`xl/worksheets/sheet${i + 1}.xml`, h.xml()]),
  ];
  return zip(ficheros);
}


const cabecera = (t) => (t.niveles.length ? t.niveles.join(' \u203a ') : 'Etiquetas de fila');
const MESES = ['ENERO', 'FEBRERO', 'MARZO', 'ABRIL', 'MAYO', 'JUNIO', 'JULIO', 'AGOSTO', 'SEPTIEMBRE', 'OCTUBRE', 'NOVIEMBRE', 'DICIEMBRE'];
const nombreMes = (mes) => MESES[+mes.slice(5, 7) - 1];
const nombreArchivo = (mes) => `Facturacion_${mes}_${nombreMes(mes)}`;

function excel(mes, t) {
  const h = new Hoja(`${nombreMes(mes)} ${mes.slice(0, 4)}`);
  h.anchos = [46, ...t.columnas.map(() => 15), 15];
  h.fijarFila = 2;
  h.fila([[`${nombreMes(mes)} ${mes.slice(0, 4)}`, 7], periodo(mes)]);
  h.fila([[cabecera(t), 1], ...t.columnas.map((c) => [c, 1]), ['Total general', 1]]);
  t.filas.forEach((f) => {
    const total = f.nivel >= t.niveles.length;
    const negrita = f.nivel === 0 || total;
    const est = negrita ? 7 : 10 + Math.min(f.nivel, 7) - 1;
    const imp = negrita ? 3 : 2;
    h.fila([[f.etiqueta, est], ...t.columnas.map((c) => [f.valores[c] === undefined ? null : f.valores[c], imp]), [f.total, imp]], total ? 0 : f.nivel);
  });
  return libro([h]);
}

function importe(v) {
  if (v === null || v === undefined) return '';
  const [e, d] = Math.abs(v).toFixed(2).split('.');
  return `${v < 0 ? '-' : ''}${e.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${d} €`;
}

function html(mes, t) {
  const titulo = `Facturación ${nombreMes(mes)} ${mes.slice(0, 4)} · ${periodo(mes)}`;
  let s = `<!DOCTYPE html>\n<html lang="es"><head><meta charset="utf-8"><title>${esc(titulo)}</title><style>body{font-family:Arial,sans-serif;font-size:13px;margin:24px;color:#111}`
    + 'h1{font-size:18px}table{border-collapse:collapse}th,td{border:1px solid #d9d9d9;padding:3px 8px}'
    + 'th{background:#0c1d2c;color:#fff;text-align:left}td.n{text-align:right;white-space:nowrap}'
    + 'tr.n0 td{font-weight:bold;background:#dde6f0}tr.total td{font-weight:bold;border-top:2px solid #0c1d2c}</style></head><body>\n';
  s += `<h1>${esc(titulo)}</h1>\n<table>\n<thead><tr><th>${esc(cabecera(t))}</th>${t.columnas.map((c) => `<th>${esc(c)}</th>`).join('')}<th>Total general</th></tr></thead>\n<tbody>\n`;
  t.filas.forEach((f) => {
    const total = f.nivel >= t.niveles.length;
    s += `<tr class="${total ? 'total' : `n${f.nivel}`}"><td style="padding-left:${8 + (total ? 0 : f.nivel) * 16}px">${esc(f.etiqueta)}</td>${t.columnas.map((c) => `<td class="n">${importe(f.valores[c])}</td>`).join('')}<td class="n">${importe(f.total)}</td></tr>\n`;
  });
  if (!t.filas.length) s += `<tr><td colspan="${t.columnas.length + 2}">Sin líneas completas este mes.</td></tr>\n`;
  return `${s}</tbody></table>\n</body></html>\n`;
}

module.exports = {
  PRODUCT_LINES, GRUPOS_EXCEL, EN_BLANCO, SIN_TIPO, REGLAS, coste, tipoEquipo, clave, validarTipos, productLine, proyectoCero,
  NIVELES, niveles, fechaLarga, inicioPeriodo, finPeriodo, periodoDe, periodo, numeroEs, entradaDeFila, linea, tabla, calcular, json, excel, html, nombreArchivo, importe, libro, Hoja,
};
