import { useEffect, useState } from 'react';
import { api } from '../api/cliente';
import type { Conectado, Sesion } from '../api/tipos';

/*
 * Piezas de la barra superior que no son de ninguna pantalla: quién está
 * conectado, aviso de versión antigua, bienvenida y aviso de modo sin
 * conexión. Reproducen renderPresence, checkVersion, showWelcome y
 * showOfflineSnapBanner de la 4.9.4.
 */

/** Burbujas de presencia: latido a los 2 s y después cada 15 s (pollPresence). */
export function Presencia({ activa }: { activa: boolean }) {
  const [conectados, setConectados] = useState<Conectado[]>([]);
  useEffect(() => {
    if (!activa) return undefined;
    let intervalo: number | undefined;
    const latido = () => api.presencia().then((r) => { if (Array.isArray(r.online)) setConectados(r.online); }).catch(() => undefined);
    const inicio = window.setTimeout(() => { void latido(); intervalo = window.setInterval(latido, 15000); }, 2000);
    return () => { window.clearTimeout(inicio); window.clearInterval(intervalo); };
  }, [activa]);

  const MAX = 6;
  const vistos = conectados.slice(0, MAX);
  const resto = conectados.slice(MAX);
  return (
    <div id="presence" className="presence-wrap" title="Usuarios conectados ahora mismo">
      {vistos.map((u) => (
        <div key={u.email} className={`pres-av ${u.me ? 'me' : ''}`} style={{ background: u.color || '#23BCA3' }}
          title={`${u.name || u.email || ''}${u.me ? ' (tú)' : ''}`}>{String(u.initials || '?').toUpperCase().slice(0, 2)}</div>
      ))}
      {resto.length > 0 && <div className="pres-more" title={resto.map((u) => u.name || u.email).join(', ')}>+{resto.length}</div>}
      {conectados.length > 0 && <span className="pres-lbl">{conectados.length} en línea</span>}
    </div>
  );
}

/** Tramos numéricos de una versión («v4.10.1» → [4, 10, 1]); verParts de la 4.9.4. */
export function tramosVersion(v: string): number[] {
  return String(v || '').trim().replace(/^v/i, '').split(/[^0-9]+/).filter((x) => x !== '').map(Number);
}

/** Comparación numérica por tramos (cmpVer): 4.10 > 4.9. */
export function compararVersiones(a: string, b: string): number {
  const x = tramosVersion(a), y = tramosVersion(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const p = x[i] || 0, q = y[i] || 0;
    if (p !== q) return p < q ? -1 : 1;
  }
  return 0;
}

/** Aviso de versión antigua: al arrancar y cada 2 min (checkVersion, isStaleVersion). */
export function AvisoVersion({ version }: { version: string }) {
  const [publicada, setPublicada] = useState('');
  useEffect(() => {
    const consultar = () => api.version().then((r) => setPublicada(String(r.published || r.backend || '').trim())).catch(() => undefined);
    void consultar();
    const t = window.setInterval(consultar, 120000);
    return () => window.clearInterval(t);
  }, []);
  const antigua = !!publicada && tramosVersion(publicada).length > 0 && compararVersiones(version, publicada) < 0;
  if (!antigua) return null;
  return (
    <div id="ver-banner" style={{ display: 'block' }}>
      &#9888;&#65039; Estás usando una versión antigua (<b>v{version}</b>). Hay disponible <b>v{publicada}</b>. Cierra y vuelve a abrir la aplicación.
    </div>
  );
}

/** «Welcome back» durante 3 s al identificar al usuario (showWelcome). */
export function Bienvenida({ sesion }: { sesion: Sesion | null }) {
  const [visible, setVisible] = useState(false);
  const clave = sesion?.email ?? null;
  useEffect(() => {
    if (clave === null) return undefined;
    const a = window.requestAnimationFrame(() => setVisible(true));
    const t = window.setTimeout(() => setVisible(false), 3000);
    return () => { window.cancelAnimationFrame(a); window.clearTimeout(t); };
  }, [clave]);
  const nombre = sesion ? (sesion.nombre || sesion.responsable || sesion.email || 'there') : '\u2026';
  const rol = !sesion ? '' : sesion.rol === 'admin' ? 'Administrator access' : sesion.rol === 'editor' ? 'Editor access' : 'Read-only access';
  return (
    <div id="welcome-toast" className={visible ? 'show' : ''}>
      <div className="wt-hi">&#128075; Welcome back</div>
      <div className="wt-name" id="wt-name">{nombre}</div>
      <div className="wt-role" id="wt-role">{rol}</div>
    </div>
  );
}

/** Banner «Modo offline · solo consulta» con la fecha de la copia (dd/mm hh:mm, hora local). */
export function AvisoSinConexion({ copia }: { copia: string | null }) {
  if (!copia) return null;
  const d = new Date(copia);
  const dos = (n: number) => String(n).padStart(2, '0');
  const cuando = Number.isNaN(d.getTime()) ? '\u2014' : `${dos(d.getDate())}/${dos(d.getMonth() + 1)} ${dos(d.getHours())}:${dos(d.getMinutes())}`;
  return (
    <div id="ro-banner" style={{ display: 'block', background: '#243447', color: '#cfe8ff', textAlign: 'center', padding: '7px 12px', fontSize: 12, fontWeight: 600 }}>
      &#9992;&#65039; <b>Modo offline &middot; solo consulta.</b> Mostrando la última copia local ({cuando}). No se guardará nada; al recuperar conexión manda SharePoint.
    </div>
  );
}
