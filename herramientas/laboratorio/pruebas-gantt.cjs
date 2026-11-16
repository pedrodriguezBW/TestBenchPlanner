'use strict';
/*
 * GANTT GENERAL REDISEÑADO (doc 09, D40): propuesta A + trazabilidad + tarjetas.
 *
 *   TZ=Europe/Madrid node pruebas-gantt.cjs [--url http://127.0.0.1:5001]
 *
 * Contra el backend en modo local (Java o el simulado) con el escenario de
 * SharePoint del 5-nov. Comprueba en el Dashboard: carriles sin solapes,
 * aviso «N en paralelo», ocupación, color por proyecto y modo «Color: Estado»,
 * categorías plegables (y recordadas), foco con ficha, bancos resaltados y
 * categorías plegadas solas, flechas de trazabilidad, tarjeta al pasar el
 * ratón, tecla F y clic que abre el ensayo en su banco. H9: Gantt a altura
 * libre (sin tirador ni scroll vertical propio, cabecera y barra horizontal
 * flotantes) y divisor del banco que desplaza el Gantt en vez de comprimirlo.
 */
const assert = require('assert');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args[iUrl + 1] : 'http://127.0.0.1:5001';

async function abrir(navegador, fecha = '2026-11-16') {
  const r = await fetch(BASE + '/api/v1/local/reinicio', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ escenario: 'sharepoint-2026-11-05', fecha, email: 'usuario01@ejemplo.local' }),
  });
  if (!r.ok) throw new Error('El backend no está en modo local');
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', locale: 'es-ES', viewport: { width: 1600, height: 1000 } });
  const p = await contexto.newPage();
  p.errores = [];
  p.on('pageerror', (e) => p.errores.push(String(e.message || e)));
  await p.goto(BASE + '/');
  await p.waitForFunction(() => /Synced/.test(document.getElementById('sp-status')?.textContent || ''));
  await p.waitForSelector('#ganttbox .g-bar');
  await p.waitForTimeout(400);
  return { p, cerrar: () => contexto.close() };
}

