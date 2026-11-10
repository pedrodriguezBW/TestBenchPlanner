import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { iniciarCicloDeVida } from './cicloDeVida';
import { aplicarOperacionesOptimistas, crearCola, prepararOperacionOptimista, type OperacionOptimista } from './operacionesOptimistas';
import { api, ErrorApi, ErrorConflicto, ErrorRegla, ErrorSinConexion, type CambioCampo } from '../api/cliente';
import type { FilaEnConflicto, FilaEnsayo, RespuestaOperacion, Sesion, TipoOperacion, ValoresCierre, Vista } from '../api/tipos';

/*
 * Estado compartido de la aplicación y comunicación con el backend.
 *
 * Reproduce la sincronización de la 4.9.4 con la protección nueva:
 *  - Arranque: lectura autoritativa (3 intentos separados 1,5 s). Si falla, la
 *    aplicación queda en solo lectura con el candado rojo. Catálogos y ensayos
 *    llegan juntos (D17).
 *  - Edición de celdas: se aplica en pantalla al momento y se guarda a los
 *    1,2 s de la última pulsación, junto con la VERSIÓN del ensayo que el
 *    usuario tenía delante. Los envíos van de uno en uno.
 *  - Si otra persona cambió el ensayo, el backend responde 409 y se muestra
 *    «Otra persona ha cambiado este ensayo» (D12).
 *  - Sin conexión: los cambios se conservan y se reintenta cada 5 s.
 *  - Relectura cada 20 s, salvo si se está escribiendo o hay cambios pendientes.
 *  - Añadir y duplicar se ven AL MOMENTO (fila provisional con su id
 *    definitivo) mientras el backend guarda en SharePoint; lo que se escriba en
 *    esa fila espera a que el alta esté guardada y después sigue la regla de
 *    los 1,2 s. Las operaciones se envían de una en una y en orden.
 */

export type EstadoSincronizacion = 'conectando' | 'ok' | 'guardando' | 'error' | 'bloqueado';

export interface Aviso {
  id: number;
  tipo: 'info' | 'error';
  texto: string;
}

export interface Conflicto {
  filas: FilaEnConflicto[];
  /** Mis cambios descritos para el usuario. */
  descripcion: string;
  verSuVersion: () => void;
  guardarLaMia: () => void;
}

interface Pendiente {
  banco: string;
  id: string;
  version: string | null;
  cambios: CambioCampo[];
}

interface Contexto {
  vista: Vista | null;
  sesion: Sesion | null;
  soloLectura: boolean;
  sincronizacion: EstadoSincronizacion;
  copiaSinConexion: string | null;
  proyectos: string[];
  personas: string[];
  estadosAprobacion: Record<string, string>;
  avisos: Aviso[];
  conflicto: Conflicto | null;
  mensajeModal: string | null;
  cerrarMensajeModal: () => void;
  editarCampo: (banco: string, id: string, campo: string, valor: string | boolean | null) => void;
  operar: (banco: string, tipo: TipoOperacion, datos?: Record<string, unknown>, afectados?: string[]) => Promise<RespuestaOperacion | null>;
  cerrarEnsayo: (banco: string, id: string, valores: ValoresCierre, correccion: boolean) => Promise<boolean>;
  avisar: (texto: string, tipo?: 'info' | 'error') => void;
  mostrarMensaje: (texto: string) => void;
  recargar: () => Promise<void>;
  recargarListas: () => Promise<void>;
  refrescarAprobaciones: () => void;
  versionDe: (id: string) => string | null;
}

const ContextoPlanificador = createContext<Contexto | null>(null);

export function usePlanificador(): Contexto {
  const ctx = useContext(ContextoPlanificador);
  if (!ctx) throw new Error('usePlanificador fuera de ProveedorPlanificador');
  return ctx;
}

const ESPERA_GUARDADO_MS = 1200;
const REINTENTO_MS = 5000;
const RELECTURA_MS = 20000;

