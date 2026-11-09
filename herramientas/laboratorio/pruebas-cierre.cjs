'use strict';
/*
 * Pruebas del cierre de la migración (H3Z): lo que la auditoría encontró sin
 * migrar y ya está en la versión nueva. Contra el backend simulado:
 *   1. Presencia: burbujas, «+N» y «N en línea» (renderPresence).
 *   2. Aviso de versión antigua solo si la publicada es MAYOR (cmpVer).
 *   3. Bienvenida de 3 s con nombre y rol (showWelcome).
 *   4. Sin conexión: se ve la última copia, en solo lectura y con su aviso (H-04).
 *   5. Calendario: se cierra con Escape y al cambiar el tamaño de la ventana.
 *   6. Sugerencias: al entrar en Project se ve la lista entera y al salir vuelve el valor.
 *   7. Tirador de etiquetas del Gantt de banco: arrastrar y doble clic para restablecer.
 *   8. Users: los Admin salen los primeros (D29 corregido).
 *
 *   TZ=Europe/Madrid node pruebas-cierre.cjs [--url http://127.0.0.1:5001]
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';
const BANCO = 'MD2';

const json = async (ruta, cuerpo) => {
  const r = await fetch(BASE + ruta, cuerpo === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cuerpo) });
  return { estado: r.status, cuerpo: await r.json().catch(() => null) };
};
const reiniciar = (email = 'usuario01@ejemplo.local') => json('/api/v1/local/reinicio', { escenario: 'sharepoint-2026-11-05', fecha: '2026-11-05', email });
const laboratorio = (cambios) => json('/api/v1/local/laboratorio', cambios);

async function abrir(navegador, hash = '') {
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', viewport: { width: 1700, height: 1000 } });
  const p = await contexto.newPage();
  const errores = [];
  p.on('pageerror', (e) => errores.push(e.message));
  await p.goto(BASE + '/' + hash);
  await p.waitForFunction(() => /Synced|Solo lectura/.test(document.getElementById('sp-status')?.textContent || ''), null, { timeout: 15000 });
  return { contexto, p, errores };
}

const PRUEBAS = {
  async 'Presencia: burbujas, +N y «N en línea»'(nav) {
    await reiniciar();
    const gente = Array.from({ length: 8 }, (_, i) => ({ email: `p${i}@x`, name: `Persona ${i}`, initials: `p${i}`, color: '#123456', me: i === 0 }));
    await laboratorio({ conectados: gente });
    const { contexto, p, errores } = await abrir(nav);
    await p.locator('#presence .pres-lbl').waitFor({ timeout: 6000 });
    assert.strictEqual(await p.locator('#presence .pres-av').count(), 6);
    assert.strictEqual(await p.locator('#presence .pres-av.me').getAttribute('title'), 'Persona 0 (tú)');
    assert.strictEqual(await p.locator('#presence .pres-av').first().textContent(), 'P0');
    assert.strictEqual(await p.locator('#presence .pres-more').textContent(), '+2');
    assert.strictEqual(await p.locator('#presence .pres-more').getAttribute('title'), 'Persona 6, Persona 7');
    assert.strictEqual(await p.locator('#presence .pres-lbl').textContent(), '8 en línea');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Aviso de versión antigua solo con una versión publicada mayor'(nav) {
    for (const [publicada, aviso] of [['5.0.0', false], ['4.9.4', false], ['5.0.1', true], ['v5.1', true]]) {
      await reiniciar();
      await laboratorio({ publicada });
      const { contexto, p, errores } = await abrir(nav);
      await p.waitForTimeout(800);
      const hay = await p.locator('#ver-banner').count();
      assert.strictEqual(hay > 0, aviso, `publicada ${publicada}`);
      if (aviso) {
        const t = (await p.locator('#ver-banner').textContent()).replace(/\s+/g, ' ').trim();
        assert.strictEqual(t, `\u26A0\uFE0F Estás usando una versión antigua (v5.0.0). Hay disponible v${publicada}. Cierra y vuelve a abrir la aplicación.`);
      }
      await contexto.close();
      assert.deepStrictEqual(errores, []);
    }
  },

  async 'Bienvenida de 3 s con nombre y rol'(nav) {
    await reiniciar();
    const contexto = await nav.newContext({ timezoneId: 'Europe/Madrid', viewport: { width: 1700, height: 1000 } });
    const p = await contexto.newPage();
    const errores = [];
    p.on('pageerror', (e) => errores.push(e.message));
    await p.goto(BASE + '/');
    await p.locator('#welcome-toast.show').waitFor({ state: 'attached', timeout: 8000 });
    assert.strictEqual(await p.locator('#wt-name').textContent(), 'Laboratorio');
    assert.strictEqual(await p.locator('#wt-role').textContent(), 'Administrator access');
    await p.waitForFunction(() => !document.querySelector('#welcome-toast.show'), null, { timeout: 5000 });
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Sin conexión: última copia en solo consulta'(nav) {
    await reiniciar();
    await laboratorio({ copiaSinConexion: '2026-11-04T16:05:00Z' });
    const { contexto, p, errores } = await abrir(nav, '#/' + BANCO);
    const aviso = (await p.locator('#ro-banner').textContent()).replace(/\s+/g, ' ').trim();
    // 16:05 UTC = 17:05 en Madrid (hora local del PC).
    assert.strictEqual(aviso, '\u2708\uFE0F Modo offline \u00B7 solo consulta. Mostrando la última copia local (04/11 17:05). No se guardará nada; al recuperar conexión manda SharePoint.');
    assert.ok(await p.locator('#lock-banner').isVisible(), 'debe verse el candado');
    assert.ok(/Solo lectura/.test(await p.locator('#sp-status').textContent()));
    assert.ok(await p.locator('#tb tr[data-id]').count() > 0, 'la planificación se ve');
    assert.ok(await p.locator('#tb tr[data-id] [data-f="comments"]').first().isDisabled(), 'no se puede editar');
    assert.strictEqual(await p.locator('#presence .pres-av').count(), 0, 'sin presencia sin conexión');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Calendario: Escape y cambio de tamaño lo cierran'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/' + BANCO);
    const fecha = p.locator('#tb tr[data-id] input.datecal:not([disabled])').first();
    await fecha.click();
    await p.locator('.cal-pop').waitFor();
    await p.keyboard.press('Escape');
    await p.locator('.cal-pop').waitFor({ state: 'detached', timeout: 2000 });
    await fecha.click();
    await p.locator('.cal-pop').waitFor();
    await p.setViewportSize({ width: 1600, height: 950 });
    await p.locator('.cal-pop').waitFor({ state: 'detached', timeout: 2000 });
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Sugerencias: lista completa al entrar y valor intacto al salir'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/' + BANCO);
    const fila = p.locator('#tb tr[data-id]').first();
    const id = await fila.getAttribute('data-id');
    const campo = fila.locator('[data-f="project"]');
    const valor = await campo.inputValue();
    assert.ok(valor, 'el ensayo de prueba debe tener proyecto');
    await campo.focus();
    assert.strictEqual(await campo.inputValue(), '');
    assert.strictEqual(await campo.getAttribute('placeholder'), valor);
    await p.locator('body').click({ position: { x: 5, y: 5 } });
    assert.strictEqual(await campo.inputValue(), valor);
    assert.strictEqual(await campo.getAttribute('placeholder'), 'project...');
    await p.waitForTimeout(1800);
    const enApi = (await json('/api/v1/planificacion')).cuerpo.bancos[BANCO].find((f) => f.id === id);
    assert.strictEqual(enApi.datos.project, valor, 'no se guarda nada');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Gantt de banco: arrastrar el tirador y doble clic para restablecer'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/' + BANCO);
    const tirador = p.locator('#ganttbox .g-lwgrip');
    const ancho = () => p.locator('#ganttbox').evaluate((e) => getComputedStyle(e).getPropertyValue('--lw').trim());
    const inicial = await ancho();
    const caja = await tirador.boundingBox();
    await p.mouse.move(caja.x + 4, caja.y + 20);
    await p.mouse.down();
    await p.mouse.move(caja.x + 84, caja.y + 20, { steps: 5 });
    await p.mouse.up();
    assert.notStrictEqual(await ancho(), inicial, 'el arrastre cambia el ancho');
    await tirador.dblclick();
    assert.strictEqual(await ancho(), inicial, 'doble clic vuelve al ancho por defecto');
    await contexto.close();
    assert.deepStrictEqual(errores, []);
  },

  async 'Users: los Admin salen los primeros (D29)'(nav) {
    await reiniciar();
    const { contexto, p, errores } = await abrir(nav, '#/__users__');
    await p.locator('table.users-tbl tbody tr td').first().waitFor();
    const roles = await p.locator('table.users-tbl tbody tr').evaluateAll((filas) => filas.map((f) => {
      const s = f.querySelector('select'); return s ? s.value : (f.children[2]?.textContent || '').trim();
    }));
    const orden = roles.map((r) => ({ admin: 0, editor: 1, readonly: 2 }[String(r).toLowerCase().replace(/[^a-z]/g, '')] ?? 3));
    assert.ok(orden.length > 2 && orden[0] === 0, `el primero debe ser Admin: ${roles.slice(0, 3)}`);
    assert.deepStrictEqual(orden, [...orden].sort((a, b) => a - b), 'ordenados por rol');
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
    await laboratorio({ publicada: null, conectados: null, copiaSinConexion: null }).catch(() => undefined);
    await navegador.close();
  }
  console.log(`\n${bien}/${nombres.length} correctas`);
  process.exit(bien === nombres.length ? 0 : 1);
})();