const PRUEBAS = [
  ['carriles: ninguna barra se pisa con otra en el mismo banco y el aviso «N en paralelo» cuadra', async (p) => {
    const r = await p.evaluate(() => [...document.querySelectorAll('#ganttbox .g-row')].map((fila) => {
      const barras = [...fila.querySelectorAll('.g-bar')].map((b) => ({ x: parseFloat(b.style.left), w: parseFloat(b.style.width), y: parseFloat(b.style.top) }));
      let pisadas = 0;
      barras.forEach((a, i) => barras.forEach((b, j) => { if (i < j && a.y === b.y && a.x < b.x + b.w && b.x < a.x + a.w) pisadas++; }));
      const carriles = new Set(barras.map((b) => b.y)).size;
      const chip = fila.querySelector('.gm-paralelo');
      return { banco: fila.dataset.banco, pisadas, carriles, paralelo: chip ? parseInt(chip.textContent, 10) : 1 };
    }));
    assert.ok(r.length >= 20);
    r.forEach((f) => assert.strictEqual(f.pisadas, 0, `${f.banco}: barras superpuestas en un carril`));
    assert.ok(r.some((f) => f.paralelo > 1), 'hay bancos con ensayos en paralelo');
    r.forEach((f) => assert.ok(f.carriles >= f.paralelo || f.carriles === 0, `${f.banco}: menos carriles que ensayos a la vez`));
  }],
  ['color por proyecto (mismo proyecto = mismo color) y «Color: Estado» vuelve a los colores de la 4.9.4', async (p) => {
    const colores = await p.evaluate(() => {
      const m = {};
      document.querySelectorAll('#ganttbox .g-bar:not(.striped)').forEach((b) => { (m[b.dataset.proj] = m[b.dataset.proj] || new Set()).add(b.style.getPropertyValue('--c')); });
      return Object.entries(m).filter(([k]) => k).map(([k, v]) => [k, v.size]);
    });
    colores.forEach(([k, n]) => assert.strictEqual(n, 1, `${k}: más de un color`));
    assert.ok(await p.locator('.gm-leyenda .gm-chip').count() > 3, 'leyenda de proyectos');
    await p.locator('select.gm-color').selectOption('estado');
    const enCurso = await p.locator('#ganttbox .g-bar.gm-curso').first().evaluate((b) => b.style.getPropertyValue('--c'));
    assert.strictEqual(enCurso.toUpperCase(), '#3DD68C');
    assert.strictEqual(await p.locator('.gm-leyenda .gm-chip').count(), 0);
    await p.locator('select.gm-color').selectOption('proyecto');
  }],
  ['formas de estado: completados con ✓, en cola discontinuos, bloqueos rayados', async (p) => {
    const r = await p.evaluate(() => ({
      hechos: [...document.querySelectorAll('#ganttbox .g-bar.gm-hecho')].every((b) => b.textContent.startsWith('\u2713')),
      cola: getComputedStyle(document.querySelector('#ganttbox .g-bar.gm-cola')).borderTopStyle,
      esp: document.querySelectorAll('#ganttbox .g-bar.gm-esp.striped').length === document.querySelectorAll('#ganttbox .g-bar.gm-esp').length,
      ocup: [...document.querySelectorAll('#ganttbox .gm-ocup')].every((o) => /^\d+%$/.test(o.textContent.trim())),
    }));
    assert.ok(r.hechos); assert.strictEqual(r.cola, 'dashed'); assert.ok(r.esp); assert.ok(r.ocup);
  }],
  ['categorías plegables y recordadas en el navegador', async (p) => {
    const cat = p.locator('#ganttbox .g-cat').first();
    const clave = await cat.getAttribute('data-categoria');
    const filas = await p.evaluate(() => document.querySelectorAll('#ganttbox .g-row.gm-oculta').length);
    await cat.click();
    assert.ok(await p.evaluate(() => document.querySelectorAll('#ganttbox .g-row.gm-oculta').length) > filas);
    await p.reload();
    await p.waitForSelector('#ganttbox .g-cat', { state: 'attached' });
    assert.ok((await p.locator(`#ganttbox .g-cat[data-categoria="${clave}"]`).textContent()).includes('(plegado)'));
    await p.locator(`#ganttbox .g-cat[data-categoria="${clave}"]`).click();
    assert.strictEqual(await p.evaluate(() => document.querySelectorAll('#ganttbox .g-row.gm-oculta').length), 0);
  }],
  ['tarjeta al pasar el ratón y F enfoca el proyecto', async (p) => {
    const barra = p.locator('#ganttbox .g-bar.gm-curso').first();
    await barra.scrollIntoViewIfNeeded();
    const proyecto = await barra.getAttribute('data-proj');
    await barra.hover();
    const tarjeta = p.locator('.gm-tarjeta');
    await tarjeta.waitFor();
    const texto = await tarjeta.textContent();
    assert.ok(texto.includes('Running') && texto.includes('Banco') && texto.includes('Fechas'), texto);
    assert.strictEqual(await barra.getAttribute('title'), null, 'sin tooltip nativo');
    await p.keyboard.press('f');
    await p.waitForTimeout(200);
    assert.strictEqual(await p.locator('.g-focsel').first().inputValue(), proyecto);
    assert.ok(await p.locator('.gm-ficha').isVisible());
  }],
  ['foco: ficha, bancos resaltados, categorías sin el proyecto plegadas y flechas de trazabilidad', async (p) => {
    // El proyecto con más bancos distintos.
    const proyecto = await p.evaluate(() => {
      const m = {};
      document.querySelectorAll('#ganttbox .g-row').forEach((r) => r.querySelectorAll('.g-bar').forEach((b) => { if (b.dataset.proj) (m[b.dataset.proj] = m[b.dataset.proj] || new Set()).add(r.dataset.banco); }));
      return Object.entries(m).sort((a, b) => b[1].size - a[1].size)[0][0];
    });
    await p.locator('.g-focsel').first().selectOption(proyecto);
    await p.waitForTimeout(300);
    const r = await p.evaluate((pr) => {
      const usa = [...document.querySelectorAll('#ganttbox .g-row.gm-usa')].map((x) => x.dataset.banco);
      const conBarras = [...document.querySelectorAll('#ganttbox .g-row')].filter((x) => [...x.querySelectorAll('.g-bar')].some((b) => b.dataset.proj === pr)).map((x) => x.dataset.banco);
      const plegadasSinProyecto = [...document.querySelectorAll('#ganttbox .g-cat.gm-sinfoco')].every((c) => c.textContent.includes('(plegado)'));
      return { usa, conBarras, plegadasSinProyecto, ficha: document.querySelector('.gm-ficha').textContent };
    }, proyecto);
    assert.deepStrictEqual(r.usa.sort(), r.conBarras.sort());
    assert.ok(r.plegadasSinProyecto);
    assert.ok(/\d+ ensayos/.test(r.ficha) && /\d+ bancos/.test(r.ficha) && /completado/.test(r.ficha), r.ficha);
    // Flechas: todas si son pocas; si son muchas, las del ensayo bajo el ratón.
    const pasos = parseInt((r.ficha.match(/(\d+) pasos? entre bancos/) || [0, 0])[1], 10);
    assert.ok(pasos > 0, 'el proyecto tiene recorrido entre bancos');
    if (pasos <= 12) assert.strictEqual(await p.locator('.gm-trazas path').count(), pasos);
    else {
      assert.strictEqual(await p.locator('.gm-trazas path').count(), 0);
      const conOrigen = p.locator('#ganttbox .g-bar.g-foc:not(.gm-esp)');
      let dibujadas = 0;
      for (let i = 0; i < Math.min(15, await conOrigen.count()) && !dibujadas; i++) {
        await conOrigen.nth(i).scrollIntoViewIfNeeded();
        await conOrigen.nth(i).hover();
        await p.waitForTimeout(120);
        dibujadas = await p.locator('.gm-trazas path').count();
      }
      assert.ok(dibujadas > 0, 'al pasar el ratón se dibuja el recorrido');
      assert.ok((await p.locator('.gm-tarjeta').textContent()).includes('Viene de'));
    }
    await p.mouse.move(2, 2);
    await p.locator('.gm-traza').click();
    await p.waitForTimeout(150);
    assert.strictEqual(await p.locator('.gm-trazas').count(), 0, 'botón Trazabilidad apaga las flechas');
    await p.locator('.gm-traza').click();
    await p.locator('.gm-ficha-cerrar').click();
    assert.strictEqual(await p.locator('.gm-ficha').count(), 0);
    assert.strictEqual(await p.locator('#ganttbox .g-row.gm-oculta').count(), 0, 'sin foco se despliega todo');
  }],
  ['clic en una barra abre el ensayo en su banco y lo resalta', async (p) => {
    const barra = p.locator('#ganttbox .g-bar.gm-conf, #ganttbox .g-bar.gm-cola').first();
    await barra.scrollIntoViewIfNeeded();
    const banco = await barra.evaluate((b) => b.closest('.g-row').dataset.banco);
    await barra.click();
    await p.waitForFunction((b) => decodeURIComponent(location.hash) === '#/' + b, banco);
    await p.waitForSelector('tr.gm-destello', { timeout: 3000 });
  }],
  ['H9 · Gantt del Dashboard a altura libre: sin tirador, sin scroll vertical propio, cabecera y barra horizontal acompañan a la página', async (p) => {
    const tarjeta = p.locator('.card.rzcard').filter({ has: p.locator('#ganttbox') });
    assert.strictEqual(await tarjeta.evaluate((c) => c.nextElementSibling && c.nextElementSibling.classList.contains('card-rz')), false, 'la tarjeta del Gantt no tiene tirador');
    await p.locator('.zbtn').nth(1).click(); await p.locator('.zbtn').nth(1).click();
    await p.waitForTimeout(300);
    const m = await p.evaluate(() => { const g = document.getElementById('ganttbox'); return { ch: g.clientHeight, sh: g.scrollHeight, cw: g.clientWidth, sw: g.scrollWidth }; });
    assert.ok(m.sh <= m.ch + 1, `el Gantt no hace scroll vertical (${m.sh} > ${m.ch})`);
    assert.ok(m.ch > 900, 'mide todo lo que necesita');
    assert.ok(m.sw > m.cw, 'con zoom desborda en horizontal');
    await p.evaluate(() => { const g = document.getElementById('ganttbox'); const main = g.closest('main'); main.scrollTop += g.getBoundingClientRect().top - main.getBoundingClientRect().top + 250; });
    await p.waitForTimeout(300);
    const r = await p.evaluate(() => { const g = document.getElementById('ganttbox'); const main = g.closest('main');
      return { cab: g.querySelector('.g-months').getBoundingClientRect().top, main: main.getBoundingClientRect().top, barra: !document.querySelector('.gm-hscroll').hidden }; });
    assert.ok(Math.abs(r.cab - r.main) <= 2, `la cabecera queda arriba (${r.cab} vs ${r.main})`);
    assert.ok(r.barra, 'barra horizontal flotante visible');
    await p.evaluate(() => { const b = document.querySelector('.gm-hscroll'); b.scrollLeft = 400; b.dispatchEvent(new Event('scroll')); });
    await p.waitForTimeout(150);
    assert.ok(Math.abs(await p.evaluate(() => document.getElementById('ganttbox').scrollLeft) - 400) <= 2, 'la barra flotante mueve el Gantt');
    await p.evaluate(() => { const main = document.getElementById('ganttbox').closest('main'); main.scrollTop = 0; });
    await p.waitForTimeout(200);
    assert.strictEqual(await p.evaluate(() => document.querySelector('#ganttbox .g-months').style.transform), '', 'arriba del todo la cabecera vuelve a su sitio');
  }],
  ['H9 · banco: ampliar la tabla desplaza el Gantt sin comprimirlo; se recuerda y doble clic lo restablece', async (p) => {
    await p.evaluate(() => { location.hash = '#/' + encodeURIComponent('1500 WC'); });
    await p.waitForSelector('#benchsplit #ganttbox .g-bar');
    await p.waitForTimeout(300);
    const medir = () => p.evaluate(() => { const g = document.getElementById('ganttbox'); return {
      tabla: document.querySelector('.bs-pane.top').getBoundingClientRect().height, alto: g.clientHeight, scroll: g.scrollHeight, arriba: g.getBoundingClientRect().top }; });
    const a = await medir();
    assert.ok(a.scroll <= a.alto + 1, 'el Gantt del banco no hace scroll vertical');
    const d = await p.locator('#bsdiv').boundingBox();
    await p.mouse.move(d.x + d.width / 2, d.y + 6); await p.mouse.down();
    await p.mouse.move(d.x + d.width / 2, d.y + 400, { steps: 8 }); await p.mouse.up();
    await p.waitForTimeout(250);
    const b = await medir();
    assert.ok(b.tabla > a.tabla + 20, `la tabla crece (${a.tabla} → ${b.tabla})`);
    assert.strictEqual(b.alto, a.alto, 'el Gantt conserva su alto');
    assert.ok(Math.abs((b.arriba - a.arriba) - (b.tabla - a.tabla)) <= 2, 'el Gantt baja lo mismo que crece la tabla');
    const guardado = Number(await p.evaluate(() => localStorage.getItem('tbp_alto_tabla_banco')));
    assert.ok(Math.abs(guardado - b.tabla) <= 1, 'alto recordado');
    await p.reload(); await p.waitForSelector('#benchsplit #ganttbox'); await p.waitForTimeout(300);
    assert.ok(Math.abs((await medir()).tabla - b.tabla) <= 1, 'se mantiene al recargar');
    await p.locator('#bsdiv').dblclick(); await p.waitForTimeout(200);
    assert.ok(Math.abs((await medir()).tabla - a.tabla) <= 1, 'doble clic vuelve al alto por defecto');
    assert.strictEqual(await p.evaluate(() => localStorage.getItem('tbp_alto_tabla_banco')), 'null');
  }],
];

(async () => {
  if (process.env.TZ !== 'Europe/Madrid') throw new Error('Lanza con TZ=Europe/Madrid');
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  let bien = 0;
  try {
    for (const [nombre, prueba] of PRUEBAS) {
      const s = await abrir(navegador);
      try {
        await prueba(s.p);
        assert.deepStrictEqual(s.p.errores, []);
        bien++;
        console.log('\u2713 ' + nombre);
      } catch (e) {
        console.log('\u2717 ' + nombre + '\n   ' + String(e && e.message || e).split('\n').slice(0, 6).join('\n   '));
      } finally {
        await s.cerrar();
      }
    }
  } finally {
    await navegador.close();
  }
  console.log(`\nGantt rediseñado: ${bien}/${PRUEBAS.length}`);
  process.exit(bien === PRUEBAS.length ? 0 : 1);
})();
