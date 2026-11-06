'use strict';
/*
 * PARIDAD DEL DASHBOARD Y «BY RESPONSIBLE» (bloque B).
 *
 * Cada caso de casos-interfaz/panel.json se ejecuta en las DOS interfaces con
 * los mismos gestos (elegir en desplegables, pulsar botones, escribir semanas)
 * y se compara lo que se ve: bloques KPI, Gantt general (barras, posiciones,
 * colores, textos), gráfica por CW (puntos del SVG), tabla de ocupación,
 * carga del equipo, aviso de IDs y mensajes.
 *
 *   TZ=Europe/Madrid node comparar-panel.cjs [--url http://127.0.0.1:5001] [filtro]
 *
 * La 4.9.4 se ejecuta con el oráculo del laboratorio; la nueva contra el
 * backend en modo local (Java o el simulado). Solo se admiten las diferencias
 * de cambios-aprobados-interfaz.json.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { abrirVersionOriginal, cerrarNavegador, leerEscenario } = require('./oraculo-4.9.4.cjs');
const { diferencias } = require('./comparar.cjs');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args.splice(iUrl, 2)[1] : 'http://127.0.0.1:5001';
const FILTRO = args[0] || '';
const ESCENARIO = 'sharepoint-2026-11-05';
// RESPS_SEED de la 4.9.4 (D24): la versión nueva no los incluye.
const NOMBRES_FIJOS_4_9_4 = ['A. Costal', 'A. Gomez', 'A. Raez', 'A. Vide', 'B. Lopez', 'B. Perez', 'C. Morell', 'F. Bouzas', 'F. Martin', 'J. Lopez'];
const CORREO_POR_ROL = { admin: 'usuario01@ejemplo.local', editor: 'usuario07@ejemplo.local', readonly: 'nadie@ejemplo.local' };

/** Lo que ve el usuario, leído igual en las dos interfaces. */
function extraer() {
  const t = (el) => (el ? el.textContent.replace(/\s+/g, ' ').trim() : null);
  const px = (v) => (v === '' || v == null ? null : Math.round(parseFloat(v) * 100) / 100);
  const main = document.getElementById('main');
  const foto = {};
  const cabeceras = [...main.querySelectorAll('.view-head')];
  foto.titulo = t(main.querySelector('.view-head h2'));
  foto.semana = t(main.querySelector('.week-nav b'));
  const selects = cabeceras.flatMap((c) => [...c.querySelectorAll('select')]);
  const org = selects.find((s) => [...s.options].some((o) => o.text === 'CTAG'));
  foto.org = org ? org.value : null;
  const resp = selects.find((s) => [...s.options].some((o) => o.value === '__manager__'));
  foto.responsable = resp ? { valor: resp.value, opciones: [...resp.options].map((o) => o.text) } : null;
  foto.botonesSemana = [...main.querySelectorAll('.week-nav button')].map(t);
  foto.kpi = [...main.querySelectorAll('.kpi-block')].map((b) => ({ texto: t(b), aviso: b.querySelector('.kpi-pend') ? b.querySelector('.kpi-pend').title : null }));
  foto.alerta = main.querySelector('.resp-alert') ? { texto: t(main.querySelector('.resp-alert')), ok: main.querySelector('.resp-alert').classList.contains('ok') } : null;
  foto.tarjetas = [...main.querySelectorAll('.card.rzcard')].map((c) => {
    const h3 = c.querySelector('h3');
    const hints = [...c.querySelectorAll('.hint')].filter((h) => !h.closest('.g-toolbar')).map(t);
    return { titulo: t(h3), hints, botones: [...c.querySelectorAll('button')].map(t), casillas: [...c.querySelectorAll('input[type=number]')].map((i) => i.value),
      desplegables: [...c.querySelectorAll('select')].filter((s) => !s.classList.contains('g-focsel')).map((s) => s.value) };
  });
  const caja = document.getElementById('ganttbox');
  if (caja) {
    const barra = caja.previousElementSibling && caja.previousElementSibling.classList.contains('g-toolbar') ? caja.previousElementSibling : null;
    const filas = [...caja.querySelectorAll('.g-row')];
    const linea = caja.querySelector('.g-todayline');
    foto.gantt = {
      zoom: barra ? t(barra.querySelector('.zlvl')) : null,
      foco: barra && barra.querySelector('.g-focsel') ? barra.querySelector('.g-focsel').value : null,
      clase: caja.className.split(/\s+/).filter((c) => c === 'g-mini').join(' '),
      lw: getComputedStyle(caja).getPropertyValue('--lw').trim(),
      cabeceras: [...caja.querySelectorAll('.g-months')].map((r) => [...r.children].map(t)),
      dias: caja.querySelector('.g-days') ? [...caja.querySelector('.g-days').children].map((d) => [t(d), d.className.split(/\s+/).filter((c) => ['we', 'hol', 'today'].includes(c)).sort().join(' ')]) : null,
      hoy: linea ? px(linea.style.left) : null,
      filas: filas.map((r) => ({
        banco: t(r.querySelector('.g-label')),
        ancho: px(r.querySelector('.g-track').style.width),
        barras: [...r.querySelectorAll('.g-bar')].map((b) => ({
          x: px(b.style.left), w: px(b.style.width), titulo: b.title, texto: t(b),
          clases: ['solid', 'striped', 'haloq', 'g-dim', 'g-foc'].filter((c) => b.classList.contains(c)),
          fondo: getComputedStyle(b).backgroundColor, opacidad: getComputedStyle(b).opacity, sombra: b.style.boxShadow ? getComputedStyle(b).boxShadow : '',
        })),
        circulos: [...r.querySelectorAll('.g-circ')].map((c) => ({ x: px(c.style.left), tipo: c.classList.contains('s') ? 's' : 'e', color: getComputedStyle(c).borderTopColor })),
      })),
    };
  } else {
    foto.gantt = { vacio: t(main.querySelector('.rzcard .empty')) };
  }
  const envoltorio = document.getElementById('trend-svg-wrap');
  if (envoltorio) {
    const svg = envoltorio.querySelector('svg');
    foto.grafica = {
      ancho: svg.getAttribute('width'),
      leyenda: [...envoltorio.previousElementSibling.children].map((s) => [t(s), getComputedStyle(s).opacity]),
      textos: [...svg.querySelectorAll('text')].map((x) => [t(x), x.getAttribute('x'), x.getAttribute('y')]),
      lineas: [...svg.querySelectorAll('polyline')].map((l) => [l.getAttribute('points'), getComputedStyle(l).stroke]),
      puntos: [...svg.querySelectorAll('circle')].map((c) => t(c.querySelector('title'))),
    };
  }
  const tablas = [...main.querySelectorAll('table.util-full')];
  foto.tablas = tablas.map((tb) => ({
    cabecera: [...tb.querySelectorAll('thead th')].map(t),
    filas: [...tb.querySelectorAll('tbody tr')].map((tr) => [...tr.children].map((td) => {
      const i = td.querySelector('.bar i');
      return i ? [t(td), i.style.width, td.querySelector('.bar').title || ''] : t(td);
    })),
    periodo: tb.closest('.card') && tb.closest('.card').children[1] && !tb.closest('.card').children[1].matches('table') ? t(tb.closest('.card').children[1]) : null,
  }));
  return foto;
}

