import { api } from '../../api/cliente';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { fmt, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { CampoSemana, horas, SelectorOrganizacion, SelectorUnidad, TarjetaRedimensionable, useConsulta } from './comunes';

/*
 * «Ocupación por tipo de banco» (utilCardHTML de la 4.9.4): la semana del
 * dashboard o el acumulado de un rango de CW, en % o en horas, con su propio
 * filtro Org. Las cifras las calcula el backend.
 */
export function TablaOcupacionCard() {
  const { vista } = usePlanificador();
  const { semana, preferencias: p, cambiarPreferencia, ponerPreferencias } = usePanel();
  const anio = vista?.calendario.anio ?? new Date().getFullYear();
  const inicioAnio = lunesDe(new Date(anio, 0, 4));
  const semanasAnio = semanaIso(new Date(anio, 11, 28));
  const conRango = !!(p.utilFrom && p.utilTo);
  let a = semana, b = sumarDias(semana, 6), cwDesde = semanaIso(semana), cwHasta = cwDesde;
  if (conRango) {
    cwDesde = p.utilFrom!;
    cwHasta = p.utilTo!;
    a = sumarDias(inicioAnio, (cwDesde - 1) * 7);
    b = sumarDias(inicioAnio, (cwHasta - 1) * 7 + 6);
  }
  const semanas = cwHasta - cwDesde + 1;
  const enHoras = p.utilMode === 'hours';
  const datos = useConsulta(() => api.tablaOcupacion(iso(a), iso(b), p.utilOrg), [iso(a), iso(b), p.utilOrg]);

  // setUtilRange de la 4.9.4: acota a 1..semanas del año y «hasta» nunca menor que «desde».
  const ponerRango = (desde: number | null, hasta: number | null) => {
    let d = p.utilFrom, h = p.utilTo;
    if (desde !== null) d = Math.max(1, Math.min(desde, semanasAnio));
    if (hasta !== null) h = Math.max(1, Math.min(hasta, semanasAnio));
    if (d && h && h < d) h = d;
    ponerPreferencias({ utilFrom: d, utilTo: h });
  };

  const maxHoras = Math.max(1, ...(datos?.grupos ?? []).map((g) => g.busy));
  return (
    <TarjetaRedimensionable clave="dash-util">
      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
        <h3 style={{ margin: '0 8px 6px 0' }}>Ocupaci&oacute;n por tipo de banco{p.utilOrg !== 'ALL' && <> &middot; {p.utilOrg}</>}</h3>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto', fontSize: 12, flexWrap: 'wrap' }}>
          <SelectorUnidad valor={p.utilMode} onCambiar={(v) => cambiarPreferencia('utilMode', v)} />
          <SelectorOrganizacion compacto etiqueta="Org" valor={p.utilOrg} onCambiar={(o) => cambiarPreferencia('utilOrg', o)} />
          <span style={{ marginLeft: 6 }}>CW</span>
          <CampoSemana className="ut-desde" valor={cwDesde} maximo={semanasAnio} onConfirmar={(v) => ponerRango(v, conRango ? null : v)} />
          &ndash;
          <CampoSemana className="ut-hasta" valor={cwHasta} maximo={semanasAnio} onConfirmar={(v) => ponerRango(conRango ? null : cwDesde, v)} />
          <button className="btn ghost sm" onClick={() => ponerRango(1, semanasAnio)}>A&ntilde;o</button>
          <button className={`btn ${conRango ? 'ghost' : ''} sm`} onClick={() => ponerPreferencias({ utilFrom: null, utilTo: null })}
            title="Volver a la semana que se ve en el dashboard">Semana del dashboard</button>
        </div>
      </div>
      <div style={{ fontSize: 13, fontWeight: 700, margin: '0 2px 6px' }}>
        {conRango && semanas > 1
          ? <>CW{cwDesde}&ndash;CW{cwHasta} &middot; {fmt(a)} &rarr; {fmt(b)} <span style={{ color: 'var(--turq2)' }}>(acumulado, {semanas} semanas)</span></>
          : <>CW{cwDesde} &middot; {fmt(a)} &rarr; {fmt(b)}</>}
      </div>
      <div className="hint" style={{ margin: '0 2px 10px' }}>
        {enHoras
          ? <><b>Horas de banco ocupadas</b>. Las barras se escalan al tipo de banco con m&aacute;s horas, para compararlos entre s&iacute;; la fila TOTAL es la suma.</>
          : <>% = <b>horas de banco ocupadas</b> / <b>horas de banco disponibles</b></>}
        {' '}en el periodo, contando los 7 d&iacute;as (24 h). Los ensayos con Weekend = No no ocupan el banco en fin de semana. Misma medida que la
        gr&aacute;fica e incluye los ensayos ya completados.
        {conRango && semanas > 1 && <> En el acumulado, <b>Trabajando</b> son los bancos con alg&uacute;n ensayo <b>activo</b> en el periodo, e <b>Inicia / Acaba</b> los
          ensayos activos que empiezan o terminan dentro de &eacute;l (igual que en la vista semanal, no incluyen los ya completados; las horas s&iacute;).</>}
      </div>
      <table className="dash util-full">
        <thead>
          <tr>
            <th style={{ textAlign: 'left', width: '22%' }}>Tipo de banco</th><th style={{ width: '9%' }}>Bancos</th><th style={{ width: '12%' }}>Trabajando</th>
            <th style={{ width: '14%' }}>Horas ocup./disp.</th><th style={{ width: '29%' }}>{enHoras ? 'Horas ocupadas' : '% ocupación'}</th>
            <th style={{ width: '7%' }}>Inicia</th><th style={{ width: '7%' }}>Acaba</th>
          </tr>
        </thead>
        <tbody>
          {datos?.grupos.map((g) => (
            <tr key={g.clave}>
              <td className="type"><span className="typedot" style={{ background: g.color }} />{g.nombre}</td>
              <td>{g.bancos}</td><td>{g.trabajando}</td>
              <td style={{ color: 'var(--muted)' }}>{horas(g.busy)} / {horas(g.cap)}</td>
              <td>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <b style={{ minWidth: enHoras ? 64 : 38, textAlign: 'right' }}>{enHoras ? `${horas(g.busy)} h` : `${g.pct}%`}</b>
                  <div className="bar" style={{ flex: 1, height: 12 }}
                    title={enHoras ? `${horas(g.busy)} h de ${horas(maxHoras)} h (escala: el tipo con mas horas)` : `${g.pct}% de las horas disponibles`}>
                    <i style={{ width: `${enHoras ? Math.round((100 * g.busy) / maxHoras) : g.pct}%`, background: g.color }} />
                  </div>
                </div>
              </td>
              <td>{g.inicia}</td><td>{g.acaba}</td>
            </tr>
          ))}
          {datos && (
            <tr style={{ borderTop: '2px solid var(--line)' }}>
              <td className="type"><b>TOTAL</b></td><td /><td />
              <td style={{ color: 'var(--muted)' }}><b>{horas(datos.busy)} / {horas(datos.cap)}</b></td>
              <td>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <b style={{ minWidth: enHoras ? 64 : 38, textAlign: 'right', color: 'var(--turq2)' }}>{enHoras ? `${horas(datos.busy)} h` : `${datos.pct}%`}</b>
                  <div className="bar" style={{ flex: 1, height: 12 }}><i style={{ width: `${enHoras ? 100 : datos.pct}%`, background: 'var(--turq)' }} /></div>
                </div>
              </td>
              <td /><td />
            </tr>
          )}
        </tbody>
      </table>
    </TarjetaRedimensionable>
  );
}
