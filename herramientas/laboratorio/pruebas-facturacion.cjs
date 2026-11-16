'use strict';
/*
 * PRUEBAS DE LA FACTURACIÓN
 *
 * A. Cálculo (sin navegador), con la réplica facturacion/hoja.cjs:
 *    - regresión con enero de 2026 del Excel (backend/src/test/resources/facturacion/
 *      enero-2026.json): cada «Coste total» de INPUTS_2026 y cada celda de la hoja «ENERO»;
 *    - horas del mes desde las fechas, parada proporcional, setup el primer mes, piezas,
 *      STC y proyecto 0, líneas incompletas fuera de la exportación.
 * B. Interfaz React contra el backend simulado:
 *    - Billing › Configuration: alta de un Equipment Type, copia de año, borrar año;
 *    - Test Benches › Equipment Type; columnas Setup (h), HV y Product Line de la tarea;
 *    - Billing › Monthly sheet: vista previa con incompletas, exportación Excel y HTML;
 *    - permisos (Editor no ve ni guarda la configuración).
 *
 *   node backend-simulado-4.9.4.cjs 5001 &   (con frontend/dist compilado)
 *   TZ=Europe/Madrid node pruebas-facturacion.cjs [carpeta_capturas]
 */
const fs = require('fs');
const path = require('path');
const h = require('./facturacion/hoja.cjs');

const BASE = process.env.TBP_URL || 'http://127.0.0.1:5001';
const CAPTURAS = process.argv[2] || null;
const SOLO_CALCULO = process.env.SOLO_CALCULO === '1';
const ENERO = path.resolve(__dirname, '../../backend/src/test/resources/facturacion/enero-2026.json');
const resultados = [];
const comprobar = (nombre, ok, detalle = '') => { resultados.push({ nombre, ok }); console.log(`${ok ? '✔' : '✘'} ${nombre}${ok ? '' : ' · ' + detalle}`); };
const cerca = (a, b, tol = 0.01) => typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) <= tol;