/** KPI History: todo lo visible de #main salvo la hora de la última lectura. */
function extraerSeccion() {
  const t = (el) => (el ? el.textContent.replace(/\s+/g, ' ').trim() : null);
  const main = document.getElementById('main');
  const fuera = (el) => !el.closest('#kpi-fresh');
  return {
    titulos: [...main.querySelectorAll('h2, h3')].map(t),
    org: [...main.querySelectorAll('select')].map((s) => s.value),
    opcionesResponsable: [...main.querySelectorAll('select')].filter((s) => [...s.options].some((o) => o.text === 'Todos')).map((s) => [...s.options].map((o) => o.text)),
    fechas: [...main.querySelectorAll('input[type=date]')].map((i) => i.value),
    botones: [...main.querySelectorAll('button')].map(t),
    hints: [...main.querySelectorAll('.hint')].filter(fuera).map(t),
    vacios: [...main.querySelectorAll('.empty')].map(t),
    kpis: [...main.querySelectorAll('.kpi')].map((k) => [t(k.querySelector('.n')), t(k.querySelector('.l')), k.className]),
    tablas: [...main.querySelectorAll('table')].map((tb) => ({
      clase: tb.className,
      cabecera: [...tb.querySelectorAll('thead th')].map(t),
      filas: [...tb.querySelectorAll('tbody tr')].map((tr) => [...tr.children].map((td) => {
        const i = td.querySelector('.bar i');
        const color = td.firstElementChild ? getComputedStyle(td.firstElementChild).color : '';
        return [t(td), i ? i.style.width : null, td.title || '', getComputedStyle(td).color, color, getComputedStyle(td).fontWeight];
      })),
    })),
  };
}

