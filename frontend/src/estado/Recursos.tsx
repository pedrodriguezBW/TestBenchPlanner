import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api } from '../api/cliente';
import type { Bloqueo, FilaEnsayo, Recurso, Vista } from '../api/tipos';
import { dia } from '../utilidades/fechas';
import { usePlanificador } from './Planificador';

/*
 * Catálogo de equipos auxiliares y bloqueos manuales (RES_CACHE de la 4.9.4).
 *
 * Se lee al arrancar y después de cada cambio. Si la lectura falla se conserva
 * la última copia buena, igual que la caché local de la 4.9.4. Sin conexión
 * (bloqueo de arranque) no se lee, como hacía resLoad con READONLY.
 */

interface ContextoRecursos {
  recursos: Recurso[];
  bloqueos: Bloqueo[];
  cargado: boolean;
  recargar: () => Promise<void>;
  /** Guarda el catálogo completo (resSaveCatalog). */
  guardarCatalogo: (lista: Recurso[]) => Promise<boolean>;
}

const Contexto = createContext<ContextoRecursos | null>(null);

export function useRecursos(): ContextoRecursos {
  const c = useContext(Contexto);
  if (!c) throw new Error('useRecursos fuera de ProveedorRecursos');
  return c;
}

export function ProveedorRecursos({ children }: { children: ReactNode }) {
  const { sincronizacion, mostrarMensaje } = usePlanificador();
  const [recursos, setRecursos] = useState<Recurso[]>([]);
  const [bloqueos, setBloqueos] = useState<Bloqueo[]>([]);
  const [cargado, setCargado] = useState(false);
  const bloqueado = sincronizacion === 'bloqueado';
  const listo = sincronizacion !== 'conectando';

  const recargar = useCallback(async () => {
    if (bloqueado) return;
    try {
      const r = await api.recursos();
      if (r && Array.isArray(r.resources)) {
        setRecursos(r.resources);
        setBloqueos(r.blocks ?? []);
        setCargado(true);
      }
    } catch {
      // se queda la última copia buena
    }
  }, [bloqueado]);

  useEffect(() => { if (listo) void recargar(); }, [listo, recargar]);

  const guardarCatalogo = useCallback(async (lista: Recurso[]) => {
    setRecursos(lista);
    try {
      await api.guardarRecursos(lista);
      return true;
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
      await recargar();
      return false;
    }
  }, [mostrarMensaje, recargar]);

  return <Contexto.Provider value={{ recursos, bloqueos, cargado, recargar, guardarCatalogo }}>{children}</Contexto.Provider>;
}

/* ---- Utilidades de presentación (resTypes, resByType, resUsageOn) ------- */

export interface TipoRecurso { key: string; name: string; dot: string }

/** Equipos agrupados por tipo, en el orden en que aparece cada tipo. */
export function porTipo(recursos: Recurso[]): { tipo: TipoRecurso; equipos: Recurso[] }[] {
  const mapa = new Map<string, { tipo: TipoRecurso; equipos: Recurso[] }>();
  recursos.forEach((r) => {
    const k = r.groupKey || 'otros';
    if (!mapa.has(k)) mapa.set(k, { tipo: { key: k, name: r.groupName || k, dot: r.dot || '#8CA0AD' }, equipos: [] });
    mapa.get(k)!.equipos.push(r);
  });
  return [...mapa.values()];
}

export interface Uso { tipo: 'test' | 'block'; motivo: string; inicio: string; fin: string; id?: string | number }

const solapa = (aS: string, aE: string, bS: string, bE: string) => {
  const a0 = dia(aS), a1 = dia(aE), b0 = dia(bS), b1 = dia(bE);
  return !!(a0 && a1 && b0 && b1 && a0 <= b1 && a1 >= b0);
};

/**
 * Quién ocupa un equipo entre dos fechas (resUsageOn): ensayos activos que lo
 * tienen asignado y cuya barra solapa el periodo, y bloqueos manuales. Las
 * fechas de cada ensayo son las que calcula el backend.
 */
export function usoDe(vista: Vista, bloqueos: Bloqueo[], nombre: string, inicio: string, fin: string, excluir?: string): Uso[] {
  const salida: Uso[] = [];
  vista.grupos.forEach((g) => g.bancos.forEach((b) => {
    (vista.bancos[b.nombre] ?? []).forEach((f: FilaEnsayo) => {
      if (f.cerrado || f.id === excluir) return;
      const lista = Array.isArray(f.datos.resources) ? f.datos.resources : [];
      if (!lista.includes(nombre)) return;
      const c = f.calculo;
      if (c.inicio && c.fin && solapa(inicio, fin, c.inicio, c.fin)) {
        salida.push({ tipo: 'test', motivo: `${b.nombre} \u00b7 ${f.datos.project || f.datos.type || 'ensayo'}`, inicio: c.inicio, fin: c.fin });
      }
    });
  }));
  bloqueos.forEach((bl) => {
    if (bl.resource === nombre && bl.start && bl.end && solapa(inicio, fin, bl.start, bl.end)) {
      salida.push({ tipo: 'block', motivo: (bl.reason || 'Bloqueo') + (bl.note ? ` \u00b7 ${bl.note}` : ''), inicio: bl.start, fin: bl.end, id: bl.id });
    }
  });
  return salida;
}

/** Equipos de una fila que ya están ocupados en sus fechas (resConflictsForRow). */
export function conflictosDeFila(vista: Vista, bloqueos: Bloqueo[], fila: FilaEnsayo): { nombre: string; usos: Uso[] }[] {
  const lista = Array.isArray(fila.datos.resources) ? fila.datos.resources : [];
  const { inicio, fin } = fila.calculo;
  if (!lista.length || !inicio || !fin) return [];
  return lista.map((nombre) => ({ nombre, usos: usoDe(vista, bloqueos, nombre, inicio, fin, fila.id) })).filter((x) => x.usos.length);
}