/** Aplica en pantalla unos cambios de campos aún no guardados. */
function aplicarEnVista(vista: Vista, pendientes: Iterable<Pendiente>): Vista {
  let copia: Vista | null = null;
  for (const p of pendientes) {
    const filas = (copia ?? vista).bancos[p.banco];
    if (!filas) continue;
    const indice = filas.findIndex((f) => f.id === p.id);
    if (indice < 0) continue;
    if (!copia) copia = { ...vista, bancos: { ...vista.bancos } };
    const datos = { ...filas[indice].datos };
    for (const c of p.cambios) datos[c.campo] = valorEnPantalla(c);
    const nuevas = [...filas];
    nuevas[indice] = { ...filas[indice], datos };
    copia.bancos[p.banco] = nuevas;
  }
  return copia ?? vista;
}

/** Cómo se ve el valor en la celda hasta que el backend lo confirma. */
function valorEnPantalla(c: CambioCampo): unknown {
  if (c.campo === 'weekend') return c.valor === 'yes';
  if (c.campo === 'inputs') return c.valor !== 'no';
  return c.valor;
}

export function ProveedorPlanificador({ children }: { children: ReactNode }) {
  const [vistaServidor, setVistaServidor] = useState<Vista | null>(null);
  const [sesion, setSesion] = useState<Sesion | null>(null);
  // Sin conexión (H-04): fecha de la copia de este PC con la que se ha arrancado.
  const [copiaSinConexion, setCopiaSinConexion] = useState<string | null>(null);
  const [sincronizacion, setSincronizacion] = useState<EstadoSincronizacion>('conectando');
  const [proyectos, setProyectos] = useState<string[]>([]);
  const [usuariosResp, setUsuariosResp] = useState<string[]>([]);
  const [responsablesCatalogo, setResponsablesCatalogo] = useState<string[]>([]);

  /** Proyectos, responsables y personas (se vuelven a leer tras cambiarlos en Configuration). */
  const recargarListas = useCallback(async () => {
    await Promise.all([
      api.catalogos().then((c) => { setProyectos(c.projects ?? []); setResponsablesCatalogo(c.responsibles ?? []); }).catch(() => undefined),
      api.usuarios().then((u) => setUsuariosResp(u.map((x) => (x.responsibleName ?? '').trim()).filter(Boolean))).catch(() => undefined),
    ]);
  }, []);
  const [estadosAprobacion, setEstadosAprobacion] = useState<Record<string, string>>({});
  const [avisos, setAvisos] = useState<Aviso[]>([]);
  const [conflicto, setConflicto] = useState<Conflicto | null>(null);
  const [mensajeModal, setMensajeModal] = useState<string | null>(null);
  const [revisionPendientes, setRevisionPendientes] = useState(0);

  const pendientes = useRef(new Map<string, Pendiente>());
  const enEnvio = useRef(false);
  const temporizador = useRef<number | undefined>(undefined);
  const vistaRef = useRef<Vista | null>(null);
  vistaRef.current = vistaServidor;

  // Altas y copias que ya se enseñan mientras se guardan (operacionesOptimistas.ts).
  const [optimistas, setOptimistas] = useState<OperacionOptimista[]>([]);
  const optimistasRef = useRef<OperacionOptimista[]>([]);
  /** Ids que el backend sustituyó al confirmar (solo si rechazó el propuesto). */
  const idsConfirmados = useRef(new Map<string, string>());
  const colaOperaciones = useRef(crearCola());
  const operacionesEnCurso = useRef(0);
  const vistaVisibleRef = useRef<Vista | null>(null);
  const sesionRef = useRef<Sesion | null>(null);
  sesionRef.current = sesion;

  const ponerOptimistas = useCallback((lista: OperacionOptimista[]) => {
    optimistasRef.current = lista;
    setOptimistas(lista);
  }, []);

  /** ¿Es una fila provisional cuyo alta aún no se ha guardado? */
  const sinConfirmar = useCallback((id: string) => optimistasRef.current.some((o) => o.ids.includes(id)), []);

  /** Id real de una fila (igual salvo que el backend no aceptase el id propuesto). */
  const idReal = useCallback((id: string) => idsConfirmados.current.get(id) ?? id, []);

  const avisar = useCallback((texto: string, tipo: 'info' | 'error' = 'info') => {
    const id = Date.now() + Math.random();
    setAvisos((a) => [...a, { id, tipo, texto }]);
    window.setTimeout(() => setAvisos((a) => a.filter((x) => x.id !== id)), tipo === 'error' ? 7000 : 4500);
  }, []);

  const soloLectura = sincronizacion === 'bloqueado' || !sesion || sesion.soloLectura;

  /** Versión de un ensayo tal como la ve el usuario ahora. */
  const versionDe = useCallback((id: string): string | null => {
    const v = vistaRef.current;
    if (!v) return null;
    for (const filas of Object.values(v.bancos)) {
      const f = filas.find((x) => x.id === id);
      if (f) return f.version;
    }
    return null;
  }, []);

  const recibirVista = useCallback((vista: Vista) => {
    vistaRef.current = vista;
    setVistaServidor(vista);
  }, []);

  // ---- guardado diferido de celdas ----------------------------------------------

  const programarEnvio = useCallback((ms: number) => {
    window.clearTimeout(temporizador.current);
    temporizador.current = window.setTimeout(() => void enviarPendientes(), ms);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const enviarPendientes = useCallback(async (): Promise<void> => {
    if (enEnvio.current) return;
    // Lo escrito en una fila que aún se está dando de alta espera a su confirmación.
    const p = [...pendientes.current.values()].find((x) => !sinConfirmar(x.id));
    if (!p) {
      if (!pendientes.current.size) setSincronizacion((s) => (s === 'guardando' || s === 'error' ? 'ok' : s));
      return;
    }
    pendientes.current.delete(p.id);
    enEnvio.current = true;
    setSincronizacion('guardando');
    try {
      const r = await api.editarCampos(p.banco, p.id, p.cambios, p.version);
      // Si mientras tanto se escribió otra vez en ese ensayo, parte de la nueva versión (es nuestra).
      const nueva = r.vista.bancos[p.banco]?.find((f) => f.id === p.id)?.version ?? null;
      const posterior = pendientes.current.get(p.id);
      if (posterior) posterior.version = nueva;
      recibirVista(r.vista);
    } catch (e) {
      if (e instanceof ErrorConflicto) {
        mostrarConflicto(p, e.filas);
      } else if (e instanceof ErrorRegla) {
        setMensajeModal(e.message);   // como el alert() de la 4.9.4: el cambio no se guarda
        void recargarSilencioso();
      } else if (e instanceof ErrorSinConexion || (e instanceof ErrorApi && e.estado >= 500 && e.estado !== 503)) {
        // Se conserva para reintentarlo (combinado con lo que se escriba después).
        const posterior = pendientes.current.get(p.id);
        if (posterior) posterior.cambios = [...p.cambios, ...posterior.cambios];
        pendientes.current.set(p.id, posterior ?? p);
        setSincronizacion('error');
        avisar('No se ha podido guardar; se volverá a intentar.', 'error');
        enEnvio.current = false;
        programarEnvio(REINTENTO_MS);
        return;
      } else {
        setMensajeModal(e instanceof Error ? e.message : String(e));
        void recargarSilencioso();
      }
    } finally {
      enEnvio.current = false;
      setRevisionPendientes((n) => n + 1);
    }
    if (pendientes.current.size) {
      void enviarPendientes();
    } else {
      setSincronizacion((s) => (s === 'guardando' || s === 'error' ? 'ok' : s));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const hayEnviables = useCallback(() => [...pendientes.current.values()].some((x) => !sinConfirmar(x.id)), [sinConfirmar]);

  /** Espera a que se guarde todo lo pendiente que ya se puede enviar (antes de una operación). */
  const vaciarPendientes = useCallback(async () => {
    window.clearTimeout(temporizador.current);
    for (let i = 0; i < 50 && (hayEnviables() || enEnvio.current); i++) {
      if (!enEnvio.current) await enviarPendientes();
      else await new Promise((r) => window.setTimeout(r, 100));
    }
    // Lo que espera a un alta sin confirmar sigue con su temporizador de 1,2 s.
    if (pendientes.current.size) programarEnvio(ESPERA_GUARDADO_MS);
  }, [enviarPendientes, hayEnviables, programarEnvio]);

  const editarCampo = useCallback((banco: string, id: string, campo: string, valor: string | boolean | null) => {
    if (soloLecturaRef.current) return;
    const existente = pendientes.current.get(id);
    if (existente) {
      existente.cambios.push({ campo, valor });
    } else {
      pendientes.current.set(id, { banco, id, version: versionDe(id), cambios: [{ campo, valor }] });
    }
    setRevisionPendientes((n) => n + 1);
    setSincronizacion('guardando');
    programarEnvio(ESPERA_GUARDADO_MS);
  }, [programarEnvio, versionDe]);

  const soloLecturaRef = useRef(soloLectura);
  soloLecturaRef.current = soloLectura;

  // ---- conflictos -----------------------------------------------------------------

  const mostrarConflicto = useCallback((p: Pendiente, filas: FilaEnConflicto[]) => {
    setConflicto({
      filas,
      descripcion: p.cambios.map((c) => `${c.campo} = ${c.valor ?? ''}`).join(', '),
      verSuVersion: () => {
        setConflicto(null);
        void recargarSilencioso();
      },
      guardarLaMia: () => {
        setConflicto(null);
        void (async () => {
          try {
            const r = await api.editarCampos(p.banco, p.id, p.cambios, null, true);
            recibirVista(r.vista);
            setSincronizacion('ok');
          } catch (e) {
            setMensajeModal(e instanceof Error ? e.message : String(e));
          }
        })();
      },
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- operaciones de banco ------------------------------------------------------

  /** Traduce ids provisionales a los definitivos (solo cambian si el backend rechazó el propuesto). */
  const traducirDatos = useCallback((datos: Record<string, unknown>): Record<string, unknown> => {
    if (!idsConfirmados.current.size) return datos;
    const salida: Record<string, unknown> = { ...datos };
    (['id', 'destino'] as const).forEach((k) => { if (typeof salida[k] === 'string') salida[k] = idReal(salida[k] as string); });
    if (Array.isArray(salida.ids)) salida.ids = (salida.ids as unknown[]).map((x) => idReal(String(x)));
    return salida;
  }, [idReal]);

  /** El backend ha guardado el alta o la copia: la fila provisional deja paso a la real. */
  const confirmarOptimista = useCallback((op: OperacionOptimista, r: RespuestaOperacion) => {
    const reales = (r.resultado ?? '').split(',').filter(Boolean);
    op.ids.forEach((provisional, i) => {
      const real = reales[i] ?? provisional;
      if (real !== provisional) {
        idsConfirmados.current.set(provisional, real);
        const p = pendientes.current.get(provisional);
        if (p) {
          pendientes.current.delete(provisional);
          pendientes.current.set(real, { ...p, id: real });
        }
      }
      // Lo escrito en la fila mientras se guardaba parte ahora de su primera versión.
      const p = pendientes.current.get(real);
      if (p) p.version = r.vista.bancos[op.banco]?.find((f) => f.id === real)?.version ?? null;
    });
    ponerOptimistas(optimistasRef.current.filter((o) => o.clave !== op.clave));
    if (pendientes.current.size) programarEnvio(ESPERA_GUARDADO_MS);
  }, [ponerOptimistas, programarEnvio]);

  const quitarOptimista = useCallback((op: OperacionOptimista | null) => {
    if (!op) return;
    ponerOptimistas(optimistasRef.current.filter((o) => o.clave !== op.clave));
    // Lo escrito en una fila que no llegó a existir se descarta con ella.
    op.ids.forEach((id) => pendientes.current.delete(id));
    setRevisionPendientes((n) => n + 1);
  }, [ponerOptimistas]);

  const operar = useCallback(async (banco: string, tipo: TipoOperacion, datos: Record<string, unknown> = {},
    afectados: string[] = []): Promise<RespuestaOperacion | null> => {
    if (soloLecturaRef.current) return null;
    // Alta o copia: se enseña ya, con el id que se pedirá al backend.
    const visible = vistaVisibleRef.current;
    const optimista = visible ? prepararOperacionOptimista(visible, banco, tipo, datos, sesionRef.current) : null;
    if (optimista) ponerOptimistas([...optimistasRef.current, optimista]);
    operacionesEnCurso.current += 1;
    try {
      return await colaOperaciones.current(async () => {
        await vaciarPendientes();
        const datosEnvio = { ...traducirDatos(datos), ...(optimista ? { idsPropuestos: optimista.ids } : {}) };
        const versiones: Record<string, string | null> = {};
        afectados.map(idReal).forEach((id) => { versiones[id] = versionDe(id); });
        const ejecutar = async (forzar: boolean): Promise<RespuestaOperacion | null> => {
          setSincronizacion('guardando');
          try {
            const r = await api.operar(banco, tipo, datosEnvio, versiones, forzar);
            recibirVista(r.vista);
            if (optimista) confirmarOptimista(optimista, r);
            // Lo escrito en la fila nueva aún está por enviar: sigue «guardando».
            setSincronizacion(pendientes.current.size ? 'guardando' : 'ok');
            return r;
          } catch (e) {
            setSincronizacion(pendientes.current.size ? 'guardando' : 'ok');
            if (e instanceof ErrorConflicto) {
              return new Promise((resolver) => {
                setConflicto({
                  filas: e.filas,
                  descripcion: `operación «${tipo}»`,
                  verSuVersion: () => { setConflicto(null); quitarOptimista(optimista); void recargarSilencioso(); resolver(null); },
                  guardarLaMia: () => { setConflicto(null); void ejecutar(true).then(resolver); },
                });
              });
            }
            quitarOptimista(optimista);
            if (e instanceof ErrorSinConexion) {
              setSincronizacion('error');
              avisar('No se ha podido guardar; inténtalo de nuevo cuando vuelva la conexión.', 'error');
              return null;
            }
            setMensajeModal(e instanceof Error ? e.message : String(e));
            return null;
          }
        };
        return ejecutar(false);
      });
    } finally {
      operacionesEnCurso.current -= 1;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vaciarPendientes, versionDe, avisar, traducirDatos, confirmarOptimista, quitarOptimista, idReal, ponerOptimistas]);

  // El cierre también va en la cola: si el ensayo se acaba de añadir, espera a su alta.
  const cerrarEnsayo = useCallback(async (banco: string, idPedido: string, valores: ValoresCierre, correccion: boolean) => {
    if (soloLecturaRef.current) return false;
    return colaOperaciones.current(() => cerrarAhoraRef.current(banco, idPedido, valores, correccion));
  }, []);

  const cerrarAhora = async (banco: string, idPedido: string, valores: ValoresCierre, correccion: boolean): Promise<boolean> => {
    await vaciarPendientes();
    const id = idReal(idPedido);
    const version = versionDe(id);
    const ejecutar = async (forzar: boolean): Promise<boolean> => {
      try {
        const r = await api.cerrar(banco, id, valores, correccion, version, forzar);
        recibirVista(r.vista);
        return true;
      } catch (e) {
        if (e instanceof ErrorConflicto) {
          return new Promise((resolver) => setConflicto({
            filas: e.filas,
            descripcion: correccion ? 'corrección del cierre' : 'cierre del ensayo',
            verSuVersion: () => { setConflicto(null); void recargarSilencioso(); resolver(false); },
            guardarLaMia: () => { setConflicto(null); void ejecutar(true).then(resolver); },
          }));
        }
        setMensajeModal(e instanceof Error ? e.message : String(e));
        return false;
      }
    };
    return ejecutar(false);
  };
  const cerrarAhoraRef = useRef(cerrarAhora);
  cerrarAhoraRef.current = cerrarAhora;

  // ---- lecturas --------------------------------------------------------------------

  const recargarSilencioso = useCallback(async () => {
    try {
      recibirVista(await api.planificacion());
      if (sincronizacionRef.current !== 'bloqueado') setSincronizacion('ok');
    } catch {
      setSincronizacion((s) => (s === 'bloqueado' ? s : 'error'));
    }
  }, [recibirVista]);

  const sincronizacionRef = useRef(sincronizacion);
  sincronizacionRef.current = sincronizacion;

  // Arranque: lectura autoritativa con 3 intentos; si no, solo lectura (candado).
  useEffect(() => {
    let cancelado = false;
    (async () => {
      let vista: Vista | null = null;
      for (let intento = 0; intento < 3 && !vista; intento++) {
        try {
          const [s, v] = await Promise.all([api.sesion(), api.planificacion()]);
          if (cancelado) return;
          setSesion(s);
          vista = v;
        } catch {
          await new Promise((r) => window.setTimeout(r, 1500));
        }
      }
      if (cancelado) return;
      if (!vista) {
        setSincronizacion('bloqueado');
        return;
      }
      recibirVista(vista);
      // Con la copia de este PC se consulta en solo lectura (candado), como la 4.9.4.
      if (vista.copiaSinConexion) {
        setCopiaSinConexion(vista.copiaSinConexion);
        setSincronizacion('bloqueado');
      } else {
        setSincronizacion('ok');
      }
      void recargarListas();
    })();
    return () => { cancelado = true; };
  }, [recibirVista, recargarListas]);

  // Relectura periódica (20 s) para ver los cambios de los demás.
  useEffect(() => {
    const t = window.setInterval(() => {
      const activo = document.activeElement?.tagName;
      const escribiendo = activo === 'INPUT' || activo === 'SELECT' || activo === 'TEXTAREA';
      if (sincronizacionRef.current === 'conectando' || pendientes.current.size || enEnvio.current || escribiendo) return;
      // Con un alta o una operación a medio guardar, la relectura podría enseñarla dos veces.
      if (operacionesEnCurso.current || optimistasRef.current.length) return;
      if (document.querySelector('.modal-bg.show')) return;
      void recargarSilencioso();
    }, RELECTURA_MS);
    return () => window.clearInterval(t);
  }, [recargarSilencioso]);

  // Proyectos, responsables y usuarios cada 60 s (pollConfig de la 4.9.4): así aparece
  // lo que den de alta otros sin recargar. Mismas guardas que la relectura.
  useEffect(() => {
    const t = window.setInterval(() => {
      const activo = document.activeElement?.tagName;
      if (activo === 'INPUT' || activo === 'SELECT' || activo === 'TEXTAREA') return;
      if (sincronizacionRef.current === 'conectando' || enEnvio.current || document.querySelector('.modal-bg.show')) return;
      void recargarListas();
    }, RELECTURA_MS * 3);
    return () => window.clearInterval(t);
  }, [recargarListas]);

  // Presencia de la pestaña: el backend se apaga solo al CERRAR la última (no al minimizar).
  useEffect(() => iniciarCicloDeVida(), []);

  // Estado global de los Approval ID que aparecen en la planificación (cada 60 s).
  const idsAprobacion = useMemo(() => {
    if (!vistaServidor) return '';
    const ids = new Set<string>();
    Object.values(vistaServidor.bancos).forEach((filas) => filas.forEach((f: FilaEnsayo) => {
      if (f.datos.approvalId) ids.add(String(f.datos.approvalId));
    }));
    return [...ids].sort().join('\n');
  }, [vistaServidor]);

  // «IDs live» (Tools) fuerza una consulta inmediata de los estados.
  const [consultaAprobaciones, setConsultaAprobaciones] = useState(0);
  const refrescarAprobaciones = useCallback(() => setConsultaAprobaciones((n) => n + 1), []);

  useEffect(() => {
    if (!idsAprobacion) return;
    const consultar = () => api.estadosAprobacion(idsAprobacion.split('\n'))
      .then((r) => setEstadosAprobacion(Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v.status]))))
      .catch(() => undefined);
    void consultar();
    const t = window.setInterval(consultar, 60000);
    return () => window.clearInterval(t);
  }, [idsAprobacion, consultaAprobaciones]);

  // Personas (respPool): usuarios + responsables del catálogo + los ya usados en
  // algún ensayo. No se incluye la lista fija de nombres de la 4.9.4 (D24).
  const personas = useMemo(() => {
    const nombres = new Set(usuariosResp);
    responsablesCatalogo.forEach((n) => nombres.add(n));
    if (vistaServidor) Object.values(vistaServidor.bancos).forEach((fs) => fs.forEach((f) => { if (f.datos.resp) nombres.add(String(f.datos.resp)); }));
    return [...nombres].filter(Boolean).sort((a, b) => a.localeCompare(b));
  }, [usuariosResp, responsablesCatalogo, vistaServidor]);

  // Lo que se ve = lo del servidor + lo escrito y aún no guardado.
  const vista = useMemo(
    () => (vistaServidor
      ? aplicarEnVista(aplicarOperacionesOptimistas(vistaServidor, optimistas), pendientes.current.values())
      : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [vistaServidor, revisionPendientes, optimistas],
  );
  vistaVisibleRef.current = vista;

  const valor: Contexto = {
    vista, sesion, soloLectura, sincronizacion, copiaSinConexion, proyectos, personas, estadosAprobacion, avisos, conflicto,
    mensajeModal, cerrarMensajeModal: () => setMensajeModal(null),
    editarCampo, operar, cerrarEnsayo, avisar, mostrarMensaje: setMensajeModal, recargar: recargarSilencioso, recargarListas, refrescarAprobaciones, versionDe,
  };
  return <ContextoPlanificador.Provider value={valor}>{children}</ContextoPlanificador.Provider>;
}