async function pasoComun(pagina, paso, esOriginal, avanzar) {
  const main = pagina.locator('#main');
  const tarjeta = (texto) => main.locator('.card.rzcard').filter({ has: pagina.locator('h3', { hasText: texto }) }).first();
  const escribir = async (casilla, valor) => { await casilla.fill(String(valor)); await casilla.press('Tab'); };
  switch (paso.op) {
    case 'org':
      await main.locator('.view-head select').filter({ has: pagina.locator('option', { hasText: /^CTAG$/ }) }).first().selectOption(paso.valor);
      break;
    case 'semana':
      await main.locator('.week-nav button', { hasText: paso.n < 0 ? 'Prev' : 'Next' }).click();
      break;
    case 'hoy':
      await main.locator('.week-nav button', { hasText: 'Today' }).click();
      break;
    case 'zoom':
      await main.locator('.dash-toolbar button', { hasText: /Zoom to week|Full year/ }).first().click();
      break;
    case 'gzoom':
      await main.locator('.g-toolbar .zbtn').nth(paso.boton === 0 ? 0 : paso.boton === 1 ? 1 : 2).click();
      break;
    case 'foco': {
      const sel = main.locator('.g-focsel').first();
      const valor = await sel.evaluate((s, i) => s.options[i].value, paso.indice);
      await sel.selectOption(valor);
      break;
    }
    case 'tendencia': {
      const c = main.locator('.card.rzcard').filter({ has: pagina.locator('#trend-svg-wrap') });
      if (paso.unidad) await c.getByRole('button', { name: paso.unidad, exact: true }).click();
      if (paso.org) { await c.locator('select').selectOption(paso.org); await avanzar(); }
      if (paso.desde) { await escribir(c.locator('input[type=number]').nth(0), paso.desde); await avanzar(); }
      if (paso.hasta) { await escribir(c.locator('input[type=number]').nth(1), paso.hasta); await avanzar(); }
      if (paso.anio) await c.getByRole('button', { name: 'Full year' }).click();
      if (paso.ocultar) await pagina.locator('#trend-svg-wrap').locator('xpath=preceding-sibling::div[1]').locator('span', { hasText: paso.ocultar }).first().click();
      break;
    }
    case 'ocupacion': {
      const c = tarjeta('Ocupación por tipo de banco');
      if (paso.unidad) await c.getByRole('button', { name: paso.unidad, exact: true }).click();
      if (paso.org) { await c.locator('select').selectOption(paso.org); await avanzar(); }
      if (paso.desde) { await escribir(c.locator('input[type=number]').nth(0), paso.desde); await avanzar(); }
      if (paso.hasta) { await escribir(c.locator('input[type=number]').nth(1), paso.hasta); await avanzar(); }
      if (paso.anio) { await tarjeta('Ocupación por tipo de banco').getByRole('button', { name: 'Año' }).click(); await avanzar(); }
      if (paso.semanaDashboard) await tarjeta('Ocupación por tipo de banco').getByRole('button', { name: 'Semana del dashboard' }).click();
      break;
    }
    case 'ir':
      await pagina.locator('.tab', { hasText: paso.vista }).first().click();
      break;
    case 'responsable':
      await main.locator('.view-head select').filter({ has: pagina.locator('option[value="__manager__"]') }).selectOption(paso.valor);
      break;
    case 'kpiOrg':
      await main.locator('select').first().selectOption(paso.valor);
      break;
    case 'estDesde':
    case 'estHasta': {
      const caja = main.locator('input[type=date]').nth(paso.op === 'estDesde' ? 0 : 1);
      await caja.fill(paso.valor);
      await caja.dispatchEvent('change');
      break;
    }
    case 'estRapido':
      await main.getByRole('button', { name: paso.boton, exact: true }).click();
      break;
    case 'estResponsable':
      await main.locator('select').filter({ has: pagina.locator('option', { hasText: /^Todos$/ }) }).selectOption(paso.valor);
      break;
    case 'recargar':
      await main.getByRole('button', { name: /Reload from SharePoint/ }).click();
      break;
    case 'fotoSemanal':
      await main.locator('.week-nav button', { hasText: 'Update Yearly Status' }).click();
      break;
    default:
      throw new Error('Paso desconocido: ' + paso.op);
  }
  await avanzar();
}

