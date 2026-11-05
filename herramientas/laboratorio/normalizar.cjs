'use strict';
/*
 * Utilidades para que las fotos de dos implementaciones sean comparables.
 *
 * Ids nuevos: los ensayos creados durante un caso reciben un id aleatorio
 * (uid() de la 4.9.4). Se renombran a «nuevo-1», «nuevo-2»… por orden de
 * aparición en la foto, de modo que la comparación no dependa del azar.
 */

/** Ids de ensayo presentes en un escenario (planner.json). */
function idsDelEscenario(escenario) {
  return new Set(escenario.planner.map((e) => e.uid));
}

/**
 * Sustituye en toda la foto los ids que no estaban en el escenario.
 * Solo se tocan cadenas que coinciden EXACTAMENTE con un id nuevo, para no
 * confundir ids con palabras como «running».
 */
function renombrarIdsNuevos(foto, idsIniciales, idsActuales) {
  const nuevos = [...idsActuales].filter((id) => !idsIniciales.has(id));
  if (!nuevos.length) return foto;
  const ordenAparicion = [];
  const texto = JSON.stringify(foto);
  for (const m of texto.matchAll(/"([^"\\]+)"/g)) {
    if (nuevos.includes(m[1]) && !ordenAparicion.includes(m[1])) ordenAparicion.push(m[1]);
  }
  const alias = new Map(ordenAparicion.map((id, i) => [id, 'nuevo-' + (i + 1)]));
  const sustituir = (valor) => {
    if (typeof valor === 'string') {
      if (alias.has(valor)) return alias.get(valor);
      // Dentro del texto DataJson los ids aparecen entre comillas.
      if (valor.startsWith('{')) return valor.replace(/"([^"\\]+)"/g, (m, s) => (alias.has(s) ? '"' + alias.get(s) + '"' : m));
      return valor;
    }
    if (Array.isArray(valor)) return valor.map(sustituir);
    if (valor && typeof valor === 'object') {
      const salida = {};
      for (const [k, v] of Object.entries(valor)) salida[alias.has(k) ? alias.get(k) : k] = sustituir(v);
      return salida;
    }
    return valor;
  };
  return sustituir(foto);
}

/** Diferencias campo a campo entre dos mapas id -> DataJson (texto). */
function diferenciasPersistidas(antes, despues) {
  const cambios = {};
  const ids = new Set([...Object.keys(antes || {}), ...Object.keys(despues || {})]);
  for (const id of ids) {
    const textoAntes = antes ? antes[id] : undefined;
    const textoDespues = despues ? despues[id] : undefined;
    if (textoAntes === textoDespues) continue;
    const a = textoAntes ? JSON.parse(textoAntes) : null;
    const d = textoDespues ? JSON.parse(textoDespues) : null;
    const cambio = { texto: textoDespues === undefined ? null : textoDespues };
    if (!a || !d) { cambio.alta = !a; cambio.baja = !d; cambios[id] = cambio; continue; }
    cambio.campos = {};
    for (const k of new Set([...Object.keys(a), ...Object.keys(d)])) {
      if (JSON.stringify(a[k]) !== JSON.stringify(d[k])) {
        cambio.campos[k] = [a[k] === undefined ? '(sin campo)' : a[k], d[k] === undefined ? '(sin campo)' : d[k]];
      }
    }
    cambios[id] = cambio;
  }
  return cambios;
}

module.exports = { idsDelEscenario, renombrarIdsNuevos, diferenciasPersistidas };
