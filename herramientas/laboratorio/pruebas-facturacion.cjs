'use strict';
/*
 * PRUEBAS DE LA FACTURACIÓN EN LA INTERFAZ (React contra el backend simulado,
 * que usa el motor JavaScript del laboratorio):
 *   1. Billing Data desde la lista de cerrados: horas, HV, setup; vista previa y guardado.
 *   2. Ventana de cierre con la sección Billing Data plegable (el cierre no depende de ella).
 *   3. Periods: crear noviembre de 2026; Review con la línea calculada (56 h + 2 h × 3,72 €).
 *   4. Ajuste sin justificación (rechazado) y con justificación; exclusión del resto.
 *   5. Enviar a revisión, aprobar con confirmación y exportar Excel, Controlling y HTML.
 *   6. Overview: indicadores, pestañas y vista previa de Controlling.
 *   7. Reabrir con justificación: exportaciones obsoletas.
 *   8. Solo lectura y Editor: sin crear periodos ni Configuration.
 *
 *   node backend-simulado-4.9.4.cjs 5001 &   (con frontend/dist compilado)
 *   TZ=Europe/Madrid node pruebas-facturacion.cjs [carpeta_capturas]
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const BASE = process.env.TBP_URL || 'http://127.0.0.1:5001';
const CAPTURAS = process.argv[2] || null;
const resultados = [];
const comprobar = (nombre, ok, detalle = '') => { resultados.push({ nombre, ok }); console.log(`${ok ? '✔' : '✘'} ${nombre}${ok ? '' : ' · ' + detalle}`); };

async function api(metodo, ruta, cuerpo) {
  const r = await fetch(BASE + ruta, { method: metodo, headers: { 'Content-Type': 'application/json' }, body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo) });
  const t = await r.text();
  return { estado: r.status, cuerpo: t ? JSON.parse(t) : null };
}

async function guardarBilling(banco, id, billing) {
  const v = (await api('GET', '/api/v1/planificacion')).cuerpo;
  const f = v.bancos[banco].find((x) => x.id === id);
  return api('PUT', `/api/v1/facturacion/bancos/${encodeURIComponent(banco)}/ensayos/${id}`, { billing, version: f.version });
}

async function captura(pagina, nombre) {
  if (!CAPTURAS) return;
  fs.mkdirSync(CAPTURAS, { recursive: true });
  await pagina.screenshot({ path: path.join(CAPTURAS, `${nombre}.png`), fullPage: false });
}

(async () => {
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05' });
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  const contexto = await navegador.newContext({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true });
  const pagina = await contexto.newPage();
  const mensajes = [];
  pagina.on('pageerror', (e) => mensajes.push(String(e)));

  // 1. Billing Data desde la lista de cerrados de 1500 Aire.
  await pagina.goto(`${BASE}/#/${encodeURIComponent('1500 Aire')}`);
  await pagina.waitForSelector('table.work');
  const cabeceras = await pagina.$$eval('table.work th', (ths) => ths.map((t) => t.textContent.trim()));
  comprobar('La tabla del banco tiene la columna Billing', cabeceras.some((t) => t.startsWith('Billing')), cabeceras.join('|'));
  await pagina.click('text=Completed');
  await pagina.click('tr[data-id="r1hm57oj"] >> text=Billing');
  await pagina.waitForSelector('#bd-title');
  await pagina.fill('#bd-ti', '08:00');
  await pagina.fill('#bd-tf', '16:00');
  await pagina.selectOption('#bd-hv', 'no');
  await pagina.fill('#bd-su', '2');
  await pagina.selectOption('#bd-ord', '463B81755MD4'); // el proyecto 81755 tiene dos órdenes: hay que elegir
  await pagina.check('text=Setup confirmado');
  await pagina.waitForFunction(() => document.querySelector('.fac-chip.fac-l-ready')?.textContent.includes('completos'), null, { timeout: 8000 })
    .catch(() => undefined);
  const previa = await pagina.textContent('.modal .fac-bloque');
  comprobar('Vista previa: 56 h netas en noviembre de 2026', /Noviembre 2026/.test(previa) && /56,00 h/.test(previa), previa.slice(0, 300));
  comprobar('Vista previa: datos completos', /Datos de facturación completos/.test(previa), previa.slice(0, 200));
  await captura(pagina, '01-billing-data');
  await pagina.click('#bd-ok');
  await pagina.waitForSelector('#bd-title', { state: 'detached' });
  const ficha = (await api('GET', '/api/v1/facturacion/bancos/1500%20Aire/ensayos/r1hm57oj')).cuerpo;
  comprobar('Billing Data guardado en el ensayo', ficha.billing.startTime === '08:00' && ficha.billing.setupConfirmed === true && ficha.estado.estado === 'COMPLETO',
    JSON.stringify(ficha.billing));
  await pagina.click('.modal-bg.show >> button:has-text("Close")');

  // Otros dos ensayos del periodo con datos completos (por la API).
  const base = { startTime: '08:00', endTime: '16:00', hv: 'no', setupHours: 1, setupConfirmed: true, stops: [], billable: true, order: '463B81755MD4', orderException: null, pieces: '', note: '' };
  let r = await guardarBilling('1500 Aire', 'r6a63mj2', { ...base, stops: [{ from: '2026-10-29T10:00', to: '2026-10-29T12:30', reason: 'Averia', billable: false }] });
  comprobar('Paradas: guardado con una parada no facturable', r.estado === 200, JSON.stringify(r.cuerpo).slice(0, 200));
  r = await guardarBilling('1500 Aire', 'r6a63mj2', { ...base, stops: [{ from: '2026-10-29T10:00', to: '2026-10-29T12:30', reason: 'Averia', billable: false },
    { from: '2026-10-29T12:00', to: '2026-10-29T13:00', reason: 'Averia', billable: false }] });
  comprobar('Paradas solapadas: rechazadas (422)', r.estado === 422 && /se solapan/.test(r.cuerpo.mensaje), JSON.stringify(r.cuerpo));
  r = await guardarBilling('1500 WC', 'rp6fkcjf', { ...base, startTime: '07:00', endTime: '19:00' });
  comprobar('Segundo banco: guardado', r.estado === 200);

  // 2. Ventana de cierre de un ensayo activo: sección Billing Data plegable.
  await pagina.goto(`${BASE}/#/${encodeURIComponent('1500 Aire')}`);
  await pagina.waitForSelector('tr[data-id="r4z9x6lr"]');
  await pagina.click('tr[data-id="r4z9x6lr"] button[title="Mark complete"]');
  await pagina.waitForSelector('#cl-billing');
  comprobar('Ventana de cierre: Billing Data plegado y sin cargar hasta abrirlo', !(await pagina.isVisible('#cl-billing #bd-hv')));
  await pagina.click('#cl-billing > summary');
  await pagina.waitForSelector('#cl-billing #bd-ti');
  await pagina.fill('#cl-billing #bd-ti', '09:00');
  await captura(pagina, '02-cierre-con-billing');
  comprobar('Ventana de cierre: sección Billing Data', await pagina.isVisible('#cl-billing #bd-hv'));
  await pagina.click('text=Cancelar');

  // 3. Periods: crear noviembre de 2026.
  await pagina.goto(`${BASE}/#/__billing_periods__`);
  await pagina.waitForSelector('#fac-crear');
  await pagina.selectOption('#fac-mes', '11');
  await pagina.fill('#fac-anio', '2026');
  comprobar('Periods: fechas del periodo 21/10 – 20/11', /21\/10\/2026 – 20\/11\/2026/.test(await pagina.textContent('.card')));
  await pagina.click('#fac-crear');
  await pagina.waitForSelector('#fac-lineas');
  const fila = await pagina.textContent('tr[data-linea="r1hm57oj@2026-11"]');
  comprobar('Review: línea READY con 56 h, 2 h de setup y 215,76 €', /READY/.test(fila) && /56,00 h/.test(fila) && /2,00 h/.test(fila) && /215,76/.test(fila), fila);
  const bloqueada = await pagina.textContent('#fac-lineas tbody');
  comprobar('Review: hay líneas bloqueadas (faltan datos de otros ensayos)', /BLOCKED/.test(bloqueada));
  await pagina.selectOption('select[data-filtro="estado"]', 'READY');
  const listas = await pagina.$$('#fac-lineas tbody tr[data-linea]');
  comprobar('Filtro por estado READY', listas.length === 3, `${listas.length} filas`);
  await captura(pagina, '03-review');

  // 4. Ajuste: sin justificación no; con justificación sí (Admin: queda validado).
  await pagina.click('tr[data-linea="r1hm57oj@2026-11"]');
  await pagina.waitForSelector('#fac-panel-linea');
  await captura(pagina, '04-panel-linea');
  await pagina.click('#fac-editar');
  await pagina.fill('input[data-campo="horasNetas"]', '55');
  await pagina.click('#fac-guardar-ajuste');
  await pagina.waitForSelector('text=Justificación obligatoria');
  comprobar('Ajuste sin justificación: rechazado', true);
  await pagina.click('.modal-bg.show >> text=OK').catch(() => pagina.keyboard.press('Escape'));
  await pagina.fill('#fac-just', 'Arranque una hora más tarde');
  await pagina.click('#fac-guardar-ajuste');
  await pagina.selectOption('select[data-filtro="estado"]', '');
  await pagina.waitForFunction(() => document.querySelector('tr[data-linea="r1hm57oj@2026-11"]')?.textContent.includes('ADJUSTED'), null, { timeout: 8000 }).catch(() => undefined);
  const ajustada = await pagina.textContent('tr[data-linea="r1hm57oj@2026-11"]');
  comprobar('Ajuste con justificación: ADJUSTED y 212,04 € (55 h + 2 h) × 3,72', /ADJUSTED/.test(ajustada) && /212,04/.test(ajustada), ajustada);
  await captura(pagina, '04b-panel-ajustado');
  await pagina.click('#fac-panel-linea button:has-text("✕")');

  // Excluir el resto de líneas con incidencias (por la API) para poder enviar a revisión.
  let vp = (await api('GET', '/api/v1/facturacion/periodos/2026-11')).cuerpo;
  for (const l of vp.periodo.lineas) {
    if (l.estado === 'BLOCKED' || l.estado === 'WARNING') {
      vp = (await api('POST', `/api/v1/facturacion/periodos/2026-11/lineas/${encodeURIComponent(l.id)}`, { accion: 'excluir', datos: {}, version: vp.version })).cuerpo;
    }
  }
  const viejo = await api('POST', '/api/v1/facturacion/periodos/2026-11/recalcular', { version: 'v0' });
  comprobar('Concurrencia: versión antigua → 409', viejo.estado === 409);

  // 5. Enviar a revisión, aprobar y exportar.
  await pagina.reload();
  await pagina.waitForSelector('#fac-enviar');
  await pagina.click('#fac-enviar');
  await pagina.click('.modal-bg.show >> button:has-text("Enviar a revisión")');
  await pagina.waitForSelector('#fac-aprobar');
  await pagina.click('#fac-aprobar');
  comprobar('Aprobar: el botón espera la confirmación', await pagina.isDisabled('#fac-aprobar-ok'));
  await captura(pagina, '05-aprobar');
  await pagina.check('#fac-confirmo');
  await pagina.click('#fac-aprobar-ok');
  await pagina.waitForSelector('#fac-exportar');
  comprobar('Periodo aprobado', /Aprobado/.test(await pagina.textContent('.fac-head')));
  await pagina.click('#fac-exportar');
  await pagina.check('input[data-tipo="html"]');
  const descargas = [];
  pagina.on('download', (d) => descargas.push(d));
  await pagina.click('#fac-exportar-ok');
  await pagina.waitForFunction(() => document.querySelector('.fac-head')?.textContent.includes('Exportado'), null, { timeout: 10000 }).catch(() => undefined);
  await pagina.waitForTimeout(1500);
  const nombres = descargas.map((d) => d.suggestedFilename()).sort();
  comprobar('Exportar: Excel completo, Controlling y HTML', nombres.length === 3 && nombres.some((n) => n.endsWith('_completo.xlsx'))
    && nombres.some((n) => n.endsWith('_controlling.xlsx')) && nombres.some((n) => n.endsWith('_presentacion.html')), nombres.join(', '));
  if (CAPTURAS) for (const d of descargas) await d.saveAs(path.join(CAPTURAS, d.suggestedFilename()));

  // 6. Overview.
  await pagina.goto(`${BASE}/#/__billing_overview__`);
  await pagina.waitForSelector('#fac-kpis');
  const kpis = await pagina.textContent('#fac-kpis');
  comprobar('Overview: coste total de las tres líneas', /Coste total/.test(kpis) && /€/.test(kpis), kpis);
  await captura(pagina, '06-overview');
  for (const s of ['projects', 'equipment', 'plants', 'incidents', 'controlling', 'exports']) {
    await pagina.click(`button[data-seccion="${s}"]`);
    await pagina.waitForTimeout(200);
  }
  await pagina.click('button[data-seccion="controlling"]');
  const ctrl = await pagina.textContent('#fac-controlling');
  comprobar('Overview: Controlling con Cámara climática, CCtr 6463-41 y la orden', /Cámara climática/.test(ctrl) && /6463-41/.test(ctrl) && /463B81755MD4/.test(ctrl), ctrl.slice(0, 300));
  await captura(pagina, '07-controlling');

  // 7. Reabrir.
  await pagina.click('#fac-reabrir');
  comprobar('Reabrir: justificación obligatoria', await pagina.isDisabled('#fac-reabrir-ok'));
  await pagina.fill('#fac-reabrir-just', 'Prueba de reapertura');
  await pagina.click('#fac-reabrir-ok');
  await pagina.waitForFunction(() => document.querySelector('.fac-head')?.textContent.includes('En revisión'), null, { timeout: 8000 }).catch(() => undefined);
  await pagina.click('button[data-seccion="exports"]');
  await pagina.waitForSelector('#fac-exportaciones');
  const exps = await pagina.textContent('#fac-exportaciones');
  comprobar('Reabrir: exportaciones obsoletas y conservadas', (exps.match(/Obsoleta/g) || []).length === 3, exps);
  await captura(pagina, '08-exportaciones');
  const aud = await pagina.textContent('#fac-auditoria');
  comprobar('Auditoría del periodo: aprobación, exportación y reapertura', /PERIODO_APROBADO/.test(aud) && /EXPORTACION/.test(aud) && /PERIODO_REABIERTO/.test(aud));

  // Configuración (Admin).
  await pagina.goto(`${BASE}/#/__billing_config__`);
  await pagina.waitForSelector('#fac-maestro-proyectos');
  await pagina.click('button[data-apartado="rates"]');
  comprobar('Configuration: tarifas iniciales del Excel', /3\.72|3,72/.test(await pagina.inputValue('#fac-maestro-tarifas tbody tr:nth-child(5) td:nth-child(7) input').catch(() => ''))
    || (await pagina.$$('#fac-maestro-tarifas tbody tr')).length > 50);
  await captura(pagina, '09-configuracion');

  // 8. Solo lectura y Editor (la sesión se lee al abrir la página: se recarga).
  const pestanasDe = () => pagina.$$eval('nav .tab', (ts) => ts.map((t) => t.textContent.trim()));
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05', email: 'nadie@ejemplo.local' });
  await pagina.goto(`${BASE}/#/__billing_periods__`);
  await pagina.reload();
  await pagina.waitForSelector('#fac-periodos, .empty');
  comprobar('Solo lectura: no puede crear periodos', !(await pagina.isVisible('#fac-crear')));
  let pestanas = await pestanasDe();
  comprobar('Solo lectura: ve Periods, Review y Overview, no Configuration', ['Periods', 'Review', 'Overview / Reports'].every((t) => pestanas.some((x) => x.endsWith(t)))
    && !pestanas.some((x) => x.endsWith('Configuration')), pestanas.join('|'));
  await api('POST', '/api/v1/local/reinicio', { fecha: '2026-11-05', email: 'usuario07@ejemplo.local' });
  await pagina.reload();
  await pagina.waitForSelector('#fac-crear');
  pestanas = await pestanasDe();
  comprobar('Editor: crea periodos y no ve Billing › Configuration', !pestanas.some((x) => x.endsWith('Configuration')), pestanas.join('|'));

  comprobar('Sin errores de JavaScript en la página', !mensajes.length, mensajes.join(' | '));
  await navegador.close();
  const fallos = resultados.filter((x) => !x.ok).length;
  console.log(`\n${resultados.length - fallos}/${resultados.length} comprobaciones correctas`);
  process.exit(fallos ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