async function ejecutarOriginal(caso, escenario) {
  const sesion = await abrirVersionOriginal({ escenario, fecha: caso.fecha, rol: caso.rol || 'admin', aceptarDialogos: true });
  try {
    const avanzar = async () => { await sesion.avanzar(400); };
    await avanzar();
    for (const paso of caso.pasos) await pasoComun(sesion.pagina, paso, true, avanzar);
    // La relectura de la 4.9.4 puede redibujar justo ahora: se deja que termine
    // (sus herramientas del Gantt se añaden en un temporizador).
    for (let i = 0; i < 12; i++) {
      await sesion.pagina.waitForTimeout(150);
      await avanzar();
      const listo = await sesion.pagina.evaluate(() => !document.getElementById('ganttbox') || !!document.querySelector('.g-toolbar'));
      if (listo && i >= 2) break;
    }
    const foto = await sesion.pagina.evaluate(caso.pantalla === 'kpi' ? extraerSeccion : extraer);
    // D24: la 4.9.4 añade a la lista de responsables diez nombres fijos de su código.
    (foto.opcionesResponsable || []).forEach((l, i) => { foto.opcionesResponsable[i] = l.filter((n) => !NOMBRES_FIJOS_4_9_4.includes(n)); });
    if (foto.responsable) foto.responsable.opciones = foto.responsable.opciones.filter((n) => !NOMBRES_FIJOS_4_9_4.includes(n));
    foto.mensajes = sesion.dialogos.map((d) => d.texto);
    foto.erroresJs = sesion.erroresJs;
    return foto;
  } finally {
    await sesion.cerrar();
  }
}

