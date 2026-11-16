'use strict';
/*
 * Pruebas de las ÁREAS de bancos (Thermal Benches, HVCH - CTAG, HVCH - STC)
 * contra el backend simulado:
 *   1. Test Benches: un bloque por área (las vacías también); sin Org en HVCH.
 *   2. Mover una categoría a HVCH - STC: Org fija STC, menú y vista agrupados.
 *   3. Menú lateral: selector de área y secciones plegables (recordados).
 *   4. Dashboard: dos desplegables; el de Org solo con Thermal Benches; filtra el Gantt.
 *   5. KPI History: mismo filtro de dos niveles.
 *   6. Renombrar un área.
 *   7. Additional Equipment: filtro Org de los equipos.
 *
 *   TZ=Europe/Madrid node pruebas-areas.cjs [--url http://127.0.0.1:5001]
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';

const json = async (ruta, cuerpo, metodo) => {
  const r = await fetch(BASE + ruta, cuerpo === undefined ? {} : { method: metodo || 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cuerpo) });
  return { estado: r.status, cuerpo: await r.json().catch(() => null) };
};
const reiniciar = (email = 'usuario01@ejemplo.local') => json('/api/v1/local/reinicio', { escenario: 'sharepoint-2026-11-05', fecha: '2026-11-05', email });

async function abrir(navegador, hash = '') {
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', viewport: { width: 1700, height: 1000 } });
  const p = await contexto.newPage();
  const errores = [];
  p.on('pageerror', (e) => errores.push(e.message));
  await p.goto(BASE + '/' + hash);
  await p.waitForFunction(() => /Synced|Solo lectura/.test(document.getElementById('sp-status')?.textContent || ''), null, { timeout: 15000 });
  return { contexto, p, errores };
}
const esperarGuardado = (p) => p.waitForFunction(() => /Synced/.test(document.getElementById('sp-status')?.textContent || ''), null, { timeout: 20000 });

/** Mueve por la API la categoría de un banco a un área (como la pantalla Test Benches). */
async function moverPorApi(banco, area) {
  const c = (await json('/api/v1/catalogos')).cuerpo;
  const clave = c.benches.find((b) => b.name === banco).groupKey;
  const benches = c.benches.map((b) => ({ ...b, area: b.groupKey === clave ? area : (b.area || 'THERMAL') }));
  const r = await json('/api/v1/configuracion/bancos', { projects: c.projects, benches, renombrados: {} }, 'PUT');
  assert.strictEqual(r.estado, 200);
  return clave;
}

