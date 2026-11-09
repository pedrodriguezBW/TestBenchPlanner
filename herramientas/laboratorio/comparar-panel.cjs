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
  // Las ventanas se leen aparte (en la 4.9.4 cuelgan de <body>, no de #main).
  const fuera = (el) => !el.closest('#kpi-fresh') && !el.closest('.modal-bg');
  return {
    titulos: [...main.querySelectorAll('h2, h3')].filter(fuera).map(t),
    org: [...main.querySelectorAll('select')].filter(fuera).map((s) => s.value),
    opcionesResponsable: [...main.querySelectorAll('select')].filter((s) => [...s.options].some((o) => o.text === 'Todos')).map((s) => [...s.options].map((o) => o.text)),
    fechas: [...main.querySelectorAll('input[type=date]')].filter(fuera).map((i) => i.value),
    botones: [...main.querySelectorAll('button')].filter(fuera).map(t),
    hints: [...main.querySelectorAll('.hint')].filter(fuera).map(t),
    vacios: [...main.querySelectorAll('.empty')].filter(fuera).map(t),
    kpis: [...main.querySelectorAll('.kpi')].map((k) => [t(k.querySelector('.n')), t(k.querySelector('.l')), k.className]),
    tablas: [...main.querySelectorAll('table')].filter(fuera).map((tb) => ({
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

/** Ventanas abiertas y aviso flotante de recursos. */
function extraerVentanas() {
  const t = (el) => (el ? el.textContent.replace(/\s+/g, ' ').trim() : null);
  return {
    ventanas: [...document.querySelectorAll('.modal-bg.show .modal')].map((m) => ({
      titulo: t(m.querySelector('h3')),
      sub: t(m.querySelector('#resm-sub')),
      filas: [...m.querySelectorAll('tbody tr')].map((tr) => [...tr.children].map(t)),
      grupos: [...m.querySelectorAll('#resm-list > div > div, #resm-list > div[style]')].map(t).filter(Boolean),
      vacio: t(m.querySelector('.empty')),
      campos: [...m.querySelectorAll('select, input')].filter((i) => i.id).map((i) => [i.id, i.value]),
    })),
    aviso: document.querySelector('#res-toast.show') ? t(document.querySelector('#res-toast')) : null,
  };
}

/** Celda «Resources» de cada fila del banco abierto (texto y aviso «!»). */
function extraerCeldasRecursos() {
  const t = (el) => (el ? el.textContent.replace(/\s+/g, ' ').trim() : null);
  return [...document.querySelectorAll('#tb tr[data-id]')].map((tr) => {
    const b = tr.querySelector('button[title="Elegir recursos"]');
    const td = b && b.closest('td');
    const alerta = td && td.querySelector('.res-alert-badge');
    return [tr.dataset.id, td ? t(td) : null, alerta ? alerta.title : null];
  });
}

/** Valores de las cajas de texto y casillas de #main (pantallas de configuración). */
function extraerEntradas() {
  const main = document.getElementById('main');
  return [...main.querySelectorAll('input:not([type=date]):not([type=file]), textarea')].filter((i) => !i.closest('.modal-bg'))
    .map((i) => (i.type === 'checkbox' ? i.checked : i.type === 'color' ? i.value.toLowerCase() : i.value));
}

/** Lo que se lee en cada tipo de pantalla (se envía a la página como texto). */
/**
 * Bloque F: se interceptan las descargas (enlace con «download» sobre un Blob) y
 * la matriz que se envía para generar el Excel, igual en las dos interfaces.
 */
function instalarCapturas() {
  if (window.__capturas) return;
  window.__capturas = [];
  const fetchOriginal = window.fetch;
  window.fetch = function (url, opciones) {
    if (/export_gantt|exportaciones\/gantt/.test(String(url)) && opciones && opciones.body) window.__capturas.push({ tipo: 'excel', matriz: JSON.parse(opciones.body) });
    return fetchOriginal.apply(this, arguments);
  };
  const crearUrl = URL.createObjectURL.bind(URL);
  const blobs = new Map();
  URL.createObjectURL = (b) => { const u = crearUrl(b); blobs.set(u, b); return u; };
  const clicOriginal = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download && blobs.has(this.href)) { window.__capturas.push({ tipo: 'descarga', nombre: this.download, blob: blobs.get(this.href) }); return; }
    return clicOriginal.apply(this, arguments);
  };
}

/** Lo descargado, legible para comparar (el contenido del Excel se prueba aparte). */
async function leerCapturas() {
  const salida = [];
  for (const c of window.__capturas || []) {
    if (c.tipo === 'excel') { salida.push(c); continue; }
    const d = { tipo: 'descarga', nombre: c.nombre, mime: c.blob.type.split(';')[0] };
    if (!/sheet/.test(c.blob.type)) {
      const bytes = new Uint8Array(await c.blob.arrayBuffer());
      d.bom = bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf;
      d.texto = new TextDecoder().decode(bytes.subarray(d.bom ? 3 : 0));
    }
    salida.push(d);
  }
  return salida;
}

/**
 * Normaliza lo descargado para compararlo:
 *  - JSON de copia: se compara el objeto (D32: la nueva no guarda los campos de cálculo «_start»...).
 *  - HTML de solo lectura: solo lo que se incrusta (estado, base de IDs, filas de IDs) y el
 *    nombre; el resto es la propia página 4.9.4 (D31).
 */
const CAMPOS_DE_CALCULO = ['_ovl', '_start', '_end', '_planStart', '_planEnd', '_confirmed', '_startWhy', '_bench', '_seq'];
function normalizarCapturas(capturas) {
  const sinCalculo = (estado) => {
    Object.values(estado.benches || {}).forEach((filas) => filas.forEach((f) => CAMPOS_DE_CALCULO.forEach((k) => delete f[k])));
    return estado;
  };
  return capturas.map((c) => {
    if (c.tipo !== 'descarga' || c.texto === undefined) return c;
    const d = Object.assign({}, c);
    if (/\.json$/.test(c.nombre)) {
      // El nombre lleva la fecha del reloj del navegador (no la del escenario).
      d.nombre = c.nombre.replace(/\d{4}-\d{2}-\d{2}/, 'AAAA-MM-DD'); d.objeto = sinCalculo(JSON.parse(c.texto)); d.formatoDosEspacios = /^\{\n  "/.test(c.texto); delete d.texto;
    }
    if (/\.html$/.test(c.nombre)) {
      const m = c.texto.match(/<script>window\.__READONLY__=true;window\.__EMBEDDED_STATE__=(.*?);window\.__EMBEDDED_IDDB__=(.*?);window\.__EMBEDDED_IDROWS__=(.*?);<\/script>\n<!--BW_APP_MARKER-->/s);
      d.incrustado = m ? { estado: sinCalculo(JSON.parse(m[1])), idDb: JSON.parse(m[2]), filasId: JSON.parse(m[3]).length } : null;
      d.empiezaPorDoctype = c.texto.startsWith('<!DOCTYPE html>');
      delete d.texto;
    }
    return d;
  });
}

function guionFoto(pantalla) {
  const f = { extraer, extraerSeccion, extraerVentanas, extraerCeldasRecursos, extraerEntradas };
  const defs = Object.entries(f).map(([n, fn]) => `const ${n} = ${fn.toString()};`).join('\n');
  const cuerpo = {
    kpi: 'return extraerSeccion();',
    recursos: 'return Object.assign({}, extraer(), { seccion: extraerSeccion() }, extraerVentanas());',
    ventana: 'return Object.assign({ celdas: extraerCeldasRecursos() }, extraerVentanas());',
    config: 'return Object.assign({ entradas: extraerEntradas(), menu: [...document.querySelectorAll("#side .tab")].map((t) => t.textContent.trim()) }, extraerSeccion(), extraerVentanas());',
  }[pantalla] || 'return extraer();';
  return `(() => { ${defs}\n${cuerpo} })()`;
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
    case 'abrirRecursos':
      await pagina.locator(`#tb tr[data-id="${paso.ensayo}"] button[title="Elegir recursos"]`).click();
      break;
    case 'pestanaRecursos':
      await pagina.locator(paso.valor === 'busy' ? '#resm-tab-busy' : '#resm-tab-free').click();
      break;
    case 'buscarRecurso':
      await pagina.locator('#resm-search').fill(paso.valor);
      break;
    case 'alternarRecurso':
      await pagina.locator('#resm-list tr').filter({ has: pagina.locator('td:first-child b', { hasText: new RegExp(`^${paso.recurso}$`) }) })
        .locator('td:last-child button').click();
      break;
    case 'cerrarVentana':
      await pagina.locator('.modal-bg.show .modal').getByRole('button', { name: paso.boton || 'Cerrar', exact: true }).last().click();
      break;
    case 'bloquear':
      await main.getByRole('button', { name: /Bloquear recurso/ }).click();
      await avanzar();
      if (paso.recurso) await pagina.locator('#blk-res').selectOption(paso.recurso);
      if (paso.desde) await pagina.locator('#blk-start').fill(paso.desde);
      if (paso.hasta) await pagina.locator('#blk-end').fill(paso.hasta);
      if (paso.motivo) await pagina.locator('#blk-reason').selectOption(paso.motivo);
      if (paso.nota) await pagina.locator('#blk-note').fill(paso.nota);
      if (paso.guardar !== false) await pagina.getByRole('button', { name: 'Guardar bloqueo' }).click();
      break;
    case 'borrarBloqueo':
      await main.locator('table.lst tbody tr').nth(paso.indice || 0).locator('button.del').click();
      break;
    case 'zoomRecursos':
      await main.locator('.week-nav button', { hasText: /Zoom to week|Full year/ }).click();
      break;
    case 'equipo': {
      // Equipments List: cajas de la fila del equipo (nombre, código) y Org.
      const fila = main.locator('table.lst tbody tr').filter({ has: pagina.locator(`input[value="${paso.nombre}"]`) }).first();
      const cajas = fila.locator('input');
      if (paso.nuevoNombre !== undefined) { await cajas.nth(0).fill(paso.nuevoNombre); await cajas.nth(0).press('Tab'); }
      if (paso.codigo !== undefined) { await cajas.nth(1).fill(paso.codigo); await cajas.nth(1).press('Tab'); }
      if (paso.org !== undefined) await fila.locator('select').selectOption(paso.org);
      if (paso.borrar) await fila.locator('button.del').click();
      break;
    }
    case 'nuevoEquipo': {
      const caja = main.locator(`input[placeholder="Nuevo equipo en ${paso.tipo}..."]`);
      await caja.fill(paso.nombre);
      await caja.locator('xpath=following-sibling::button').click();
      break;
    }
    case 'nuevoTipo':
      await main.locator('#newrestype').fill(paso.nombre);
      await main.getByRole('button', { name: /Añadir tipo/ }).click();
      break;
    // ---- Configuración (bloque E) ----
    case 'cfgCategoriaNueva':
      await main.locator('#newcat').fill(paso.nombre);
      await main.getByRole('button', { name: /Add category/ }).click();
      break;
    case 'cfgBanco': {
      const fila = main.locator('table.lst tbody tr').filter({ has: pagina.locator(`input[value="${paso.banco}"]`) }).first();
      if (paso.nuevo !== undefined) { const c = fila.locator('input').first(); await c.fill(paso.nuevo); await c.press('Tab'); }
      if (paso.org !== undefined) await fila.locator('select').first().selectOption(paso.org);
      if (paso.categoria !== undefined) await fila.locator('select').nth(1).selectOption({ label: paso.categoria });
      if (paso.subir) await fila.getByRole('button', { name: '\u25B2' }).click();
      if (paso.bajar) await fila.getByRole('button', { name: '\u25BC' }).click();
      if (paso.borrar) await fila.locator('button.del').click();
      break;
    }
    case 'cfgBancoNuevo': {
      const caja = main.locator(`input[placeholder="New bench in ${paso.categoria}..."]`);
      await caja.fill(paso.banco);
      await caja.locator('xpath=following-sibling::button').click();
      break;
    }
    case 'cfgCategoria': {
      const cat = paso.indice !== undefined ? main.locator('.cfg-cat').nth(paso.indice)
        : main.locator('.cfg-cat').filter({ has: pagina.locator(`.catrow input.f[value="${paso.categoria}"]`) }).first();
      if (paso.nuevo !== undefined) { const c = cat.locator('.catrow input.f'); await c.fill(paso.nuevo); await c.press('Tab'); }
      if (paso.subir) await cat.locator('.catrow').getByRole('button', { name: '\u25B2' }).click();
      if (paso.borrar) await cat.locator('.catrow').getByRole('button', { name: /Delete/ }).click();
      break;
    }
    case 'proyNuevo':
      await main.locator('#proj-new').fill(paso.valor);
      await main.getByRole('button', { name: /^\uFF0B Add$/ }).click();
      break;
    case 'proyBuscar':
      await main.locator('#proj-search').fill(paso.valor);
      break;
    case 'proyPegar':
      await main.locator('#proj-paste').fill(paso.valor);
      await main.getByRole('button', { name: /Add pasted/ }).click();
      break;
    case 'proyBorrar':
      await main.locator('table.lst tbody tr').filter({ has: pagina.locator('td', { hasText: new RegExp(`^${paso.valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }) })
        .first().locator('button.del').click();
      break;
    case 'proyLimpiar':
      await main.getByRole('button', { name: /Clear all/ }).click();
      break;
    case 'usuario':
      if (paso.nombre !== undefined) await main.locator('#u-name').fill(paso.nombre);
      if (paso.email !== undefined) await main.locator('#u-email').fill(paso.email);
      if (paso.rol !== undefined) await main.locator('#u-role').selectOption(paso.rol);
      if (paso.responsable !== undefined) await main.locator('#u-resp').fill(paso.responsable);
      if (paso.guardar) await main.getByRole('button', { name: /Add \/ Update/ }).click();
      break;
    case 'usuarioFila': {
      const fila = main.locator('table.users-tbl tbody tr').filter({ has: pagina.locator('td', { hasText: paso.email }) }).first();
      await fila.locator(paso.accion === 'borrar' ? 'button.del' : 'button.btn').click();
      break;
    }
    case 'abrirCalendario':
      await main.getByRole('button', { name: /Calendar/ }).click();
      break;
    case 'calendario': {
      const v = pagina.locator('.modal-bg.show .modal');
      if (paso.anio !== undefined) await v.locator('#cfg-year').selectOption(String(paso.anio));
      if (paso.saltar !== undefined) await v.locator('#cfg-skip').setChecked(paso.saltar);
      if (paso.fecha !== undefined) {
        await v.locator('#cfg-newd').fill(paso.fecha);
        if (paso.nombre) await v.locator('#cfg-newn').fill(paso.nombre);
        await v.getByRole('button', { name: /Add/ }).click();
      }
      if (paso.borrar !== undefined) await v.locator('#cfg-list .hol-item').nth(paso.borrar).locator('button').click();
      break;
    }
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
    case 'excel':
      await pagina.locator('.g-toolbar button', { hasText: 'Excel' }).first().click();
      break;
    case 'csv':
      await main.getByRole('button', { name: /Exportar CSV/ }).click();
      break;
    case 'exportarJson':
      await main.getByRole('button', { name: /Export \(JSON backup\)/ }).click();
      break;
    case 'importarJson':
      await pagina.locator('#imp').setInputFiles({ name: 'copia.json', mimeType: 'application/json',
        buffer: Buffer.from(typeof paso.contenido === 'string' ? paso.contenido : JSON.stringify(paso.contenido)) });
      break;
    case 'exportarSoloLectura':
      await pagina.getByRole('button', { name: /Export read-only/ }).click();
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
    if (/^UD/.test(caso.id) || caso.esperarRecursos) {
      // La 4.9.4 lee el catálogo de equipos a los 6,5 s de arrancar (v45Init): se deja
      // pasar ese tiempo, como un usuario que lleva un rato con la app abierta.
      await sesion.avanzar(7000);
      await sesion.pagina.waitForFunction(() => RES_LOADED, null, { timeout: 5000 });
      await sesion.evaluar(() => render());
      await avanzar();
    }
    if (caso.capturas) await sesion.pagina.evaluate(instalarCapturas);
    for (const paso of caso.pasos) await pasoComun(sesion.pagina, paso, true, avanzar);
    // La relectura de la 4.9.4 puede redibujar justo ahora: se deja que termine
    // (sus herramientas del Gantt se añaden en un temporizador).
    for (let i = 0; i < 12; i++) {
      await sesion.pagina.waitForTimeout(150);
      await avanzar();
      const listo = await sesion.pagina.evaluate(() => !document.getElementById('ganttbox') || !!document.querySelector('.g-toolbar'));
      if (listo && i >= 2) break;
    }
    const foto = await sesion.pagina.evaluate(guionFoto(caso.pantalla));
    if (caso.capturas) foto.capturas = normalizarCapturas(await sesion.pagina.evaluate(leerCapturas));
    // D24: la 4.9.4 añade a la lista de responsables diez nombres fijos de su código.
    (foto.opcionesResponsable || []).forEach((l, i) => { foto.opcionesResponsable[i] = l.filter((n) => !NOMBRES_FIJOS_4_9_4.includes(n)); });
    if (foto.responsable) foto.responsable.opciones = foto.responsable.opciones.filter((n) => !NOMBRES_FIJOS_4_9_4.includes(n));
    // D29 (corregido por decisión de Pedro): la 4.9.4 pone a los Admin al final de
    // Users. Se reordena su foto como lo hace la versión nueva (orden estable por rol).
    (foto.tablas || []).forEach((t) => {
      if (JSON.stringify(t.cabecera) !== JSON.stringify(['Name', 'Email', 'Role', 'ResponsibleName', ''])) return;
      const orden = (f) => ({ admin: 0, editor: 1, readonly: 2 }[String(f[2] && f[2][0]).toLowerCase()] ?? 3);
      t.filas = t.filas.map((f, i) => [f, i]).sort((x, y) => (orden(x[0]) - orden(y[0])) || (x[1] - y[1])).map(([f]) => f);
    });
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
  const dialogos = [];
  pagina.on('pageerror', (e) => errores.push(String(e.message || e)));
  // Igual que el oráculo de la 4.9.4: se aceptan confirmaciones y avisos y se anotan.
  pagina.on('dialog', (d) => { dialogos.push(d.message()); void d.accept(); });
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
    if (caso.capturas) await pagina.evaluate(instalarCapturas);
    for (const paso of caso.pasos) await pasoComun(pagina, paso, false, asentar);
    await asentar();
    const foto = await pagina.evaluate(guionFoto(caso.pantalla));
    if (caso.capturas) foto.capturas = normalizarCapturas(await pagina.evaluate(leerCapturas));
    foto.mensajes = [...dialogos, ...await pagina.locator('.tbp-mensaje').allTextContents()];
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
      const original = await ejecutarOriginal(caso, escenario).catch((e) => { throw new Error('[4.9.4] ' + e.message); });
      const nueva = await ejecutarNueva(navegador, caso).catch((e) => { throw new Error('[nueva] ' + e.message); });
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
