import { useEffect, useMemo, useRef, type MouseEvent as EventoRaton } from 'react';
import type { FilaEnsayo, Vista } from '../../api/tipos';
import { dia, diasEntre, esFinDeSemana, fmt, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';
import { colorEstado, colorEstadoTransparente, etiquetaEstado } from '../../utilidades/textos';

/*
 * Gantt diario de un banco (ganttHTML de la 4.9.4): una barra por ensayo
 * activo con fechas, coloreada por estado, con el % de avance en el texto
 * (sin relleno, ver D22), los círculos del
 * compromiso, fines de semana y festivos sombreados y la línea de HOY.
 * Herramientas: zoom (−, +, 100 %) y foco por proyecto.
 */

const ANCHO_DIA_POR_DEFECTO = 30;

export function Gantt({ filas, vista, proyectos }: { filas: FilaEnsayo[]; vista: Vista; proyectos: string[] }) {
  const [anchoDia, setAnchoDia] = usePreferencia<number>('bw_bench_gw', ANCHO_DIA_POR_DEFECTO);
  const [anchoEtiqueta, setAnchoEtiqueta] = usePreferencia<number | null>('bw_gantt_lw', null);
  const [foco, setFoco] = usePreferencia<string>('tbp_gantt_foco', '');
  const caja = useRef<HTMLDivElement>(null);
  const W = anchoDia;
  const hoy = dia(vista.hoy)!;
  const festivos = useMemo(() => new Set(vista.calendario.festivos.map((f) => f.fecha)), [vista.calendario.festivos]);

  const conFechas = filas.filter((f) => f.calculo.inicio && f.calculo.fin);
  const rango = useMemo(() => {
    if (!conFechas.length) return null;
    let min = dia(conFechas[0].calculo.inicio)!, max = dia(conFechas[0].calculo.fin)!;
    conFechas.forEach((f) => {
      const s = dia(f.calculo.inicio)!, e = dia(f.calculo.fin)!;
      if (s < min) min = s;
      if (e > max) max = e;
    });
    min = lunesDe(min);
    max = sumarDias(max, 2);
    const n = diasEntre(min, max) + 1;
    return { min, n, dias: Array.from({ length: n }, (_, i) => sumarDias(min, i)) };
  }, [conFechas]);

  // Al abrir, centrar el día de hoy (scrollGantt).
  useEffect(() => {
    if (!caja.current || !rango) return;
    const etiqueta = anchoEtiqueta ?? 170;
    caja.current.scrollLeft = Math.max(0, etiqueta + diasEntre(rango.min, hoy) * W - caja.current.clientWidth / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rango?.min.getTime(), W]);

  const zoom = (direccion: number) => {
    const paso = Math.max(2, Math.round(W * 0.2));
    setAnchoDia(Math.max(6, Math.min(90, W + direccion * paso)));
  };

  const barra = (
    <div className="g-toolbar no-print">
      <span>Zoom:</span>
      <button className="zbtn" onClick={() => zoom(-1)} title="Alejar">&#8722;</button>
      <span className="zlvl">{Math.round((W / ANCHO_DIA_POR_DEFECTO) * 100)}%</span>
      <button className="zbtn" onClick={() => zoom(1)} title="Acercar">&#43;</button>
      <button className="zbtn" onClick={() => setAnchoDia(ANCHO_DIA_POR_DEFECTO)} title="Restablecer zoom" style={{ fontSize: 11 }}>100%</button>
      <select className="g-focsel" value={foco} onChange={(e) => setFoco(e.target.value)}
        title="Resalta un proyecto y atenúa el resto (vista para cliente)">
        <option value="">Focus proyecto: (todos)</option>
        {proyectos.map((p) => <option key={p} value={p}>{p.length > 46 ? p.slice(0, 46) + '…' : p}</option>)}
      </select>
      <span className="hint" style={{ margin: '0 0 0 8px' }}>
        Elige un proyecto para resaltarlo (el resto se aten&uacute;a). Arrastra el borde de la 1&ordf; columna para ensancharla.
      </span>
    </div>
  );

  if (!rango) {
    return <>{barra}<div className="empty">No scheduled tests. Add a duration to see the Gantt.</div></>;
  }

  const { min, n, dias } = rango;
  const etiquetas = conFechas.map((f, i) => {
    const a1 = `P${i + 1} - ${f.datos.project || f.datos.type || ''}`;
    const a2 = [f.datos.type, f.datos.comments || ''].filter(Boolean).join(' - ');
    return a1.length > a2.length ? a1 : a2;
  });
  const LW = anchoEtiqueta ?? Math.min(440, Math.max(180, etiquetas.reduce((m, s) => Math.max(m, s.length), 0) * 7 + 26));
  const esFestivo = (d: Date) => festivos.has(iso(d));

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

  const celdas = dias.map((d) => (
    <div key={d.getTime()} className={`g-cell ${esFinDeSemana(d) ? 'we' : ''} ${esFestivo(d) ? 'hol' : ''}`} style={{ width: W, minWidth: W }} />
  ));

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
      <div className="gantt" id="ganttbox" ref={caja} style={{ ['--lw' as string]: `${LW}px`, position: 'relative' }}>
        <div className="g-lwgrip no-print" style={{ left: LW - 4 }} onMouseDown={empezarAnchoEtiqueta} />
        <div style={{ width: LW + n * W, position: 'relative' }}>
          <div className="g-months"><div className="g-label" />{meses.map((m, i) => <div key={i} className="g-month" style={{ width: m.dias * W, minWidth: m.dias * W }}>{m.etiqueta}</div>)}</div>
          <div className="g-months"><div className="g-label" />{semanas.map((s, i) => <div key={i} className="g-month" style={{ width: s.dias * W, minWidth: s.dias * W }}>CW{s.cw}</div>)}</div>
          <div className="g-days"><div className="g-label" />{dias.map((d) => (
            <div key={d.getTime()} className={`g-day ${esFinDeSemana(d) ? 'we' : ''} ${esFestivo(d) ? 'hol' : ''} ${d.getTime() === hoy.getTime() ? 'today' : ''}`}
              style={{ width: W, minWidth: W }}>{'MTWTFSS'[(d.getDay() + 6) % 7]}<br />{d.getDate()}</div>
          ))}</div>
          {conFechas.map((f, i) => {
            const c = f.calculo;
            const inicio = dia(c.inicio)!, fin = dia(c.fin)!;
            const desplazamiento = diasEntre(min, inicio), largo = diasEntre(inicio, fin) + 1;
            const especial = c.tipoEspecial;
            const color = colorEstado(c.estado);
            const comprometido = !!f.datos.confirmedStart && !especial;
            const pct = Math.round(c.fraccionAvance * 100);
            const atenuada = !!foco && f.datos.project !== foco;
            const titulo = `${especial ? `${f.datos.type} (bench blocked)` : etiquetaEstado(c.estado)} \u00b7 ${f.datos.type ?? ''} \u00b7 ${fmt(c.inicio)}\u2192${fmt(c.fin)}`
              + (especial ? '' : ` \u00b7 ${pct}%${comprometido ? ` \u00b7 confirmed ${fmt(f.datos.confirmedStart)}\u2192${f.datos.confirmedEnd ? fmt(f.datos.confirmedEnd) : '?'}` : ''}`);
            const texto = [f.datos.type || '', f.datos.comments || ''].filter(Boolean).join(' \u00b7 ') + (comprometido ? ` \u00b7 ${pct}%` : '');
            const linea2 = [f.datos.type, f.datos.comments || ''].filter(Boolean).join(' \u00b7 ');
            const etiqueta = f.datos.project || f.datos.type || `P${i + 1}`;
            return (
              <div key={f.id} className="g-row">
                <div className="g-label gl2" title={`P${i + 1} \u00b7 ${etiqueta}${linea2 ? '  |  ' + linea2 : ''}`}>
                  <span className="l1">P{i + 1} &middot; {etiqueta}</span>{linea2 && <span className="l2">{linea2}</span>}
                </div>
                <div className="g-track" style={{ width: n * W }}>
                  {celdas}
                  <div className={`g-bar ${especial ? 'striped' : 'solid'}${comprometido ? ' haloq' : ''}${atenuada ? ' g-dim' : ''}`} data-proj={f.datos.project ?? ''}
                    title={titulo}
                    style={{
                      left: desplazamiento * W, width: Math.max(largo * W - 4, 20),
                      ...(especial ? {} : { background: color }),
                      ...(comprometido && !atenuada ? { boxShadow: `0 0 0 2px ${colorEstadoTransparente(c.estado, 0.9)},0 0 10px 3px ${colorEstadoTransparente(c.estado, 0.55)}` } : {}),
                    }}>
                    <span className="g-lab">{texto}</span>
                  </div>
                  {comprometido && <Circulo min={min} fecha={f.datos.confirmedStart!} W={W} color={color} clase="s" titulo="Confirmed start" />}
                  {comprometido && f.datos.confirmedEnd && <Circulo min={min} fecha={f.datos.confirmedEnd} W={W} color={color} clase="e" titulo="Confirmed end" />}
                </div>
              </div>
            );
          })}
          <div className="g-todayline" style={{ left: LW + diasEntre(min, hoy) * W + W / 2 }} />
        </div>
      </div>
    </>
  );
}

function Circulo({ min, fecha, W, color, clase, titulo }: { min: Date; fecha: string; W: number; color: string; clase: string; titulo: string }) {
  const d = dia(fecha);
  if (!d) return null;
  return <div className={`g-circ ${clase}`} style={{ left: diasEntre(min, d) * W + W / 2, borderColor: color }} title={`${titulo} ${fmt(d)}`} />;
}
