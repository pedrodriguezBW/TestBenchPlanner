'use strict';
/*
 * Exportaciones de un periodo aprobado (réplica de
 * infraestructura/facturacion/ExportacionesFacturacion.java):
 *   - Excel completo del periodo (8 hojas, sin las pestañas internas de configuración),
 *   - Excel reducido con la hoja «Controlling - Internal Testing»,
 *   - HTML con formato de presentación.
 * El .xlsx se escribe en Office Open XML directamente (sin librerías).
 */
const zlib = require('zlib');
const m = require('./motor.cjs');

// ---- ZIP mínimo (deflate) ----------------------------------------------------------
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

// ---- libro de Excel sencillo ----------------------------------------------------------
// Estilos: 0 normal · 1 cabecera · 2 importe · 3 importe en negrita · 4 fecha · 5 horas · 6 título · 7 negrita · 8 mes (mmm-yy) · 9 horas en negrita
const ESTILOS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="3"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/><numFmt numFmtId="166" formatCode="mmm\\-yy"/></numFmts><fonts count="4"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Arial"/></font><font><b/><sz val="14"/><color rgb="FF0C1D2C"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0C1D2C"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9D9D9"/></left><right style="thin"><color rgb="FFD9D9D9"/></right><top style="thin"><color rgb="FFD9D9D9"/></top><bottom style="thin"><color rgb="FFD9D9D9"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="10"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"><alignment wrapText="1" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="3" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="2" fontId="3" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;

const esc = (t) => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
function columna(c) { let s = ''; let n = c + 1; while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26); } return s; }
/** Fecha «aaaa-mm-dd» → número de serie de Excel. */
const serie = (dia) => (m.aMin(dia) / 1440) + 25569;

class Hoja {
  constructor(nombre) { this.nombre = nombre; this.filas = []; this.anchos = []; this.fijarFila = 0; }
  /** celdas: [valor, estilo] o valor; null = vacía. */
  fila(celdas) { this.filas.push(celdas); return this; }
  vacia() { this.filas.push([]); return this; }
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
      return `<row r="${i + 1}">${cs}</row>`;
    }).join('');
    const cols = this.anchos.length ? `<cols>${this.anchos.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('')}</cols>` : '';
    const panel = this.fijarFila ? `<sheetViews><sheetView workbookViewId="0"><pane ySplit="${this.fijarFila}" topLeftCell="A${this.fijarFila + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>` : '<sheetViews><sheetView workbookViewId="0"/></sheetViews>';
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">${panel}<sheetFormatPr defaultRowHeight="13.2"/>${cols}<sheetData>${filas}</sheetData></worksheet>`;
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

// ---- contenido ---------------------------------------------------------------------------
const H = (t) => [t, 1];
const EUR = (v) => [v === null || v === undefined ? '' : v, 2];
const HRS = (v) => [v === null || v === undefined ? '' : v, 5];
const dia = (t) => (t ? [serie(String(t).slice(0, 10)), 4] : '');
const ESTADOS = { BORRADOR: 'Borrador', EN_REVISION: 'En revisión', APROBADO: 'Aprobado', EXPORTADO: 'Exportado' };
const ESTADO_LINEA = { READY: 'Lista', WARNING: 'Advertencia', BLOCKED: 'Bloqueada', EXCLUDED: 'Excluida', PENDING: 'Pendiente', ADJUSTED: 'Ajustada', DEFERRED: 'Aplazada' };
const hvTexto = (hv) => (hv === 'yes' ? 'Sí' : hv === 'no' ? 'No' : '');
const fechaHora = (t) => (t ? `${t.slice(8, 10)}/${t.slice(5, 7)}/${t.slice(0, 4)}${t.length > 10 ? ' ' + t.slice(11, 16) : ''}` : '');

