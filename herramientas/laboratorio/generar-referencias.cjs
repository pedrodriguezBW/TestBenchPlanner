'use strict';
/*
 * Ejecuta todos los casos de casos/*.json en la versión 4.9.4 y guarda el
 * resultado en referencias/<id>.json. Esos ficheros son la «verdad» contra la
 * que se comparará la versión nueva.
 *
 *   TZ=Europe/Madrid node generar-referencias.cjs [filtro]
 */
const fs = require('fs');
const path = require('path');
const { abrirVersionOriginal, cerrarNavegador, leerEscenario } = require('./oraculo-4.9.4.cjs');
const { ejecutarPaso, fotografiar } = require('./controlador-4.9.4.cjs');
const { idsDelEscenario, renombrarIdsNuevos, diferenciasPersistidas } = require('./normalizar.cjs');

const RAIZ = path.resolve(__dirname, '../..');
const CARPETA_CASOS = path.join(__dirname, 'casos');
const CARPETA_REFERENCIAS = path.join(__dirname, 'referencias');

function cargarCasos(filtro) {
  const casos = [];
  for (const fichero of fs.readdirSync(CARPETA_CASOS).filter((f) => f.endsWith('.json')).sort()) {
    for (const caso of JSON.parse(fs.readFileSync(path.join(CARPETA_CASOS, fichero), 'utf8'))) {
      if (!filtro || caso.id.includes(filtro)) casos.push(caso);
    }
  }
  return casos;
}

async function ejecutarCaso(caso, escenarios) {
  if (!escenarios[caso.escenario]) escenarios[caso.escenario] = leerEscenario(path.join(RAIZ, 'datos/escenarios', caso.escenario));
  const escenario = escenarios[caso.escenario];
  const sesion = await abrirVersionOriginal({
    escenario, fecha: caso.fecha, rol: caso.rol || 'admin', aceptarDialogos: caso.aceptarDialogos !== false,
  });
  try {
    const antes = (await fotografiar(sesion, ['persistido'])).persistido;
    sesion.vaciarRegistro();
    for (const paso of caso.pasos || []) await ejecutarPaso(sesion, paso);
    // Por defecto: planificación y cambios. Los casos «foto completa» piden más.
    const apartados = caso.observar || ['planificacion'];
    const foto = await fotografiar(sesion, [...new Set([...apartados, 'persistido'])]);
    const despues = foto.persistido;
    if (!apartados.includes('persistido')) delete foto.persistido;
    foto.cambiosPersistidos = diferenciasPersistidas(antes, despues);
    foto.dialogos = sesion.dialogos.map((d) => d.tipo + ': ' + d.texto);
    // Lo que la app ha mandado a guardar (para comprobar que no escribe de más).
    foto.guardados = sesion.guardados().map((g) => ({
      upserts: (g.upserts || []).map((u) => u.uid), deletes: (g.deletes || []).slice(),
    }));
    foto.otrasEscrituras = sesion.peticiones.filter((p) => /^\/api\/(save_|delete_)/.test(p.url)).map((p) => ({ url: p.url, cuerpo: p.cuerpo }));
    foto.erroresJs = sesion.erroresJs;
    const normalizada = renombrarIdsNuevos(foto, idsDelEscenario(escenario), new Set(Object.keys(despues)));
    // El orden de envío no es observable: se ordena DESPUÉS de renombrar los ids nuevos.
    normalizada.guardados.forEach((g) => { g.upserts.sort(); g.deletes.sort(); });
    return normalizada;
  } finally {
    await sesion.cerrar();
  }
}

(async () => {
  if (process.env.TZ !== 'Europe/Madrid') throw new Error('Lanza con TZ=Europe/Madrid');
  fs.mkdirSync(CARPETA_REFERENCIAS, { recursive: true });
  const casos = cargarCasos(process.argv[2]);
  const escenarios = {};
  let fallos = 0;
  for (const caso of casos) {
    try {
      const foto = await ejecutarCaso(caso, escenarios);
      const salida = { id: caso.id, titulo: caso.titulo, funcionalidad: caso.funcionalidad, fecha: caso.fecha, resultado: foto };
      fs.writeFileSync(path.join(CARPETA_REFERENCIAS, caso.id + '.json'), JSON.stringify(salida, null, 1));
      const aviso = foto.erroresJs.length ? `  (errores JS: ${foto.erroresJs.length})` : '';
      console.log(`ok  ${caso.id}${aviso}`);
    } catch (e) {
      fallos++;
      console.log(`ERR ${caso.id}: ${e.message.split('\n')[0]}`);
    }
  }
  await cerrarNavegador();
  console.log(`\n${casos.length - fallos}/${casos.length} referencias generadas`);
  process.exit(fallos ? 1 : 0);
})();
