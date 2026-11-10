'use strict';
/*
 * Regresión de rendimiento de la interfaz React (latencia con SharePoint).
 * Contra el backend simulado, con un guardado «lento» de 2,5 s:
 *   1. Añadir: la fila aparece al momento (sin esperar al guardado), marcada
 *      como «guardando», y al confirmarse queda una sola fila con el MISMO id.
 *   2. Lo escrito en la fila nueva mientras se guarda no se pierde: se envía
 *      cuando el alta está guardada (regla de los 1,2 s).
 *   3. Duplicar: las copias aparecen al momento debajo de sus originales.
 *   4. Si el backend no acepta el id propuesto, la fila cambia al id real sin
 *      duplicarse y lo escrito en ella se guarda en el id real.
 *   5. Si el guardado falla, la fila provisional desaparece y se avisa.
 *   6. Varias altas seguidas: todas se ven al momento y se guardan en orden.
 *   7. Todas las peticiones /api/v1 llevan el código de la pestaña (X-TBP-Pestana),
 *      y una recarga usa un código nuevo.
 *
 *   TZ=Europe/Madrid node pruebas-rendimiento.cjs [--url http://127.0.0.1:5001]
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';
const BANCO = 'MD2';
const RETARDO_MS = 2500;
/** Lo máximo que puede tardar en verse una fila nueva (antes: lo que tardase SharePoint). */
// Menos de la mitad del guardado simulado: si la interfaz volviera a esperar a SharePoint, tardaría ≥ 2,5 s.
const AL_MOMENTO_MS = 1200;
const FORMATO_ID = /^r[0-9a-z]{7}$/;

const json = async (ruta, cuerpo) => {
  const r = await fetch(BASE + ruta, cuerpo === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cuerpo) });
  return { estado: r.status, cuerpo: await r.json().catch(() => null) };
};
const reiniciar = () => json('/api/v1/local/reinicio', { escenario: 'sharepoint-2026-11-05', fecha: '2026-11-05', email: 'usuario07@ejemplo.local' });
const laboratorio = (cambios) => json('/api/v1/local/laboratorio', cambios);
const filasApi = async () => (await json('/api/v1/planificacion')).cuerpo.bancos[BANCO];
const esperarSynced = (p, timeout = 15000) => p.waitForFunction(() => /Synced/.test(document.getElementById('sp-status')?.textContent || ''), null, { timeout });
const idsTabla = (p) => p.locator('#tb tr[data-id]').evaluateAll((trs) => trs.map((t) => t.getAttribute('data-id')));

async function abrir(navegador) {
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', viewport: { width: 1700, height: 1000 } });
  const p = await contexto.newPage();
  const errores = [];
  p.on('pageerror', (e) => errores.push(e.message));
  p.on('dialog', (d) => d.accept());
  await p.goto(BASE + '/#/' + encodeURIComponent(BANCO));
  await esperarSynced(p);
  await p.waitForFunction((b) => document.querySelector('.view-head h2')?.textContent === b, BANCO);
  return { contexto, p, errores };
}

/**
 * Mide DENTRO de la página cuánto pasa entre el clic y que la tabla tenga al
 * menos «filas» filas (sin contar lo que tarda Playwright en hacer el clic).
 */
async function medirAparicion(p, boton, filas) {
  await p.evaluate((n) => {
    window.__medida = new Promise((resolver) => {
      let inicio = null;
      document.addEventListener('click', () => { inicio = performance.now(); }, { capture: true, once: true });
      const mirar = () => {
        if (inicio !== null && document.querySelectorAll('#tb tr[data-id]').length >= n) resolver(performance.now() - inicio);
        else requestAnimationFrame(mirar);
      };
      mirar();
    });
  }, filas);
  await boton.click();
  return p.evaluate(() => window.__medida);
}

