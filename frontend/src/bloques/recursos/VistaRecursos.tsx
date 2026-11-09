import { useEffect, useLayoutEffect, useRef, useState, type ReactElement } from 'react';
import { api } from '../../api/cliente';
import { Dialogo } from '../../componentes/Dialogo';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { porTipo, useRecursos } from '../../estado/Recursos';
import { dia, diasEntre, esFinDeSemana, fmt, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';
import { colorEstado } from '../../utilidades/textos';
import { fijarCabeceras } from '../panel/GanttGeneral';
import { exportarGanttExcel, matrizGantt } from '../exportaciones/descargas';

/*
 * 📋 List of Equipments (renderResources de la 4.9.4): ocupación de cada equipo
 * auxiliar por tipo, con los ensayos que lo usan y los bloqueos manuales
 * (rayados); los solapes se marcan en ámbar. Debajo, la lista de bloqueos.
 * Bloquear y borrar bloqueos es solo para Admin, como en la 4.9.4.
 */
const MOTIVOS_BLOQUEO = ['Calibración', 'Mantenimiento', 'Avería', 'Otro'];
const ANCHO_ETIQUETA = 200;

export function VistaRecursos() {
  const { vista, sesion, mostrarMensaje, soloLectura } = usePlanificador();
  const { bloqueos, recargar } = useRecursos();
  const { semana, setSemana, zoomRecursos, setZoomRecursos } = usePanel();
  const [bloquear, setBloquear] = useState<string | null>(null);
  const esAdmin = !!sesion?.esAdmin;
  const puedeEscribir = esAdmin && !soloLectura;
  if (!vista) return null;

  const borrar = async (id: string | number) => {
    if (!puedeEscribir) return;
    if (!window.confirm('Eliminar este bloqueo?')) return;
    try { await api.borrarBloqueo(id); } catch { /* como la 4.9.4: se quita igualmente de la vista */ }
    await recargar();
  };
  const lista = [...bloqueos].sort((x, y) => String(x.start || '').localeCompare(String(y.start || '')));

  return (
    <div id="vista-recursos">
      <div className="view-head">
        <h2>&#128295; Additional Equipment</h2>
        <div className="week-nav no-print" style={{ marginLeft: 'auto' }}>
          <button className="btn ghost sm" onClick={() => setSemana(sumarDias(semana, -7))}>&#9664; Prev</button>
          <b>CW{semanaIso(semana)}</b>
          <button className="btn ghost sm" onClick={() => setSemana(sumarDias(semana, 7))}>Next &#9654;</button>
          <button className="btn ghost sm" onClick={() => setSemana(lunesDe(dia(vista.hoy)!))}>Today</button>
          <button className="btn ghost sm" onClick={() => setZoomRecursos(!zoomRecursos)}>{zoomRecursos ? '\u{1F5D3} Full year' : '\u{1F50D} Zoom to week'}</button>
          {puedeEscribir && <button className="btn sm" onClick={() => setBloquear('')}>&#128274; Bloquear recurso</button>}
        </div>
      </div>
      <div className="hint">
        Ocupación de cilindros, generadores de pulsos y demás equipos auxiliares, agrupados por tipo. Las barras son los ensayos que usan el
        recurso; las rayadas son bloqueos manuales (calibración, avería…). La ocupación sale de las fechas de cada ensayo.
      </div>
      <div className="card"><GanttRecursos /></div>
      <div className="card">
        <h3 style={{ margin: '0 0 8px' }}>Bloqueos manuales ({lista.length})</h3>
        {!lista.length
          ? <div className="hint">No hay bloqueos. Usa &#128274; Bloquear recurso para reservar un equipo por calibración, avería, etc.</div>
          : (
            <table className="lst" id="tabla-bloqueos">
              <thead><tr><th>Recurso</th><th>Desde</th><th>Hasta</th><th>Motivo</th><th>Nota</th><th /></tr></thead>
              <tbody>
                {lista.map((b) => (
                  <tr key={String(b.id)}>
                    <td><b>{b.resource || ''}</b></td><td>{b.start ? fmt(b.start) : '\u2014'}</td><td>{b.end ? fmt(b.end) : '\u2014'}</td>
                    <td>{b.reason || ''}</td><td>{b.note || ''}</td>
                    <td style={{ textAlign: 'right' }}>{puedeEscribir && <button className="icbtn del" onClick={() => void borrar(b.id)}>&#128465;</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </div>
      {bloquear !== null && (
        <VentanaBloqueo inicial={bloquear} hoy={vista.hoy} onCerrar={() => setBloquear(null)}
          onGuardado={() => void recargar()} onError={mostrarMensaje} />
      )}
    </div>
  );
}

/** Ventana «Bloquear recurso» (openBlockModal / saveBlock). */
function VentanaBloqueo({ inicial, hoy, onCerrar, onGuardado, onError }: {
  inicial: string; hoy: string; onCerrar: () => void; onGuardado: () => void; onError: (m: string) => void;
}) {
  const { recursos } = useRecursos();
  const [recurso, setRecurso] = useState(inicial || recursos[0]?.name || '');
  const [inicio, setInicio] = useState(hoy);
  const [fin, setFin] = useState(iso(sumarDias(dia(hoy)!, 1)));
  const [motivo, setMotivo] = useState(MOTIVOS_BLOQUEO[0]);
  const [nota, setNota] = useState('');

  const guardar = async () => {
    if (!recurso || !inicio || !fin) { window.alert('Completa recurso y fechas.'); return; }
    onCerrar();
    try {
      const r = await api.guardarBloqueo({ resource: recurso, start: inicio, end: fin, reason: motivo, note: nota });
      if (r && r.ok) onGuardado(); else onError('No se pudo guardar el bloqueo (backend).');
    } catch {
      onError('Sin backend: no se guardó el bloqueo.');
    }
  };

  return (
    <Dialogo abierto onCerrar={onCerrar}>
      <h3>Bloquear recurso</h3>
      <div className="row-inline"><label style={{ minWidth: 80 }}>Recurso</label>
        <select id="blk-res" style={{ flex: 1 }} value={recurso} onChange={(e) => setRecurso(e.target.value)}>
          {recursos.map((r) => <option key={r.name}>{r.name}</option>)}
        </select></div>
      <div className="row-inline"><label style={{ minWidth: 80 }}>Desde</label><input type="date" id="blk-start" value={inicio} onChange={(e) => setInicio(e.target.value)} />
        <label>Hasta</label><input type="date" id="blk-end" value={fin} onChange={(e) => setFin(e.target.value)} /></div>
      <div className="row-inline"><label style={{ minWidth: 80 }}>Motivo</label>
        <select id="blk-reason" value={motivo} onChange={(e) => setMotivo(e.target.value)}>{MOTIVOS_BLOQUEO.map((m) => <option key={m}>{m}</option>)}</select></div>
      <div className="row-inline"><label style={{ minWidth: 80 }}>Nota</label>
        <input id="blk-note" placeholder="opcional" style={{ flex: 1 }} value={nota} onChange={(e) => setNota(e.target.value)} /></div>
      <div style={{ textAlign: 'right', marginTop: 14 }}>
        <button className="btn ghost" onClick={onCerrar}>Cancelar</button>{' '}
        <button className="btn" onClick={() => void guardar()}>Guardar bloqueo</button>
      </div>
    </Dialogo>
  );
}

/** Gantt por equipo (resourceGanttHTML). */
function GanttRecursos() {
  const { vista, proyectos } = usePlanificador();
  const { recursos, bloqueos } = useRecursos();
  const { semana, zoomRecursos: zoom, anchoRecursos, setAnchoRecursos } = usePanel();
  const [foco, setFoco] = usePreferencia<string>('tbp_gantt_foco', '');
  const [anchoEtiqueta, setAnchoEtiqueta] = usePreferencia<number | null>('bw_gantt_lw', null);
  const caja = useRef<HTMLDivElement>(null);
  const base = zoom ? 26 : 9;
  const W = anchoRecursos || base;
  const LW = anchoEtiqueta ?? ANCHO_ETIQUETA;
  const hoy = dia(vista!.hoy)!;

  const min = zoom ? semana : lunesDe(new Date(vista!.calendario.anio, 0, 4));
  const max = zoom ? sumarDias(semana, 6) : sumarDias(min, 53 * 7 - 1);
  const n = diasEntre(min, max) + 1;
  const desplazamientoHoy = hoy >= min && hoy <= max ? diasEntre(min, hoy) * W : -9999;

  useEffect(() => {
    if (!caja.current) return;
    caja.current.scrollLeft = Math.max(0, LW + (desplazamientoHoy > 0 ? desplazamientoHoy : 0) - caja.current.clientWidth / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [min.getTime(), W, zoom]);
  useLayoutEffect(() => fijarCabeceras(caja.current));

  const grupos = porTipo(recursos);
  if (!grupos.length) return <div className="empty">No hay recursos. Créalos en Configuration → Equipments List.</div>;

  const dias = Array.from({ length: n }, (_, i) => sumarDias(min, i));
  const festivos = new Set(vista!.calendario.festivos.map((f) => f.fecha));
  const meses: { etiqueta: string; dias: number }[] = [];
  for (let i = 0; i < n;) {
    const m = dias[i].getMonth();
    let sp = 0;
    while (i + sp < n && dias[i + sp].getMonth() === m) sp++;
    meses.push({ etiqueta: dias[i].toLocaleDateString('en-GB', { month: 'short', year: '2-digit' }), dias: sp });
    i += sp;
  }
  const semanas: { cw: number; dias: number }[] = [];
  for (let i = 0; i < n; i += 7) semanas.push({ cw: semanaIso(dias[i]), dias: Math.min(7, n - i) });

  const zoomPaso = (dir: number) => setAnchoRecursos(Math.max(6, Math.min(90, W + dir * Math.max(2, Math.round(W * 0.2)))));

  const filas: ReactElement[] = [];
  grupos.forEach(({ tipo, equipos }) => equipos.forEach((eq) => {
    interface Tramo { s: Date; e: Date; bloqueo: boolean; proyecto: string; color: string; etiqueta: string; titulo: string; solape?: boolean }
    const tramos: Tramo[] = [];
    vista!.grupos.forEach((g) => g.bancos.forEach((b) => (vista!.bancos[b.nombre] ?? []).forEach((f) => {
      const usa = Array.isArray(f.datos.resources) && f.datos.resources.includes(eq.name);
      if (f.cerrado || !usa || !f.calculo.inicio || !f.calculo.fin) return;
      const s = dia(f.calculo.inicio)!, e = dia(f.calculo.fin)!;
      if (e < min || s > max) return;
      tramos.push({ s, e, bloqueo: false, proyecto: String(f.datos.project || ''), color: colorEstado(f.calculo.estado),
        etiqueta: `${b.nombre} \u00b7 ${f.datos.project || f.datos.type || ''}`,
        titulo: `${b.nombre} \u00b7 ${f.datos.project || f.datos.type || 'ensayo'} \u00b7 ${fmt(s)}\u2192${fmt(e)}` });
    })));
    bloqueos.forEach((bl) => {
      if (bl.resource !== eq.name || !bl.start || !bl.end) return;
      const s = dia(bl.start)!, e = dia(bl.end)!;
      if (e < min || s > max) return;
      tramos.push({ s, e, bloqueo: true, proyecto: '', color: '', etiqueta: `\u{1F512} ${bl.reason || 'Bloqueo'}`,
        titulo: `${bl.reason || 'Bloqueo'}${bl.note ? ` \u00b7 ${bl.note}` : ''} \u00b7 ${fmt(s)}\u2192${fmt(e)}` });
    });
    // Solape con otro tramo del mismo equipo → contorno ámbar.
    tramos.forEach((a, i) => { a.solape = tramos.some((o, j) => j !== i && a.s <= o.e && a.e >= o.s); });
    filas.push(
      <div key={eq.name} className="g-row" data-recurso={eq.name}>
        <div className="g-label" title={eq.name + (eq.code ? ` \u00b7 ${eq.code}` : '')}>
          <span className="dot" style={{ background: tipo.dot, width: 8, height: 8, borderRadius: '50%', display: 'inline-block', marginRight: 6 }} />
          {eq.name}{eq.code && <> <span style={{ color: 'var(--muted)', fontSize: 10 }}>[{eq.code}]</span></>}
        </div>
        <div className="g-track" style={{ width: n * W }}>
          {tramos.map((t, i) => {
            const s = t.s < min ? min : t.s, e = t.e > max ? max : t.e;
            const focoClase = foco ? (t.proyecto === foco ? ' g-foc' : ' g-dim') : ''; // applyFocus también atenúa los bloqueos
            return (
              <div key={i} className={`${t.bloqueo ? 'g-bar resblk' : 'g-bar solid'}${t.solape ? ' resconf' : ''}${focoClase}`} data-proj={t.proyecto}
                title={t.titulo + (t.solape ? ' \u00b7 \u26A0 solape' : '')}
                style={{ left: diasEntre(min, s) * W, width: Math.max((diasEntre(s, e) + 1) * W - 2, 6), ...(t.bloqueo ? {} : { background: t.color }) }}>
                <span className="g-lab" style={{ fontSize: 10 }}>{t.etiqueta}</span>
              </div>
            );
          })}
        </div>
      </div>,
    );
  }));

  return (
    <>
      <div className="g-toolbar no-print">
        <span>Zoom:</span>
        <button className="zbtn" onClick={() => zoomPaso(-1)} title="Alejar">&#8722;</button>
        <span className="zlvl">{Math.round((W / base) * 100)}%</span>
        <button className="zbtn" onClick={() => zoomPaso(1)} title="Acercar">&#43;</button>
        <button className="zbtn" onClick={() => setAnchoRecursos(0)} title="Restablecer zoom" style={{ fontSize: 11 }}>100%</button>
        <select className="g-focsel" value={foco} onChange={(e) => setFoco(e.target.value)} title="Resalta un proyecto y atenúa el resto (vista para cliente)">
          <option value="">Focus proyecto: (todos)</option>
          {proyectos.map((p) => <option key={p} value={p}>{p.length > 46 ? p.slice(0, 46) + '\u2026' : p}</option>)}
        </select>
        <button className="xbtn" onClick={() => void exportarGanttExcel(matrizGantt(vista!, { tipo: 'recursos', recursos, bloqueos }, foco))} title="Exportar este Gantt a un Excel editable">&#128202; Excel</button>
        <span className="hint" style={{ margin: '0 0 0 8px' }}>
          Elige un proyecto para resaltarlo (el resto se aten&uacute;a). Arrastra el borde de la 1&ordf; columna para ensancharla.
        </span>
      </div>
      <div className="gantt g-mini" id="ganttbox" ref={caja} style={{ ['--lw' as string]: `${LW}px`, position: 'relative' }}>
        <div className="g-lwgrip no-print" style={{ left: LW - 4 }} onDoubleClick={() => setAnchoEtiqueta(null)}
          onPointerDown={(ev) => {
            ev.preventDefault();
            const x0 = ev.clientX, w0 = LW;
            const mover = (m: MouseEvent) => setAnchoEtiqueta(Math.max(110, Math.min(640, Math.round(w0 + m.clientX - x0))));
            const soltar = () => { document.removeEventListener('pointermove', mover); document.removeEventListener('pointerup', soltar); };
            document.addEventListener('pointermove', mover);
            document.addEventListener('pointerup', soltar);
          }} />
        <div style={{ width: LW + n * W, position: 'relative' }}>
          <div className="g-months"><div className="g-label" />{meses.map((m, i) => <div key={i} className="g-month" style={{ width: m.dias * W, minWidth: m.dias * W }}>{m.etiqueta}</div>)}</div>
          <div className="g-months"><div className="g-label" />{semanas.map((s, i) => <div key={i} className="g-month" style={{ width: s.dias * W, minWidth: s.dias * W }}>CW{s.cw}</div>)}</div>
          {zoom && (
            <div className="g-days"><div className="g-label" />{dias.map((d) => (
              <div key={d.getTime()} className={`g-day ${esFinDeSemana(d) ? 'we' : ''} ${festivos.has(iso(d)) ? 'hol' : ''} ${d.getTime() === hoy.getTime() ? 'today' : ''}`}
                style={{ width: W, minWidth: W }}>{'MTWTFSS'[(d.getDay() + 6) % 7]}<br />{d.getDate()}</div>
            ))}</div>
          )}
          {filas}
          {desplazamientoHoy >= 0 && <div className="g-todayline" style={{ left: LW + desplazamientoHoy }} />}
        </div>
      </div>
    </>
  );
}