// ---- A. cálculo ------------------------------------------------------------------------------
function pruebasCalculo() {
  const doc = JSON.parse(fs.readFileSync(ENERO, 'utf8'));
  const tipos = {};
  doc.tipos.map(h.tipoEquipo).forEach((t) => { tipos[h.clave(t.titulo, t.anio)] = t; });
  let costesOk = 0;
  const lineas = doc.lineas.map((l) => {
    const t = tipos[h.clave(l.equipmentType, 2026)];
    const tarifa = l.hv === 'yes' ? t.conHv : t.sinHv;
    const c = h.coste(t.regla, l.horasTotales, tarifa);
    if (cerca(c, l.costeExcel, 0.005) && t.grupo === l.grupo && cerca(tarifa, l.tarifaExcel, 1e-9)) costesOk++;
    return { entrada: { productLine: l.productLine, requester: l.requester, hv: l.hv, inicio: l.inicio, fin: l.fin }, desde: l.inicio, hasta: l.fin, orden: l.orden, grupo: t.grupo,
      equipmentType: t.titulo, tarifa, coste: c, completa: true };
  });
  comprobar(`Enero (Excel): ${doc.lineas.length} «Coste total» iguales (tarifa, regla y grupo)`, costesOk === doc.lineas.length, `${costesOk}/${doc.lineas.length}`);
  const tabla = h.tabla(lineas);
  comprobar('Enero (Excel): mismas columnas y en el mismo orden que la hoja ENERO', JSON.stringify(tabla.columnas) === JSON.stringify(doc.columnas),
    JSON.stringify(tabla.columnas));
  const porOrden = Object.fromEntries(tabla.filas.filter((f) => f.nivel === 0).map((f) => [f.etiqueta, f.total]));
  const ordenesOk = Object.entries(doc.ordenesTablaDinamica).filter(([o, v]) => cerca(porOrden[o], v)).length;
  comprobar(`Enero: las ${Object.keys(doc.ordenesTablaDinamica).length} Billing Orders = columna de enero de la tabla dinámica del Excel`,
    ordenesOk === Object.keys(doc.ordenesTablaDinamica).length && Object.keys(porOrden).length === ordenesOk, JSON.stringify(porOrden));
  const total = tabla.filas[tabla.filas.length - 1];
  comprobar('Enero (Excel): Total general 47.210,59 €', total.nivel === 8 && h.importe(total.total) === '47.210,59 €', h.importe(total.total));
  const esperado = ['463B11364MD4', 'Heaters', 'HVCH (STC)', 'HVCH Lubu', 'No', 'jueves, 1 de enero de 2026', 'martes, 20 de enero de 2026', '19,45'];
  const camino = [];
  let hojaLubu = null;
  tabla.filas.forEach((f) => { if (f.nivel >= 8) return; camino.length = f.nivel; camino.push(f.etiqueta); if (camino.join('|') === esperado.join('|')) hojaLubu = f; });
  comprobar('Ocho niveles como la tabla dinámica: Order › Planta › Tipo › Equipo › HV › Inicio › Fin › Coste/hora', !!hojaLubu && cerca(hojaLubu.total, 4482.94 + 4858.55 + 5300.19, 0.02),
    JSON.stringify(hojaLubu));
  const soloPlanta = h.tabla(lineas, h.niveles('planta'));
  const filasEnero = doc.filas.filter((f) => h.PRODUCT_LINES.includes(f.etiqueta) || f.etiqueta === 'Total general');
  comprobar('Solo «Planta»: igual que las filas de planta de la hoja ENERO', soloPlanta.filas.length === filasEnero.length && filasEnero.every((f, k) => {
    const n = soloPlanta.filas[k];
    return n.etiqueta === f.etiqueta && cerca(n.total, f.total) && Object.entries(f.valores).every(([g, v]) => cerca(n.valores[g], v));
  }), JSON.stringify(soloPlanta.filas.map((f) => f.etiqueta)));
  comprobar('Campos elegibles: orden fijo, «-» = ninguno, desconocido rechazado', h.niveles('hv,orden').map((n) => n.id).join() === 'orden,hv'
    && h.niveles('-').length === 0 && h.niveles('').length === 8 && (() => { try { h.niveles('requester'); return false; } catch (e) { return true; } })());

  // Desde las fechas de la tarea.
  const en = (o2) => Object.assign({ banco: 'B', ensayoId: 'x', proyecto: '1 - P', tipoEnsayo: 'T', requester: 'ana', productLine: 'Vigo', hv: 'no', setup: null,
    paradaDias: 0, inicio: '2026-01-01', fin: '2026-01-01' }, o2);
  const cc = tipos[h.clave('Cámara climática', 2026)];
  const l1 = h.linea(en({ inicio: '2026-01-01', fin: '2026-01-19', proyecto: '80000380 - P' }), '2026-01', 'Cámara climática', tipos, { '80000380 - P': '463B80380MA0' });
  comprobar('Fechas 1–19 ene: 456 h × 3,72 = 1.696,32 € y Billing Order del proyecto', l1 && l1.horasNetas === 456 && cerca(l1.coste, 1696.32, 0.005)
    && l1.orden === '463B80380MA0' && l1.grupo === cc.grupo, JSON.stringify(l1));
  const sh = { [h.clave('Shaker', 2026)]: h.tipoEquipo({ titulo: 'Shaker', grupo: 'Shakers', anio: 2026, sinHv: 10, conHv: 14, regla: 'LINEAR' }) };
  comprobar('Periodo de facturación 21–20: febrero 2026 = 21/01/2026 – 20/02/2026', h.inicioPeriodo('2026-02') === '2026-01-21'
    && h.finPeriodo('2026-02') === '2026-02-20' && h.inicioPeriodo('2026-01') === '2025-12-21' && h.periodoDe('2026-01-21') === '2026-02'
    && h.periodoDe('2026-01-20') === '2026-01');
  // 16–25 ene: 5 días en el periodo de enero (16–20) y 5 en el de febrero (21–25); 2 días de parada (1 por periodo); setup 6 h.
  const dos = en({ hv: 'yes', setup: 6, paradaDias: 2, inicio: '2026-01-16', fin: '2026-01-25' });
  const e1 = h.linea(dos, '2026-01', 'Shaker', sh, {});
  const e2 = h.linea(dos, '2026-02', 'Shaker', sh, {});
  comprobar('Dos periodos: parada proporcional, setup solo el primero; Inicio/Fin = tramo en el periodo', e1.horasNetas === 96 && e1.horasSetup === 6
    && cerca(e1.coste, 102 * 14) && e1.desde === '2026-01-16' && e1.hasta === '2026-01-20'
    && e2.horasNetas === 96 && e2.horasSetup === 0 && e2.desde === '2026-01-21' && e2.hasta === '2026-01-25'
    && h.linea(dos, '2026-03', 'Shaker', sh, {}) === null, `${JSON.stringify(e1)} ${JSON.stringify(e2)}`);
  const largo = h.linea(en({ inicio: '2025-12-01', fin: '2026-03-31' }), '2026-02', 'Shaker', sh, {});
  comprobar('Tarea larga: en febrero solo 21/01 – 20/02 (31 días = 744 h)', largo.desde === '2026-01-21' && largo.hasta === '2026-02-20'
    && largo.horasNetas === 744, JSON.stringify(largo));
  const racs = { [h.clave('RACS', 2026)]: h.tipoEquipo({ titulo: 'RACS', grupo: 'RACS', anio: 2026, sinHv: 126.92, conHv: 126.92, unidad: 'PIEZAS', regla: 'PER_PIECE' }) };
  const pz = en({ setup: 5, inicio: '2026-01-10', fin: '2026-02-20' });
  comprobar('Por piezas: las piezas en Setup, solo el primer periodo', cerca(h.linea(pz, '2026-01', 'RACS', racs, {}).coste, 634.6) && h.linea(pz, '2026-02', 'RACS', racs, {}) === null);
  const stc = h.linea(en({ productLine: 'stc', hv: '' }), '2026-01', 'Shaker', sh, {});
  const cero = h.linea(en({ proyecto: '00000 - STC General Works' }), '2026-01', 'Shaker', sh, {});
  comprobar('STC → coste 0 (sin HV ni tarifa); proyecto 0 → coste 0', stc.completa && stc.coste === 0 && stc.entrada.productLine === 'STC' && cero.coste === 0);
  const tiposMix = [h.tipoEquipo({ titulo: 'Shaker', grupo: 'Shakers', anio: 2026, sinHv: 10, conHv: null }),
    h.tipoEquipo({ titulo: 'Banco viejo', grupo: 'Otros', anio: 2026, sinHv: 5, conHv: 5, regla: 'NOT_BILLABLE' })];
  const hoja = h.calcular([en({ banco: 'S1' }), en({ banco: 'S1', hv: 'yes', inicio: '2026-01-02', fin: '2026-01-02' }), en({ banco: 'S1', hv: '', inicio: '2026-01-03', fin: '2026-01-03' }),
    en({ banco: 'SIN' }), en({ banco: 'OLD' })], '2026-01', { S1: 'Shaker', OLD: 'Banco viejo' }, tiposMix, { '1 - P': '463BX' });
  const totalExp = hoja.exportable.filas[hoja.exportable.filas.length - 1];
  comprobar('Incompletas: en la vista previa (⚠) pero no en la exportación; No facturable fuera', hoja.lineas.length === 4
    && hoja.lineas.filter((l) => !l.completa).length === 3 && JSON.stringify(hoja.vistaPrevia.columnas) === JSON.stringify(['Shakers', h.SIN_TIPO])
    && hoja.vistaPrevia.filas[0].incompleta && JSON.stringify(hoja.exportable.columnas) === '["Shakers"]' && totalExp.total === 240);
  const errores = h.validarTipos([{ titulo: 'A', anio: 2026, regla: 'LINEAR' }, { titulo: 'a', anio: 2026, regla: 'LINEAR' }, { titulo: '', anio: 1990, regla: 'X', sinHv: 'abc' }]);
  comprobar('Validación: repetido, sin nombre, año, regla y tarifa no válidos', errores.length === 5, errores.join(' | '));
}