function hojaControlling(doc, r) {
  const h = new Hoja('Controlling - Internal Testing');
  h.anchos = [26, 16, 18, 16, 3, 3, 12, 12, 10, 13, 12, 7, 11, 16, 10, 11, 6, 10, 26];
  h.fila([['Mes facturación', 7], [serie(`${doc.id}-01`), 8]]);
  h.fila([['Ensayos por horas', 7]]);
  h.fila(['Equipo', 'Orden', 'Suma de Coste total', 'Sum of Horas totales', null, null, 'CCtr (Old)', 'Order (old)', 'Oper. (old)', 'Cost Element', 'Amount', 'Crcy', 'CCtr (new)', 'Order (new)', 'Oper. (new)', 'Total Qty', 'UM', 'Pers.No.', 'Text'].map((t) => (t === null ? null : H(t))));
  r.controlling.forEach((f) => h.fila([f.equipo, f.orden, [f.sumaCoste, 0], [f.sumaHoras, 0], null, null, f.cctrOld, f.orderOld, f.operOld, f.costElement,
    [f.amount, 2], f.crcy, f.cctrNew, f.orderNew, f.operNew, [f.totalQty, 0], f.um, f.persNo, f.text]));
  h.fijarFila = 3;
  return h;
}

function hojasCompletas(doc, r, meta) {
  const ind = r.indicadores;
  const hojas = [];
  const res = new Hoja('Resumen');
  res.anchos = [34, 28];
  res.fila([[`Facturación de ${doc.nombre}`, 6]]);
  res.fila(['Periodo', `${fechaHora(doc.inicio)} - ${fechaHora(doc.fin)}`]);
  res.fila(['Estado', ESTADOS[doc.estado] || doc.estado]);
  res.fila(['Versión aprobada', meta.version]);
  res.fila(['Aprobado por', meta.aprobadoPor]);
  res.fila(['Aprobado el', fechaHora(meta.aprobadoEn)]);
  res.fila(['Exportado por', meta.exportadoPor]);
  res.fila(['Exportado el', fechaHora(meta.exportadoEn)]);
  res.fila(['Identificador de la exportación', meta.id]);
  res.vacia();
  res.fila([H('Indicador'), H('Valor')]);
  [['Coste total (€)', EUR(ind.coste)], ['Horas netas', HRS(ind.horasNetas)], ['Horas de setup', HRS(ind.horasSetup)], ['Horas totales', HRS(ind.horasTotales)],
    ['Ensayos', ind.ensayos], ['Líneas', ind.lineas], ['Líneas que suman', ind.lineasContadas], ['Líneas preparadas', ind.preparadas],
    ['Líneas bloqueadas', ind.bloqueadas], ['Advertencias', ind.advertencias], ['Líneas excluidas', ind.excluidas], ['Líneas aplazadas', ind.aplazadas],
    ['Líneas pendientes (sin arrancar)', ind.pendientes], ['Líneas con ajustes', ind.ajustadas]].forEach((f) => res.fila(f));
  hojas.push(res);

  const det = new Hoja('Detalle');
  det.anchos = [30, 12, 14, 12, 16, 14, 14, 22, 6, 17, 17, 12, 10, 12, 10, 12, 11, 16, 8, 12, 11, 11, 30];
  det.fila(['Proyecto', 'Test / Task', 'Requester', 'Número proyecto', 'Orden', 'Planta / línea', 'Tipo', 'Equipo', 'HV', 'Inicio', 'Fin',
    'Horas netas ensayo', 'Horas set-up', 'Horas totales', 'Coste/hora', 'Coste total', 'Mes facturación', 'Banco', 'Planta', 'Tipo de ensayo',
    'Regla', 'Periodo ejecución', 'Ajustes'].map(H));
  doc.lineas.filter(m.cuenta).forEach((l) => {
    const a = l.aplicado;
    det.fila([a.proyectoNombre || l.proyectoTexto, l.ensayoId, a.requester, a.proyecto, a.orden, a.lineaProducto, a.grupo, a.categoria, hvTexto(a.hv),
      fechaHora(l.tramoInicio), fechaHora(l.tramoFin), HRS(a.horasNetas), HRS(a.horasSetup), HRS(a.horasTotales), EUR(a.tarifa), [a.coste, 2],
      [serie(`${doc.id}-01`), 8], l.banco, l.organizacion, l.tipoEnsayo, a.regla, l.periodoEjecucion,
      Object.keys(l.ajustes || {}).join(', ')]);
  });
  det.fila([['Total', 7], null, null, null, null, null, null, null, null, null, null, [ind.horasNetas, 9], [ind.horasSetup, 9], [ind.horasTotales, 9], null, [ind.coste, 3]]);
  det.fijarFila = 1;
  hojas.push(det);

  const ppo = new Hoja('Proyecto y orden');
  ppo.anchos = [12, 32, 16, 9, 11, 9, 13];
  ppo.fila(['Proyecto', 'Nombre', 'Orden', 'Ensayos', 'Horas', 'Setup', 'Coste'].map(H));
  r.porProyectoOrden.forEach((g) => ppo.fila([g.proyecto, g.proyectoNombre, g.orden, g.ensayos, HRS(g.horas), HRS(g.setup), EUR(g.coste)]));
  ppo.fijarFila = 1;
  hojas.push(ppo);

  const eq = new Hoja('Equipos');
  eq.anchos = [24, 14, 28, 11, 9, 12, 22, 13];
  eq.fila(['Equipo', 'Tipo', 'Bancos', 'Horas', 'Setup', 'Horas totales', 'Tarifa o regla', 'Coste'].map(H));
  r.porEquipo.forEach((g) => eq.fila([g.categoria, g.grupo, g.bancos.join(', '), HRS(g.horas), HRS(g.setup), HRS(g.horasTotales),
    `${g.tarifas.map((t) => t.toFixed(2).replace('.', ',')).join(' / ')} €/h${g.noLineal ? ` · ${g.regla} (no lineal)` : ''}`, EUR(g.coste)]));
  eq.fijarFila = 1;
  hojas.push(eq);

  const te = new Hoja('Tipo de ensayo');
  te.anchos = [28, 9, 11, 9, 13];
  te.fila(['Tipo de ensayo', 'Ensayos', 'Horas', 'Setup', 'Coste'].map(H));
  r.desgloses.tipoEnsayo.forEach((g) => te.fila([g.etiqueta, g.ensayos, HRS(g.horas), HRS(g.setup), EUR(g.coste)]));
  te.fijarFila = 1;
  hojas.push(te);

  const pl = new Hoja('Planta y línea');
  pl.anchos = [30, 12, 12, 12, 12, 12, 12, 12];
  pl.fila(['Planta', 'Ensayos', 'Horas', 'Setup', 'Coste'].map(H));
  r.porPlanta.forEach((g) => pl.fila([g.etiqueta, g.ensayos, HRS(g.horas), HRS(g.setup), EUR(g.coste)]));
  pl.vacia();
  pl.fila([H('Planta (si EGM) / product line'), ...r.porLineaProducto.columnas.map((c) => H(c || '(sin tipo)')), H('Total general')]);
  r.porLineaProducto.filas.forEach((f) => pl.fila([[`${'   '.repeat(Math.max(0, f.nivel))}${f.etiqueta}`, f.nivel <= 0 ? 7 : 0],
    ...r.porLineaProducto.columnas.map((c) => (c in f.valores ? [f.valores[c], f.nivel <= 0 ? 3 : 2] : null)), [f.total, f.nivel <= 0 ? 3 : 2]]));
  hojas.push(pl);

  hojas.push(hojaControlling(doc, r));

  const inc = new Hoja('Incidencias y ajustes');
  inc.anchos = [24, 12, 14, 13, 14, 22, 60, 16, 16, 18, 30, 10];
  inc.fila([['Incidencias', 7]]);
  inc.fila(['Línea', 'Ensayo', 'Banco', 'Estado', 'Nivel', 'Código', 'Mensaje'].map(H));
  r.incidencias.forEach((i) => inc.fila([i.lineaId, i.ensayoId, i.banco, ESTADO_LINEA[i.estado] || i.estado, i.nivel, i.codigo, i.mensaje]));
  inc.vacia();
  inc.fila([['Exclusiones y aplazamientos', 7]]);
  inc.fila(['Línea', 'Ensayo', 'Banco', 'Estado', 'Usuario', 'Fecha', 'Detalle'].map(H));
  doc.lineas.filter((l) => l.exclusion || l.aplazamiento).forEach((l) => {
    const x = l.exclusion || l.aplazamiento;
    inc.fila([l.id, l.ensayoId, l.banco, ESTADO_LINEA[l.estado], x.por, fechaHora(x.en),
      l.exclusion ? `Excluida (${l.exclusion.origen === 'ENSAYO' ? 'ensayo no facturable' : l.exclusion.origen === 'REGLA' ? 'categoría no facturable' : 'en la revisión'})` : `Aplazada a ${m.periodoDeId(l.aplazamiento.a).nombre}`]);
  });
  inc.vacia();
  inc.fila([['Ajustes', 7]]);
  inc.fila(['Línea', 'Ensayo', 'Campo', 'Valor calculado', 'Valor aplicado', 'Usuario', 'Fecha', 'Rol', 'Validado', 'Justificación'].map(H));
  doc.lineas.forEach((l) => Object.entries(l.ajustes || {}).forEach(([c, a]) => inc.fila([l.id, l.ensayoId, c, a.original === null ? '' : a.original,
    a.valor === null ? '' : a.valor, a.por, fechaHora(a.en), a.rol, a.economico ? (a.validado ? 'Sí' : 'No') : '', a.justificacion])));
  inc.vacia();
  inc.fila([['Tarifas excepcionales', 7]]);
  inc.fila(['Línea', 'Ensayo', 'Tarifa calculada', 'Tarifa propuesta', 'Diferencia (€)', 'Propuesta por', 'Estado', 'Resuelta por', 'Tarifa final', 'Alcance', 'Justificación'].map(H));
  doc.lineas.filter((l) => l.propuestaTarifa).forEach((l) => { const p = l.propuestaTarifa; inc.fila([l.id, l.ensayoId, EUR(p.calculada), EUR(p.propuesta), EUR(p.diferencia), p.por, p.estado, p.resueltaPor, EUR(p.tarifaFinal), p.alcance, p.justificacion]); });
  if ((doc.reaperturas || []).length) {
    inc.vacia();
    inc.fila([['Reaperturas', 7]]);
    inc.fila(['Versión reabierta', 'Usuario', 'Fecha', 'Justificación'].map(H));
    doc.reaperturas.forEach((x) => inc.fila([x.version, x.por, fechaHora(x.en), x.justificacion]));
  }
  hojas.push(inc);
  return hojas;
}

