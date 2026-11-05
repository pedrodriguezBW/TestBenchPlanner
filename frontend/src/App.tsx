import { useEffect, useState, type ReactNode } from 'react';
import { VistaBanco } from './bloques/banco/VistaBanco';
import { Avisos, DialogoConflicto } from './componentes/Avisos';
import { usePlanificador, type EstadoSincronizacion } from './estado/Planificador';
import { TIPOS_DE_ENSAYO } from './utilidades/textos';

/*
 * Estructura de la aplicación: barra superior, menú lateral y la pantalla
 * elegida. Las pantallas de los bloques B–F (dashboard, KPI, recursos,
 * configuración, exportaciones) se irán incorporando; mientras tanto se
 * indica que siguen disponibles en la interfaz clásica.
 */

const VERSION = '5.0.0';

const SINCRONIZACION: Record<EstadoSincronizacion, [string, string]> = {
  ok: ['\u25CF Synced', 'var(--ok)'],
  guardando: ['\u21BB Saving\u2026', 'var(--warn)'],
  error: ['\u26A0 Sin backend', 'var(--danger)'],
  conectando: ['\u25CB Conectando\u2026', 'var(--muted)'],
  bloqueado: ['\uD83D\uDD12 Solo lectura (sin conexion)', 'var(--danger)'],
};

/** Secciones que aún no están migradas (se indican, pero no se inventan). */
const PENDIENTES: Record<string, [string, string]> = {
  __dash__: ['\u{1F4CA} Dashboard', 'bloque B'],
  __resp__: ['\u{1F464} By Responsible', 'bloque B'],
  __kpihist__: ['\u{1F4C8} KPI History', 'bloque C'],
  __resources__: ['\u{1F4CB} List of Equipments', 'bloque D'],
  __benches__: ['\u{1F527} Test Benches', 'bloque E'],
  __projects__: ['\u{1F4C1} Projects', 'bloque E'],
  __users__: ['\u{1F464} Users/Rights Management', 'bloque E'],
  __tools__: ['\u{1F527} Tools', 'bloque E'],
  __about__: ['\u2139\uFE0F About', 'bloque E'],
};

function leerRuta(): string {
  return decodeURIComponent(window.location.hash.replace(/^#\/?/, '')) || '';
}

export function App() {
  const { vista, sesion, soloLectura, sincronizacion, proyectos, personas } = usePlanificador();
  const [actual, setActual] = useState(leerRuta);

  useEffect(() => {
    const alCambiar = () => setActual(leerRuta());
    window.addEventListener('hashchange', alCambiar);
    return () => window.removeEventListener('hashchange', alCambiar);
  }, []);

  const ir = (destino: string) => { window.location.hash = '/' + encodeURIComponent(destino); };
  const bancos = vista ? vista.grupos.flatMap((g) => g.bancos.map((b) => b.nombre)) : [];
  // Sin ruta, se abre el primer banco mientras el dashboard no esté migrado.
  const pantalla = actual || bancos[0] || '';
  const esAdmin = !!sesion?.esAdmin;
  const esEditor = sesion?.rol === 'editor';
  const [textoSync, colorSync] = SINCRONIZACION[sincronizacion];
  const idsUsados = vista ? [...new Set(Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.approvalId ?? ''))).filter(Boolean))] : [];
  const proyectosUsados = vista ? [...new Set([...proyectos, ...Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.project ?? '')))].filter(Boolean))] : proyectos;

  const pestana = (clave: string, contenido: ReactNode) => (
    <div key={clave} className={`tab ${pantalla === clave ? 'active' : ''}`} onClick={() => ir(clave)}>{contenido}</div>
  );

  return (
    <div className={soloLectura ? 'ro' : ''}>
      <header className="topbar">
        <div className="logo-chip"><img src="/logo.png" alt="BorgWarner" /></div>
        <h1>
          Test Bench Planning <span className="build-tag">v{VERSION}</span>
          {sesion && <span className={`role-pill ${sesion.rol}`}>{sesion.rol === 'readonly' ? 'Read only' : sesion.rol}</span>}
        </h1>
        <div className="spacer" />
        <a className="btn ghost sm no-print" href="/clasico" title="Interfaz 4.9.4 (respaldo)">Versión clásica</a>
        <span id="sp-status" style={{ fontSize: 12, fontWeight: 600, color: colorSync, margin: '0 8px', whiteSpace: 'nowrap' }}>{textoSync}</span>
        <button onClick={() => window.print()}>&#128424; Print</button>
      </header>
      {sincronizacion === 'bloqueado' && (
        <div id="lock-banner" style={{ display: 'block' }}>&#128274; <b>SIN CONEXI&Oacute;N CON SHAREPOINT</b> &middot; no se ha podido leer el servidor,
          as&iacute; que la app est&aacute; en <b>solo lectura</b> para proteger los datos. <b>No se guardar&aacute; nada.</b> Cierra y vuelve a abrir cuando tengas sesi&oacute;n.</div>
      )}
      <div className="layout">
        <nav className="side" id="side">
          {pestana('__dash__', <>&#128202; Dashboard</>)}
          {pestana('__resp__', <>&#128100; By Responsible</>)}
          {pestana('__kpihist__', <>&#128200; KPI History</>)}
          {vista?.grupos.map((g) => (
            <div key={g.clave}>
              <div className="grp-title">{g.nombre}</div>
              {g.bancos.map((b) => pestana(b.nombre, <><span className="dot" style={{ background: g.color }} />{b.nombre}</>))}
            </div>
          ))}
          <div className="grp-title">Additional Equipment</div>
          {pestana('__resources__', <>&#128203; List of Equipments</>)}
          <div className="grp-title">Configuration</div>
          {esAdmin && pestana('__benches__', <>&#128296; Test Benches</>)}
          {(esAdmin || esEditor) && pestana('__projects__', <>&#128193; Projects</>)}
          {esAdmin && pestana('__users__', <>&#128100; Users/Rights Management</>)}
          {esAdmin && pestana('__tools__', <>&#128295; Tools</>)}
          {pestana('__about__', <>&#8505;&#65039; About</>)}
        </nav>
        <main id="main">
          {!vista && sincronizacion !== 'bloqueado' && <div className="empty">Conectando&hellip;</div>}
          {vista && bancos.includes(pantalla) && (
            <fieldset disabled={soloLectura} className="tbp-sin-marco"><VistaBanco banco={pantalla} onIrABanco={ir} /></fieldset>
          )}
          {vista && PENDIENTES[pantalla] && (
            <div className="sec">
              <h2>{PENDIENTES[pantalla][0]}</h2>
              <div className="hint">
                Esta pantalla se incorpora en el {PENDIENTES[pantalla][1]} de la migración. Mientras tanto está disponible en
                la <a href="/clasico">versión clásica</a>.
              </div>
            </div>
          )}
        </main>
      </div>
      <datalist id="dl-types">{TIPOS_DE_ENSAYO.map((t) => <option key={t} value={t} />)}</datalist>
      <datalist id="dl-resp">{personas.map((p) => <option key={p} value={p} />)}</datalist>
      <datalist id="dl-proj">{proyectosUsados.map((p) => <option key={p} value={p} />)}</datalist>
      <datalist id="dl-ids">{idsUsados.map((p) => <option key={p} value={p} />)}</datalist>
      <Avisos />
      <DialogoConflicto />
    </div>
  );
}