/** Pulsa «Add test» y devuelve el id de la fila nueva y cuánto tardó en verse. */
async function anadir(p) {
  const antes = await idsTabla(p);
  const ms = await medirAparicion(p, p.getByRole('button', { name: /Add test/ }), antes.length + 1);
  const nuevos = (await idsTabla(p)).filter((id) => !antes.includes(id));
  assert.strictEqual(nuevos.length, 1, 'una sola fila nueva');
  return { id: nuevos[0], ms };
}

const PRUEBAS = {
  async 'Añadir: la fila aparece al momento y conserva su id al guardarse'(nav) {
    await reiniciar();
    await laboratorio({ retardoOperacionesMs: RETARDO_MS, aceptarIdsPropuestos: true });
    const { contexto, p, errores } = await abrir(nav);
    const { id, ms } = await anadir(p);
    assert.ok(ms < AL_MOMENTO_MS, `la fila nueva tardó ${Math.round(ms)} ms en verse`);
    assert.match(id, FORMATO_ID);
    assert.strictEqual(await p.locator(`#tb tr[data-id="${id}"]`).getAttribute('class').then((c) => /guardando-fila/.test(c || '')), true);
    assert.ok(!(await filasApi()).some((f) => f.id === id), 'todavía no está guardada');
    await esperarSynced(p, 45000);
    const fila = p.locator(`#tb tr[data-id="${id}"]`);
    assert.strictEqual(await fila.count(), 1, 'una sola fila con ese id tras confirmar');
    assert.ok(!/guardando-fila/.test(await fila.getAttribute('class') || ''));
    assert.ok((await filasApi()).some((f) => f.id === id), 'guardada con el id que ya se veía');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Lo escrito en la fila nueva mientras se guarda se envía después'(nav) {
    await reiniciar();
    await laboratorio({ retardoOperacionesMs: RETARDO_MS, aceptarIdsPropuestos: true });
    const { contexto, p, errores } = await abrir(nav);
    const { id } = await anadir(p);
    await p.locator(`#tb tr[data-id="${id}"] [data-f="comments"]`).first().fill('escrito mientras se guarda');
    await p.waitForTimeout(300);
    assert.ok(!(await filasApi()).some((f) => f.id === id));
    await esperarSynced(p, 45000);
    const guardada = (await filasApi()).find((f) => f.id === id);
    assert.ok(guardada, 'alta guardada');
    assert.strictEqual(guardada.datos.comments, 'escrito mientras se guarda');
    assert.strictEqual(await p.locator(`#tb tr[data-id="${id}"] [data-f="comments"]`).first().inputValue(), 'escrito mientras se guarda');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Duplicar: las copias aparecen al momento debajo de sus originales'(nav) {
    await reiniciar();
    await laboratorio({ retardoOperacionesMs: RETARDO_MS, aceptarIdsPropuestos: true });
    const { contexto, p, errores } = await abrir(nav);
    const antes = await idsTabla(p);
    const elegidos = [antes[0], antes[2]];
    for (const id of elegidos) await p.locator(`#tb tr[data-id="${id}"] .rowsel`).setChecked(true);
    const ms = await medirAparicion(p, p.locator('#selbar').getByRole('button', { name: /Duplicate/ }), antes.length + 2);
    assert.ok(ms < AL_MOMENTO_MS, `las copias tardaron ${Math.round(ms)} ms en verse`);
    const despues = await idsTabla(p);
    const copias = despues.filter((id) => !antes.includes(id));
    assert.strictEqual(copias.length, 2);
    assert.strictEqual(despues[despues.indexOf(elegidos[0]) + 1], copias[0]);
    assert.strictEqual(despues[despues.indexOf(elegidos[1]) + 1], copias[1]);
    await esperarSynced(p, 45000);
    assert.deepStrictEqual(await idsTabla(p), despues, 'el orden y los ids no cambian al confirmar');
    const api = (await filasApi()).map((f) => f.id);
    copias.forEach((id) => assert.ok(api.includes(id)));
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Si el backend da otro id, la fila pasa al id real sin duplicarse'(nav) {
    await reiniciar();
    await laboratorio({ retardoOperacionesMs: 1500, aceptarIdsPropuestos: false });
    const { contexto, p, errores } = await abrir(nav);
    const antes = await idsTabla(p);
    const { id: provisional } = await anadir(p);
    await p.locator(`#tb tr[data-id="${provisional}"] [data-f="comments"]`).first().fill('va al id real');
    await esperarSynced(p, 45000);
    const nuevos = (await idsTabla(p)).filter((id) => !antes.includes(id));
    assert.strictEqual(nuevos.length, 1, 'una sola fila nueva tras confirmar');
    assert.notStrictEqual(nuevos[0], provisional);
    const guardada = (await filasApi()).find((f) => f.id === nuevos[0]);
    assert.strictEqual(guardada.datos.comments, 'va al id real');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Si el guardado falla, la fila provisional desaparece y se avisa'(nav) {
    await reiniciar();
    await laboratorio({ fallarOperaciones: true });
    const { contexto, p, errores } = await abrir(nav);
    const antes = await idsTabla(p);
    await p.getByRole('button', { name: /Add test/ }).click();
    await p.locator('.modal-bg.show').first().waitFor({ timeout: 5000 });
    assert.match(await p.locator('.modal-bg.show').first().textContent(), /SharePoint no responde/);
    assert.deepStrictEqual(await idsTabla(p), antes);
    await laboratorio({ fallarOperaciones: false });
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Varias altas seguidas: se ven todas al momento y se guardan en orden'(nav) {
    await reiniciar();
    await laboratorio({ retardoOperacionesMs: 1200, aceptarIdsPropuestos: true });
    const { contexto, p, errores } = await abrir(nav);
    const antes = await idsTabla(p);
    const boton = p.getByRole('button', { name: /Add test/ });
    await boton.click();
    await boton.click();
    await boton.click();
    await p.waitForFunction((n) => document.querySelectorAll('#tb tr[data-id]').length >= n + 3, antes.length, { timeout: AL_MOMENTO_MS * 2 });
    const vistas = (await idsTabla(p)).filter((id) => !antes.includes(id));
    assert.strictEqual(vistas.length, 3);
    await esperarSynced(p, 45000);
    assert.deepStrictEqual((await idsTabla(p)).filter((id) => !antes.includes(id)), vistas, 'mismos ids y mismo orden');
    const api = (await filasApi()).map((f) => f.id);
    assert.deepStrictEqual(api.filter((id) => vistas.includes(id)), vistas);
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Las peticiones llevan el código de la pestaña y una recarga usa otro'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav);
    await p.waitForTimeout(500);
    const primera = (await laboratorio({})).cuerpo;
    assert.strictEqual(primera.peticionesSinPestana, 0, 'ninguna petición sin código');
    assert.strictEqual(primera.pestanas.length, 1);
    await p.reload();
    await esperarSynced(p);
    await p.waitForTimeout(500);
    const segunda = (await laboratorio({})).cuerpo;
    assert.strictEqual(segunda.pestanas.length, 2, 'la página recargada es otra pestaña para el backend');
    assert.strictEqual(segunda.peticionesSinPestana, 0);
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
        await Promise.all(navegador.contexts().map((c) => c.close()));
        bien++;
        console.log('✓ ' + n);
      } catch (e) {
        await Promise.all(navegador.contexts().map((c) => c.close().catch(() => undefined)));
        console.log('✗ ' + n + '\n   ' + String(e && e.message || e).split('\n').slice(0, 4).join('\n   '));
      }
    }
  } finally {
    await laboratorio({ retardoOperacionesMs: 0, fallarOperaciones: false, aceptarIdsPropuestos: false }).catch(() => undefined);
    await navegador.close();
  }
  console.log(`\n${bien}/${nombres.length} correctas`);
  process.exit(bien === nombres.length ? 0 : 1);
})();