const nombreFichero = (doc, tipo, version, ext) => `Facturacion_${doc.id}_v${version}_${tipo}.${ext}`;

/** Excel completo del periodo. */
function excelCompleto(doc, maestros, meta) {
  const r = m.resumen(doc, maestros);
  return { nombre: nombreFichero(doc, 'completo', meta.version, 'xlsx'), contenido: libro(hojasCompletas(doc, r, meta)) };
}
/** Excel reducido: solo «Controlling - Internal Testing». */
function excelControlling(doc, maestros, meta) {
  const r = m.resumen(doc, maestros);
  return { nombre: nombreFichero(doc, 'controlling', meta.version, 'xlsx'), contenido: libro([hojaControlling(doc, r)]) };
}

// ---- HTML con formato de presentación -------------------------------------------------------
/** 1234.5 → «1.234,50» (siempre con separador de miles, igual en Java). */
function numeroEs(v) {
  const negativo = v < 0;
  const [ent, dec] = Math.abs(m.redondear2(Number(v))).toFixed(2).split('.');
  return `${negativo ? '-' : ''}${ent.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${dec}`;
}
const eur = (v) => (v === null || v === undefined ? '—' : `${numeroEs(v)} €`);
const hrs = (v) => `${numeroEs(v || 0)} h`;

