/*
 * Cola de guardado de la configuración compartida (Test Benches y Projects).
 *
 * Como en la 4.9.4 (pushConfig), la pantalla cambia EN EL ACTO y el guardado en
 * SharePoint va en segundo plano. A diferencia de la 4.9.4, un fallo no pasa
 * desapercibido: se avisa y la pantalla vuelve a lo que hay de verdad guardado.
 *
 * - Los trabajos se envían de uno en uno y en orden.
 * - Un trabajo «fusionable» que todavía no ha salido se sustituye por el
 *   siguiente de la misma clave (cada uno lleva la lista completa: basta con
 *   enviar el último). Renombrar no es fusionable: el backend necesita cada
 *   renombrado para mover los ensayos.
 * - Si un trabajo falla, los que esperaban se descartan (estaban hechos sobre
 *   un estado que no se ha guardado).
 * - Al vaciarse la cola se ejecuta «despues» (recargar vista y listas) una sola
 *   vez y luego se avisa a las pantallas para que suelten su copia local.
 *
 * Este módulo no depende de React: se prueba con node --test.
 */

export interface TrabajoConfiguracion {
  clave: string;
  fusionable: boolean;
  ejecutar: () => Promise<void>;
}

export interface EstadoColaConfiguracion {
  /** Trabajos sin terminar (el que se está enviando incluido). */
  pendientes: number;
}

export interface Vaciado {
  /** Error del trabajo que falló (null si todo se guardó). */
  fallo: unknown;
}

export interface OpcionesCola {
  despues?: (v: Vaciado) => Promise<void> | void;
  alFallar?: (error: unknown) => void;
}

export function crearColaConfiguracion() {
  let cola: TrabajoConfiguracion[] = [];
  let enCurso = false;
  let estado: EstadoColaConfiguracion = { pendientes: 0 };
  let opciones: OpcionesCola = {};
  const oyentes = new Set<() => void>();
  const alVaciar = new Set<(v: Vaciado) => void>();

  const avisar = (enviando: boolean) => {
    const pendientes = cola.length + (enviando ? 1 : 0);
    if (pendientes !== estado.pendientes) {
      estado = { pendientes };
      oyentes.forEach((f) => f());
    }
  };

  async function procesar() {
    if (enCurso) return;
    enCurso = true;
    let fallo: unknown = null;
    for (;;) {
      while (cola.length) {
        const trabajo = cola.shift()!;
        avisar(true);
        try {
          await trabajo.ejecutar();
        } catch (e) {
          fallo = e;
          cola = [];
          try { opciones.alFallar?.(e); } catch { /* el aviso no puede parar la cola */ }
        }
      }
      try { await opciones.despues?.({ fallo }); } catch { /* recargar es lo mejor que se puede hacer */ }
      if (!cola.length) break; // si se ha cambiado algo mientras se recargaba, se sigue
    }
    enCurso = false;
    avisar(false);
    const v: Vaciado = { fallo };
    [...alVaciar].forEach((f) => { try { f(v); } catch { /* una pantalla no para a las demás */ } });
  }

  return {
    encolar(trabajo: TrabajoConfiguracion) {
      const ultimo = cola[cola.length - 1];
      if (ultimo && trabajo.fusionable && ultimo.fusionable && ultimo.clave === trabajo.clave) {
        cola[cola.length - 1] = trabajo;
      } else {
        cola.push(trabajo);
      }
      avisar(enCurso);
      void procesar();
    },
    configurar(o: OpcionesCola) { opciones = o; },
    estado: () => estado,
    pendiente: () => enCurso || cola.length > 0,
    suscribir(f: () => void) { oyentes.add(f); return () => { oyentes.delete(f); }; },
    alVaciarse(f: (v: Vaciado) => void) { alVaciar.add(f); return () => { alVaciar.delete(f); }; },
  };
}

export type ColaConfiguracion = ReturnType<typeof crearColaConfiguracion>;

/** La cola de la aplicación (una para todas las pantallas de configuración). */
export const colaConfiguracion = crearColaConfiguracion();
