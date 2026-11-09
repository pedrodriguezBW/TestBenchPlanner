import { useEffect, useState, type ReactNode } from 'react';
import { api } from './api/cliente';
import { VistaBanco } from './bloques/banco/VistaBanco';
import { exportarSoloLectura } from './bloques/exportaciones/descargas';
import { VistaDashboard } from './bloques/panel/VistaDashboard';
import { VistaResponsable } from './bloques/panel/VistaResponsable';
import { VistaKpiHistorico } from './bloques/kpi/VistaKpiHistorico';
import { ListaEquipos } from './bloques/recursos/ListaEquipos';
import { AcercaDe } from './bloques/configuracion/AcercaDe';
import { ConfigBancos } from './bloques/configuracion/ConfigBancos';
import { ConfigHerramientas } from './bloques/configuracion/ConfigHerramientas';
import { ConfigProyectos } from './bloques/configuracion/ConfigProyectos';
import { ConfigUsuarios } from './bloques/configuracion/ConfigUsuarios';
import { RegistroActividad } from './bloques/configuracion/RegistroActividad';
import { VistaRecursos } from './bloques/recursos/VistaRecursos';
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
const PENDIENTES: Record<string, [string, string]> = {};

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
  // Sin ruta se abre el Dashboard, como en la 4.9.4.
  const pantalla = actual || '__dash__';
  const esAdmin = !!sesion?.esAdmin;
  const esEditor = sesion?.rol === 'editor';
  const [textoSync, colorSync] = SINCRONIZACION[sincronizacion];
  const idsUsados = vista ? [...new Set(Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.approvalId ?? ''))).filter(Boolean))] : [];
  const proyectosUsados = vista ? [...new Set([...proyectos, ...Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.project ?? '')))].filter(Boolean))] : proyectos;

  /** 📤 Export read-only: antes pregunta si se guarda la foto semanal, como la 4.9.4. */
  const exportarFotoSoloLectura = async () => {
    if (!vista) return;
    try {
      if (window.confirm('Before exporting: dump this week\u2019s KPIs into the annual total (Update Yearly Status)?\n\nOK = save the yearly snapshot now.  Cancel = just export.')) {
        await api.fotoSemanal().catch(() => undefined);
      }
      await exportarSoloLectura(vista.hoy);
    } catch (e) {
      window.alert(`Could not export read-only snapshot: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

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
        {esAdmin && vista && (
          <button onClick={() => void exportarFotoSoloLectura()} title="Exportar una foto HTML de solo lectura para el proveedor">&#128228; Export read-only</button>
        )}
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
          {esAdmin && pestana('__rescfg__', <>&#128296; Equipments List</>)}
          {(esAdmin || esEditor) && pestana('__projects__', <>&#128193; Projects</>)}
          {esAdmin && pestana('__users__', <>&#128100; Users/Rights Management</>)}
          {esAdmin && pestana('__tools__', <>&#128295; Tools</>)}
          {pestana('__about__', <>&#8505;&#65039; About</>)}
          {esAdmin && pestana('__activity__', <>&#128220; Activity log</>)}
        </nav>
        <main id="main">
          {!vista && sincronizacion !== 'bloqueado' && <div className="empty">Conectando&hellip;</div>}
          {vista && bancos.includes(pantalla) && (
            <div className="tbp-sin-marco"><VistaBanco banco={pantalla} onIrABanco={ir} /></div>
          )}
          {vista && pantalla === '__dash__' && <VistaDashboard />}
          {vista && pantalla === '__resp__' && <VistaResponsable />}
          {vista && pantalla === '__kpihist__' && <VistaKpiHistorico />}
          {vista && pantalla === '__resources__' && <VistaRecursos />}
          {vista && pantalla === '__rescfg__' && (esAdmin ? <ListaEquipos /> : <VistaDashboard />)}
          {/* Configuración (bloque E): las pantallas de Admin llevan al Dashboard si no se es Admin, como en la 4.9.4. */}
          {vista && pantalla === '__benches__' && (esAdmin ? <ConfigBancos /> : <VistaDashboard />)}
          {vista && pantalla === '__projects__' && (esAdmin || esEditor ? <ConfigProyectos /> : <VistaDashboard />)}
          {vista && pantalla === '__users__' && (esAdmin ? <ConfigUsuarios /> : <VistaDashboard />)}
          {vista && pantalla === '__tools__' && (esAdmin ? <ConfigHerramientas /> : <VistaDashboard />)}
          {vista && pantalla === '__activity__' && (esAdmin ? <RegistroActividad /> : <VistaDashboard />)}
          {vista && pantalla === '__about__' && <AcercaDe version={VERSION} />}
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
