import type { FilaEnsayo, Sesion, TipoOperacion, Vista } from '../api/tipos';

/*
 * Altas y copias de ensayos que se enseñan EN EL ACTO, antes de que el backend
 * termine de guardarlas en SharePoint (antes la interfaz esperaba ~5 s).
 *
 * - La fila provisional ya lleva el id definitivo: lo genera la interfaz con el
 *   formato de siempre («r» + 7 caracteres en base 36) y el backend lo usa si
 *   es válido y está libre (idsPropuestos). Así la selección, el foco y lo que
 *   el usuario escriba en la fila siguen valiendo al confirmarse.
 * - Se colocan donde las pondrá el backend: el alta al final de los activos y
 *   cada copia justo debajo de su original (OperacionesBanco.anadir/duplicar).
 * - Al confirmar, manda la vista del backend (fechas, versión…). Si falla, la
 *   fila provisional desaparece y se avisa como antes.
 *
 * Este módulo no depende de React: se prueba con node --test.
 */

/** Operación estructural visible antes de que el backend confirme el guardado. */
export interface OperacionOptimista {
  clave: string;
  tipo: 'anadir' | 'duplicar';
  banco: string;
  inserciones: { ancla: string | null; fila: FilaEnsayo }[];
  /** Ids de las filas provisionales, en el orden en que el backend los usará. */
  ids: string[];
}

const TIPOS_OPTIMISTAS: ReadonlySet<TipoOperacion> = new Set<TipoOperacion>(['anadir', 'duplicar']);

export function esOperacionOptimista(tipo: TipoOperacion): tipo is OperacionOptimista['tipo'] {
  return TIPOS_OPTIMISTAS.has(tipo);
}

const ALFABETO = '0123456789abcdefghijklmnopqrstuvwxyz';
export const FORMATO_ID = /^r[0-9a-z]{7}$/;

/** Id nuevo con el formato de la 4.9.4 y del backend (GeneradorIds). */
export function nuevoIdEnsayo(azar: (n: number) => number = azarSeguro): string {
  let id = 'r';
  for (let i = 0; i < 7; i++) id += ALFABETO[azar(ALFABETO.length)];
  return id;
}

function azarSeguro(n: number): number {
  try {
    const buf = new Uint32Array(1);
    crypto.getRandomValues(buf);
    return buf[0] % n;
  } catch {
    return Math.floor(Math.random() * n);
  }
}

function idsExistentes(vista: Vista): Set<string> {
  const ids = new Set<string>();
  Object.values(vista.bancos).forEach((filas) => filas.forEach((f) => ids.add(f.id)));
  return ids;
}

/** Id que no está en la vista ni entre los ya elegidos. */
function idLibre(ocupados: Set<string>, generar: () => string): string {
  for (let i = 0; i < 50; i++) {
    const id = generar();
    if (!ocupados.has(id)) {
      ocupados.add(id);
      return id;
    }
  }
  throw new Error('No se ha podido generar un id libre');
}

function copiar<T>(valor: T): T {
  return JSON.parse(JSON.stringify(valor)) as T;
}

/** Ensayo nuevo igual que OperacionesBanco.anadir (mismos campos y valores). */
function filaNueva(id: string, responsable: string): FilaEnsayo {
  return {
    id,
    version: null,
    cerrado: false,
    optimista: true,
    datos: {
      id, done: false, type: '', resp: responsable, project: '', comments: '', avail: '',
      startLock: false, startOverride: '', endLock: false, endOverride: '', actualStart: '', manualState: '',
      confirmedStart: '', confirmedEnd: '', durMode: 'manual', manualUnit: 'days', durManual: '', cycles: '',
      cycleVal: '', cycleUnit: 'min', cf: 1.2, weekend: false, inputs: false, approvalId: '', progressActual: '',
      lastUpdate: '',
    },
    calculo: {
      estado: 'queued', estadoDeclarado: 'ok', diasNominales: 0, diasPlanificados: 0, fraccionAvance: 0,
      tipoEspecial: false,
    },
  } as FilaEnsayo;
}

