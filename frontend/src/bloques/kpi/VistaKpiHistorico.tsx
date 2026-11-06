import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { Historico, SemanaHistorico } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { colorEstado } from '../../utilidades/textos';
import { SelectorOrganizacion } from '../panel/comunes';
import { Estadisticas } from './Estadisticas';

/*
 * 📈 KPI History (renderKpiHistory de la 4.9.4). Dos lecturas:
 *  - arriba, las estadísticas de ensayos cerrados (cada ensayo cuenta una vez);
 *  - abajo, la foto semanal guardada con 📊 Update Yearly Status.
 * El histórico se lee al abrir la pantalla y con «Reload from SharePoint».
 */

/** Semanas nuevas: solo «en retraso declarado»; antiguas (antes de 4.9.3): Delayed + Behind. */
const enRetraso = (r: SemanaHistorico) => (r.isNew ? (+r.delayed || 0) : (+r.delayed || 0) + (+r.behind || 0));

/** Hora de la última lectura (fmtStamp): hoy «hh:mm», otro día «dd/mm hh:mm». */
function sello(ts: number): string {
  const d = new Date(ts), ahora = new Date();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d.toDateString() === ahora.toDateString()) return hm;
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')} ${hm}`;
}

export function VistaKpiHistorico() {
  const { sincronizacion } = usePlanificador();
  // Solo el bloqueo por falta de conexión impide leer (READONLY de la 4.9.4); el rol de solo lectura sí ve el histórico.
  const bloqueado = sincronizacion === 'bloqueado';
  const { kpiOrg, setKpiOrg } = usePanel();
  const [historico, setHistorico] = useState<Historico | null>(null);
  const [leyendo, setLeyendo] = useState(false);
  const [leidoEn, setLeidoEn] = useState(0);

  const leer = useCallback(async () => {
    // Sin conexión (bloqueo) la 4.9.4 no lee el histórico: se muestra vacío.
    if (bloqueado) { setHistorico({ filas: 0, semanas: [] }); return; }
    setLeyendo(true);
    try {
      setHistorico(await api.historico(kpiOrg));
      setLeidoEn(Date.now());
    } catch {
      setHistorico((h) => h ?? { filas: 0, semanas: [] });
    } finally {
      setLeyendo(false);
    }
  }, [kpiOrg, bloqueado]);

  useEffect(() => { void leer(); }, [leer]);

  const filas = historico?.semanas ?? [];
  const cabecera = (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
      <h2 style={{ margin: 0 }}>&#128200; KPI History</h2>
      <SelectorOrganizacion valor={kpiOrg} onCambiar={setKpiOrg} />
      <button className="btn ghost sm" onClick={() => void leer()}>&#8635; Reload from SharePoint</button>
      <span id="kpi-fresh" className={`data-fresh ${leyendo ? 'updating' : leidoEn ? 'ok' : 'stale'}`}>
        <span className="rf-dot" />{leyendo ? 'Actualizando\u2026' : leidoEn ? `Actualizado ${sello(leidoEn)}` : 'Sin datos aún'}
      </span>
      <span className="hint" style={{ margin: 0 }}>
        {historico && historico.filas ? `Source: SharePoint (${historico.filas} rows)` : 'No snapshots yet'}
      </span>
    </div>
  );

  if (!historico) {
    return <div className="sec"><h2>&#128200; KPI History</h2><div className="hint">Loading weekly snapshots&hellip;</div></div>;
  }

  return (
    <div className="sec" style={{ maxWidth: '100%' }}>
      {cabecera}
      <div className="hint">
        Dos lecturas distintas y complementarias: arriba, las <b>estad&iacute;sticas de ensayos</b> (cada ensayo cuenta <b>una sola vez</b>,
        por su estado final al cerrarlo); abajo, la <b>foto semanal</b> guardada con <b>&#128202; Update Yearly Status</b>, que es una
        instant&aacute;nea de c&oacute;mo estaba cada semana.
      </div>
      <Estadisticas />
      {filas.length ? <FotoSemanal filas={filas} org={kpiOrg} /> : (
        <div className="card">
          <h3 style={{ margin: '0 0 8px' }}>Foto semanal</h3>
          <div className="empty">A&uacute;n no hay fotos semanales para {kpiOrg}. Pulsa &#128202; Update Yearly Status en el Dashboard para guardar esta semana.</div>
        </div>
      )}
    </div>
  );
}

