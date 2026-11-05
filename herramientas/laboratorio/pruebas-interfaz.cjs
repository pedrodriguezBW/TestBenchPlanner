'use strict';
/*
 * Pruebas propias de la interfaz React (no tienen equivalente en la 4.9.4):
 *   1. Conflicto: otra persona guarda el mismo ensayo → aviso con
 *      «Ver su versión» y «Guardar la mía encima» (D12).
 *   2. Sin conexión al guardar: la edición no se pierde y se reintenta sola.
 *   3. Sin backend al arrancar: tres intentos y bloqueo en solo lectura.
 *   4. Usuario de solo lectura: celdas desactivadas y nada se guarda.
 *   5. Primera carga completa: aparecen todos los ensayos a la primera (D17).
 *
 *   TZ=Europe/Madrid node pruebas-interfaz.cjs [--url http://127.0.0.1:5001]
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';
const ENSAYO = 'r3rtxond';
const BANCO = 'MD2';

const json = async (ruta, cuerpo) => {
  const r = await fetch(BASE + ruta, cuerpo === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cuerpo) });
  return { estado: r.status, cuerpo: await r.json().catch(() => null) };
};
const reiniciar = (email = 'usuario01@ejemplo.local') => json('/api/v1/local/reinicio', { escenario: 'sharepoint-2026-11-05', fecha: '2026-11-05', email });
const filaEnApi = async (id = ENSAYO) => (await json('/api/v1/planificacion')).cuerpo.bancos[BANCO].find((f) => f.id === id);
const textoEstado = (p) => p.locator('#sp-status').textContent();
const esperarSynced = (p) => p.waitForFunction(() => /Synced/.test(document.getElementById('sp-status')?.textContent || ''), null, { timeout: 15000 });

async function abrir(navegador, hash = '#/' + BANCO) {
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', viewport: { width: 1700, height: 1000 } });
  const p = await contexto.newPage();
  const errores = [];
  p.on('pageerror', (e) => errores.push(e.message));
  return { contexto, p, errores, ir: async () => { await p.goto(BASE + '/' + hash); await esperarSynced(p); } };
}

/** Otra persona (otra sesión, vía API) cambia el comentario mientras yo edito. */
async function conflicto(navegador, eleccion) {
  await reiniciar();
  const { contexto, p, errores, ir } = await abrir(navegador);
  await ir();
  const suya = await filaEnApi();
  await p.locator(`#tb tr[data-id="${ENSAYO}"] [data-f="comments"]`).fill('Mi comentario');
  // Dentro de la espera de 1,2 s, otra persona guarda el mismo ensayo.
  const r = await json(`/api/v1/bancos/${BANCO}/ensayos/${ENSAYO}/campos`, { cambios: [{ campo: 'comments', valor: 'Su comentario' }], version: suya.version });
  assert.strictEqual(r.estado, 200, 'el guardado de la otra persona debe entrar');
  await p.getByText('Otra persona ha cambiado este ensayo').waitFor({ timeout: 10000 });
  await p.getByRole('button', { name: eleccion }).click();
  await esperarSynced(p);
  await p.waitForTimeout(800);
  const final = (await filaEnApi()).datos.comments;
  const pantalla = await p.locator(`#tb tr[data-id="${ENSAYO}"] [data-f="comments"]`).inputValue();
  await contexto.close();
  assert.deepStrictEqual(errores, []);
  return { final, pantalla };
}

const PRUEBAS = {
  async 'Conflicto → «Guardar la mía encima» guarda mi valor'(nav) {
    const { final, pantalla } = await conflicto(nav, 'Guardar la mía encima');
    assert.strictEqual(final, 'Mi comentario');
    assert.strictEqual(pantalla, 'Mi comentario');
  },
  async 'Conflicto → «Ver su versión» deja el valor de la otra persona'(nav) {
    const { final, pantalla } = await conflicto(nav, 'Ver su versión');
    assert.strictEqual(final, 'Su comentario');
    assert.strictEqual(pantalla, 'Su comentario');
  },
  async 'Sin conexión al guardar: la edición espera y se guarda al volver'(nav) {
    await reiniciar();
    const { contexto, p, ir } = await abrir(nav);
    await ir();
    await p.route('**/api/v1/bancos/**', (ruta) => ruta.abort('internetdisconnected'));
    await p.locator(`#tb tr[data-id="${ENSAYO}"] [data-f="comments"]`).fill('Escrito sin red');
    await p.waitForFunction(() => /Sin backend/.test(document.getElementById('sp-status').textContent), null, { timeout: 10000 });
    assert.notStrictEqual((await filaEnApi()).datos.comments, 'Escrito sin red', 'no debe haberse guardado aún');
    await p.unroute('**/api/v1/bancos/**');
    await esperarSynced(p); // reintento automático cada 5 s
    assert.strictEqual((await filaEnApi()).datos.comments, 'Escrito sin red');
    await contexto.close();
  },
  async 'Sin backend al arrancar: tres intentos y bloqueo en solo lectura'(nav) {
    await reiniciar();
    const { contexto, p } = await abrir(nav);
    let intentos = 0;
    await p.route('**/api/v1/planificacion', (ruta) => { intentos++; return ruta.abort('connectionrefused'); });
    await p.goto(BASE + '/#/' + BANCO);
    await p.locator('#lock-banner').waitFor({ timeout: 15000 });
    assert.strictEqual(intentos, 3);
    assert.match(await textoEstado(p), /Solo lectura/);
    await contexto.close();
  },
  async 'Usuario de solo lectura: celdas desactivadas y sin guardados'(nav) {
    await reiniciar('nadie@ejemplo.local');
    const { contexto, p, ir } = await abrir(nav);
    await ir();
    const antes = JSON.stringify((await filaEnApi()).datos);
    assert.ok(await p.locator(`#tb tr[data-id="${ENSAYO}"] [data-f="comments"]`).isDisabled());
    const anadir = p.getByRole('button', { name: /Add test/ });
    assert.ok((await anadir.count()) === 0 || !(await anadir.isVisible()) || !(await anadir.isEnabled()), '«Add test» no debe poder usarse');
    await p.waitForTimeout(1500);
    assert.strictEqual(JSON.stringify((await filaEnApi()).datos), antes);
    await contexto.close();
  },
  async 'Primera carga: todos los ensayos a la primera (D17)'(nav) {
    await reiniciar();
    const vista = (await json('/api/v1/planificacion')).cuerpo;
    const { contexto, p, ir } = await abrir(nav, '#/XXL CTAG');
    await ir();
    const enApi = (vista.bancos['XXL CTAG'] || []).filter((f) => !f.cerrado).length;
    assert.ok(enApi > 0, 'el escenario debe tener ensayos en XXL CTAG');
    assert.strictEqual(await p.locator('#tb tr[data-id]').count(), enApi);
    await contexto.close();
  },
};

(async () => {
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
  let fallos = 0;
  for (const [nombre, prueba] of Object.entries(PRUEBAS)) {
    try { await prueba(navegador); console.log('✓ ' + nombre); } catch (e) { fallos++; console.log('✗ ' + nombre + '\n   ' + e.message.split('\n').slice(0, 4).join('\n   ')); }
  }
  await navegador.close();
  console.log(`\n${Object.keys(PRUEBAS).length - fallos}/${Object.keys(PRUEBAS).length} correctas`);
  process.exit(fallos ? 1 : 0);
})();
