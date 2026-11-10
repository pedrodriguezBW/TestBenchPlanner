import { peticion } from './cliente';
import type { RespuestaOperacion } from './tipos';

/*
 * Tipos y llamadas de la API de facturación (/api/v1/facturacion). Los
 * documentos tienen la misma forma que en el backend Java (dominio/facturacion)
 * y que la réplica del laboratorio (herramientas/laboratorio/facturacion).
 */

export type EstadoPeriodo = 'BORRADOR' | 'EN_REVISION' | 'APROBADO' | 'EXPORTADO';
export type EstadoLinea = 'READY' | 'WARNING' | 'BLOCKED' | 'EXCLUDED' | 'PENDING' | 'ADJUSTED' | 'DEFERRED';
export type Nivel = 'BLOQUEANTE' | 'ADVERTENCIA';

export interface Parada {
  id?: string;
  from: string;
  to: string;
  reason: string;
  billable: boolean;
  note?: string;
}

/** DataJson.billing de un ensayo. */
export interface DatosFacturacionEnsayo {
  startTime: string;
  endTime: string;
  stops: Parada[];
  setupHours: number | string;
  setupConfirmed: boolean;
  hv: '' | 'yes' | 'no';
  billable: boolean;
  billableBy?: string;
  billableAt?: string;
  order: string;
  orderException: { justification: string; by?: string; at?: string } | null;
  pieces: number | string;
  note: string;
}

export interface Incidencia {
  codigo: string;
  nivel: Nivel;
  mensaje: string;
  soloAprobacion?: boolean;
}

export interface Ajuste {
  valor: unknown;
  original: unknown;
  por: string;
  rol: string;
  en: string;
  justificacion: string;
  economico: boolean;
  validado: boolean;
  validadoPor?: string;
  validadoEn?: string;
  excepcional?: boolean;
  conflicto?: { anterior: unknown; nuevo: unknown };
}

export interface ValoresLinea {
  minutosBrutos?: number;
  minutosFinDeSemana?: number;
  minutosParadas?: number;
  minutosNetos?: number;
  horasNetas: number;
  horasSetup: number;
  piezas: number | null;
  horasTotales: number;
  cantidad: number | null;
  proyecto: string;
  proyectoNombre: string;
  orden: string;
  categoria: string;
  grupo: string;
  unidad: 'H' | 'PIEZAS';
  regla: string;
  hv: '' | 'yes' | 'no';
  tarifa: number | null;
  tarifaId: string;
  nivelTarifa: number;
  lineaProducto: string;
  requester: string;
  ordenExcepcional: boolean;
  coste: number | null;
  costeCalculado?: number | null;
  textoControlling: string;
}

export interface PropuestaTarifa {
  calculada: number | null;
  propuesta: number;
  diferencia: number;
  por: string;
  en: string;
  justificacion: string;
  estado: 'PENDIENTE' | 'APROBADA' | 'RECHAZADA';
  resueltaPor: string;
  resueltaEn: string;
  alcance: '' | 'LINEA' | 'MAESTRA';
  tarifaFinal: number | null;
}

export interface Historia {
  en: string;
  por: string;
  accion: string;
  campo: string;
  de: unknown;
  a: unknown;
  justificacion: string;
}

export interface LineaFacturacion {
  id: string;
  ensayoId: string;
  periodoEjecucion: string;
  periodoFacturacion: string;
  origen: 'PERIODO' | 'APLAZADA';
  banco: string;
  organizacion: string;
  tipoEnsayo: string;
  responsable: string;
  proyectoTexto: string;
  approvalId: string;
  cerrado: boolean;
  abierto: boolean;
  pendiente: boolean;
  inicioReal: string;
  finReal: string;
  tramoInicio: string;
  tramoFin: string;
  calculado: ValoresLinea | null;
  aplicado: ValoresLinea | null;
  ajustes: Record<string, Ajuste>;
  propuestaTarifa: PropuestaTarifa | null;
  exclusion: { por: string; en: string; origen: 'LINEA' | 'ENSAYO' | 'REGLA' } | null;
  aplazamiento: { a: string; por: string; en: string } | null;
  incidencias: Incidencia[];
  estado: EstadoLinea;
  historial: Historia[];
}

export interface Exportacion {
  id: string;
  version: number;
  tipo: 'excel' | 'controlling' | 'html';
  nombre: string;
  por: string;
  en: string;
  estado: 'VALIDA' | 'OBSOLETA';
}

export interface DocumentoPeriodo {
  id: string;
  anio: number;
  mes: number;
  nombre: string;
  inicio: string;
  fin: string;
  estado: EstadoPeriodo;
  creadoPor: string;
  creadoEn: string;
  ultimoCalculo: string;
  calculadoPor: string;
  lineas: LineaFacturacion[];
  versionAprobada: number;
  fotos: { version: number; aprobadoPor: string; aprobadoEn: string; indicadores: Indicadores }[];
  reaperturas: { por: string; en: string; justificacion: string; version: number }[];
  exportaciones: Exportacion[];
  envios: { por: string; en: string }[];
}

