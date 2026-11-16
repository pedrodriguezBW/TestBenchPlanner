import { useEffect, useSyncExternalStore } from 'react';
import { colaConfiguracion, type Vaciado } from './colaConfiguracion';

/** Trabajos de configuración sin terminar (para el indicador «Saving…»). */
export function useColaConfiguracion() {
  return useSyncExternalStore(colaConfiguracion.suscribir, colaConfiguracion.estado);
}

/** Avisa a la pantalla cuando la cola se vacía (para soltar su copia local). */
export function useAlVaciarConfiguracion(f: (v: Vaciado) => void) {
  useEffect(() => colaConfiguracion.alVaciarse(f), [f]);
}
