/*
 * Cálculos del Gantt general rediseñado (propuesta A + trazabilidad de D +
 * tarjetas). Funciones puras, sin React, para poder probarlas aparte.
 *
 * Los tramos se dan en índices de día (0 = primer día visible) y son
 * inclusivos: un ensayo del día 3 al 5 ocupa los días 3, 4 y 5.
 */

export interface Tramo {
  id: string;
  /** Primer día (índice) del tramo. */
  d0: number;
  /** Último día (índice, inclusivo) del tramo. */
  d1: number;
}

/**
 * Carriles de un banco: los ensayos que coinciden en el tiempo se apilan en
 * filas distintas en vez de pintarse uno encima de otro. Reparto voraz por
 * fecha de inicio (cada ensayo va al primer carril libre), que usa tantos
 * carriles como ensayos simultáneos hay como máximo.
 */
export function asignarCarriles(tramos: Tramo[]): { carril: Record<string, number>; carriles: number } {
  const orden = [...tramos].sort((a, b) => a.d0 - b.d0 || a.d1 - b.d1 || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const finCarril: number[] = [];
  const carril: Record<string, number> = {};
  orden.forEach((t) => {
    let i = finCarril.findIndex((fin) => fin < t.d0);
    if (i < 0) { i = finCarril.length; finCarril.push(t.d1); } else finCarril[i] = t.d1;
    carril[t.id] = i;
  });
  return { carril, carriles: Math.max(1, finCarril.length) };
}

/** Máximo de ensayos a la vez en un mismo día (para el aviso «N en paralelo»). */
export function maximoSimultaneo(tramos: Tramo[]): number {
  const eventos: [number, number][] = [];
  tramos.forEach((t) => { eventos.push([t.d0, 1]); eventos.push([t.d1 + 1, -1]); });
  eventos.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let actual = 0, maximo = 0;
  eventos.forEach(([, paso]) => { actual += paso; maximo = Math.max(maximo, actual); });
  return maximo;
}

/**
 * Ocupación del periodo visible: días con al menos un ensayo / días del
 * periodo (0–100, redondeado). Los ensayos solapados no cuentan dos veces.
 * Es una medida rápida para ver bancos saturados o vacíos; la ocupación
 * oficial (horas, fines de semana) sigue siendo la de la tabla del Dashboard.
 */
export function ocupacionPeriodo(tramos: Tramo[], dias: number): number {
  if (dias <= 0) return 0;
  const ocupado = new Uint8Array(dias);
  tramos.forEach((t) => {
    for (let d = Math.max(0, t.d0); d <= Math.min(dias - 1, t.d1); d++) ocupado[d] = 1;
  });
  let total = 0;
  for (let d = 0; d < dias; d++) total += ocupado[d];
  return Math.round((total / dias) * 100);
}

export interface EnsayoTrazable {
  id: string;
  banco: string;
  /** Inicio y fin en milisegundos (o cualquier escala comparable). */
  inicio: number;
  fin: number;
}

export interface Enlace { de: string; a: string }

/**
 * Trazabilidad del proyecto en foco: de dónde viene cada ensayo. Como la app
 * no guarda la muestra de cada ensayo, el recorrido se deduce de las fechas:
 * el origen de un ensayo es el ensayo del mismo proyecto, en OTRO banco, que
 * termina más tarde sin pasar de su inicio (si empatan, el que empezó más
 * tarde). Un ensayo tiene como mucho un origen; un origen puede alimentar a
 * varios ensayos (muestras que se reparten). Con `maxHueco` (misma escala
 * que las fechas) no se enlazan ensayos separados por más tiempo: una muestra
 * no espera meses entre un banco y el siguiente.
 */
export function enlacesTrazabilidad(ensayos: EnsayoTrazable[], maxHueco = Infinity): Enlace[] {
  const enlaces: Enlace[] = [];
  ensayos.forEach((b) => {
    let mejor: EnsayoTrazable | null = null;
    for (const a of ensayos) {
      if (a.id === b.id || a.banco === b.banco || a.fin > b.inicio || a.inicio >= b.inicio || b.inicio - a.fin > maxHueco) continue;
      if (!mejor || a.fin > mejor.fin || (a.fin === mejor.fin && a.inicio > mejor.inicio)) mejor = a;
    }
    if (mejor) enlaces.push({ de: mejor.id, a: b.id });
  });
  return enlaces;
}

/**
 * Tramo visible [d0, d1] de un ensayo dentro de una ventana de `dias` días
 * que empieza en `min`, o null si cae fuera. Fechas como Date a medianoche.
 */
export function tramoVisible(inicio: Date, fin: Date, min: Date, dias: number): { d0: number; d1: number } | null {
  const DIA = 86400000;
  const a = Math.round((inicio.getTime() - min.getTime()) / DIA);
  const b = Math.round((fin.getTime() - min.getTime()) / DIA);
  if (b < 0 || a > dias - 1) return null;
  return { d0: Math.max(0, a), d1: Math.min(dias - 1, b) };
}

/**
 * Flechas que se dibujan: todas si son pocas; si el proyecto tiene muchas,
 * solo las del ensayo bajo el ratón (de dónde viene y adónde va), para que el
 * Gantt no se llene de líneas.
 */
export const MAX_FLECHAS_A_LA_VEZ = 12;
export function flechasVisibles(enlaces: Enlace[], enfocado: string | null): Enlace[] {
  if (enlaces.length <= MAX_FLECHAS_A_LA_VEZ) return enlaces;
  return enfocado ? enlaces.filter((e) => e.de === enfocado || e.a === enfocado) : [];
}
