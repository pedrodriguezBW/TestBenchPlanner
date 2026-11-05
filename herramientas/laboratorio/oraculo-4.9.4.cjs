'use strict';
/*
 * ORÁCULO de paridad: ejecuta la versión 4.9.4 original (HTML sin tocar) en un
 * Chromium sin ventana y sustituye el backend Python por un doble que «sirve»
 * un escenario de datos y registra todo lo que la aplicación intenta guardar.
 *
 * Es el modo laboratorio del manual técnico (apartado 10.3) llevado un paso más
 * allá: en vez de inyectar el estado y anular fetch, se deja arrancar la app
 * entera (lectura autoritativa, catálogos, usuario…) contra datos conocidos.
 *
 *   - La fecha se fija con el reloj de Playwright (la 4.9.4 calcula TODAY al
 *     cargar y usa temporizadores de 1,2 s, 5 s y 20 s).
 *   - La zona horaria es Europe/Madrid: la app trabaja con fechas locales.
 *   - Ninguna petición sale del navegador: todo /api/* lo responde este fichero.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const RUTA_HTML = path.resolve(__dirname, '../../legacy/BW_TestBench_Planner_31_Local.html');
const BASE = 'http://127.0.0.1:5001';

let navegadorCompartido = null;
async function navegador() {
  if (!navegadorCompartido) {
    navegadorCompartido = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
  }
  return navegadorCompartido;
}
async function cerrarNavegador() {
  if (navegadorCompartido) await navegadorCompartido.close();
  navegadorCompartido = null;
}

/**
 * Abre la 4.9.4 contra un escenario.
 * @param {object} opciones
 * @param {object} opciones.escenario  datos devueltos por leerEscenario()
 * @param {string} opciones.fecha      'aaaa-mm-dd' (hoy para la aplicación)
 * @param {string} [opciones.rol]      admin | editor | readonly
 * @param {boolean} [opciones.aceptarDialogos] respuesta a confirm()
 * @param {object} [opciones.cacheLocal] contenido previo de localStorage (bw_bench_planner_v10)
 */
async function abrirVersionOriginal(opciones) {
  const { escenario, fecha, rol = 'admin', aceptarDialogos = true, cacheLocal = null } = opciones;
  const contexto = await (await navegador()).newContext({ timezoneId: 'Europe/Madrid', locale: 'es-ES', viewport: { width: 1600, height: 900 } });
  const pagina = await contexto.newPage();
  const peticiones = [];
  const dialogos = [];
  const erroresJs = [];
  pagina.on('pageerror', (e) => erroresJs.push(String(e && e.message || e)));
  pagina.on('dialog', async (d) => {
    dialogos.push({ tipo: d.type(), texto: d.message() });
    if (d.type() === 'confirm') await (aceptarDialogos ? d.accept() : d.dismiss());
    else if (d.type() === 'prompt') await d.dismiss();
    else await d.accept();
  });
  // Reloj PARADO: los temporizadores solo avanzan cuando el caso lo pide
  // (avanzar), así el resultado no depende de lo rápido que vaya la máquina.
  await pagina.clock.install({ time: new Date(`${fecha}T09:59:59`) });
  await pagina.clock.pauseAt(new Date(`${fecha}T10:00:00`));
  if (cacheLocal) {
    await pagina.addInitScript((c) => { localStorage.setItem('bw_bench_planner_v10', JSON.stringify(c)); }, cacheLocal);
  }
  await pagina.route('**/*', async (ruta) => {
    const url = new URL(ruta.request().url());
    if (url.origin === BASE && url.pathname === '/') {
      return ruta.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: fs.readFileSync(RUTA_HTML, 'utf8') });
    }
    if (url.origin === BASE && url.pathname.startsWith('/api/')) {
      let cuerpo = null;
      try { cuerpo = JSON.parse(ruta.request().postData() || 'null'); } catch (_) { cuerpo = ruta.request().postData(); }
      peticiones.push({ url: url.pathname, cuerpo });
      const respuesta = responderComoBackend(escenario, url.pathname, cuerpo, rol);
      if (respuesta === undefined) return ruta.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"sin ruta"}' });
      return ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(respuesta) });
    }
    return ruta.abort(); // nada sale a Internet (fuentes, CDN…)
  });
  await pagina.goto(BASE + '/');
  const sesion = {
    pagina, peticiones, dialogos, erroresJs,
    /** Avanza el reloj de la aplicación (temporizadores incluidos). */
    async avanzar(ms) { await pagina.clock.runFor(ms); },
    /** Ejecuta una expresión dentro de la 4.9.4 y devuelve su valor. */
    async evaluar(expresion, arg) { return pagina.evaluate(expresion, arg); },
    /** Guardados enviados a /api/save desde el último vaciado. */
    guardados() { return peticiones.filter((p) => p.url === '/api/save').map((p) => p.cuerpo); },
    vaciarRegistro() { peticiones.length = 0; dialogos.length = 0; },
    async cerrar() { await contexto.close(); },
  };
  await esperarArranque(sesion, escenario);
  return sesion;
}

