/*
 * Utilidades de PRESENTACIÓN de fechas. No contienen reglas de negocio: solo
 * convierten textos aaaa-mm-dd en días para dibujar y les dan formato con el
 * mismo estilo que la 4.9.4 («05 Nov 26»).
 */

/** Día a partir de un texto aaaa-mm-dd (o un instante ISO), a medianoche local. */
export function dia(texto: string | null | undefined): Date | null {
  if (!texto) return null;
  const solo = /^(\d{4})-(\d{2})-(\d{2})$/.exec(texto);
  const fecha = solo ? new Date(+solo[1], +solo[2] - 1, +solo[3]) : new Date(texto);
  if (Number.isNaN(fecha.getTime())) return null;
  fecha.setHours(0, 0, 0, 0);
  return fecha;
}

export function iso(fecha: Date): string {
  return `${fecha.getFullYear()}-${String(fecha.getMonth() + 1).padStart(2, '0')}-${String(fecha.getDate()).padStart(2, '0')}`;
}

/** Formato corto de la 4.9.4: «05 Nov 26»; «—» si no hay fecha. */
export function fmt(entrada: string | Date | null | undefined): string {
  const fecha = entrada instanceof Date ? entrada : dia(entrada ?? null);
  return fecha ? fecha.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: '2-digit' }) : '\u2014';
}

export function sumarDias(fecha: Date, dias: number): Date {
  const nueva = new Date(fecha);
  nueva.setDate(nueva.getDate() + dias);
  return nueva;
}

export function diasEntre(desde: Date, hasta: Date): number {
  return Math.round((hasta.getTime() - desde.getTime()) / 86400000);
}

export function lunesDe(fecha: Date): Date {
  return sumarDias(fecha, -((fecha.getDay() + 6) % 7));
}

export function esFinDeSemana(fecha: Date): boolean {
  return fecha.getDay() === 0 || fecha.getDay() === 6;
}

/** Semana ISO, para la columna CW del calendario y la cabecera del Gantt. */
export function semanaIso(fecha: Date): number {
  const jueves = sumarDias(fecha, 3 - ((fecha.getDay() + 6) % 7));
  const primeroEnero = new Date(jueves.getFullYear(), 0, 4);
  return 1 + Math.round((diasEntre(primeroEnero, jueves) - 3 + ((primeroEnero.getDay() + 6) % 7)) / 7);
}