export interface Indicadores {
  coste: number;
  horasNetas: number;
  horasSetup: number;
  horasTotales: number;
  ensayos: number;
  lineas: number;
  lineasContadas: number;
  preparadas: number;
  bloqueadas: number;
  advertencias: number;
  excluidas: number;
  aplazadas: number;
  pendientes: number;
  ajustadas: number;
  ajustesPendientes: number;
  porEstado: Record<string, number>;
}

export interface Grupo {
  clave: string;
  etiqueta: string;
  ensayos: number;
  lineas: string[];
  horas: number;
  setup: number;
  horasTotales: number;
  piezas: number;
  coste: number;
  [campo: string]: unknown;
}

export interface FilaControlling {
  equipo: string;
  orden: string;
  sumaCoste: number;
  sumaHoras: number;
  cctrOld: string;
  orderOld: string;
  operOld: string;
  costElement: string;
  amount: number;
  crcy: string;
  cctrNew: string;
  orderNew: string;
  operNew: string;
  totalQty: number;
  um: string;
  persNo: string;
  text: string;
  lineas: { lineaId: string; ensayoId: string; banco: string; tipoEnsayo: string; coste: number; cantidad: number | null }[];
}

export interface Resumen {
  indicadores: Indicadores;
  porProyectoOrden: (Grupo & { proyecto: string; proyectoNombre: string; orden: string })[];
  porEquipo: (Grupo & { categoria: string; grupo: string; regla: string; unidad: string; tarifas: number[]; bancos: string[]; noLineal: boolean })[];
  porPlanta: Grupo[];
  porLineaProducto: { columnas: string[]; filas: { nivel: number; etiqueta: string; valores: Record<string, number>; total: number }[] };
  desgloses: Record<string, Grupo[]>;
  controlling: FilaControlling[];
  incidencias: (Incidencia & { lineaId: string; ensayoId: string; banco: string; estado: EstadoLinea })[];
}

export interface Alerta {
  lineaId: string;
  ensayoId: string;
  mensaje: string;
  costeAprobado?: number | null;
  costeActual?: number | null;
}

export interface VistaPeriodo {
  periodo: DocumentoPeriodo;
  version: string;
  resumen: Resumen;
  comprobacionesEnvio: string[];
  comprobacionesAprobacion: string[];
  alertas: Alerta[];
  anterior: string;
  siguiente: string;
}

export interface PeriodoListado {
  id: string;
  nombre: string;
  inicio: string;
  fin: string;
  estado: EstadoPeriodo;
  creadoPor: string;
  creadoEn: string;
  ultimoCalculo: string;
  versionAprobada: number;
  resumenRapido: { ensayos?: number; lineas?: number; incidencias?: number; bloqueadas?: number; importe?: number };
  exportaciones: number;
}

export interface Proyecto {
  numero: string;
  nombre: string;
  requester: string;
  lineaProducto: string;
  activo: boolean;
  desde: string;
  hasta: string;
  ordenes: { codigo: string; descripcion: string; activa: boolean }[];
}

export interface Categoria {
  codigo: string;
  grupo: string;
  unidad: 'H' | 'PIEZAS';
  regla: string;
  ordenControlling: number;
  activa: boolean;
  nota: string;
}

export interface Mapeo {
  id: string;
  banco: string;
  tipoEnsayo: string;
  categoria: string;
  regla: string;
  activo: boolean;
  nota?: string;
}

export interface Tarifa {
  id: string;
  categoria: string;
  proyecto: string;
  planta: string;
  lineaProducto: string;
  hv: 'yes' | 'no' | '*';
  importe: number | string;
  unidad: string;
  desde: string;
  hasta: string;
  nota: string;
}

export interface MaestrosFacturacion {
  revision: number;
  origen?: string;
  proyectos: Proyecto[];
  categorias: Categoria[];
  mapeos: Mapeo[];
  tarifas: Tarifa[];
  setupRecomendado: { categoria: string; banco: string; tipoEnsayo: string; horas: number | string }[];
  controlling: { cctrOld: string; costElement: string; moneda: string; unidadHoras: string; unidadPiezas: string; texto: string };
  parametros: { umbralHorasAltas: number | string };
}

export interface Regla {
  codigo: string;
  nombre: string;
  descripcion: string;
  divisor: number;
  unidad: string;
}

export interface VistaMaestros {
  maestros: MaestrosFacturacion;
  version: string;
  guardados: boolean;
  reglas: Regla[];
  motivosParada: string[];
  bancos: { nombre: string; organizacion: string }[];
  tiposEnsayo: string[];
}

export interface EstadoDatos {
  estado: 'COMPLETO' | 'BILLING_DATA_INCOMPLETE' | 'NO_FACTURABLE' | 'NO_APLICA';
  faltan: string[];
}

export interface Tramo {
  periodo: string;
  nombre: string;
  tramoInicio: string;
  tramoFin: string;
  minutosBrutos: number;
  minutosFinDeSemana: number;
  minutosParadas: number;
  horasNetas: number;
  abierto: boolean;
}