const PRUEBAS = {
  async 'Test Benches: un bloque por área y sin Org fuera de Thermal Benches'(nav) {
    await reiniciar();
    await moverPorApi('Snakes dP', 'HVCH-CTAG');
    const { contexto, p, errores } = await abrir(nav, '#/__benches__');
    await p.locator('.cfg-area').first().waitFor();
    assert.deepStrictEqual(await p.locator('.cfg-area').evaluateAll((xs) => xs.map((x) => x.dataset.area)), ['THERMAL', 'HVCH-CTAG', 'HVCH-STC']);
    assert.deepStrictEqual(await p.locator('[data-nombre-area]').evaluateAll((xs) => xs.map((x) => x.value)), ['Thermal Benches', 'HVCH - CTAG', 'HVCH - STC']);
    assert.strictEqual(await p.locator('.cfg-area[data-area="HVCH-STC"] .area-empty').count(), 1, 'el área vacía se ve');
    const hvch = p.locator('.cfg-area[data-area="HVCH-CTAG"]');
    assert.strictEqual(await hvch.locator('tr[data-banco="Snakes dP"]').count(), 1);
    assert.ok(!(await hvch.locator('thead').first().textContent()).includes('Org'), 'HVCH sin columna Org');
    assert.ok((await p.locator('.cfg-area[data-area="THERMAL"] thead').first().textContent()).includes('Org'));
    assert.strictEqual(await p.getByRole('button', { name: /Add category/ }).count(), 3);
    const c = (await json('/api/v1/catalogos')).cuerpo;
    assert.strictEqual(c.benches.find((b) => b.name === 'Snakes dP').org, 'CTAG');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Mover una categoría a HVCH - STC desde la pantalla'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/__benches__');
    p.on('dialog', (d) => d.accept());
    await p.locator('select[data-area-categoria="Corrosion"]').selectOption('HVCH-STC');
    await p.waitForTimeout(300);
    await esperarGuardado(p);
    await p.waitForFunction(() => document.querySelector('.cfg-area[data-area="HVCH-STC"] [data-categoria="Corrosion"]'));
    const c = (await json('/api/v1/catalogos')).cuerpo;
    const corr = c.benches.filter((b) => b.groupName === 'Corrosion');
    assert.ok(corr.length >= 2);
    corr.forEach((b) => { assert.strictEqual(b.area, 'HVCH-STC'); assert.strictEqual(b.org, 'STC'); });
    const v = (await json('/api/v1/planificacion')).cuerpo;
    assert.strictEqual(v.grupos[v.grupos.length - 1].nombre, 'Corrosion', 'las categorías HVCH van detrás de las térmicas');
    // Añadir un banco en el área HVCH lo crea con Org STC.
    const cat = p.locator('.cfg-area[data-area="HVCH-STC"] [data-categoria="Corrosion"]');
    await cat.locator('input[placeholder^="New bench"]').fill('STC 1');
    await cat.getByRole('button', { name: /Add bench/ }).click();
    await p.waitForTimeout(300);
    await esperarGuardado(p);
    const c2 = (await json('/api/v1/catalogos')).cuerpo;
    assert.strictEqual(c2.benches.find((b) => b.name === 'STC 1').org, 'STC');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Menú lateral: selector de área y secciones plegables'(nav) {
    await reiniciar();
    await moverPorApi('MD1', 'HVCH-STC');
    const { contexto, p, errores } = await abrir(nav);
    const areas = () => p.locator('#side .area-menu').evaluateAll((xs) => xs.map((x) => x.dataset.area));
    assert.deepStrictEqual(await areas(), ['THERMAL', 'HVCH-STC'], 'un área sin categorías no sale en el menú');
    assert.strictEqual(await p.locator('#side .area-menu[data-area="HVCH-STC"] .tab', { hasText: 'MD1' }).count(), 1);
    await p.locator('#side select.area-sel').selectOption('HVCH-STC');
    assert.deepStrictEqual(await areas(), ['HVCH-STC']);
    assert.strictEqual(await p.locator('#side .tab', { hasText: /^Turismos$/ }).count(), 0);
    await p.locator('#side select.area-sel').selectOption('ALL');
    await p.locator('#side .area-menu[data-area="THERMAL"] .area-title').click();
    assert.strictEqual(await p.locator('#side .tab', { hasText: /^Turismos$/ }).count(), 0, 'Thermal Benches plegado');
    await p.reload();
    await p.waitForFunction(() => /Synced/.test(document.getElementById('sp-status')?.textContent || ''));
    assert.strictEqual(await p.locator('#side .tab', { hasText: /^Turismos$/ }).count(), 0, 'el plegado se recuerda');
    await p.locator('#side .area-menu[data-area="THERMAL"] .area-title').click();
    assert.strictEqual(await p.locator('#side .tab', { hasText: /^Turismos$/ }).count(), 1);
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Dashboard: área y, solo con Thermal Benches, Org'(nav) {
    await reiniciar();
    await moverPorApi('MD1', 'HVCH-STC');
    const { contexto, p, errores } = await abrir(nav);
    const cab = p.locator('#main .view-head').first();
    assert.strictEqual(await cab.locator('select[data-filtro-org]').count(), 0, 'con ALL no hay Org');
    const filas = () => p.locator('#ganttbox .g-row').evaluateAll((xs) => xs.map((x) => x.dataset.banco));
    const todos = await filas();
    assert.ok(todos.includes('MD1') && todos.includes('Fatiga'));
    assert.deepStrictEqual(await p.locator('#ganttbox .g-area').evaluateAll((xs) => xs.map((x) => x.dataset.area)), ['THERMAL', 'HVCH-STC']);
    await cab.locator('select[data-filtro-area]').selectOption('HVCH-STC');
    await p.waitForTimeout(400);
    assert.strictEqual(await cab.locator('select[data-filtro-org]').count(), 0, 'HVCH sin Org');
    const stc = await filas();
    assert.ok(stc.includes('MD1') && !stc.includes('Fatiga') && !stc.includes('Turismos'));
    assert.strictEqual(await p.locator('#ganttbox .g-area').count(), 0, 'una sola área: sin cabecera');
    await cab.locator('select[data-filtro-area]').selectOption('THERMAL');
    await cab.locator('select[data-filtro-org]').selectOption('CTAG');
    await p.waitForTimeout(400);
    const ctag = await filas();
    assert.ok(ctag.includes('Fatiga') && !ctag.includes('MD1') && !ctag.includes('Turismos'));
    assert.ok((await p.locator('#main').textContent()).includes('Filtered by Thermal Benches \u00b7 CTAG'));
    const kpiAll = (await json('/api/v1/indicadores/bloques?org=ALL')).cuerpo.estaSemana.total;
    const kpiT = (await json('/api/v1/indicadores/bloques?org=THERMAL')).cuerpo.estaSemana.total;
    const kpiS = (await json('/api/v1/indicadores/bloques?org=HVCH-STC')).cuerpo.estaSemana.total;
    assert.strictEqual(kpiAll, kpiT + kpiS);
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'KPI History y tarjetas con el filtro de dos niveles'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/__kpihist__');
    await p.locator('#main select[data-filtro-area]').first().selectOption('THERMAL');
    assert.strictEqual(await p.locator('#main select[data-filtro-org]').count(), 1);
    await p.locator('#main select[data-filtro-org]').first().selectOption('BW');
    await p.waitForTimeout(600);
    assert.ok((await p.locator('#main').textContent()).includes('Thermal Benches \u00b7 BW'));
    await p.locator('.tab', { hasText: 'Dashboard' }).first().click();
    const tarjeta = p.locator('.card.rzcard').filter({ has: p.locator('#trend-svg-wrap') });
    await tarjeta.locator('select[data-filtro-area]').selectOption('HVCH-CTAG');
    assert.strictEqual(await tarjeta.locator('select[data-filtro-org]').count(), 0);
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Renombrar un área'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/__benches__');
    const caja = p.locator('[data-nombre-area="HVCH-STC"]');
    await caja.fill('HVCH - STC Vigo');
    await caja.press('Enter');
    await p.waitForTimeout(300);
    await esperarGuardado(p);
    const v = (await json('/api/v1/planificacion')).cuerpo;
    assert.strictEqual(v.areas.find((a) => a.clave === 'HVCH-STC').nombre, 'HVCH - STC Vigo');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Additional Equipment: filtro Org de los equipos'(nav) {
    await reiniciar();
    const r = (await json('/api/v1/recursos')).cuerpo;
    const { contexto, p, errores } = await abrir(nav, '#/__resources__');
    await p.locator('#equipos-org').waitFor();
    const filas = () => p.locator('#vista-recursos .g-row').count();
    const total = await filas();
    await p.locator('#equipos-org').selectOption('CTAG');
    const ctag = (r.resources || []).filter((x) => x.org === 'CTAG').length;
    if (ctag) assert.strictEqual(await filas(), ctag); else assert.ok((await p.locator('#vista-recursos').textContent()).includes('No hay recursos de CTAG'));
    await p.locator('#equipos-org').selectOption('BW');
    assert.strictEqual(await filas(), (r.resources || []).filter((x) => (x.org || 'BW') === 'BW').length);
    await p.locator('#equipos-org').selectOption('ALL');
    assert.strictEqual(await filas(), total);
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },
};

    (async () => {
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  let bien = 0;
  const nombres = Object.keys(PRUEBAS);
  try {
    for (const n of nombres) {
      try {
        await PRUEBAS[n](navegador);
        bien++;
        console.log('✓ ' + n);
      } catch (e) {
        console.log('✗ ' + n + '\n   ' + String(e && e.message || e).split('\n').slice(0, 4).join('\n   '));
      }
    }
  } finally {
    await navegador.close();
  }
  console.log(`\n${bien}/${nombres.length} correctas`);
  process.exit(bien === nombres.length ? 0 : 1);
})();
