import { peticion } from './cliente';

/*
 * API de facturación (/api/v1/facturacion): Equipment Types por año y hoja
 * mensual. Misma forma que el backend Java (dominio/facturacion) y que la
 * réplica del laboratorio (herramientas/laboratorio/facturacion).
 */

export type Unidad = 'HORAS' | 'PIEZAS';

/** Una fila de TestBenchEquipmentTypes: un Equipment Type en un año. */
export interface TipoEquipo {
  titulo: string;
  grupo: string;
  anio: number;
  sinHv: number | null;
  conHv: number | null;
  unidad: Unidad;
  regla: string;
}

export interface Regla { codigo: string; nombre: string; calculo: string; unidad: Unidad }

export interface ConfiguracionFacturacion {
  tipos: TipoEquipo[];
  reglas: Regla[];
  grupos: string[];
  productLines: string[];
  bancos: { nombre: string; organizacion: string; equipmentType: string }[];
  /** La lista de Equipment Types no se ha podido leer (falta la lista o alguna columna). */
  error?: string;
}

/** Campos que se pueden anidar en las filas de la hoja, en su orden fijo (HojaMensual.NIVELES). */
export const NIVELES_HOJA: { id: string; nombre: string }[] = [
  { id: 'orden', nombre: 'Billing Order' }, { id: 'planta', nombre: 'Planta' }, { id: 'tipo', nombre: 'Tipo' },
  { id: 'equipo', nombre: 'Equipo' }, { id: 'hv', nombre: 'HV' }, { id: 'inicio', nombre: 'Inicio' },
  { id: 'fin', nombre: 'Fin' }, { id: 'tarifa', nombre: 'Coste/hora' },
];

export interface FilaHoja {
  /** Posición del campo en los niveles elegidos (0 = el primero); niveles.length = «Total general». */
  nivel: number;
  etiqueta: string;
  valores: Record<string, number>;
  total: number;
  incompleta: boolean;
}

export interface TablaHoja { columnas: string[]; niveles: string[]; filas: FilaHoja[] }

export interface LineaHoja {
  banco: string;
  ensayoId: string;
  proyecto: string;
  tipoEnsayo: string;
  requester: string;
  productLine: string;
  orden: string;
  equipmentType: string;
  grupo: string;
  hv: '' | 'yes' | 'no';
  /** Tramo de la tarea dentro del periodo de facturación (lo que se factura). */
  inicio: string;
  fin: string;
  /** Inicio y fin de la tarea completa. */
  inicioTarea: string;
  finTarea: string;
  unidad: Unidad;
  regla: string;
  horasNetas: number;
  horasSetup: number;
  cantidad: number;
  tarifa: number | null;
  coste: number | null;
  completa: boolean;
  faltas: string[];
  avisos: string[];
}

export interface HojaMensual {
  mes: string;
  /** Periodo de facturación del mes: del 21 del mes anterior al 20 del mes. */
  periodoInicio: string;
  periodoFin: string;
  vistaPrevia: TablaHoja;
  exportable: TablaHoja;
  lineas: LineaHoja[];
  incompletas: number;
}

export interface Exportacion { ok: boolean; filename: string; b64: string; excluidas: number }

export const apiFacturacion = {
  tiposEquipo: () => peticion<ConfiguracionFacturacion>('GET', '/api/v1/facturacion/tipos-equipo'),
  guardarTiposEquipo: (tipos: TipoEquipo[]) => peticion<ConfiguracionFacturacion>('PUT', '/api/v1/facturacion/tipos-equipo', { tipos }),
  hoja: (mes: string, niveles: string[]) =>
    peticion<HojaMensual>('GET', `/api/v1/facturacion/hoja?mes=${encodeURIComponent(mes)}&niveles=${parametroNiveles(niveles)}`),
  exportar: (mes: string, formato: 'excel' | 'html', niveles: string[]) =>
    peticion<Exportacion>('GET', `/api/v1/facturacion/hoja/exportar?mes=${encodeURIComponent(mes)}&formato=${formato}&niveles=${parametroNiveles(niveles)}`),
};

/** Ids elegidos para la URL; «-» = ninguno (solo el total). */
const parametroNiveles = (niveles: string[]) => (niveles.length ? encodeURIComponent(niveles.join(',')) : '-');

/** Descarga un archivo recibido en base64. */
export function descargarArchivo(nombre: string, b64: string, tipo: string) {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const url = URL.createObjectURL(new Blob([bytes], { type: tipo }));
  const a = document.createElement('a');
  a.href = url;
  a.download = nombre;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 1234.5 → «1.234,50 €» (como el Excel). */
export function euros(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '';
  const [entero, dec] = Math.abs(v).toFixed(2).split('.');
  return `${v < 0 ? '-' : ''}${entero.replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${dec} \u20ac`;
}
