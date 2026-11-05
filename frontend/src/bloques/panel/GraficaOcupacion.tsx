import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/cliente';
import type { Tendencia } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { dia, semanaIso } from '../../utilidades/fechas';
import { CampoSemana, horas, SelectorOrganizacion, SelectorUnidad, TarjetaRedimensionable, useConsulta } from './comunes';

/*
 * «% Ocupación por CW» (trendChartHTML + trendSVG de la 4.9.4): una línea por
 * tipo de banco, en % o en horas, con su propio filtro Org y rango de CW. Los
 * valores los da el backend; aquí solo se dibuja el SVG a todo el ancho.
 */
export function GraficaOcupacion() {
  const { vista } = usePlanificador();
  const { tendencia, setTendencia, preferencias, cambiarPreferencia } = usePanel();
  const datos = useConsulta(() => api.tendencia(tendencia.desde, tendencia.hasta, preferencias.trendOrg),
    [tendencia.desde, tendencia.hasta, preferencias.trendOrg]);
  const enHoras = preferencias.trendMode === 'hours';
  if (!datos || !vista) return null;

  const rango = (desde: number | null, hasta: number | null) => setTendencia({
    ...tendencia,
    desde: desde === null || Number.isNaN(desde) ? tendencia.desde : desde,
    hasta: hasta === null || Number.isNaN(hasta) ? tendencia.hasta : hasta,
  });
  const alternar = (clave: string) => setTendencia({ ...tendencia, ocultas: { ...tendencia.ocultas, [clave]: !tendencia.ocultas[clave] } });

  return (
    <TarjetaRedimensionable clave="dash-trend">
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
        <h3 style={{ margin: '0 8px 8px 0' }}>
          {enHoras ? 'Horas de banco ocupadas por CW' : '% Ocupación por CW'} &middot; {datos.anio}
          {preferencias.trendOrg !== 'ALL' && <> &middot; {preferencias.trendOrg}</>}
        </h3>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto', fontSize: 12, flexWrap: 'wrap' }}>
          <SelectorUnidad valor={preferencias.trendMode} onCambiar={(v) => cambiarPreferencia('trendMode', v)} />
          <SelectorOrganizacion compacto etiqueta="Org" valor={preferencias.trendOrg} onCambiar={(o) => cambiarPreferencia('trendOrg', o)} />
          <span style={{ marginLeft: 6 }}>CW</span>
          <CampoSemana className="tr-desde" valor={datos.desde} maximo={datos.semanasDelAnio} onConfirmar={(v) => rango(v, null)} />
          &ndash;
          <CampoSemana className="tr-hasta" valor={datos.hasta} maximo={datos.semanasDelAnio} onConfirmar={(v) => rango(null, v)} />
          <button className="btn ghost sm" onClick={() => rango(1, datos.semanasDelAnio)}>Full year</button>
        </div>
      </div>
      <div style={{ marginBottom: 8 }}>
        {datos.series.map((s) => (
          <span key={s.clave} className="tr-ley" data-clave={s.clave} onClick={() => alternar(s.clave)}
            style={{ cursor: 'pointer', opacity: tendencia.ocultas[s.clave] ? 0.35 : 1, display: 'inline-flex', alignItems: 'center', gap: 5, marginRight: 14, fontSize: 12 }}>
            <span style={{ width: 14, height: 3, background: s.color, display: 'inline-block' }} />{s.nombre}
          </span>
        ))}
      </div>
      <Svg datos={datos} enHoras={enHoras} ocultas={tendencia.ocultas} semanaHoy={semanaIso(dia(vista.hoy)!)} />
      <div className="hint">
        {enHoras
          ? <>Horas de banco <b>ocupadas</b> cada semana por tipo de banco</>
          : <>% = <b>horas de banco ocupadas / horas de banco disponibles</b> de cada semana (7 d&iacute;as &times; 24 h)</>}
        , la misma medida que la tabla de ocupaci&oacute;n. Incluye los ensayos ya completados. Pulsa en la leyenda para mostrar u
        ocultar un tipo de banco. El filtro Org de esta tarjeta es independiente.
      </div>
    </TarjetaRedimensionable>
  );
}