async function ejecutarNueva(navegador, caso) {
  const r = await fetch(BASE + '/api/v1/local/reinicio', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ escenario: ESCENARIO, fecha: caso.fecha, email: CORREO_POR_ROL[caso.rol || 'admin'] }),
  });
  if (!r.ok) throw new Error('El backend no está en modo local');
  const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', locale: 'es-ES', viewport: { width: 1600, height: 900 } });
  const pagina = await contexto.newPage();
  const errores = [];
  pagina.on('pageerror', (e) => errores.push(String(e.message || e)));
  try {
    // Espera a que lleguen los indicadores (peticiones aparte de la vista). No basta
    // con networkidle: con el equipo cargado, una respuesta lenta del backend llega
    // después. Se cuentan las peticiones /api/v1 pendientes y se espera a que no
    // quede ninguna durante un rato.
    let pendientes = 0;
    pagina.on('request', (r) => { if (r.url().includes('/api/v1/')) pendientes++; });
    const fin = (r) => { if (r.url().includes('/api/v1/')) pendientes--; };
    pagina.on('requestfinished', fin);
    pagina.on('requestfailed', fin);
    const asentar = async () => {
      let quieto = 0;
      for (let i = 0; i < 200 && quieto < 4; i++) {
        await pagina.waitForTimeout(100);
        quieto = pendientes === 0 ? quieto + 1 : 0;
      }
      await pagina.waitForTimeout(200);
    };
    await pagina.goto(BASE + '/');
    await pagina.waitForFunction(() => /Synced|Solo lectura/.test(document.getElementById('sp-status')?.textContent || ''));
    await asentar();
    for (const paso of caso.pasos) await pasoComun(pagina, paso, false, asentar);
    await asentar();
    const foto = await pagina.evaluate(caso.pantalla === 'kpi' ? extraerSeccion : extraer);
    foto.mensajes = await pagina.locator('.tbp-mensaje').allTextContents();
    foto.erroresJs = errores;
    return foto;
  } finally {
    await contexto.close();
  }
}

async function main() {
  if (process.env.TZ !== 'Europe/Madrid') throw new Error('Lanza con TZ=Europe/Madrid');
  const casos = fs.readdirSync(path.join(__dirname, 'casos-interfaz')).filter((f) => f.endsWith('.json')).sort()
    .flatMap((f) => JSON.parse(fs.readFileSync(path.join(__dirname, 'casos-interfaz', f), 'utf8')))
    .filter((c) => c.id.includes(FILTRO));
  const aprobados = JSON.parse(fs.readFileSync(path.join(__dirname, 'cambios-aprobados-interfaz.json'), 'utf8'));
  const escenario = leerEscenario(path.resolve(__dirname, '../../datos/escenarios', ESCENARIO));
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--disable-gpu', '--disable-dev-shm-usage'] });
  const salida = path.join(__dirname, 'resultados-interfaz');
  fs.mkdirSync(salida, { recursive: true });
  let iguales = 0;
  const fallos = [];
  for (const caso of casos) {
    try {
      const original = await ejecutarOriginal(caso, escenario);
      const nueva = await ejecutarNueva(navegador, caso);
      fs.writeFileSync(path.join(salida, caso.id + '.json'), JSON.stringify({ original, nueva }, null, 1));
      const difs = diferencias(original, nueva, '').filter((d) => !aprobados.some((a) => new RegExp(a.caso).test(caso.id) && new RegExp(a.ruta).test(d.ruta)
        && (a.referencia === undefined || JSON.stringify(a.referencia) === JSON.stringify(d.referencia))));
      if (difs.length) fallos.push({ id: caso.id, difs }); else iguales++;
      console.log(`${difs.length ? '✗' : '✓'} ${caso.id}${difs.length ? ` (${difs.length} diferencias)` : ''}`);
    } catch (e) {
      fallos.push({ id: caso.id, difs: [{ ruta: '(ejecución)', referencia: '', nueva: e.message.split('\n')[0] }] });
      console.log(`✗ ${caso.id}: ${e.message.split('\n')[0]}`);
    }
  }
  await navegador.close();
  await cerrarNavegador();
  console.log(`\nIguales a la 4.9.4: ${iguales}/${casos.length}`);
  for (const f of fallos) {
    console.log(`\n✗ ${f.id}`);
    f.difs.slice(0, 10).forEach((d) => console.log(`   ${d.ruta}\n     4.9.4: ${String(JSON.stringify(d.referencia)).slice(0, 220)}\n     nueva: ${String(JSON.stringify(d.nueva)).slice(0, 220)}`));
  }
  process.exit(fallos.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
