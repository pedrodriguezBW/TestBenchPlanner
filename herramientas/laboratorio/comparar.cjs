'use strict';
/*
 * Compara las fotos de la versión nueva con las referencias de la 4.9.4.
 *
 *   node comparar.cjs <carpeta_resultados> [carpeta_referencias]
 *
 * Cada diferencia se clasifica:
 *   - «aprobada»: está en cambios-aprobados.json (cambio de comportamiento
 *     aprobado y ligado a una discrepancia, p. ej. D1 o D8).
 *   - «sin clasificar»: cualquier otra. Hace fallar la comparación.
 * Nunca se ajusta una referencia para que una comparación pase.
 */
const fs = require('fs');
const path = require('path');

function diferencias(a, b, ruta = '', salida = []) {
  if (JSON.stringify(a) === JSON.stringify(b)) return salida;
  const esObjeto = (x) => x && typeof x === 'object';
  if (esObjeto(a) && esObjeto(b) && Array.isArray(a) === Array.isArray(b)) {
    const claves = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of claves) diferencias(a[k], b[k], ruta + '/' + k, salida);
    return salida;
  }
  salida.push({ ruta, referencia: a, nueva: b });
  return salida;
}

function cargarAprobados() {
  const fichero = path.join(__dirname, 'cambios-aprobados.json');
  if (!fs.existsSync(fichero)) return [];
  return JSON.parse(fs.readFileSync(fichero, 'utf8'));
}

function comparar(carpetaNueva, carpetaReferencia) {
  const aprobados = cargarAprobados();
  let casosIguales = 0; let casosConAprobadas = 0; const fallos = [];
  for (const fichero of fs.readdirSync(carpetaReferencia).filter((f) => f.endsWith('.json')).sort()) {
    const id = fichero.replace(/\.json$/, '');
    const rutaNueva = path.join(carpetaNueva, fichero);
    if (!fs.existsSync(rutaNueva)) { fallos.push({ id, motivo: 'sin resultado en la versión nueva' }); continue; }
    const ref = JSON.parse(fs.readFileSync(path.join(carpetaReferencia, fichero), 'utf8')).resultado;
    const nueva = JSON.parse(fs.readFileSync(rutaNueva, 'utf8')).resultado;
    const difs = diferencias(ref, nueva);
    const sinClasificar = difs.filter((d) => !aprobados.some((a) => new RegExp(a.caso).test(id) && new RegExp(a.ruta).test(d.ruta)));
    if (!difs.length) casosIguales++;
    else if (!sinClasificar.length) casosConAprobadas++;
    else fallos.push({ id, diferencias: sinClasificar.slice(0, 20), total: sinClasificar.length });
  }
  return { casosIguales, casosConAprobadas, fallos };
}

if (require.main === module) {
  const [nueva, referencia = path.join(__dirname, 'referencias')] = process.argv.slice(2);
  if (!nueva) { console.error('Uso: node comparar.cjs <carpeta_resultados> [carpeta_referencias]'); process.exit(2); }
  const r = comparar(nueva, referencia);
  console.log(`Iguales: ${r.casosIguales} · Con cambios aprobados: ${r.casosConAprobadas} · Con diferencias sin clasificar: ${r.fallos.length}`);
  for (const f of r.fallos) {
    console.log(`\n✗ ${f.id}${f.motivo ? ': ' + f.motivo : ` (${f.total} diferencias)`}`);
    (f.diferencias || []).forEach((d) => console.log(`   ${d.ruta}\n     4.9.4: ${JSON.stringify(d.referencia)}\n     nueva: ${JSON.stringify(d.nueva)}`));
  }
  process.exit(r.fallos.length ? 1 : 0);
}

module.exports = { comparar, diferencias };