/**
 * Prepara la operación provisional a partir de lo que el usuario ve. Devuelve
 * null si no es un alta o una copia, o si no hay nada que copiar (entonces la
 * operación sigue el camino normal y el backend da el aviso de siempre).
 */
export function prepararOperacionOptimista(vista: Vista, banco: string, tipo: TipoOperacion,
  datos: Record<string, unknown>, sesion: Sesion | null, generarId: () => string = () => nuevoIdEnsayo()): OperacionOptimista | null {
  if (!esOperacionOptimista(tipo)) return null;
  const filas = vista.bancos[banco];
  if (!filas) return null;
  const ocupados = idsExistentes(vista);
  const clave = `op-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  if (tipo === 'anadir') {
    const id = idLibre(ocupados, generarId);
    // Admin: sin responsable; resto: su nombre de responsable (Usuario.responsablePorDefecto).
    const responsable = sesion && !sesion.esAdmin ? (sesion.responsable ?? '') : '';
    return { clave, tipo, banco, inserciones: [{ ancla: null, fila: filaNueva(id, responsable) }], ids: [id] };
  }
  const seleccion = new Set(Array.isArray(datos.ids) ? datos.ids.map(String) : []);
  const elegidas = filas.filter((f) => !f.cerrado && seleccion.has(f.id));
  if (!elegidas.length) return null;
  const inserciones = elegidas.map((original) => {
    const copia = copiar(original);
    const id = idLibre(ocupados, generarId);
    copia.id = id;
    copia.version = null;
    copia.optimista = true;
    copia.datos = { ...copia.datos, id };
    return { ancla: original.id, fila: copia };
  });
  return { clave, tipo, banco, inserciones, ids: inserciones.map((i) => i.fila.id) };
}

function aplicarUna(vista: Vista, operacion: OperacionOptimista): Vista {
  const existentes = vista.bancos[operacion.banco];
  if (!existentes) return vista;
  const filas = [...existentes];
  for (const insercion of operacion.inserciones) {
    if (filas.some((f) => f.id === insercion.fila.id)) continue; // ya confirmada en la vista del backend
    const fila = copiar(insercion.fila);
    if (insercion.ancla == null) {
      const primerCerrado = filas.findIndex((f) => f.cerrado);
      filas.splice(primerCerrado < 0 ? filas.length : primerCerrado, 0, fila);
    } else {
      const indice = filas.findIndex((f) => f.id === insercion.ancla);
      if (indice >= 0) filas.splice(indice + 1, 0, fila);
    }
  }
  let prioridad = 0;
  const numeradas = filas.map((f) => {
    if (f.cerrado) return f;
    prioridad += 1;
    return f.prioridad === prioridad ? f : { ...f, prioridad };
  });
  return { ...vista, bancos: { ...vista.bancos, [operacion.banco]: numeradas } };
}

/** Superpone las altas y copias pendientes sobre la última vista del backend. */
export function aplicarOperacionesOptimistas(vista: Vista, operaciones: Iterable<OperacionOptimista>): Vista {
  let salida = vista;
  for (const operacion of operaciones) salida = aplicarUna(salida, operacion);
  return salida;
}

/**
 * Activa primero el estado provisional y después espera la confirmación real.
 * Ante cualquier error restaura el estado anterior antes de propagarlo.
 */
export async function ejecutarConActualizacionOptimista<T>(activar: () => void, ejecutar: () => Promise<T>,
  confirmar: (resultado: T) => void, revertir: (error: unknown) => void): Promise<T> {
  activar();
  try {
    const resultado = await ejecutar();
    confirmar(resultado);
    return resultado;
  } catch (error) {
    revertir(error);
    throw error;
  }
}

/**
 * Cola de operaciones: se ejecutan de una en una y en el orden en que el
 * usuario las pidió (una copia de una fila recién añadida espera a que el alta
 * se haya guardado).
 */
export function crearCola(): <T>(tarea: () => Promise<T>) => Promise<T> {
  let ultima: Promise<unknown> = Promise.resolve();
  return <T>(tarea: () => Promise<T>): Promise<T> => {
    const resultado = ultima.then(tarea, tarea);
    ultima = resultado.catch(() => undefined);
    return resultado;
  };
}
