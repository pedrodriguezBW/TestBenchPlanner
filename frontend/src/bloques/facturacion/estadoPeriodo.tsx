import { useCallback, useEffect, useState } from 'react';
import { ErrorApi } from '../../api/cliente';
import { apiFacturacion, type VistaPeriodo } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';

/*
 * Periodo que se está mirando (común a Periods, Review y Overview mientras la
 * pestaña está abierta) y carga/acciones con el aviso de conflicto 409.
 */

const CLAVE = 'tbp_facturacion_periodo';
let actual: string | null = (() => { try { return window.sessionStorage.getItem(CLAVE); } catch { return null; } })();
const oyentes = new Set<(id: string | null) => void>();

export function elegirPeriodo(id: string | null) {
  actual = id;
  try { if (id) window.sessionStorage.setItem(CLAVE, id); else window.sessionStorage.removeItem(CLAVE); } catch { /* sin almacenamiento */ }
  oyentes.forEach((f) => f(id));
}

export function usePeriodoElegido(): [string | null, (id: string | null) => void] {
  const [id, setId] = useState<string | null>(actual);
  useEffect(() => {
    oyentes.add(setId);
    return () => { oyentes.delete(setId); };
  }, []);
  return [id, elegirPeriodo];
}

/** Carga el periodo y ejecuta acciones que devuelven la vista nueva. */
export function useVistaPeriodo(id: string | null) {
  const { mostrarMensaje, avisar } = usePlanificador();
  const [vista, setVista] = useState<VistaPeriodo | null>(null);
  const [cargando, setCargando] = useState(false);
  const [ocupado, setOcupado] = useState(false);

  const recargar = useCallback(async () => {
    if (!id) { setVista(null); return; }
    setCargando(true);
    try {
      setVista(await apiFacturacion.periodo(id));
    } catch (e) {
      setVista(null);
      if (!(e instanceof ErrorApi && e.estado === 422)) mostrarMensaje(e instanceof Error ? e.message : String(e));
    } finally {
      setCargando(false);
    }
  }, [id, mostrarMensaje]);

  useEffect(() => { void recargar(); }, [recargar]);

  /** Ejecuta una acción; si otra persona cambió el periodo (409) avisa y vuelve a cargarlo. */
  const ejecutar = useCallback(async (accion: (version: string) => Promise<VistaPeriodo>, textoOk?: string): Promise<boolean> => {
    if (!vista) return false;
    setOcupado(true);
    try {
      setVista(await accion(vista.version));
      if (textoOk) avisar(textoOk);
      return true;
    } catch (e) {
      if (e instanceof ErrorApi && e.estado === 409) {
        mostrarMensaje(`${e.message} Se ha vuelto a cargar el periodo.`);
        await recargar();
      } else {
        mostrarMensaje(e instanceof Error ? e.message : String(e));
      }
      return false;
    } finally {
      setOcupado(false);
    }
  }, [vista, avisar, mostrarMensaje, recargar]);

  return { vista, setVista, cargando, ocupado, recargar, ejecutar };
}
