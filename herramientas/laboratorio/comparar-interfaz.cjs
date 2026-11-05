'use strict';
/*
 * PARIDAD DE LA INTERFAZ: ejecuta los casos del laboratorio sobre la interfaz
 * React (pulsando, escribiendo y arrastrando como un usuario) y compara el
 * resultado con las referencias de la 4.9.4.
 *
 *   TZ=Europe/Madrid node comparar-interfaz.cjs [--url http://127.0.0.1:5001] [filtro]
 *
 * El backend debe estar en modo LOCAL (tiene /api/v1/local/reinicio). Sirve:
 *   - el backend Java:  mvnw spring-boot:run (con la interfaz compilada en frontend/dist)
 *   - o el simulado:    node backend-simulado-4.9.4.cjs
 *
 * Se comparan los apartados que dependen de la interfaz: planificación, orden,
 * cambios guardados (DataJson) y ventana de cierre. Los KPI y estadísticas ya
 * los cubre la paridad del backend. Solo se admiten las diferencias de
 * cambios-aprobados.json.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { ejecutarPaso, fotografiar } = require('./controlador-react.cjs');
const { renombrarIdsNuevos, diferenciasPersistidas } = require('./normalizar.cjs');
const { diferencias } = require('./comparar.cjs');

const args = process.argv.slice(2);
const iUrl = args.indexOf('--url');
const BASE = iUrl >= 0 ? args.splice(iUrl, 2)[1] : 'http://127.0.0.1:5001';
const FILTRO = args[0] || '';
const CORREO_POR_ROL = { admin: 'usuario01@ejemplo.local', editor: 'usuario07@ejemplo.local', readonly: 'nadie@ejemplo.local' };
const CASOS_DE_INTERFAZ = /^(F|E|S|C|Q|K|R)\d/; // los casos P (foto KPI) no dependen de la interfaz

async function main() {
  if (process.env.TZ !== 'Europe/Madrid') throw new Error('Lanza con TZ=Europe/Madrid');
  const aprobados = JSON.parse(fs.readFileSync(path.join(__dirname, 'cambios-aprobados.json'), 'utf8'));
  const casos = fs.readdirSync(path.join(__dirname, 'casos')).filter((f) => f.endsWith('.json')).sort()
    .flatMap((f) => JSON.parse(fs.readFileSync(path.join(__dirname, 'casos', f), 'utf8')))
    .filter((c) => CASOS_DE_INTERFAZ.test(c.id) && c.id.includes(FILTRO));
  const navegador = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
  const salida = path.join(__dirname, 'resultados-interfaz');
  fs.mkdirSync(salida, { recursive: true });
  let iguales = 0;
  const fallos = [];
  for (const caso of casos) {
    const r = await fetch(BASE + '/api/v1/local/reinicio', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ escenario: caso.escenario, fecha: caso.fecha, email: CORREO_POR_ROL[caso.rol || 'admin'] }),
    });
    if (!r.ok) throw new Error('El backend no está en modo local (no responde /api/v1/local/reinicio)');
    const idsIniciales = new Set(JSON.parse(fs.readFileSync(path.join(__dirname, '../../datos/escenarios', caso.escenario, 'planner.json'), 'utf8')).map((f) => f.uid));
    const contexto = await navegador.newContext({ timezoneId: 'Europe/Madrid', locale: 'es-ES', viewport: { width: 1700, height: 1000 } });
    const pagina = await contexto.newPage();
    const errores = [];
    pagina.on('pageerror', (e) => errores.push(String(e.message || e)));
    pagina.on('dialog', (d) => (d.type() === 'confirm' ? (caso.aceptarDialogos === false ? d.dismiss() : d.accept()) : d.accept()));
    try {
      await pagina.goto(BASE + '/');
      await pagina.waitForFunction(() => /Synced|Solo lectura/.test(document.getElementById('sp-status')?.textContent || ''));
      const antes = (await fotografiar(pagina, BASE)).textos;
      const estado = { pasos: 0 };
      for (const paso of caso.pasos) await ejecutarPaso(pagina, paso, estado);
      const foto = await fotografiar(pagina, BASE);
      const resultado = { planificacion: foto.planificacion, orden: foto.orden, cambiosPersistidos: diferenciasPersistidas(antes, foto.textos), cierre: foto.cierre, erroresJs: errores };
      const normal = renombrarIdsNuevos(resultado, idsIniciales, new Set(Object.keys(foto.textos)));
      fs.writeFileSync(path.join(salida, caso.id + '.json'), JSON.stringify({ id: caso.id, resultado: normal }, null, 1));
      const ref = JSON.parse(fs.readFileSync(path.join(__dirname, 'referencias', caso.id + '.json'), 'utf8')).resultado;
      const difs = [];
      for (const apartado of ['planificacion', 'orden', 'cambiosPersistidos']) {
        if (ref[apartado] !== undefined) difs.push(...diferencias(ref[apartado], normal[apartado], '/' + apartado));
      }
      if (ref.cierre) {
        for (const k of ['abierta', 'arranqueReal', 'finReal', 'estadoFinal', 'motivo', 'diasParada', 'error']) {
          if (k in ref.cierre && String(ref.cierre[k]) !== String(normal.cierre[k])) difs.push({ ruta: '/cierre/' + k, referencia: ref.cierre[k], nueva: normal.cierre[k] });
        }
      }
      if (errores.length) difs.push({ ruta: '/erroresJs', referencia: [], nueva: errores });
      const sinClasificar = difs.filter((d) => !aprobados.some((a) => new RegExp(a.caso).test(caso.id) && new RegExp(a.ruta).test(d.ruta)));
      if (sinClasificar.length) fallos.push({ id: caso.id, difs: sinClasificar });
      else iguales++;
      console.log(`${sinClasificar.length ? '✗' : '✓'} ${caso.id}${sinClasificar.length ? ` (${sinClasificar.length} diferencias)` : ''}`);
    } catch (e) {
      fallos.push({ id: caso.id, difs: [{ ruta: '(ejecución)', referencia: '', nueva: e.message.split('\n')[0] }] });
      console.log(`✗ ${caso.id}: ${e.message.split('\n')[0]}`);
      await pagina.screenshot({ path: path.join(salida, caso.id + '.png') }).catch(() => undefined);
    } finally {
      await contexto.close();
    }
  }
  await navegador.close();
  console.log(`\nIguales a la 4.9.4: ${iguales}/${casos.length}`);
  for (const f of fallos) {
    console.log(`\n✗ ${f.id}`);
    f.difs.slice(0, 8).forEach((d) => console.log(`   ${d.ruta}\n     4.9.4: ${String(JSON.stringify(d.referencia)).slice(0, 200)}\n     nueva: ${String(JSON.stringify(d.nueva)).slice(0, 200)}`));
  }
  process.exit(fallos.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