function FotoSemanal({ filas, org }: { filas: SemanaHistorico[]; org: string }) {
  const ultima = filas[filas.length - 1];
  const sumaRetraso = filas.reduce((x, r) => x + enRetraso(r), 0);
  const sumaCorriendo = filas.reduce((x, r) => x + (+r.running || 0), 0);
  const unDecimal = (v: number) => Math.round(v * 10) / 10;
  const conOcupacion = filas.filter((r) => r.occPct != null);

  const motivos: [string, number][] = [
    ['Falta de recursos', 0], ['Piezas con retraso', 0], ['Avería / Mant. no planificado', 0],
    ['Caída de ensayo', 0], ['Cambio de prioridad', 0], ['Otro', 0],
  ];
  const columnas = ['ReasonResources', 'ReasonParts', 'ReasonBreakdown', 'ReasonTestDrop', 'ReasonPriority', 'ReasonOther'];
  filas.forEach((r) => columnas.forEach((c, i) => { motivos[i][1] += +(r.reasons?.[c] ?? 0) || 0; }));
  const totalMotivos = motivos.reduce((x, m) => x + m[1], 0);

  return (
    <>
      <div className="kpis">
        <div className="kpi"><div className="n">{filas.length}</div><div className="l">Semanas registradas ({org})</div></div>
        <div className="kpi green"><div className="n">{unDecimal(sumaCorriendo / filas.length)}</div><div className="l">Media corriendo/acumulando / semana</div></div>
        <div className="kpi bad"><div className="n">{unDecimal(sumaRetraso / filas.length)}</div><div className="l">Media en retraso / semana</div></div>
        <div className="kpi">
          <div className="n">{conOcupacion.length ? `${Math.round(conOcupacion.reduce((x, r) => x + (r.occPct ?? 0), 0) / conOcupacion.length)}%` : '\u2014'}</div>
          <div className="l">Media ocupaci&oacute;n / semana{conOcupacion.length && conOcupacion.length < filas.length ? ` (${conOcupacion.length} sem. con dato)` : ''}</div>
        </div>
        <div className="kpi"><div className="n">CW{ultima.cw}</div><div className="l">&Uacute;ltima semana &middot; en retraso {enRetraso(ultima)}</div></div>
      </div>
      <div className="hint">
        Las semanas guardadas antes de la 4.9.3 no tienen ocupaci&oacute;n (&laquo;&mdash;&raquo;) y su &laquo;en retraso&raquo; sigue el
        criterio antiguo. Son <b>medias semanales</b>, no totales: un ensayo largo aparece en varias semanas, as&iacute; que sumarlas
        contar&iacute;a el mismo ensayo m&aacute;s de una vez.
      </div>
      <div className="card" style={{ overflow: 'auto' }}>
        <h3 style={{ margin: '0 0 8px' }}>Foto semanal &middot; {org}</h3>
        <table className="dash">
          <thead>
            <tr>
              <th style={{ textAlign: 'left' }}>Semana</th><th>Bancos con carga</th><th>Corriendo / acumulando</th>
              <th>Ocupaci&oacute;n (horas)</th><th>En retraso esa semana</th>
            </tr>
          </thead>
          <tbody>
            {filas.map((r) => {
              const retraso = enRetraso(r);
              const corriendo = +r.running || 0;
              return (
                <tr key={`${r.year}-${r.cw}`}>
                  <td className="type">{r.year} &middot; CW{String(r.cw).padStart(2, '0')}</td>
                  <td>{r.benches || 0}{r.total != null && <span style={{ color: 'var(--muted)' }}>/{r.total}</span>}</td>
                  <td style={corriendo > 0 ? { color: colorEstado('running'), fontWeight: 700 } : undefined}>{corriendo}</td>
                  <td title={r.occPct != null ? `${r.occBusy} / ${r.occCap} h` : 'Semana guardada antes de 4.9.3: sin dato de ocupación'}>
                    {r.occPct != null ? <b>{r.occPct}%</b> : <span style={{ color: 'var(--muted)' }}>&mdash;</span>}
                  </td>
                  <td style={retraso > 0 ? { color: colorEstado('delayed'), fontWeight: 700 } : undefined}>{retraso}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="card">
        <h3 style={{ margin: '0 0 8px' }}>Motivos acumulados en las fotos semanales &middot; {org}</h3>
        {!totalMotivos ? <div className="hint">No delay reasons recorded yet.</div> : (
          <table className="dash util-full">
            <thead><tr><th style={{ textAlign: 'left', width: '30%' }}>Reason</th><th style={{ width: '14%' }}>Count</th><th style={{ width: '56%' }}>Share</th></tr></thead>
            <tbody>
              {motivos.map(([nombre, n]) => {
                const p = Math.round((100 * n) / totalMotivos);
                return (
                  <tr key={nombre}>
                    <td className="type">{nombre}</td><td><b>{n}</b></td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <b style={{ minWidth: 40, textAlign: 'right' }}>{p}%</b>
                        <div className="bar" style={{ flex: 1, height: 12 }}><i style={{ width: `${p}%`, background: colorEstado('behind') }} /></div>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