// ---- B. interfaz ---------------------------------------------------------------------------------
async function api(metodo, ruta, cuerpo) {
  const r = await fetch(BASE + ruta, { method: metodo, headers: { 'Content-Type': 'application/json' }, body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo) });
  const t = await r.text();
  return { estado: r.status, cuerpo: t ? JSON.parse(t) : null };
}
/** Texto de xl/worksheets/sheet1.xml de un .xlsx (zip con deflate). */
function leerHoja1(buf) {
  const zlib = require('zlib');
  let i = 0;
  while (i < buf.length - 30 && buf.readUInt32LE(i) === 0x04034b50) {
    const metodo = buf.readUInt16LE(i + 8); const comp = buf.readUInt32LE(i + 18);
    const ln = buf.readUInt16LE(i + 26); const le = buf.readUInt16LE(i + 28);
    const nombre = buf.slice(i + 30, i + 30 + ln).toString();
    const datos = buf.slice(i + 30 + ln + le, i + 30 + ln + le + comp);
    if (nombre === 'xl/worksheets/sheet1.xml') return (metodo === 8 ? zlib.inflateRawSync(datos) : datos).toString('utf8');
    i += 30 + ln + le + comp;
  }
  return '';
}

async function captura(pagina, nombre) {
  if (!CAPTURAS) return;
  fs.mkdirSync(CAPTURAS, { recursive: true });
  await pagina.screenshot({ path: path.join(CAPTURAS, `${nombre}.png`), fullPage: false });
}
async function esperar(condicion, ms = 10000) {
  const fin = Date.now() + ms;
  while (Date.now() < fin) {
    if (await condicion()) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return false;
}

async function pruebasInterfaz() {
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05' });
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  const contexto = await navegador.newContext({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true });
  const pagina = await contexto.newPage();
  const errores = [];
  pagina.on('pageerror', (e) => errores.push(String(e)));
  pagina.on('dialog', (d) => void d.accept());

  comprobar('Sin datos precargados: no hay Equipment Types', (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 0);

  // Menú: solo «Monthly sheet» y «Configuration».
  await pagina.goto(`${BASE}/#/__billing_config__`);
  await pagina.waitForSelector('#fac-tipos');
  const menu = await pagina.$$eval('#side .grp-title', (xs) => xs.map((x) => x.textContent.trim()));
  const billing = await pagina.evaluate(() => {
    const titulos = [...document.querySelectorAll('#side .grp-title')];
    const t = titulos.find((x) => x.textContent.trim() === 'Billing');
    const salida = [];
    for (let n = t.nextElementSibling; n && !n.classList.contains('grp-title'); n = n.nextElementSibling) salida.push(n.textContent.trim());
    return salida;
  });
  comprobar('Menú Billing: Monthly sheet y Configuration (sin Periods, Review ni Overview)', menu.includes('Billing')
    && billing.length === 2 && /Monthly sheet/.test(billing[0]) && /Configuration/.test(billing[1]), billing.join('|'));

  // Configuration: una sola tabla por año.
  const cab = await pagina.$$eval('#fac-tipos th', (ths) => ths.map((t) => t.textContent.trim()));
  comprobar('Configuration: columnas Equipment Type, Group, With HV, Without HV, Units, Billing Rule', ['Equipment Type', 'Group', 'With HV', 'Without HV', 'Units', 'Billing Rule']
    .every((c, i) => cab[i] === c), cab.join('|'));
  comprobar('Configuration: sin pestañas Rates, Controlling ni Audit', !(await pagina.$('text=Controlling')) && !(await pagina.$('text=Audit')) && !(await pagina.$('.fac-pestanas')));
  await pagina.click('#fac-nuevo-tipo');
  const filaNueva = 'table#fac-tipos tbody tr:last-child';
  await pagina.fill(`${filaNueva} [data-campo="titulo"]`, 'Cámara climática');
  await pagina.fill(`${filaNueva} [data-campo="grupo"]`, 'Climático');
  await pagina.fill(`${filaNueva} [data-campo="conHv"]`, '8.13');
  await pagina.fill(`${filaNueva} [data-campo="sinHv"]`, '3.72');
  await pagina.click('#fac-nuevo-tipo');
  await pagina.fill(`${filaNueva} [data-campo="titulo"]`, 'RACS');
  await pagina.fill(`${filaNueva} [data-campo="grupo"]`, 'RACS');
  await pagina.fill(`${filaNueva} [data-campo="sinHv"]`, '126.92');
  await pagina.selectOption(`${filaNueva} [data-campo="regla"]`, 'PER_PIECE');
  comprobar('Por pieza pone la unidad Pieces', await pagina.inputValue(`${filaNueva} [data-campo="unidad"]`) === 'PIEZAS');
  await captura(pagina, '01-configuracion');
  await pagina.click('#fac-guardar-tipos');
  await esperar(async () => (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 2);
  const guardados = (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos;
  const cc = guardados.find((t) => t.titulo === 'Cámara climática');
  comprobar('Configuration: guardado en la lista (2026, With HV 8,13, Without HV 3,72, LINEAR)', guardados.length === 2 && cc && cc.anio === 2026
    && cc.conHv === 8.13 && cc.sinHv === 3.72 && cc.regla === 'LINEAR' && cc.unidad === 'HORAS', JSON.stringify(guardados));
  // Año siguiente como copia y borrado de año.
  await pagina.click('#fac-nuevo-anio');
  comprobar('«+ Año 2027» copia los Equipment Types de 2026', await pagina.getAttribute('#fac-tipos', 'data-anio') === '2027'
    && (await pagina.$$('#fac-tipos tbody tr')).length === 2);
  await pagina.click('#fac-guardar-tipos');
  await esperar(async () => (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 4);
  comprobar('Año 2027 guardado (4 filas)', (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 4);
  await pagina.click('text=Borrar 2027');
  await pagina.click('#fac-guardar-tipos');
  await esperar(async () => (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 2);
  comprobar('Borrar año 2027', (await api('GET', '/api/v1/facturacion/tipos-equipo')).cuerpo.tipos.length === 2);
  const repetido = await api('PUT', '/api/v1/facturacion/tipos-equipo', { tipos: [...guardados, guardados[0]] });
  comprobar('Equipment Type repetido en el mismo año: rechazado (422)', repetido.estado === 422 && /repetido/.test(repetido.cuerpo.mensaje), JSON.stringify(repetido.cuerpo));

  // Test Benches: Equipment Type del banco.
  await pagina.goto(`${BASE}/#/__benches__`);
  await pagina.waitForSelector('td[data-categoria-facturacion="1500 Aire"] select');
  const cabBancos = await pagina.$$eval('table th', (ths) => ths.map((t) => t.textContent.trim()));
  comprobar('Test Benches: columna «Equipment Type»', cabBancos.includes('Equipment Type') && !cabBancos.includes('Billing equipment'));
  await esperar(async () => (await pagina.$$eval('td[data-categoria-facturacion="1500 Aire"] select option', (os) => os.length)) > 1);
  await pagina.selectOption('td[data-categoria-facturacion="1500 Aire"] select', 'Cámara climática');
  const asignado = await esperar(async () => {
    const c = (await api('GET', '/api/v1/catalogos')).cuerpo;
    return (c.benches.find((b) => b.name === '1500 Aire') || {}).equipmentType === 'Cámara climática';
  });
  comprobar('Test Benches: Equipment Type guardado en la columna EquipmentType', asignado);

  // Billing Order del proyecto de una tarea en marcha de 1500 Aire.
  const v0 = (await api('GET', '/api/v1/planificacion')).cuerpo;
  const enMarcha = v0.bancos['1500 Aire'].filter((f) => !f.cerrado && f.calculo.arranqueEfectivo && f.calculo.arranqueEfectivo <= v0.hoy);
  const tarea = enMarcha[0];
  comprobar('Hay una tarea en marcha en 1500 Aire', !!tarea);
  const cat = (await api('GET', '/api/v1/catalogos')).cuerpo;
  const proyecto = String(tarea.datos.project || '');
  const ordenes = Object.assign({}, cat.projectOrders, proyecto ? { [proyecto]: '463B99999TS0' } : {});
  const rCat = await api('PUT', '/api/v1/catalogos', { projects: cat.projects, projectOrders: ordenes, responsibles: [] });
  comprobar('Billing Order del proyecto guardada', rCat.estado === 200 && (!proyecto || (await api('GET', '/api/v1/catalogos')).cuerpo.projectOrders[proyecto] === '463B99999TS0'));

  // Columnas de la tarea.
  await pagina.goto(`${BASE}/#/${encodeURIComponent('1500 Aire')}`);
  await pagina.waitForSelector(`tr[data-id="${tarea.id}"]`);
  const cols = await pagina.$$eval('table.work thead th', (ths) => ths.map((t) => t.textContent.trim()));
  const iParada = cols.findIndex((c) => c.startsWith('Parada'));
  comprobar('Tarea: sin columna Billing; Setup (h) detrás de Parada; HV y Product Line al final', !cols.some((c) => c === 'Billing')
    && cols[iParada + 1] === 'Setup (h)' && cols[cols.length - 2] === 'HV' && cols[cols.length - 1] === 'Product Line', cols.join('|'));
  const fila = `tr[data-id="${tarea.id}"]`;
  await pagina.selectOption(`${fila} select[data-f="hv"]`, 'no');
  await pagina.selectOption(`${fila} select[data-f="productLine"]`, 'Viana');
  await pagina.fill(`${fila} input[data-f="setupHours"]`, '2');
  await pagina.press(`${fila} input[data-f="setupHours"]`, 'Tab');
  const guardada = await esperar(async () => {
    const f = (await api('GET', '/api/v1/planificacion')).cuerpo.bancos['1500 Aire'].find((x) => x.id === tarea.id);
    return f.datos.hv === 'no' && f.datos.productLine === 'Viana' && Number(f.datos.setupHours) === 2;
  }, 15000);
  comprobar('Tarea: HV, Product Line y Setup guardados en la tarea', guardada);
  await captura(pagina, '02-tarea');

  // Hoja mensual del mes de arranque de la tarea.
  const vista = (await api('GET', '/api/v1/planificacion')).cuerpo;
  const f = vista.bancos['1500 Aire'].find((x) => x.id === tarea.id);
  const entrada = h.entradaDeFila('1500 Aire', f, vista.hoy);
  const mes = h.periodoDe(entrada.inicio);
  const tiposMap = {};
  guardados.map(h.tipoEquipo).forEach((t) => { tiposMap[h.clave(t.titulo, t.anio)] = t; });
  const esperada = h.linea(entrada, mes, 'Cámara climática', tiposMap, ordenes);
  await pagina.goto(`${BASE}/#/__billing_sheet__`);
  await pagina.waitForSelector('#fac-mes-nombre');
  await pagina.selectOption('#fac-mes-anio', mes.slice(0, 4));
  await pagina.selectOption('#fac-mes-nombre', String(Number(mes.slice(5, 7))));
  await pagina.click('#fac-mes-anterior');
  await pagina.click('#fac-mes-siguiente');
  comprobar('Periodo visible junto al selector (21 del mes anterior – 20)', (await pagina.textContent('#fac-periodo')).includes(h.periodo(mes)));
  comprobar('Selector de mes: desplegables de mes y año y flechas ◀ ▶', await pagina.inputValue('#fac-mes-nombre') === String(Number(mes.slice(5, 7)))
    && await pagina.inputValue('#fac-mes-anio') === mes.slice(0, 4));
  await pagina.waitForSelector('#fac-tabla-mes');
  await esperar(async () => /Viana/.test(await pagina.textContent('#fac-tabla-mes')));
  const tablaTexto = await pagina.textContent('#fac-tabla-mes');
  const hojaApi = (await api('GET', `/api/v1/facturacion/hoja?mes=${mes}`)).cuerpo;
  const lineaApi = hojaApi.lineas.find((l) => l.ensayoId === tarea.id);
  comprobar(`Hoja ${mes}: la tarea completa con su coste (${h.importe(esperada.coste)})`, lineaApi && lineaApi.completa && cerca(lineaApi.coste, esperada.coste)
    && lineaApi.orden === (proyecto ? '463B99999TS0' : '') && lineaApi.horasSetup === 2, JSON.stringify(lineaApi));
  const cabHoja = await pagina.textContent('#fac-tabla-mes thead th');
  comprobar('Hoja: Billing Order › Planta › Tipo › Equipo › HV › Inicio › Fin › Coste/hora', cabHoja.includes('Billing Order') && cabHoja.includes('Coste/hora')
    && /Viana/.test(tablaTexto) && (!proyecto || /463B99999TS0/.test(tablaTexto)) && tablaTexto.includes('Cámara climática')
    && tablaTexto.includes(h.fechaLarga(esperada.desde)) && tablaTexto.includes(h.fechaLarga(esperada.hasta)) && tablaTexto.includes('3,72') && tablaTexto.includes(h.importe(esperada.coste)), cabHoja + ' ' + tablaTexto.slice(0, 300));
  const filasAntes = (await pagina.$$('#fac-tabla-mes tbody tr')).length;
  await pagina.click('#fac-tabla-mes tbody tr[data-nivel="0"] .fac-plegar');
  const filasPlegadas = (await pagina.$$('#fac-tabla-mes tbody tr')).length;
  await pagina.click('#fac-tabla-mes tbody tr[data-nivel="0"] .fac-plegar');
  await pagina.selectOption('#fac-plegar-nivel', '0');
  const soloOrdenes = await pagina.$$eval('#fac-tabla-mes tbody tr', (trs) => trs.every((t) => ['0', '8'].includes(t.dataset.nivel)));
  await pagina.selectOption('#fac-plegar-nivel', 'todo');
  comprobar('Plegar/desplegar filas (⊟ ⊞) y «Mostrar hasta»', filasPlegadas < filasAntes && soloOrdenes
    && (await pagina.$$('#fac-tabla-mes tbody tr')).length === filasAntes, `${filasAntes} ${filasPlegadas} ${soloOrdenes}`);
  await captura(pagina, '03a-hoja-8-niveles');
  for (const id of ['inicio', 'fin', 'tarifa']) await pagina.uncheck(`#fac-campos input[data-nivel="${id}"]`);
  await esperar(async () => (await pagina.textContent('#fac-tabla-mes thead th')) === 'Billing Order › Planta › Tipo › Equipo › HV');
  const cab5 = await pagina.textContent('#fac-tabla-mes thead th');
  const max5 = await pagina.$$eval('#fac-tabla-mes tbody tr', (trs) => Math.max(...trs.map((t) => Number(t.dataset.nivel))));
  comprobar('Campos elegidos: 5 niveles (Order › Planta › Tipo › Equipo › HV)', cab5 === 'Billing Order › Planta › Tipo › Equipo › HV' && max5 === 5, `${cab5} ${max5}`);
  comprobar('Hoja: incompletas listadas aparte y marcadas ⚠', hojaApi.incompletas > 0 && await pagina.isVisible('#fac-incompletas')
    && (await pagina.$$('#fac-tabla-mes tr.incompleta')).length > 0);
  await captura(pagina, '03-hoja');
  await pagina.check('#fac-como-exportada');
  const soloExport = await pagina.textContent('#fac-tabla-mes');
  const totalExport = hojaApi.exportable.filas[hojaApi.exportable.filas.length - 1];
  comprobar('API: nivelesDisponibles y niveles de la tabla', hojaApi.nivelesDisponibles.length === 8 && hojaApi.vistaPrevia.niveles.length === 8);
  comprobar('«Ver solo lo que se exporta»: sin filas incompletas ni (sin Equipment Type)', (await pagina.$$('#fac-tabla-mes tr.incompleta')).length === 0
    && !soloExport.includes(h.SIN_TIPO) && soloExport.includes(h.importe(totalExport.total)));
  const [descarga] = await Promise.all([pagina.waitForEvent('download'), pagina.click('#fac-exportar-excel')]);
  const ruta = await descarga.path();
  const bytes = fs.readFileSync(ruta);
  const hoja1 = leerHoja1(bytes);
  comprobar('Excel: cabecera con los campos elegidos, periodo y filas agrupadas (outlineLevel)', hoja1.includes('Billing Order › Planta › Tipo › Equipo › HV')
    && hoja1.includes(h.periodo(mes))
    && hoja1.includes('outlineLevel="4"') && !hoja1.includes('outlineLevel="5"') && hoja1.includes('summaryBelow="0"'), hoja1.slice(0, 400));
  comprobar(`Exportar Excel: ${descarga.suggestedFilename()}`, descarga.suggestedFilename() === `Facturacion_${mes}_${h.nombreArchivo(mes).split('_')[2]}.xlsx`
    && bytes[0] === 0x50 && bytes[1] === 0x4b);
  const [descargaHtml] = await Promise.all([pagina.waitForEvent('download'), pagina.click('#fac-exportar-html')]);
  const html = fs.readFileSync(await descargaHtml.path(), 'utf8');
  comprobar('Exportar HTML: solo lo completo y con el periodo', html.includes(h.periodo(mes).replace('–', '\u2013')) && html.includes('<table') && html.includes(h.importe(totalExport.total)) && !html.includes(h.SIN_TIPO));
  if (CAPTURAS) { fs.copyFileSync(ruta, path.join(CAPTURAS, descarga.suggestedFilename())); fs.writeFileSync(path.join(CAPTURAS, descargaHtml.suggestedFilename()), html); }
  comprobar('Mes no válido: 422', (await api('GET', '/api/v1/facturacion/hoja?mes=2026-13')).estado === 422);

  // Editor: ve la hoja, no la configuración ni puede guardarla.
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05', email: 'usuario07@ejemplo.local' });
  const editor = await contexto.newPage();
  await editor.goto(`${BASE}/#/__billing_sheet__`);
  await editor.waitForSelector('#fac-mes-nombre');
  const menuEditor = await editor.$$eval('#side', (xs) => xs.map((x) => x.textContent).join(' '));
  comprobar('Editor: ve Monthly sheet pero no Billing › Configuration', /Monthly sheet/.test(menuEditor) && !/128296; Configuration/.test(menuEditor)
    && (await editor.$$('#side >> text=Configuration')).length <= 1);
  const rEditor = await api('PUT', '/api/v1/facturacion/tipos-equipo', { tipos: [] });
  comprobar('Editor: guardar Equipment Types rechazado (403)', rEditor.estado === 403);
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05' });

  comprobar('Sin errores de JavaScript en la página', errores.length === 0, errores.join(' | '));
  await navegador.close();
}

(async () => {
  pruebasCalculo();
  if (!SOLO_CALCULO) await pruebasInterfaz();
  const fallos = resultados.filter((r) => !r.ok).length;
  console.log(`\n${resultados.length - fallos}/${resultados.length} comprobaciones correctas`);
  process.exit(fallos ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
