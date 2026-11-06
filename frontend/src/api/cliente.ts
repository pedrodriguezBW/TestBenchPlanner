import type {
  Aprobacion, BloquesKpi, FilaEnConflicto, FilaEstadistica, FotoSemanal, Historico, PropuestaCierre, RespuestaOperacion, Sesion, TablaOcupacion,
  Tendencia, TipoOperacion, ValidacionCierre, ValoresCierre, Vista,
} from './tipos';

/*
 * Cliente de la API del backend. Traduce los códigos HTTP a errores con
 * significado para la interfaz:
 *   409 → ErrorConflicto (otra persona ha cambiado el ensayo)
 *   422 → ErrorRegla (la regla de negocio no lo permite; el mensaje es el aviso)
 *   401, 403, 412, 503 → ErrorApi con el mensaje del backend
 *   sin respuesta → ErrorSinConexion (se reintentará)
 */

export class ErrorApi extends Error {
  constructor(public readonly estado: number, public readonly codigo: string, mensaje: string) {
    super(mensaje);
  }
}

export class ErrorRegla extends ErrorApi {}

export class ErrorConflicto extends ErrorApi {
  constructor(public readonly filas: FilaEnConflicto[]) {
    super(409, 'conflicto', 'Otra persona ha cambiado este ensayo');
  }
}

export class ErrorSinConexion extends Error {
  constructor() {
    super('No se ha podido contactar con el backend');
  }
}

async function peticion<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
  let respuesta: Response;
  try {
    respuesta = await fetch(ruta, {
      method: metodo,
      headers: cuerpo === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    });
  } catch {
    throw new ErrorSinConexion();
  }
  const texto = await respuesta.text();
  const json = texto ? JSON.parse(texto) : null;
  if (respuesta.ok) {
    return json as T;
  }
  const mensaje: string = json?.mensaje ?? json?.error ?? `Error ${respuesta.status}`;
  if (respuesta.status === 409 && json?.error === 'conflicto') {
    throw new ErrorConflicto(json.filas ?? []);
  }
  if (respuesta.status === 422) {
    throw new ErrorRegla(422, 'regla', mensaje);
  }
  throw new ErrorApi(respuesta.status, json?.error ?? 'error', mensaje);
}

const enc = encodeURIComponent;

export interface CambioCampo {
  campo: string;
  valor: string | boolean | null;
}

export const api = {
  sesion: () => peticion<Sesion>('GET', '/api/v1/sesion'),
  planificacion: () => peticion<Vista>('GET', '/api/v1/planificacion'),
  catalogos: () => peticion<{ projects: string[]; responsibles: string[] }>('GET', '/api/v1/catalogos'),
  usuarios: () => peticion<{ responsibleName: string }[]>('GET', '/api/v1/usuarios'),

  editarCampos: (banco: string, id: string, cambios: CambioCampo[], version: string | null, forzar = false) =>
    peticion<RespuestaOperacion>('POST', `/api/v1/bancos/${enc(banco)}/ensayos/${enc(id)}/campos`,
      { cambios, version, forzar }),

  operar: (banco: string, tipo: TipoOperacion, datos: Record<string, unknown>, versiones: Record<string, string | null>,
    forzar = false) =>
    peticion<RespuestaOperacion>('POST', `/api/v1/bancos/${enc(banco)}/operaciones`,
      { tipo, ...datos, versiones, forzar }),

  propuestaCierre: (banco: string, id: string, correccion: boolean) =>
    peticion<PropuestaCierre>('GET', `/api/v1/bancos/${enc(banco)}/ensayos/${enc(id)}/cierre?correccion=${correccion}`),

  validarCierre: (banco: string, id: string, valores: ValoresCierre) =>
    peticion<ValidacionCierre>('POST', `/api/v1/bancos/${enc(banco)}/ensayos/${enc(id)}/cierre/validacion`, { valores }),

  cerrar: (banco: string, id: string, valores: ValoresCierre, correccion: boolean, version: string | null, forzar = false) =>
    peticion<RespuestaOperacion>('POST', `/api/v1/bancos/${enc(banco)}/ensayos/${enc(id)}/cierre`,
      { valores, correccion, versiones: { [id]: version }, forzar }),

  aprobaciones: () => peticion<Aprobacion[]>('GET', '/api/v1/aprobaciones'),
  estadosAprobacion: (ids: string[]) =>
    peticion<Record<string, { status: string }>>('POST', '/api/v1/aprobaciones/estados', { ids }),

  // Dashboard (bloque B)
  bloquesKpi: (lunes: string, org: string, responsable?: string | null) =>
    peticion<BloquesKpi>('GET', `/api/v1/indicadores/bloques?lunes=${lunes}&org=${enc(org)}`
      + (responsable == null ? '' : `&responsable=${enc(responsable)}`)),
  tablaOcupacion: (desde: string, hasta: string, org: string) =>
    peticion<TablaOcupacion>('GET', `/api/v1/indicadores/tabla-ocupacion?desde=${desde}&hasta=${hasta}&org=${enc(org)}`),
  tendencia: (cwDesde: number, cwHasta: number, org: string) =>
    peticion<Tendencia>('GET', `/api/v1/indicadores/tendencia?cwDesde=${cwDesde}&cwHasta=${cwHasta}&org=${enc(org)}`),
  fotoSemanal: () => peticion<FotoSemanal[]>('POST', '/api/v1/indicadores/foto-semanal'),

  // KPI History (bloque C)
  historico: (org: string) => peticion<Historico>('GET', `/api/v1/indicadores/historico?org=${enc(org)}`),
  estadisticas: (desde: string, hasta: string, org: string, responsable: string) =>
    peticion<FilaEstadistica[]>('GET', `/api/v1/indicadores/estadisticas?desde=${desde}&hasta=${hasta}&org=${enc(org)}`
      + `&responsable=${enc(responsable)}`),
};