function Svg({ datos, enHoras, ocultas, semanaHoy }: { datos: Tendencia; enHoras: boolean; ocultas: Record<string, boolean>; semanaHoy: number }) {
  const envoltorio = useRef<HTMLDivElement>(null);
  const [anchoDisponible, setAnchoDisponible] = useState(1200);
  useEffect(() => {
    const medir = () => {
      const ancho = envoltorio.current?.clientWidth || (document.getElementById('main')?.clientWidth ?? 1200) - 60;
      setAnchoDisponible(ancho);
    };
    medir();
    let t = 0;
    const alCambiar = () => { window.clearTimeout(t); t = window.setTimeout(medir, 150); };
    window.addEventListener('resize', alCambiar);
    return () => window.removeEventListener('resize', alCambiar);
  }, []);

  const W = Math.max(480, anchoDisponible);
  const padL = enHoras ? 58 : 40, padR = 14, padT = 16, padB = 30, H = 240, plotH = H - padT - padB;
  const semanas = datos.cw;
  const pasoX = Math.max(1, (W - padL - padR) / Math.max(1, semanas.length));
  const valores = (s: Tendencia['series'][number]) => (enHoras ? s.busy : s.pct);

  // Eje Y: 0-100 % fijo; en horas, un máximo «redondo» sobre lo que se ve.
  let yMax = 100, marcas = [0, 25, 50, 75, 100];
  if (enHoras) {
    let mx = 0;
    datos.series.forEach((s) => { if (!ocultas[s.clave]) valores(s).forEach((v) => { if (v > mx) mx = v; }); });
    mx = Math.max(mx, 168);
    const mag = Math.pow(10, Math.floor(Math.log10(mx)));
    const redondo = [1, 2, 2.5, 5, 10].map((k) => k * mag).find((x) => x >= mx / 4) || mag * 10;
    yMax = redondo * 4;
    marcas = [0, redondo, redondo * 2, redondo * 3, redondo * 4];
  }
  const aY = (v: number) => padT + plotH - (v / yMax) * plotH;
  const aX = (i: number) => padL + i * pasoX + pasoX / 2;
  const maxEtiquetas = Math.max(4, Math.floor((W - padL - padR) / 46));
  const pasoEtiqueta = Math.max(1, Math.ceil(semanas.length / maxEtiquetas));
  const iHoy = semanas.findIndex((c) => c === semanaHoy);
  const radio = semanas.length > 30 ? 2 : 3;

  return (
    <div id="trend-svg-wrap" ref={envoltorio} style={{ width: '100%', overflow: 'hidden' }}>
      <svg width={W} height={H} style={{ display: 'block' }}>
        {marcas.map((v) => (
          <g key={'y' + v}>
            <line x1={padL} y1={aY(v)} x2={W - padR} y2={aY(v)} stroke="var(--line)" strokeWidth={1} />
            <text x={padL - 6} y={aY(v) + 3} fill="var(--muted)" fontSize={10} textAnchor="end">{enHoras ? horas(v) + ' h' : v + '%'}</text>
          </g>
        ))}
        {semanas.map((c, i) => (i % pasoEtiqueta === 0
          ? <text key={'x' + i} x={aX(i)} y={H - 10} fill="var(--muted)" fontSize={9} textAnchor="middle">CW{c}</text> : null))}
        {iHoy >= 0 && (
          <g>
            <line x1={aX(iHoy)} y1={padT} x2={aX(iHoy)} y2={padT + plotH} stroke="var(--turq)" strokeDasharray="3,3" strokeWidth={1.5} />
            <text x={aX(iHoy)} y={padT - 4} fill="var(--turq)" fontSize={9} textAnchor="middle">CW{semanaHoy}</text>
          </g>
        )}
        {datos.series.filter((s) => !ocultas[s.clave]).map((s) => (
          <g key={s.clave} data-serie={s.clave}>
            <polyline fill="none" stroke={s.color} strokeWidth={2} points={valores(s).map((v, i) => `${aX(i)},${aY(v)}`).join(' ')} />
            {valores(s).map((v, i) => (
              <circle key={i} cx={aX(i)} cy={aY(v)} r={radio} fill={s.color}>
                <title>{`${s.nombre} \u00b7 CW${semanas[i]}: ${enHoras ? horas(v) + ' h' : v + '%'}`}</title>
              </circle>
            ))}
          </g>
        ))}
      </svg>
    </div>
  );
}
