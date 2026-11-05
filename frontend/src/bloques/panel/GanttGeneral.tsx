import { useEffect, useLayoutEffect, useMemo, useRef, type MouseEvent as EventoRaton, type ReactElement } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { dia, diasEntre, esFinDeSemana, fmt, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';
import { colorEstado, colorEstadoTransparente, etiquetaEstado } from '../../utilidades/textos';

/*
 * Gantt de todos los bancos (overviewGantt de la 4.9.4), una fila por banco:
 *  - año completo a resolución semanal (9 px/día) o una semana a resolución
 *    diaria (30 px/día) con fila de días y círculos del compromiso;
 *  - barras de los activos coloreadas por estado (rayadas si son tipos
 *    especiales) y de los completados con sus fechas históricas, atenuadas;
 *  - con `responsable` solo se pintan sus ensayos y se omiten los bancos vacíos.
 * Mismas herramientas que el Gantt del banco: zoom −/+, foco de proyecto,
 * ancho de la 1ª columna (compartido con el del banco) y línea de HOY.
 */

const ANCHO_ETIQUETA = 170;

interface Props {
  /** null = todos los responsables. */
  responsable: string | null;
}

export function GanttGeneral({ responsable }: Props) {
  const { vista, proyectos } = usePlanificador();
  const { semana, zoom, organizacion, anchoDia, setAnchoDia } = usePanel();
  const [foco, setFoco] = usePreferencia<string>('tbp_gantt_foco', '');
  const [anchoEtiqueta, setAnchoEtiqueta] = usePreferencia<number | null>('bw_gantt_lw', null);
  const caja = useRef<HTMLDivElement>(null);
  const base = zoom ? 30 : 9;
  const W = anchoDia || base;
  const LW = anchoEtiqueta ?? ANCHO_ETIQUETA;
  const hoy = dia(vista?.hoy)!;
  const festivos = useMemo(() => new Set((vista?.calendario.festivos ?? []).map((f) => f.fecha)), [vista]);

  let min: Date, max: Date;
  if (zoom) {
    min = semana;
    max = sumarDias(semana, 6);
  } else {
    min = lunesDe(new Date(vista!.calendario.anio, 0, 4));
    max = sumarDias(min, 53 * 7 - 1);
  }
  const n = diasEntre(min, max) + 1;
  const dias = Array.from({ length: n }, (_, i) => sumarDias(min, i));
  const desplazamientoHoy = hoy >= min && hoy <= max ? diasEntre(min, hoy) * W : -9999;

  // Centrar HOY al dibujar (scrollGantt) y fijar las cabeceras al hacer scroll vertical.
  useEffect(() => {
    if (!caja.current) return;
    caja.current.scrollLeft = Math.max(0, LW + (desplazamientoHoy || 0) - caja.current.clientWidth / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [min.getTime(), W, zoom]);
  useLayoutEffect(() => fijarCabeceras(caja.current));

  const zoomPaso = (direccion: number) => {
    const paso = Math.max(2, Math.round(W * 0.2));
    setAnchoDia(Math.max(6, Math.min(90, W + direccion * paso)));
  };

  const barra = (
    <div className="g-toolbar no-print">
      <span>Zoom:</span>
      <button className="zbtn" onClick={() => zoomPaso(-1)} title="Alejar">&#8722;</button>
      <span className="zlvl" id="gw-zlvl">{Math.round((W / base) * 100)}%</span>
      <button className="zbtn" onClick={() => zoomPaso(1)} title="Acercar">&#43;</button>
      <button className="zbtn" onClick={() => setAnchoDia(0)} title="Restablecer zoom" style={{ fontSize: 11 }}>100%</button>
      <select className="g-focsel" id="gw-focsel" value={foco} onChange={(e) => setFoco(e.target.value)}
        title="Resalta un proyecto y atenúa el resto (vista para cliente)">
        <option value="">Focus proyecto: (todos)</option>
        {proyectos.map((p) => <option key={p} value={p}>{p.length > 46 ? p.slice(0, 46) + '\u2026' : p}</option>)}
      </select>
      <span className="hint" style={{ margin: '0 0 0 8px' }}>
        Elige un proyecto para resaltarlo (el resto se aten&uacute;a). Arrastra el borde de la 1&ordf; columna para ensancharla.
      </span>
    </div>
  );

  if (!vista) return null;
  const filtro = (f: FilaEnsayo) => responsable === null || String(f.datos.resp ?? '') === responsable;
  const claseFoco = (proyecto: string) => (foco ? (proyecto === foco ? ' g-foc' : ' g-dim') : '');

  const filasBanco: ReactElement[] = [];
  let alguno = false;
  vista.grupos.forEach((g) => g.bancos.forEach((b) => {
    if (organizacion !== 'ALL' && b.organizacion !== organizacion) return;
    const todas = vista.bancos[b.nombre] ?? [];
    const activas = todas.filter((f) => !f.cerrado && f.calculo.inicio && f.calculo.fin && filtro(f));
    const cerradas = todas.filter((f) => f.cerrado && f.datos.histStart && f.datos.histEnd && filtro(f));
    if (!activas.length && !cerradas.length && responsable !== null) return;
    alguno = true;
    const barras: ReactElement[] = [];
    activas.forEach((f) => {
      const c = f.calculo;
      const s0 = dia(c.inicio)!, e0 = dia(c.fin)!;
      if (e0 < min || s0 > max) return;
      const s = s0 < min ? min : s0, e = e0 > max ? max : e0;
      const izquierda = diasEntre(min, s) * W, ancho = Math.max((diasEntre(s, e) + 1) * W - 2, 6);
      const d = f.datos;
      const especial = c.tipoEspecial;
      const color = colorEstado(c.estado);
      const comprometido = !!d.confirmedStart && !especial;
      const texto = (d.project || d.type || 'test') + (d.type ? ' \u00b7 ' + d.type : '') + (d.comments ? ' \u00b7 ' + d.comments : '');
      const titulo = `${b.nombre} \u00b7 ${especial ? `${d.type} (bench blocked)` : etiquetaEstado(c.estado)} \u00b7 ${d.project || ''} ${d.type || ''} \u00b7 ${fmt(s0)}\u2192${fmt(e0)}`;
      const foc = claseFoco(String(d.project || ''));
      barras.push(
        <div key={f.id} className={`g-bar ${especial ? 'striped' : 'solid'}${comprometido ? ' haloq' : ''}${foc}`} data-proj={d.project || ''} title={titulo}
          style={{
            left: izquierda, width: ancho,
            ...(especial ? {} : { background: color }),
            ...(comprometido ? { boxShadow: `0 0 0 2px ${colorEstadoTransparente(c.estado, 0.9)},0 0 8px 2px ${colorEstadoTransparente(c.estado, 0.5)}` } : {}),
          }}>
          <span className="g-lab" style={{ fontSize: 10 }}>{texto}</span>
        </div>,
      );
      if (comprometido && zoom) {
        const cs = dia(d.confirmedStart);
        if (cs && cs >= min && cs <= max) barras.push(<div key={f.id + 's'} className="g-circ s" style={{ left: diasEntre(min, cs) * W + W / 2, borderColor: color }} />);
        const ce = dia(d.confirmedEnd);
        if (ce && ce >= min && ce <= max) barras.push(<div key={f.id + 'e'} className="g-circ e" style={{ left: diasEntre(min, ce) * W + W / 2, borderColor: color }} />);
      }
    });
    cerradas.forEach((f) => {
      const s0 = dia(String(f.datos.histStart))!, e0 = dia(String(f.datos.histEnd))!;
      if (e0 < min || s0 > max) return;
      const s = s0 < min ? min : s0, e = e0 > max ? max : e0;
      const d = f.datos;
      barras.push(
        <div key={f.id} className={`g-bar solid${claseFoco(String(d.project || ''))}`} data-proj={d.project || ''}
          title={`${b.nombre} \u00b7 Completed \u00b7 ${d.project || ''} ${d.type || ''}`}
          style={{ left: diasEntre(min, s) * W, width: Math.max((diasEntre(s, e) + 1) * W - 2, 6), background: colorEstado('completed'), opacity: 0.55 }}>
          <span className="g-lab" style={{ fontSize: 10 }}>{'\u2713 '}{d.project || d.type || ''}</span>
        </div>,
      );
    });
    filasBanco.push(
      <div key={b.nombre} className="g-row" data-banco={b.nombre}>
        <div className="g-label"><span className="dot" style={{ background: g.color, width: 8, height: 8, borderRadius: '50%', display: 'inline-block', marginRight: 6 }} />{b.nombre}</div>
        <div className="g-track" style={{ width: n * W }}>{barras}</div>
      </div>,
    );
  }));

  // Sin barras no hay Gantt ni barra de herramientas (enhanceGantts solo actúa si existe #ganttbox).
  if (!alguno) return <div className="empty">No scheduled tests in this range.</div>;

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

  const empezarAnchoEtiqueta = (e: EventoRaton) => {
    e.preventDefault();
    const x0 = e.clientX, w0 = LW;
    const mover = (ev: MouseEvent) => setAnchoEtiqueta(Math.max(110, Math.min(640, Math.round(w0 + ev.clientX - x0))));
    const soltar = () => { document.removeEventListener('mousemove', mover); document.removeEventListener('mouseup', soltar); };
    document.addEventListener('mousemove', mover);
    document.addEventListener('mouseup', soltar);
  };

  return (
    <>
      {barra}
      <div className="gantt g-mini" id="ganttbox" ref={caja} style={{ ['--lw' as string]: `${LW}px`, position: 'relative' }}>
        <div className="g-lwgrip no-print" style={{ left: LW - 4 }} onMouseDown={empezarAnchoEtiqueta}
          onDoubleClick={() => setAnchoEtiqueta(null)} />
        <div style={{ width: LW + n * W, position: 'relative' }}>
          <div className="g-months"><div className="g-label" />{meses.map((m, i) => <div key={i} className="g-month" style={{ width: m.dias * W, minWidth: m.dias * W }}>{m.etiqueta}</div>)}</div>
          <div className="g-months"><div className="g-label" />{semanas.map((s, i) => <div key={i} className="g-month" style={{ width: s.dias * W, minWidth: s.dias * W }}>CW{s.cw}</div>)}</div>
          {zoom && (
            <div className="g-days"><div className="g-label" />{dias.map((d) => (
              <div key={d.getTime()} className={`g-day ${esFinDeSemana(d) ? 'we' : ''} ${festivos.has(iso(d)) ? 'hol' : ''} ${d.getTime() === hoy.getTime() ? 'today' : ''}`}
                style={{ width: W, minWidth: W }}>{'MTWTFSS'[(d.getDay() + 6) % 7]}<br />{d.getDate()}</div>
            ))}</div>
          )}
          {filasBanco}
          {desplazamientoHoy >= 0 && <div className="g-todayline" style={{ left: LW + desplazamientoHoy }} />}
        </div>
      </div>
    </>
  );
}

/** Cabeceras fijas al hacer scroll vertical (stickyHeaders de la 4.9.4). */
export function fijarCabeceras(caja: HTMLElement | null) {
  const interior = caja?.firstElementChild?.nextElementSibling ?? caja?.firstElementChild;
  if (!caja || !interior) return;
  caja.classList.add('stickyhdr');
  let arriba = 0;
  [...interior.children].forEach((fila) => {
    if (!(fila instanceof HTMLElement) || !(fila.classList.contains('g-months') || fila.classList.contains('g-days'))) return;
    fila.style.top = arriba + 'px';
    arriba += fila.offsetHeight || 24;
  });
}
