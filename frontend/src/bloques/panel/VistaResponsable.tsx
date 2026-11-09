import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { colorEstado, URL_TICKETS_APROBACION } from '../../utilidades/textos';
import { BloquesKpi } from './BloquesKpi';
import { SelectorOrganizacion, TarjetaRedimensionable } from './comunes';
import { GanttGeneral } from './GanttGeneral';
import { Chip, NavegacionSemana } from './NavegacionSemana';

/*
 * «By Responsible» (renderRespDash de la 4.9.4): la planificación de una
 * persona o, con «Manager View», la de todo el equipo con la carga por
 * responsable. Avisa de los ensayos sin Approval ID aprobado.
 */
const VISTA_MANAGER = '__manager__';

export function VistaResponsable() {
  const { vista, personas, estadosAprobacion } = usePlanificador();
  const { responsable, setResponsable, organizacion, setOrganizacion, zoom, setZoom } = usePanel();
  if (!vista) return null;

  const elegido = responsable || personas[0] || '';
  const manager = elegido === VISTA_MANAGER;
  const esSuyo = (resp: unknown) => manager || String(resp ?? '') === elegido;

  const bancos = vista.grupos.flatMap((g) => g.bancos).filter((b) => organizacion === 'ALL' || b.organizacion === organizacion);
  const activas = bancos.flatMap((b) => (vista.bancos[b.nombre] ?? []).filter((f) => !f.cerrado).map((f) => ({ banco: b.nombre, f })));

  // Ensayos sin ID aprobado (o sin ID), sin contar los tipos especiales.
  const sinId = activas.filter(({ f }) => esSuyo(f.datos.resp) && !f.calculo.tipoEspecial
    && estadosAprobacion[String(f.datos.approvalId ?? '').trim()] !== 'approved');

  // Carga del equipo: activos, corriendo y en retraso por responsable (de más a menos).
  const equipo = manager
    ? personas.map((rp) => {
      const suyos = activas.filter(({ f }) => String(f.datos.resp ?? '') === rp);
      return {
        rp, tot: suyos.length,
        run: suyos.filter(({ f }) => f.calculo.estado === 'running').length,
        del: suyos.filter(({ f }) => f.calculo.estado === 'delayed').length,
      };
    }).filter((x) => x.tot).sort((x, y) => y.tot - x.tot)
    : [];

  return (
    <div id="vista-responsable">
      <div className="view-head">
        <h2>{manager ? '\u{1F465} Team planning' : '\u{1F464} My planning'}</h2>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <label className="hint" style={{ margin: 0 }}>Responsible:</label>
          <select id="resp-sel" value={elegido} onChange={(e) => setResponsable(e.target.value)}
            style={{ padding: 7, borderRadius: 8, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 13 }}>
            <option value={VISTA_MANAGER}>{'\u{1F465} Manager View \u2014 all team'}</option>
            {personas.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
          <SelectorOrganizacion valor={organizacion} onCambiar={setOrganizacion} />
        </div>
      </div>
      <div className="view-head"><NavegacionSemana activarZoom={false} /></div>
      <div className={`resp-alert ${sinId.length ? '' : 'ok'}`}>
        {sinId.length
          ? <>
            <b>&#9888; {sinId.length} test(s) without an approved ID (or no ID).</b>{' '}
            {manager ? 'Across the team' : 'Create/track the approval tickets to avoid blocking your tests'}:{' '}
            {sinId.slice(0, 8).map(({ banco, f }) => (f.datos.resp ? `${f.datos.resp} \u2013 ` : '') + (f.datos.project || f.datos.type || banco)
              + (f.datos.approvalId ? ` \u00b7 ID ${f.datos.approvalId}` : ' \u00b7 no ID')).join(' | ')}
            {sinId.length > 8 ? ' \u2026' : ''}
          </>
          : <b>&#10003; All tests have an approved ID.</b>}
        <div style={{ marginTop: 10 }}>
          <button className="btn" onClick={() => window.open(URL_TICKETS_APROBACION, '_blank')}>&#128203; Open approval tickets form</button>
        </div>
      </div>
      <BloquesKpi responsable={manager ? null : elegido} />
      {manager && (
        <TarjetaRedimensionable clave="mgr-table">
          <h3 style={{ margin: '0 0 8px' }}>Team load by responsible{organizacion !== 'ALL' && <> &middot; {organizacion}</>}</h3>
          <table className="dash util-full" id="tabla-equipo">
            <thead>
              <tr>
                <th style={{ textAlign: 'left', width: '28%' }}>Responsible</th><th style={{ width: '12%' }}>Active tests</th>
                <th style={{ width: '22%' }}>Running</th><th style={{ width: '22%' }}>En retraso</th>
              </tr>
            </thead>
            <tbody>
              {!equipo.length && <tr><td colSpan={7} className="empty">No active tests.</td></tr>}
              {equipo.map((o) => (
                <tr key={o.rp}>
                  <td className="type">{o.rp}</td><td><b>{o.tot}</b></td><td>{o.run}</td>
                  <td>{o.del ? <b style={{ color: colorEstado('delayed') }}>{o.del}</b> : '0'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </TarjetaRedimensionable>
      )}
      <TarjetaRedimensionable clave="resp-plan">
        <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
          <h3 style={{ margin: '0 8px 0 0' }}>{manager ? 'Team plan by bench' : 'My plan by bench'}</h3>
          <div className="dash-toolbar" style={{ marginLeft: 'auto' }}>
            <Chip color={colorEstado('running')} texto="Running" />
            <Chip color={colorEstado('delayed')} texto="En retraso" />
            <button className="btn ghost sm" id="dash-zoom" onClick={() => setZoom(!zoom)}>{zoom ? '\u{1F5D3} Full year' : '\u{1F50D} Zoom to week'}</button>
          </div>
        </div>
        <div className="hint">
          {manager
            ? `All responsibles${organizacion !== 'ALL' ? ' \u00b7 ' + organizacion : ''}.`
            : <>Only tests where Responsible = <b>{elegido || '\u2014'}</b>.</>}
        </div>
        <GanttGeneral responsable={manager ? null : elegido} enResponsable />
      </TarjetaRedimensionable>
    </div>
  );
}
