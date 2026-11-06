'use strict';
/*
 * Ciclo de vida de la pestaña React (corrección del 6-nov):
 *   1. Ocultar/minimizar (visibilitychange → hidden) y «freeze» (pestaña dormida)
 *      NO envían /api/shutdown.
 *   2. Los latidos llevan el código de la pestaña; cada carga tiene uno distinto.
 *   3. Salir de la página (cerrar o recargar) SÍ envía /api/shutdown con su código.
 *
 *   TZ=Europe/Madrid node pruebas-ciclo-de-vida.cjs [--url http://127.0.0.1:5001]
 *
 * Funciona contra cualquier backend que sirva la interfaz (se interceptan las
 * llamadas de latido y cierre: no se apaga nada).
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';

(async () => {
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid' });
  const latidos = [], cierres = [];
  await contexto.route('**/api/heartbeat', (r) => { latidos.push(r.request().postData()); return r.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' }); });
  await contexto.route('**/api/shutdown', (r) => { cierres.push(r.request().postData()); return r.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' }); });
  let fallos = 0;
  const prueba = async (nombre, fn) => {
    try { await fn(); console.log('✓ ' + nombre); } catch (e) { fallos++; console.log('✗ ' + nombre + '\n   ' + e.message.split('\n')[0]); }
  };

  const p1 = await contexto.newPage();
  await p1.goto(BASE + '/');
  await p1.waitForTimeout(800);

  await prueba('El latido lleva el código de la pestaña', async () => {
    assert.ok(latidos.length >= 1, 'sin latidos');
    assert.match(JSON.parse(latidos[0]).pestana || '', /\S{8,}/);
  });

  await prueba('Minimizar u ocultar la pestaña no avisa de cierre', async () => {
    await p1.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
      document.dispatchEvent(new Event('visibilitychange'));
      document.dispatchEvent(new Event('freeze'));
    });
    await p1.waitForTimeout(500);
    assert.strictEqual(cierres.length, 0, 'se ha enviado /api/shutdown');
  });

  await prueba('Al volver a mostrarse envía un latido en el acto', async () => {
    const antes = latidos.length;
    await p1.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await p1.waitForTimeout(300);
    assert.ok(latidos.length > antes);
  });

  await prueba('Al volver desde la caché del navegador se registra de nuevo', async () => {
    const antes = latidos.length;
    await p1.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
    await p1.waitForTimeout(300);
    assert.ok(latidos.length > antes);
  });

  await prueba('Otra pestaña tiene otro código', async () => {
    const p2 = await contexto.newPage();
    await p2.goto(BASE + '/');
    await p2.waitForTimeout(800);
    const codigos = new Set(latidos.map((l) => JSON.parse(l).pestana));
    assert.ok(codigos.size >= 2, 'mismo código en dos pestañas');
    await p2.close({ runBeforeUnload: true });
  });

  await prueba('Salir de la página (cierre o recarga) sí avisa, con su código', async () => {
    const codigo = JSON.parse(latidos[0]).pestana;
    // Salir de la página dispara el mismo «pagehide» que cerrar la pestaña, pero
    // la pestaña sigue viva y el aviso se puede interceptar.
    await p1.goto('about:blank');
    await new Promise((r) => setTimeout(r, 800));
    assert.ok(cierres.map((c) => JSON.parse(c || '{}').pestana).includes(codigo), `cierres: ${JSON.stringify(cierres)}`);
  });

  await navegador.close();
  console.log(fallos ? `\n${fallos} fallo(s)` : '\nTodo correcto');
  process.exit(fallos ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