export interface FichaFacturacion {
  ensayoId: string;
  banco: string;
  organizacion: string;
  tipoEnsayo: string;
  proyectoTexto: string;
  requester: string;
  fechaInicio: string;
  fechaFin: string;
  cerrado: boolean;
  finDeSemana: boolean;
  billing: DatosFacturacionEnsayo;
  proyecto: { numero: string; nombre: string; lineaProducto: string; ordenes: { codigo: string; descripcion: string; activa: boolean }[] } | null;
  numeroProyecto: string;
  categoria: { codigo: string; grupo: string; unidad: string; regla: string } | null;
  setupRecomendado: number | null;
  motivosParada: string[];
  errores: string[];
  estado: EstadoDatos;
  tramos: Tramo[];
  inicioReal: string;
  finReal: string;
  version: string;
  puedeEditar: boolean;
  esAdmin: boolean;
}

export interface EventoAuditoria {
  id: string;
  en: string;
  por: string;
  email: string;
  rol: string;
  accion: string;
  periodo: string;
  linea: string;
  detalle: string;
  justificacion: string;
  costeAntes: number | null;
  costeDespues: number | null;
}

export interface ArchivoExportado {
  id: string;
  tipo: string;
  nombre: string;
  estado: string;
  b64: string;
}

const enc = encodeURIComponent;
const B = '/api/v1/facturacion';

export const apiFacturacion = {
  maestros: () => peticion<VistaMaestros>('GET', `${B}/maestros`),
  guardarMaestros: (maestros: MaestrosFacturacion, version: string) => peticion<VistaMaestros>('PUT', `${B}/maestros`, { maestros, version }),
  periodos: () => peticion<PeriodoListado[]>('GET', `${B}/periodos`),
  crearPeriodo: (anio: number, mes: number) => peticion<VistaPeriodo>('POST', `${B}/periodos`, { anio, mes }),
  periodo: (id: string) => peticion<VistaPeriodo>('GET', `${B}/periodos/${enc(id)}`),
  recalcular: (id: string, version: string) => peticion<VistaPeriodo>('POST', `${B}/periodos/${enc(id)}/recalcular`, { version }),
  enviar: (id: string, version: string) => peticion<VistaPeriodo>('POST', `${B}/periodos/${enc(id)}/enviar`, { version }),
  aprobar: (id: string, version: string, confirmacion: boolean) =>
    peticion<VistaPeriodo>('POST', `${B}/periodos/${enc(id)}/aprobar`, { version, confirmacion }),
  reabrir: (id: string, version: string, justificacion: string) =>
    peticion<VistaPeriodo>('POST', `${B}/periodos/${enc(id)}/reabrir`, { version, justificacion }),
  accionLinea: (id: string, lineaId: string, accion: string, datos: Record<string, unknown>, version: string) =>
    peticion<VistaPeriodo>('POST', `${B}/periodos/${enc(id)}/lineas/${enc(lineaId)}`, { accion, datos, version }),
  exportar: (id: string, tipos: string[], version: string) =>
    peticion<{ exportaciones: ArchivoExportado[]; periodo: VistaPeriodo }>('POST', `${B}/periodos/${enc(id)}/exportar`, { tipos, version }),
  descargar: (id: string, exportId: string) => peticion<ArchivoExportado>('GET', `${B}/periodos/${enc(id)}/exportaciones/${enc(exportId)}`),
  auditoria: (periodo?: string) => peticion<EventoAuditoria[]>('GET', `${B}/auditoria${periodo ? `?periodo=${enc(periodo)}` : ''}`),
  estadosEnsayos: () => peticion<Record<string, EstadoDatos>>('GET', `${B}/ensayos/estados`),
  ficha: (banco: string, id: string) => peticion<FichaFacturacion>('GET', `${B}/bancos/${enc(banco)}/ensayos/${enc(id)}`),
  vistaPrevia: (banco: string, id: string, billing: DatosFacturacionEnsayo, fechas?: { fechaInicio?: string; fechaFin?: string }) =>
    peticion<FichaFacturacion>('POST', `${B}/bancos/${enc(banco)}/ensayos/${enc(id)}/vista-previa`, { billing, ...(fechas ?? {}) }),
  guardarEnsayo: (banco: string, id: string, billing: DatosFacturacionEnsayo, version: string | null, forzar = false) =>
    peticion<RespuestaOperacion>('PUT', `${B}/bancos/${enc(banco)}/ensayos/${enc(id)}`, { billing, version, forzar }),
};

/** Descarga un fichero recibido en base64. */
export function descargarArchivo(a: { nombre: string; b64: string }) {
  const binario = atob(a.b64);
  const bytes = new Uint8Array(binario.length);
  for (let i = 0; i < binario.length; i++) bytes[i] = binario.charCodeAt(i);
  const tipo = a.nombre.endsWith('.html') ? 'text/html' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  const url = URL.createObjectURL(new Blob([bytes], { type: tipo }));
  const enlace = document.createElement('a');
  enlace.href = url;
  enlace.download = a.nombre;
  document.body.appendChild(enlace);
  enlace.click();
  enlace.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 5000);
}
