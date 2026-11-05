import type { CSSProperties } from 'react';
import type { Calculo, DatosEnsayo, EstadoEnsayo, Sugerencia } from '../api/tipos';
import { fmt } from './fechas';

/* Etiquetas, colores y textos de la interfaz, idénticos a los de la 4.9.4. */

export const COLOR_ESTADO: Record<string, string> = {
  queued: '#23BCA3', confirmed: '#4C8DFF', running: '#3DD68C', behind: '#F5A623',
  delayed: '#ff6b6b', completed: '#8CA0AD', risk: '#B98BEA', special: '#6b7785',
};

export function colorEstado(estado: string): string {
  return COLOR_ESTADO[estado] ?? '#8CA0AD';
}

export function colorEstadoTransparente(estado: string, alfa: number): string {
  const hex = colorEstado(estado).replace('#', '');
  return `rgba(${parseInt(hex.slice(0, 2), 16)},${parseInt(hex.slice(2, 4), 16)},${parseInt(hex.slice(4, 6), 16)},${alfa})`;
}

export function etiquetaEstado(estado: EstadoEnsayo | string): string {
  return ({
    queued: 'Queued', confirmed: 'Confirmed', running: 'Running', behind: 'Running Behind Schedule',
    delayed: 'Delayed', completed: 'Completed', risk: 'Risk of Launch/Progress Behind Schedule', special: 'Bench blocked',
  } as Record<string, string>)[estado] ?? estado;
}

/** Estados que se declaran a mano. */
export const ESTADOS_DECLARABLES = [
  { valor: 'ok', etiqueta: 'En plazo' },
  { valor: 'delayed', etiqueta: 'En retraso' },
];

export function etiquetaDeclarado(valor: string): string {
  return ESTADOS_DECLARABLES.find((e) => e.valor === valor)?.etiqueta ?? 'En plazo';
}

/** Motivos de retraso: el valor guardado es estable; la etiqueta puede cambiar. */
export const MOTIVOS_RETRASO = ['', 'Falta de recursos', 'Piezas con retraso', 'Averia', 'Caida de ensayo', 'Cambio de prioridad', 'Otro'];

const ETIQUETA_MOTIVO: Record<string, string> = {
  'Falta de recursos': 'Falta de recursos',
  'Piezas con retraso': 'Piezas con retraso',
  Averia: 'Avería / Mantenimiento no planificado',
  'Caida de ensayo': 'Caída de ensayo',
  'Cambio de prioridad': 'Cambio de prioridad',
  Otro: 'Otro',
};

export function etiquetaMotivo(valor: string): string {
  return valor ? ETIQUETA_MOTIVO[valor] ?? valor : '';
}

export const TIPOS_DE_ENSAYO = ['Eff & dP', 'dP Coolant', 'Thermal Fatigue', 'Pressure Fatigue', 'Burst Test', 'Corrosion', 'Ageing',
  'Type Test', 'K09', 'dT', 'Actuations Durability', 'Calibration', 'Maintenance', 'Breakdown', 'Not available'];

/** Insignia del criterio que fija el inicio (R/A/F/C/H/M) y su explicación. */
export const CRITERIO_INICIO: Record<string, [string, string]> = {
  real: ['R', 'Lo fija el ARRANQUE REAL reportado: el ensayo ya esta en marcha.'],
  asumido: ['A', 'ASUMIDO: su dia de inicio comprometido ya vencio y nadie ha dicho lo contrario, asi que se da por arrancado. Escribe la fecha real si arranco otro dia.'],
  forzado: ['F', 'Inicio forzado a una fecha fija.'],
  cola: ['C', 'Lo fija la COLA: el banco no se libera antes.'],
  hoy: ['H', 'Lo fija HOY: el banco esta libre y no hay restriccion de muestras.'],
  muestras: ['M', 'Lo fijan las MUESTRAS: no hay piezas disponibles antes.'],
};

/** Texto de una sugerencia, como en suggestionsFor de la 4.9.4. */
export function textoSugerencia(s: Sugerencia): string {
  switch (s.clave) {
    case 'noprog': return `Lleva ${s.dias} dias en marcha sin reportar avance.`;
    case 'endlate': return `El fin previsto (${fmt(s.fecha)}) se pasa del comprometido (${fmt(s.referencia)}).`;
    case 'startlate': return `Arranco el ${fmt(s.fecha)}, despues de lo comprometido (${fmt(s.referencia)}).`;
    case 'slow': return `Al ritmo actual terminaria el ${fmt(s.fecha)}, mas tarde de lo planificado (${fmt(s.referencia)}).`;
    default: return '';
  }
}

/** Texto emergente del botón «⚠ En retraso?». */
export function ayudaSugerencias(calculo: Calculo): string {
  const lineas = (calculo.sugerencias ?? []).map((s) => '\u2022 ' + textoSugerencia(s)).join('\n');
  return `${lineas}\n\nLa app sugiere: En retraso. Pulsa para aplicarlo (puedes cambiarlo despues).`;
}

export function unidadDuracion(d: DatosEnsayo): string {
  if (d.durMode === 'cycles') return 'cyc';
  return d.manualUnit === 'hours' ? 'h' : 'd';
}

/** Objetivo de avance para el texto «of N d/cyc» (solo presentación). */
export function objetivoAvance(d: DatosEnsayo): string {
  const valor = d.durMode === 'cycles' ? d.cycles : d.durManual;
  return valor === undefined || valor === '' ? '0' : String(Number(valor) || 0);
}

/** Duración resumida para la lista de cerrados (durLabelOf). */
export function etiquetaDuracion(d: DatosEnsayo): string {
  if (d.durMode === 'cycles') return `${d.cycles || 0} cyc @ ${d.cycleVal || 0}${d.cycleUnit || ''}`;
  return `${d.durManual || 0} ${d.manualUnit === 'hours' ? 'h' : 'd'}`;
}

/** Clase CSS del Approval ID según su estado global (idClass). */
export function claseAprobacion(id: string | undefined, estados: Record<string, string>): string {
  if (!id) return '';
  const estado = estados[String(id)] ?? 'not_created';
  if (estado === 'approved') return 'idok';
  if (estado === 'pending') return 'idwait';
  return 'idbad';
}

export function estiloPildoraAprobacion(clase: string): CSSProperties {
  if (clase === 'idok') return { background: 'rgba(61,214,140,.22)', border: '1px solid var(--ok)', color: '#eafff4' };
  if (clase === 'idwait') return { background: 'rgba(245,166,35,.20)', border: '1px solid var(--warn)', color: '#fff3dc' };
  if (clase === 'idbad') return { background: 'rgba(255,107,107,.20)', border: '1px solid var(--danger)', color: '#ffe3e3' };
  return { color: 'var(--muted)' };
}
