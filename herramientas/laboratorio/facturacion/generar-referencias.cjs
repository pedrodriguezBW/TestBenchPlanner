'use strict';
/*
 * Ejecuta casos-facturacion.json con el motor JavaScript, comprueba las
 * expectativas escritas a mano y guarda referencias-facturacion.json, que es lo
 * que la prueba Java ParidadFacturacionTest debe reproducir exactamente.
 *
 *   node herramientas/laboratorio/facturacion/generar-referencias.cjs
 */
const fs = require('fs');
const path = require('path');
const { ejecutar, aplicarParches } = require('./guion.cjs');

const RAIZ = path.resolve(__dirname, '../../..');
const MAESTROS = path.join(RAIZ, 'backend/src/main/resources/facturacion/maestros-iniciales.json');
const casos = JSON.parse(fs.readFileSync(path.join(__dirname, 'casos-facturacion.json'), 'utf8'));
const maestros = aplicarParches(JSON.parse(fs.readFileSync(MAESTROS, 'utf8')), casos.maestrosComunes);
let fallos = 0;
const referencias = { generado: 'motor.cjs', casos: [] };
for (const caso of casos.casos) {
  const salida = ejecutar(caso, maestros);
  referencias.casos.push({ nombre: caso.nombre, pasos: salida });
  for (const esp of caso.esperado || []) {
    const obs = salida[esp.paso];
    const fallo = (t) => { fallos++; console.log(`✗ ${caso.nombre} paso ${esp.paso}: ${t}`); };
    if (esp.error === true) { if (!obs.error) fallo('se esperaba un error'); continue; }
    if (obs.error) { fallo(`error inesperado: ${obs.error}`); continue; }
    if (esp.estadoPeriodo && obs.resultado.estado !== esp.estadoPeriodo) fallo(`estado ${obs.resultado.estado}`);
    if (esp.alertas && JSON.stringify(obs.alertas) !== JSON.stringify(esp.alertas)) fallo(`alertas ${JSON.stringify(obs.alertas)}`);
    if (!esp.linea) continue;
    const l = obs.resultado.lineas.find((x) => x.id === esp.linea);
    if (esp.ausente) { if (l) fallo(`${esp.linea} no debería aparecer`); continue; }
    if (!l) { fallo(`falta ${esp.linea}`); continue; }
    const comp = (k, v) => { if (v !== undefined && Math.abs(Number(v) - Number(k === 'coste' ? Math.round(l.aplicado.coste * 100) / 100 : (l.aplicado && k in l.aplicado && k !== 'horasSetup' && k !== 'horasNetas' ? l.aplicado[k] : l[k]))) > 1e-6) fallo(`${esp.linea} ${k}=${JSON.stringify(k === 'coste' ? l.aplicado.coste : l[k])} (esperado ${v})`); };
    ['horasNetas', 'horasSetup', 'tarifa', 'coste', 'minutosBrutos', 'minutosFinDeSemana', 'minutosParadas'].forEach((k) => comp(k, esp[k]));
    if (esp.estado && l.estado !== esp.estado) fallo(`${esp.linea} estado ${l.estado} (esperado ${esp.estado}) ${l.incidencias}`);
    if (esp.incidencia && !l.incidencias.includes(esp.incidencia)) fallo(`${esp.linea} sin ${esp.incidencia}: ${l.incidencias}`);
  }
}
fs.writeFileSync(path.join(__dirname, 'referencias-facturacion.json'), JSON.stringify(referencias, null, 1) + '\n');
console.log(fallos ? `${fallos} expectativa(s) no cumplidas` : `${casos.casos.length} casos: todas las expectativas se cumplen`);
process.exit(fallos ? 1 : 0);