function htmlPresentacion(doc, maestros, meta) {
  const r = m.resumen(doc, maestros);
  const ind = r.indicadores;
  const max = (xs) => Math.max(1, ...xs.map((x) => x.coste));
  const barras = (xs, etiqueta) => {
    const mx = max(xs);
    return `<div class="barras">${xs.map((x) => `<div class="barra"><span class="et">${esc(etiqueta(x))}</span><span class="pista"><span class="relleno" style="width:${(100 * x.coste / mx).toFixed(1)}%"></span></span><span class="va">${eur(x.coste)}</span></div>`).join('')}</div>`;
  };
  const tabla = (cab, filas) => `<table><thead><tr>${cab.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead><tbody>${filas.map((f) => `<tr>${f.map((c) => `<td${typeof c === 'object' && c && c.n ? ' class="n"' : ''}>${esc(typeof c === 'object' && c ? c.t : c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
  const N = (t) => ({ t, n: true });
  const kpi = (t, v) => `<div class="kpi"><div class="v">${esc(v)}</div><div class="t">${esc(t)}</div></div>`;
  const diapositivas = [];
  diapositivas.push(['Portada', `<div class="portada"><div class="marca">TestBenchPlanner · Billing</div><h1>Facturación de ${esc(doc.nombre)}</h1><p class="sub">Periodo ${esc(fechaHora(doc.inicio))} – ${esc(fechaHora(doc.fin))}</p><p class="total">${eur(ind.coste)}</p><p class="meta">Versión aprobada ${meta.version} · aprobada por ${esc(meta.aprobadoPor)} el ${esc(fechaHora(meta.aprobadoEn))}</p></div>`]);
  diapositivas.push(['Resumen general', `<div class="kpis">${kpi('Coste total', eur(ind.coste))}${kpi('Horas netas', hrs(ind.horasNetas))}${kpi('Horas de setup', hrs(ind.horasSetup))}${kpi('Ensayos', String(ind.ensayos))}${kpi('Líneas que suman', String(ind.lineasContadas))}${kpi('Excluidas', String(ind.excluidas))}${kpi('Aplazadas', String(ind.aplazadas))}${kpi('Con ajustes', String(ind.ajustadas))}</div>`]);
  diapositivas.push(['Por planta', tabla(['Planta', 'Ensayos', 'Horas', 'Setup', 'Coste'], r.porPlanta.map((g) => [g.etiqueta, N(g.ensayos), N(hrs(g.horas)), N(hrs(g.setup)), N(eur(g.coste))])) + barras(r.porPlanta, (g) => g.etiqueta)]);
  diapositivas.push(['Por equipo', tabla(['Equipo', 'Tipo', 'Horas', 'Setup', 'Tarifa o regla', 'Coste'], r.porEquipo.map((g) => [g.categoria, g.grupo, N(hrs(g.horas)), N(hrs(g.setup)),
    `${g.tarifas.map((t) => t.toFixed(2).replace('.', ',')).join(' / ')} €/h${g.noLineal ? ' · no lineal (' + g.regla + ')' : ''}`, N(eur(g.coste))])) + barras(r.porEquipo, (g) => g.categoria)]);
  diapositivas.push(['Por proyecto y orden', tabla(['Proyecto', 'Nombre', 'Orden', 'Ensayos', 'Horas', 'Setup', 'Coste'], r.porProyectoOrden.map((g) => [g.proyecto, g.proyectoNombre, g.orden, N(g.ensayos), N(hrs(g.horas)), N(hrs(g.setup)), N(eur(g.coste))]))]);
  diapositivas.push(['Por línea de producto', tabla(['Línea / orden / requester', ...r.porLineaProducto.columnas.map((c) => c || '(sin tipo)'), 'Total'],
    r.porLineaProducto.filas.map((f) => [`${'\u2003'.repeat(Math.max(0, f.nivel))}${f.etiqueta}`, ...r.porLineaProducto.columnas.map((c) => N(c in f.valores ? eur(f.valores[c]) : '')), N(eur(f.total))]))]);
  const ajustes = [];
  doc.lineas.forEach((l) => Object.entries(l.ajustes || {}).forEach(([c, a]) => ajustes.push([l.id, c, String(a.original ?? ''), String(a.valor ?? ''), a.por, a.justificacion])));
  diapositivas.push(['Incidencias y ajustes', (r.incidencias.length ? tabla(['Línea', 'Nivel', 'Incidencia'], r.incidencias.map((i) => [i.lineaId, i.nivel, i.mensaje])) : '<p class="vacio">Sin incidencias.</p>')
    + (ajustes.length ? `<h3>Ajustes</h3>${tabla(['Línea', 'Campo', 'Calculado', 'Aplicado', 'Usuario', 'Justificación'], ajustes)}` : '')]);
  diapositivas.push(['Controlling - Internal Testing', tabla(['Equipo', 'Orden', 'CCtr (Old)', 'Cost Element', 'Amount', 'Crcy', 'Order (new)', 'Total Qty', 'UM', 'Text'],
    r.controlling.map((f) => [f.equipo, f.orden, f.cctrOld, f.costElement, N(eur(f.amount)), f.crcy, f.orderNew, N(String(f.totalQty)), f.um, f.text]))
    + `<p class="total-ctrl">Total Controlling: ${eur(r.controlling.reduce((s, f) => s + f.amount, 0))}</p>`]);
  const secciones = diapositivas.map(([t, c], i) => `<section class="diapo${i === 0 ? ' activa' : ''}" data-i="${i}"><header><h2>${esc(t)}</h2><span>${i + 1} / ${diapositivas.length}</span></header><div class="contenido">${c}</div></section>`).join('\n');
  const html = `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><title>Facturación ${esc(doc.nombre)} · v${meta.version}</title>
<style>
*{box-sizing:border-box}body{margin:0;font-family:Segoe UI,Arial,sans-serif;background:#0c1d2c;color:#e8eef5}
.diapo{display:none;min-height:100vh;padding:28px 48px 80px}.diapo.activa{display:block}
header{display:flex;justify-content:space-between;align-items:baseline;border-bottom:2px solid #23bca3;margin-bottom:18px}h2{margin:0 0 8px;font-size:26px}header span{color:#9fb3c8}
.portada{text-align:center;padding-top:12vh}.portada h1{font-size:44px;margin:12px 0}.marca{color:#23bca3;font-weight:700;letter-spacing:.08em;text-transform:uppercase}.sub{font-size:20px;color:#9fb3c8}.total{font-size:56px;font-weight:700;color:#ffc800;margin:24px 0}.meta{color:#9fb3c8}
.kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.kpi{background:#13293d;border-radius:10px;padding:18px}.kpi .v{font-size:28px;font-weight:700;color:#ffc800}.kpi .t{color:#9fb3c8;margin-top:6px}
table{width:100%;border-collapse:collapse;font-size:13px;background:#13293d;border-radius:8px;overflow:hidden;margin-bottom:16px}th{background:#1d3a55;text-align:left;padding:7px 9px}td{padding:6px 9px;border-top:1px solid #1d3a55}td.n{text-align:right;white-space:nowrap}
.barras{margin-top:10px}.barra{display:grid;grid-template-columns:220px 1fr 130px;gap:10px;align-items:center;margin:6px 0}.pista{background:#13293d;border-radius:6px;height:16px;overflow:hidden}.relleno{display:block;height:100%;background:linear-gradient(90deg,#23bca3,#4c8dff)}.va{text-align:right}
.vacio{color:#9fb3c8}.total-ctrl{text-align:right;font-weight:700;font-size:18px;color:#ffc800}
nav{position:fixed;bottom:0;left:0;right:0;display:flex;gap:10px;justify-content:center;align-items:center;padding:12px;background:rgba(12,29,44,.95);border-top:1px solid #1d3a55}
nav button{background:#23bca3;color:#0c1d2c;border:0;border-radius:6px;padding:8px 16px;font-weight:700;cursor:pointer}nav button:disabled{opacity:.4;cursor:default}nav .pie{color:#9fb3c8;font-size:12px}
@media print{nav{display:none}.diapo{display:block;page-break-after:always;min-height:auto}}
</style></head><body>
${secciones}
<nav><button id="ant" type="button">&#9664; Anterior</button><button id="sig" type="button">Siguiente &#9654;</button><span class="pie">TestBenchPlanner · ${esc(doc.nombre)} · versión aprobada ${meta.version} · exportación ${esc(meta.id)} · ${esc(fechaHora(meta.exportadoEn))} · solo lectura</span></nav>
<script>
(function(){var d=document.querySelectorAll('.diapo'),i=0,a=document.getElementById('ant'),s=document.getElementById('sig');
function ir(n){i=Math.max(0,Math.min(d.length-1,n));for(var k=0;k<d.length;k++)d[k].className='diapo'+(k===i?' activa':'');a.disabled=i===0;s.disabled=i===d.length-1;}
a.onclick=function(){ir(i-1)};s.onclick=function(){ir(i+1)};document.addEventListener('keydown',function(e){if(e.key==='ArrowRight'||e.key==='PageDown')ir(i+1);if(e.key==='ArrowLeft'||e.key==='PageUp')ir(i-1);});ir(0);})();
</script></body></html>`;
  return { nombre: nombreFichero(doc, 'presentacion', meta.version, 'html'), contenido: Buffer.from(html, 'utf8') };
}

module.exports = { excelCompleto, excelControlling, htmlPresentacion, libro, Hoja, zip, crc32 };
