/*
 * Tipos de la API /api/v1 del backend Java. Reflejan exactamente lo que
 * devuelve RepresentacionVista: los datos del ensayo tal cual (DataJson) y,
 * aparte, todo lo calculado por el dominio. La interfaz no recalcula reglas.
 */

/** Campos del ensayo guardados en DataJson (tipos mezclados, como en la 4.9.4). */
export interface DatosEnsayo {
  id: string;
  done?: boolean;
  type?: string;
  resp?: string;
  project?: string;
  comments?: string;
  approvalId?: string;
  avail?: string;
  weekend?: boolean;
  inputs?: boolean;
  durMode?: string;
  durManual?: number | string;
  manualUnit?: string;
  cycles?: number | string;
  cycleVal?: number | string;
  cycleUnit?: string;
  cf?: number | string;
  startLock?: boolean;
  startOverride?: string;
  endLock?: boolean;
  endOverride?: string;
  confirmedStart?: string;
  confirmedEnd?: string;
  actualStart?: string;
  trueEnd?: string;
  progressActual?: number | string;
  lastUpdate?: string;
  manualState?: string;
  delayReason?: string;
  downtimeDays?: number | string;
  downtimeNote?: string;
  resources?: string[];
  histStart?: string;
  histEnd?: string;
  doneDate?: string;
  [campo: string]: unknown;
}

export interface Solape {
  etiqueta: string;
  inicio: string;
  fin: string;
}

export interface Sugerencia {
  clave: 'noprog' | 'endlate' | 'startlate' | 'slow';
  dias?: number;
  fecha: string | null;
  referencia: string | null;
}

export type EstadoEnsayo = 'queued' | 'confirmed' | 'running' | 'delayed' | 'completed' | 'special';

/** Resultado del dominio para un ensayo (activo o cerrado). */
export interface Calculo {
  estado: EstadoEnsayo;
  estadoDeclarado: 'ok' | 'delayed';
  diasNominales: number;
  diasPlanificados: number;
  fraccionAvance: number;
  tipoEspecial: boolean;
  // Solo activos
  inicio?: string | null;
  fin?: string | null;
  criterioInicio?: string;
  arranqueEfectivo?: string;
  arranqueAsumido?: boolean;
  finTolerado?: string | null;
  finPlanificado?: string | null;
  diasSinAvance?: number;
  solapes?: Solape[];
  sugerencias?: Sugerencia[];
  sugerenciaAbierta?: boolean;
  // Solo cerrados
  estadoFinal?: 'ok' | 'delayed';
  cierreConfirmado?: boolean;
}

export interface FilaEnsayo {
  id: string;
  version: string | null;
  cerrado: boolean;
  prioridad?: number;
  datos: DatosEnsayo;
  calculo: Calculo;
}

export interface Grupo {
  clave: string;
  nombre: string;
  color: string;
  bancos: { nombre: string; organizacion: string }[];
}

export interface Vista {
  hoy: string;
  semanaHoy: number;
  calendario: { anio: number; saltarFestivos: boolean; festivos: { fecha: string; nombre: string }[] };
  grupos: Grupo[];
  bancos: Record<string, FilaEnsayo[]>;
}

export interface Sesion {
  email: string;
  nombre: string;
  responsable: string;
  rol: 'admin' | 'editor' | 'readonly';
  esAdmin: boolean;
  soloLectura: boolean;
}

export interface Aprobacion {
  id: number;
  status: string;
  color: string;
  raw: string | null;
  requester: string | null;
  service: string | null;
}

/** Valores de la ventana de cierre (Cierre.Valores en el backend). */
export interface ValoresCierre {
  arranqueReal: string;
  finReal: string;
  estadoFinal: string;
  motivo: string;
  explicacion: string;
  diasParada: string;
  notaParada: string;
}

export interface ValidacionCierre {
  error: string;
  desviacion: number | null;
  sugerirRetraso: boolean;
  diasCalendario: number;
  diasFacturables: number | null;
}

export interface PropuestaCierre {
  valores: ValoresCierre;
  validacion: ValidacionCierre;
  comprometidoInicio: string;
  comprometidoFin: string;
}

/** Fila en conflicto devuelta con un 409. */
export interface FilaEnConflicto {
  id: string;
  version: string | null;
  borrado: boolean;
  datos: DatosEnsayo | null;
}

/** Respuesta de cualquier operación: la vista ya actualizada. */
export interface RespuestaOperacion {
  resultado?: string;
  filasGuardadas: number;
  vista: Vista;
}

/** Tipos de operación de banco que acepta /bancos/{banco}/operaciones. */
export type TipoOperacion =
  | 'comprometer' | 'deshacerCompromiso' | 'comprometerTodos' | 'estado' | 'aplicarSugerencia'
  | 'reordenar' | 'duplicar' | 'borrarSeleccion' | 'borrar' | 'mover' | 'anadir'
  | 'alternarRecurso' | 'restaurar' | 'reutilizar';

/* ===== Dashboard (bloque B) ============================================ */

/** Un bloque KPI («Esta semana» / «Semana que viene»), nombres de la 4.9.4. */
export interface BloqueKpi {
  total: number;
  benches: number;
  accr: number;
  delayed: number;
  sug: number;
}

export interface BloquesKpi {
  estaSemana: BloqueKpi;
  siguiente: BloqueKpi;
}

export interface FilaOcupacion {
  clave: string;
  nombre: string;
  color: string;
  bancos: number;
  trabajando: number;
  inicia: number;
  acaba: number;
  busy: number;
  cap: number;
  pct: number;
}

export interface TablaOcupacion {
  grupos: FilaOcupacion[];
  busy: number;
  cap: number;
  pct: number;
}

export interface SerieTendencia {
  clave: string;
  nombre: string;
  color: string;
  bancos: number;
  busy: number[];
  pct: number[];
}

export interface Tendencia {
  anio: number;
  semanasDelAnio: number;
  desde: number;
  hasta: number;
  lunes: string[];
  cw: number[];
  series: SerieTendencia[];
}

/** Una fila de la foto semanal (📊 Update Yearly Status). */
export interface FotoSemanal {
  year: number;
  cw: number;
  org: string;
}

/* ===== KPI History (bloque C) ========================================== */

/** Una semana del histórico (kpiHistRows de la 4.9.4). */
export interface SemanaHistorico {
  year: number;
  cw: number;
  org: string;
  benches: number;
  total: number | null;
  starts: number;
  ends: number;
  running: number;
  noid: number;
  behind: number;
  delayed: number;
  risk: number;
  occPct: number | null;
  occBusy: number | null;
  occCap: number | null;
  isNew: boolean;
  unplanned: number;
  reasons: Record<string, number>;
}

export interface Historico {
  /** Filas leídas de la lista (todas las organizaciones). */
  filas: number;
  semanas: SemanaHistorico[];
}

/** Un ensayo cerrado de las estadísticas (statRows). */
export interface FilaEstadistica {
  banco: string;
  id: string;
  fecha: string;
  estadoFinal: string;
  motivo: string;
  confirmado: boolean;
  datos: DatosEnsayo;
}
