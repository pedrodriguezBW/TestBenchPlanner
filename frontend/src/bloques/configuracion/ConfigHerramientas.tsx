import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/cliente';
import type { CalendarioPc } from '../../api/tipos';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { dia } from '../../utilidades/fechas';
import { exportarCopiaJson } from '../exportaciones/descargas';

/*
 * 🔧 Tools (renderTools de la 4.9.4, solo Admin): calendario de festivos de
 * este PC, copia de seguridad JSON (Export / Import), «IDs live» y «Reset (local)».
 */
export function ConfigHerramientas() {
  const { vista, soloLectura, refrescarAprobaciones, recargar, recargarListas, avisar, mostrarMensaje } = usePlanificador();
  const [calendarioAbierto, setCalendarioAbierto] = useState(false);
  const fichero = useRef<HTMLInputElement>(null);

  /**
   * Import (JSON), igual que importJSON de la 4.9.4 (D10): la planificación se
   * sustituye por la de la copia sin más confirmación. El backend mantiene las
   * protecciones (solo Admin, listas que no se pueden vaciar).
   */
  const importar = async (f: File) => {
    let d: { benches?: unknown };
    try {
      d = JSON.parse(await f.text());
    } catch {
      window.alert('Could not read file');
      return;
    }
    if (!d || !d.benches) { window.alert('Invalid file'); return; }
    try {
      await api.importarCopia(d);
      await Promise.all([recargar(), recargarListas()]);
      window.alert('Data imported');
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };

  const reiniciarLocal = async () => {
    if (!window.confirm('Reset all LOCAL data to seed? (SharePoint no se toca)')) return;
    // Lo local de la versión nueva: preferencias de pantalla de este navegador y
    // calendario de este PC (año actual, festivos de Vigo, saltar festivos).
    const anio = (dia(vista?.hoy) ?? new Date()).getFullYear();
    try {
      const c = await api.cambiarAnioCalendario(anio);
      await api.guardarCalendario({ ...c, saltarFestivos: true });
    } catch {
      // sin backend: al menos se limpian las preferencias
    }
    Object.keys(localStorage).filter((k) => /^(tbp_|bw_)/.test(k)).forEach((k) => localStorage.removeItem(k));
    window.location.reload();
  };

  return (
    <div className="sec" id="config-herramientas">
      <h2>&#128295; Tools</h2>
      <div className="hint">Acciones movidas aqui desde la barra superior. Solo Admin.</div>
      <div className="cfg-tools">
        <button className="btn" onClick={() => setCalendarioAbierto(true)}>&#128197; Calendar</button>
        <button className="btn ghost" onClick={() => void exportarCopiaJson().catch((e: Error) => mostrarMensaje(e.message))}>&#11015;&#65039; Export (JSON backup)</button>
        <button className="btn ghost" disabled={soloLectura} onClick={() => fichero.current?.click()}>&#11014;&#65039; Import (JSON)</button>
        <input ref={fichero} id="imp" type="file" accept="application/json" style={{ display: 'none' }} onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          if (f) void importar(f);
        }} />
        <button className="btn ghost" onClick={() => { refrescarAprobaciones(); avisar('Estados de aprobación actualizados.'); }}>&#128260; IDs live</button>
        <button className="btn" style={{ background: 'var(--danger)', color: '#2a0b0b' }} disabled={soloLectura} onClick={() => void reiniciarLocal()}>&#8635; Reset (local)</button>
      </div>
      <div className="hint" style={{ marginTop: 10 }}>
        &#128197; <b>Calendar</b>: festivos y reglas de fin de semana &middot; <b>Export/Import</b>: copia de seguridad JSON local &middot; <b>IDs live</b>: refresca estados de aprobacion desde SharePoint &middot; <b>Reset</b>: reinicia solo tu copia local
        (los datos del equipo en SharePoint permanecen).
      </div>
      {calendarioAbierto && <VentanaCalendario onCerrar={() => { setCalendarioAbierto(false); void recargar(); }} />}
    </div>
  );
}

/** «Holiday calendar (local · Vigo)» (openCfg). Cada cambio se guarda en el acto, como en la 4.9.4. */
export function VentanaCalendario({ onCerrar }: { onCerrar: () => void }) {
  const { vista, soloLectura, mostrarMensaje, recargar } = usePlanificador();
  const [cal, setCal] = useState<CalendarioPc | null>(null);
  const [nuevaFecha, setNuevaFecha] = useState('');
  const [nuevoNombre, setNuevoNombre] = useState('');
  const hoy = (dia(vista?.hoy) ?? new Date()).getFullYear();
  const anios = [hoy - 1, hoy, hoy + 1, hoy + 2];

  useEffect(() => { api.calendario().then(setCal).catch((e: Error) => mostrarMensaje(e.message)); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const aplicar = async (accion: Promise<CalendarioPc>) => {
    try {
      setCal(await accion);
      await recargar(); // el calendario cambia el plan
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };
  if (!cal) return null;
  const anadir = () => {
    if (!nuevaFecha) return;
    const festivos = cal.festivos.some((f) => f.fecha === nuevaFecha) ? cal.festivos
      : [...cal.festivos, { fecha: nuevaFecha, nombre: nuevoNombre || 'Custom' }].sort((a, b) => (a.fecha < b.fecha ? -1 : 1));
    setNuevaFecha('');
    setNuevoNombre('');
    void aplicar(api.guardarCalendario({ ...cal, festivos }));
  };

  return (
    <Dialogo abierto onCerrar={onCerrar}>
      <h3>Holiday calendar (local &middot; Vigo)</h3>
      <div className="row-inline">
        <label>Work year:</label>
        <select id="cfg-year" value={cal.anio} disabled={soloLectura} onChange={(e) => void aplicar(api.cambiarAnioCalendario(Number(e.target.value)))}>
          {(anios.includes(cal.anio) ? anios : [...anios, cal.anio]).map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <button className="btn sm" disabled={soloLectura} onClick={() => void aplicar(api.cambiarAnioCalendario(cal.anio))}>&#8635; Regenerate</button>
      </div>
      <div className="toggle">
        <input type="checkbox" id="cfg-skip" checked={cal.saltarFestivos} disabled={soloLectura}
          onChange={(e) => { const c = { ...cal, saltarFestivos: e.target.checked }; setCal(c); void aplicar(api.guardarCalendario(c)); }} />
        <label htmlFor="cfg-skip">Skip holidays when scheduling (Weekend run = No)</label>
      </div>
      <div className="row-inline">
        <input type="date" id="cfg-newd" value={nuevaFecha} disabled={soloLectura} onChange={(e) => setNuevaFecha(e.target.value)} />
        <input id="cfg-newn" placeholder="name (optional)" style={{ flex: 1 }} value={nuevoNombre} disabled={soloLectura} onChange={(e) => setNuevoNombre(e.target.value)} />
        <button className="btn sm" disabled={soloLectura} onClick={anadir}>&#65291; Add</button>
      </div>
      <div id="cfg-list">
        {cal.festivos.map((f, i) => (
          <div className="hol-item" key={f.fecha + i}>
            <span className="d">{f.fecha}</span><span className="n">{f.nombre || ''}</span>
            <button className="icbtn del" disabled={soloLectura}
              onClick={() => void aplicar(api.guardarCalendario({ ...cal, festivos: cal.festivos.filter((_, j) => j !== i) }))}>&#128465;</button>
          </div>
        ))}
      </div>
      <div style={{ textAlign: 'right', marginTop: 14 }}><button className="btn" onClick={onCerrar}>Done</button></div>
    </Dialogo>
  );
}
