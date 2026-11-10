import type { EstadoLinea, EstadoPeriodo } from '../../api/facturacion';

/*
 * Textos y formatos de PRESENTACIÓN de la facturación. Las reglas (horas,
 * tarifas, incidencias, estados) las calcula el backend.
 */

export const ESTADO_PERIODO: Record<EstadoPeriodo, { texto: string; icono: string; clase: string }> = {
  BORRADOR: { texto: 'Borrador', icono: '\u270E', clase: 'fac-p-borrador' },
  EN_REVISION: { texto: 'En revisión', icono: '\uD83D\uDD0E', clase: 'fac-p-revision' },
  APROBADO: { texto: 'Aprobado', icono: '\uD83D\uDD12', clase: 'fac-p-aprobado' },
  EXPORTADO: { texto: 'Exportado', icono: '\uD83D\uDCE4', clase: 'fac-p-exportado' },
};

/** Estados de línea: texto e icono, no solo color. */
export const ESTADO_LINEA: Record<EstadoLinea, { texto: string; icono: string; clase: string; ayuda: string }> = {
  READY: { texto: 'READY', icono: '\u2714', clase: 'fac-l-ready', ayuda: 'Lista para facturar' },
  WARNING: { texto: 'WARNING', icono: '\u26A0', clase: 'fac-l-warning', ayuda: 'Con advertencias' },
  BLOCKED: { texto: 'BLOCKED', icono: '\u26D4', clase: 'fac-l-blocked', ayuda: 'Con errores bloqueantes' },
  EXCLUDED: { texto: 'EXCLUDED', icono: '\u2298', clase: 'fac-l-excluded', ayuda: 'Excluida: visible, no suma' },
  PENDING: { texto: 'PENDING', icono: '\u23F3', clase: 'fac-l-pending', ayuda: 'Planificado, sin arrancar: sin importe' },
  ADJUSTED: { texto: 'ADJUSTED', icono: '\u270E', clase: 'fac-l-adjusted', ayuda: 'Con ajustes manuales' },
  DEFERRED: { texto: 'DEFERRED', icono: '\u23ED', clase: 'fac-l-deferred', ayuda: 'Aplazada al periodo siguiente' },
};

export const NOMBRE_CAMPO: Record<string, string> = {
  horasNetas: 'Horas netas', horasSetup: 'Setup', piezas: 'Piezas', proyecto: 'Proyecto', orden: 'Orden', hv: 'Alta tensión',
  categoria: 'Categoría', coste: 'Coste final', textoControlling: 'Texto de Controlling', tarifa: 'Tarifa', periodoFacturacion: 'Periodo de facturación',
};

export const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

/** 1234.5 → «1.234,50». */
export function numero(v: number | null | undefined, decimales = 2): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '\u2014';
  const negativo = v < 0;
  const [entero, dec] = Math.abs(v).toFixed(decimales).split('.');
  return `${negativo ? '-' : ''}${entero.replace(/\B(?=(\d{3})+(?!\d))/g, '.')}${dec ? ',' + dec : ''}`;
}
export const euros = (v: number | null | undefined) => (v === null || v === undefined ? '\u2014' : `${numero(v)} \u20AC`);
export const horas = (v: number | null | undefined) => (v === null || v === undefined ? '\u2014' : `${numero(v)} h`);
export const tarifaTexto = (v: number | null | undefined, unidad = 'H') => (v === null || v === undefined ? '\u2014' : `${numero(v)} \u20AC/${unidad === 'PIEZAS' ? 'pieza' : 'h'}`);
export const hvTexto = (hv: string | null | undefined) => (hv === 'yes' ? 'Sí' : hv === 'no' ? 'No' : '\u2014');

/** «2026-02-12T10:15» → «12/02/2026 10:15». */
export function fechaHora(t: string | null | undefined): string {
  if (!t) return '\u2014';
  const d = `${t.slice(8, 10)}/${t.slice(5, 7)}/${t.slice(0, 4)}`;
  return t.length > 10 ? `${d} ${t.slice(11, 16)}` : d;
}
export const fechaCorta = (t: string | null | undefined) => (t ? `${t.slice(8, 10)}/${t.slice(5, 7)}` : '\u2014');

/** Duración en horas entre dos «aaaa-mm-ddThh:mm» (solo para mostrar). */
export function duracionHoras(desde: string, hasta: string): number | null {
  const a = Date.parse(`${desde}:00Z`);
  const b = Date.parse(`${hasta}:00Z`);
  if (Number.isNaN(a) || Number.isNaN(b)) return null;
  return (b - a) / 3600000;
}

/** Valor de un ajuste o de un valor calculado para mostrar. */
export function valorTexto(campo: string, v: unknown): string {
  if (v === null || v === undefined || v === '') return '\u2014';
  if (campo === 'hv') return hvTexto(String(v));
  if (campo === 'coste') return euros(Number(v));
  if (campo === 'tarifa') return tarifaTexto(Number(v));
  if (campo === 'horasNetas' || campo === 'horasSetup') return horas(Number(v));
  return String(v);
}

/** Etiqueta de un periodo nominal y sus fechas (21 del mes anterior – 20 del mes). */
export function nombrePeriodo(id: string): string {
  const [a, m] = id.split('-').map(Number);
  return `${MESES[m - 1]} ${a}`;
}
export function fechasPeriodo(anio: number, mes: number): string {
  const anterior = mes === 1 ? [anio - 1, 12] : [anio, mes - 1];
  return `21/${String(anterior[1]).padStart(2, '0')}/${anterior[0]} \u2013 20/${String(mes).padStart(2, '0')}/${anio}`;
}