/**
 * Espera a que termine el arranque y a que el estado sea el estable.
 *
 * La primera lectura descarta los ensayos de bancos que aún no estaban en la
 * caché local (discrepancia D17) y el sondeo de los 20 s los recupera cuando ya
 * han llegado los catálogos. Aquí lanzamos ese sondeo de forma explícita.
 */
async function esperarArranque(sesion, escenario) {
  const pagina = sesion.pagina;
  await pagina.waitForFunction(() => typeof LOADED_OK !== 'undefined' && LOADED_OK === true, null, { timeout: 15000 });
  const bancosEsperados = (escenario.configuracion.benches || []).length;
  if (bancosEsperados) {
    await pagina.waitForFunction((n) => GROUPS.reduce((s, g) => s + g.benches.length, 0) >= n, bancosEsperados, { timeout: 15000 });
  }
  await pagina.evaluate(async () => { await lbPoll(); });
  // Deja terminar las lecturas en segundo plano (usuario, KPI, recursos…).
  await sesion.avanzar(100);
  await pagina.waitForTimeout(200);
}

/**
 * Responde como el backend Python 4.9.4 a partir de un escenario de datos.
 * Las escrituras no se aplican: solo se registran en `peticiones`.
 */
function responderComoBackend(escenario, ruta, cuerpo, rol) {
  const usuario = escenario.usuarioActual || {
    email: 'laboratorio@ejemplo.local', displayName: 'Laboratorio', responsibleName: 'Laboratorio',
  };
  switch (ruta) {
    case '/api/load': return escenario.planner;
    case '/api/load_config': return escenario.configuracion;
    case '/api/load_kpis': return escenario.kpis;
    case '/api/load_resources': return escenario.recursos;
    case '/api/approvals_all': return escenario.aprobaciones;
    case '/api/approvals': {
      const salida = {};
      (cuerpo && cuerpo.ids || []).forEach((id) => {
        const n = (String(id).match(/\d+/) || [])[0];
        const a = n && escenario.aprobaciones.find((x) => String(x.id) === n);
        salida[String(id)] = a
          ? { input: String(id), resolvedId: a.id, status: a.status, color: a.color, raw: a.raw, requester: a.requester, service: a.service }
          : { input: String(id), resolvedId: n ? Number(n) : null, status: 'not_created', color: 'red', raw: null };
      });
      return salida;
    }
    case '/api/whoami': return {
      email: usuario.email, displayName: usuario.displayName, role: rol, responsibleName: usuario.responsibleName,
      isAdmin: rol === 'admin', users: escenario.usuarios,
    };
    case '/api/app_version': return { backend: '4.9.4', published: (escenario.version && escenario.version.published) || '4.9.4' };
    case '/api/presence': return { me: usuario.email, online: [] };
    case '/api/activity_log': return { rows: [], columns: [] };
    case '/api/heartbeat': case '/api/log_event': case '/api/shutdown': return { ok: true };
    case '/api/save': return { ok: true, created: 0, updated: (cuerpo.upserts || []).length, deleted: (cuerpo.deletes || []).length };
    case '/api/save_config': case '/api/save_users': case '/api/save_kpis':
    case '/api/save_resources': case '/api/save_resource_block': case '/api/delete_resource_block':
      return { ok: true };
    case '/api/update_info': return { enabled: false };
    default: return undefined;
  }
}

/** Lee una carpeta de escenario generada por herramientas/importar-sharepoint. */
function leerEscenario(carpeta) {
  const leer = (n) => JSON.parse(fs.readFileSync(path.join(carpeta, n), 'utf8'));
  return {
    planner: leer('planner.json'),
    configuracion: leer('configuracion.json'),
    kpis: leer('kpis.json'),
    usuarios: leer('usuarios.json'),
    recursos: leer('recursos.json'),
    aprobaciones: leer('aprobaciones.json'),
    version: leer('version.json'),
  };
}

/** Lee una carpeta de escenario generada por herramientas/importar-sharepoint. */
function leerEscenario(carpeta) {
  const leer = (n) => JSON.parse(fs.readFileSync(path.join(carpeta, n), 'utf8'));
  return {
    planner: leer('planner.json'),
    configuracion: leer('configuracion.json'),
    kpis: leer('kpis.json'),
    usuarios: leer('usuarios.json'),
    recursos: leer('recursos.json'),
    aprobaciones: leer('aprobaciones.json'),
    version: leer('version.json'),
  };
}

module.exports = { abrirVersionOriginal, cerrarNavegador, leerEscenario, responderComoBackend };
