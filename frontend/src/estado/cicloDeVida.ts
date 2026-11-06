/*
 * Presencia de esta pestaña ante el backend local.
 *
 * - Cada carga de la página tiene su propio código (una pestaña duplicada o una
 *   recarga son pestañas distintas para el backend).
 * - Mientras la pestaña existe envía un latido cada 5 s con su código. Si está
 *   oculta o minimizada el navegador los espacia o los detiene; da igual: el
 *   backend no da de baja una pestaña por dejar de oírla.
 * - Solo SALIR DE LA PÁGINA la da de baja («pagehide»: cerrar, recargar o ir a
 *   otra dirección en la misma pestaña). Minimizar, cambiar de pestaña,
 *   «visibilitychange» o «freeze» (pestaña dormida) no avisan de nada.
 * - Si la página vuelve desde la caché del navegador («pageshow» con persisted),
 *   o vuelve a estar visible o se despierta («resume»), se envía un latido en el
 *   acto para volver a registrarse.
 */

const INTERVALO_LATIDO_MS = 5000;

function nuevoCodigo(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

export function iniciarCicloDeVida(): () => void {
  const codigo = nuevoCodigo();
  const cuerpo = JSON.stringify({ pestana: codigo });

  const latido = () => {
    void fetch('/api/heartbeat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: cuerpo, keepalive: true })
      .catch(() => undefined);
  };

  // También con persisted = true (la página se guarda en la caché del navegador):
  // si luego se cierra la pestaña ya no habrá otro aviso; si vuelve, «pageshow»
  // la registra de nuevo.
  const alOcultarPagina = () => {
    const datos = new Blob([cuerpo], { type: 'application/json' });
    if (!navigator.sendBeacon?.('/api/shutdown', datos)) {
      void fetch('/api/shutdown', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: cuerpo, keepalive: true })
        .catch(() => undefined);
    }
  };
  const alMostrarPagina = (e: PageTransitionEvent) => { if (e.persisted) latido(); };
  const alCambiarVisibilidad = () => { if (document.visibilityState === 'visible') latido(); };

  latido();
  const temporizador = window.setInterval(latido, INTERVALO_LATIDO_MS);
  window.addEventListener('pagehide', alOcultarPagina);
  window.addEventListener('pageshow', alMostrarPagina);
  document.addEventListener('visibilitychange', alCambiarVisibilidad);
  document.addEventListener('resume', latido);

  return () => {
    window.clearInterval(temporizador);
    window.removeEventListener('pagehide', alOcultarPagina);
    window.removeEventListener('pageshow', alMostrarPagina);
    document.removeEventListener('visibilitychange', alCambiarVisibilidad);
    document.removeEventListener('resume', latido);
  };
}
