import { api } from '../../api/cliente';
import type { FilaEstadistica } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { dia, fmt, iso } from '../../utilidades/fechas';
import { colorEstado, etiquetaDeclarado, etiquetaMotivo } from '../../utilidades/textos';
import { useConsulta } from '../panel/comunes';
import { descargar } from '../exportaciones/descargas';

/*
 * «📊 Estadísticas de ensayos» (statsSectionHTML de la 4.9.4): ensayos ya
 * cerrados del periodo, contados por el estado final declarado al cerrarlos.
 * El backend da la lista (statRows); aquí solo se agrupa por mes, trimestre y
 * motivo para mostrarla.
 */

const MESES = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic'];
const porcentaje = (n: number, d: number) => (d ? Math.round((100 * n) / d) : 0);

interface Grupo { clave: string; total: number; ok: number; delayed: number }

/** statBucket: agrupa por la clave de la fecha de referencia, en orden de clave. */
function agrupar(filas: FilaEstadistica[], clave: (d: Date) => string): Grupo[] {
  const m: Record<string, Grupo> = {};
  filas.forEach((f) => {
    const k = clave(dia(f.fecha)!);
    m[k] ??= { clave: k, total: 0, ok: 0, delayed: 0 };
    m[k].total++;
    if (f.estadoFinal === 'ok') m[k].ok++;
    if (f.estadoFinal === 'delayed') m[k].delayed++;
  });
  return Object.values(m).sort((x, y) => x.clave.localeCompare(y.clave));
}

/** Días de calendario entre dos fechas, ambas incluidas (spanDays); 0 si falta alguna. */
function diasDeCalendario(a: unknown, b: unknown): number {
  const da = a ? dia(String(a)) : null, db = b ? dia(String(b)) : null;
  return da && db ? Math.round((db.getTime() - da.getTime()) / 86400000) + 1 : 0;
}

/**
 * ⬇️ Exportar CSV (statExportCSV de la 4.9.4): un ensayo cerrado por línea, con
 * «;» como separador y BOM para que Excel lo abra bien. Base para validar la
 * futura facturación: días facturables = días de calendario − días de parada.
 */
function exportarCsv(filas: FilaEstadistica[], desde: string, hasta: string) {
  const cabecera = ['Fecha arranque', 'Banco', 'Proyecto', 'Tipo', 'Responsable', 'Estado final', 'Motivo', 'Explicacion',
    'Comprometido inicio', 'Comprometido fin', 'Arranque real', 'Fin real', 'Desviacion (d)', 'Dias calendario', 'Dias parada', 'Nota parada',
    'Dias facturables', 'Cierre confirmado', 'Confirmado por', 'Confirmado el'];
  const lineas = [cabecera.join(';')];
  filas.forEach((f) => {
    const d = f.datos;
    const inicio = d.finalTrueStart || d.finalActualStart || d.histStart || '';
    const fin = d.finalTrueEnd || d.histEnd || '';
    const dias = diasDeCalendario(inicio, fin), parada = Number(d.finalDowntimeDays) || 0;
    lineas.push([f.fecha, f.banco, d.project || '', d.type || '', d.resp || '', etiquetaDeclarado(f.estadoFinal),
      f.motivo ? etiquetaMotivo(f.motivo) : '', String(d.finalComment || '').replace(/\r?\n/g, ' '),
      d.confirmedStart || '', d.finalCommittedEnd || d.confirmedEnd || '', inicio, fin,
      d.finalDelayDays != null ? d.finalDelayDays : '', dias || '', parada, d.finalDowntimeNote || '', dias ? Math.max(0, dias - parada) : '',
      f.confirmado ? 'SI' : 'NO', d.finalConfirmedBy || '', d.finalConfirmedAt || '']
      .map((x) => String(x).replace(/;/g, ',')).join(';'));
  });
  descargar(new Blob(['\ufeff' + lineas.join('\n')], { type: 'text/csv;charset=utf-8' }), `BW_Estadisticas_${desde}_${hasta}.csv`);
}

export function Estadisticas() {
  const { vista, personas } = usePlanificador();
  const { kpiOrg, estadisticas, setEstadisticas } = usePanel();
  const anio = vista!.calendario.anio;
  // statDefaults: si no hay periodo elegido, el año del calendario.
  const desde = estadisticas.desde || `${anio}-01-01`;
  const hasta = estadisticas.hasta || `${anio}-12-31`;
  const filas = useConsulta(() => api.estadisticas(desde, hasta, kpiOrg, estadisticas.responsable),
    [desde, hasta, kpiOrg, estadisticas.responsable]) ?? [];

  const poner = (cambio: Partial<typeof estadisticas>) => setEstadisticas({ ...estadisticas, desde, hasta, ...cambio });
  // statQuick: año, año hasta hoy, trimestre y mes de HOY dentro del año del calendario.
  const rapido = (tipo: 'year' | 'ytd' | 'q' | 'm') => {
    const hoy = dia(vista!.hoy)!;
    if (tipo === 'year') poner({ desde: iso(new Date(anio, 0, 1)), hasta: iso(new Date(anio, 11, 31)) });
    else if (tipo === 'ytd') poner({ desde: iso(new Date(anio, 0, 1)), hasta: iso(hoy) });
    else if (tipo === 'q') {
      const q = Math.floor(hoy.getMonth() / 3);
      poner({ desde: iso(new Date(anio, q * 3, 1)), hasta: iso(new Date(anio, q * 3 + 3, 0)) });
    } else poner({ desde: iso(new Date(anio, hoy.getMonth(), 1)), hasta: iso(new Date(anio, hoy.getMonth() + 1, 0)) });
  };

  const total = filas.length;
  const retrasados = filas.filter((f) => f.estadoFinal === 'delayed').length;
  const enPlazo = filas.filter((f) => f.estadoFinal === 'ok').length;
  const caja = { padding: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6 };

  const filtros = (
    <div className="card" style={{ padding: '12px 14px', borderColor: 'var(--turq)' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <b style={{ color: 'var(--turq2)' }}>&#128202; Estad&iacute;sticas de ensayos</b>
        <label className="hint" style={{ margin: '0 0 0 10px' }}>Desde</label>
        <input type="date" id="st-desde" value={desde} style={caja} onChange={(e) => { if (e.target.value) poner({ desde: e.target.value }); }} />
        <label className="hint" style={{ margin: 0 }}>Hasta</label>
        <input type="date" id="st-hasta" value={hasta} style={caja} onChange={(e) => { if (e.target.value) poner({ hasta: e.target.value }); }} />
        <button className="btn ghost sm" onClick={() => rapido('year')}>A&ntilde;o</button>
        <button className="btn ghost sm" onClick={() => rapido('ytd')}>A&ntilde;o hasta hoy</button>
        <button className="btn ghost sm" onClick={() => rapido('q')}>Trimestre</button>
        <button className="btn ghost sm" onClick={() => rapido('m')}>Mes</button>
        <label className="hint" style={{ margin: 0 }}>Responsable</label>
        <select style={caja} value={estadisticas.responsable} onChange={(e) => poner({ responsable: e.target.value })}>
          <option value="">Todos</option>
          {personas.map((p) => <option key={p} value={p}>{p}</option>)}
        </select>
        <button className="btn sm" style={{ marginLeft: 'auto' }} onClick={() => exportarCsv(filas, desde, hasta)}>&#11015;&#65039; Exportar CSV</button>
      </div>
      <div className="hint" style={{ margin: '8px 0 0' }}>
        Ensayos <b>ya cerrados</b>, contados por el <b>estado final que declaraste</b>. La fecha de referencia es la de <b>arranque</b> (real
        o comprometido), porque &laquo;ensayos lanzados&raquo; se cuenta por cu&aacute;ndo empezaron. El filtro <b>Org</b> es el de arriba.
      </div>
    </div>
  );

  const tarjetas = (
    <div className="kpis">
      <div className="kpi"><div className="n">{total}</div><div className="l">Ensayos lanzados (cerrados) en el periodo</div></div>
      <div className="kpi green"><div className="n">{enPlazo}</div><div className="l">Terminados en plazo</div></div>
      <div className="kpi bad"><div className="n">{retrasados}</div><div className="l">Terminados en retraso</div></div>
      <div className="kpi bad"><div className="n">{porcentaje(retrasados, total)}%</div><div className="l">Tasa de retraso</div></div>
    </div>
  );

  if (!total) {
    return <>{filtros}{tarjetas}<div className="card"><div className="empty">No hay ensayos cerrados en este periodo. El estado final se guarda al marcar un ensayo como completado.</div></div></>;
  }

  // Motivos de los cerrados que no acabaron «en plazo», de más a menos.
  const motivos: Record<string, number> = {};
  filas.filter((f) => f.estadoFinal !== 'ok').forEach((f) => {
    const k = f.motivo ? etiquetaMotivo(f.motivo) : '(sin motivo)';
    motivos[k] = (motivos[k] || 0) + 1;
  });
  const totalMotivos = Object.values(motivos).reduce((a, b) => a + b, 0);
  const sinConfirmar = filas.filter((f) => !f.confirmado).length;

  return (
    <>
      {filtros}
      {tarjetas}
      <TablaGrupos titulo="Ensayos por mes" columna="Mes" grupos={agrupar(filas, (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)}
        etiqueta={(k) => { const p = k.split('-'); return `${MESES[+p[1] - 1]} ${p[0]}`; }} />
      <TablaGrupos titulo="Ensayos por trimestre" columna="Trimestre" grupos={agrupar(filas, (d) => `${d.getFullYear()}-Q${Math.floor(d.getMonth() / 3) + 1}`)}
        etiqueta={(k) => k} />
      <div className="card">
        <h3 style={{ margin: '0 0 8px' }}>Motivos de los ensayos cerrados en retraso</h3>
        {!totalMotivos ? <div className="hint">Ning&uacute;n ensayo cerrado en retraso en este periodo.</div> : (
          <table className="dash util-full">
            <thead><tr><th style={{ textAlign: 'left', width: '34%' }}>Motivo</th><th style={{ width: '14%' }}>Ensayos</th><th style={{ width: '52%' }}>Peso</th></tr></thead>
            <tbody>
              {Object.keys(motivos).sort((a, b) => motivos[b] - motivos[a]).map((k) => {
                const p = porcentaje(motivos[k], totalMotivos);
                return (
                  <tr key={k}>
                    <td className="type">{k}</td><td><b>{motivos[k]}</b></td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <b style={{ minWidth: 40, textAlign: 'right' }}>{p}%</b>
                        <div className="bar" style={{ flex: 1, height: 12 }}><i style={{ width: `${p}%`, background: colorEstado('delayed') }} /></div>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
      <div className="card" style={{ padding: 0, maxHeight: '46vh', overflow: 'auto' }}>
        <table className="lst">
          <thead>
            <tr>
              <th>Arranque</th><th>Banco</th><th>Proyecto</th><th>Tipo</th><th>Resp.</th><th>Estado final</th>
              <th>Motivo</th><th>Fin real</th><th>Desv.</th><th>Parada</th><th>Cierre</th>
            </tr>
          </thead>
          <tbody>
            {filas.slice().reverse().map((f) => {
              const d = f.datos;
              const desviacion = d.finalDelayDays;
              return (
                <tr key={f.banco + f.id}>
                  <td style={{ whiteSpace: 'nowrap' }}>{fmt(f.fecha)}</td>
                  <td><b style={{ color: 'var(--turq2)' }}>{f.banco}</b></td>
                  <td>{d.project || '\u2014'}</td><td>{d.type || '\u2014'}</td><td>{d.resp || '\u2014'}</td>
                  <td><span style={{ color: colorEstado(f.estadoFinal === 'delayed' ? 'delayed' : 'running'), fontWeight: 700 }}>{etiquetaDeclarado(f.estadoFinal)}</span></td>
                  <td title={String(d.finalComment || '')}>{f.motivo ? etiquetaMotivo(f.motivo) : '\u2014'}{d.finalComment ? ' \u{1F4AC}' : ''}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    {d.finalTrueEnd ? fmt(String(d.finalTrueEnd)) : d.histEnd ? <span style={{ color: 'var(--muted)' }}>{fmt(d.histEnd)}</span> : '\u2014'}
                  </td>
                  <td>{desviacion != null && desviacion !== '' ? `${Number(desviacion) > 0 ? '+' : ''}${desviacion} d` : '\u2014'}</td>
                  <td>{d.finalDowntimeDays ? `${d.finalDowntimeDays} d` : '\u2014'}</td>
                  <td>
                    {f.confirmado
                      ? <span style={{ color: 'var(--ok)' }} title={`Confirmado por ${d.finalConfirmedBy || ''}`}>&#10004; confirmado</span>
                      : <span style={{ color: 'var(--warn)' }} title="Cerrado sin la ventana de cierre (versión anterior). Fechas no confirmadas.">sin confirmar</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {sinConfirmar > 0 && (
        <div className="hint">
          {sinConfirmar} ensayo(s) cerrados <b>sin confirmar</b> (con una versi&oacute;n anterior o antes de la ventana de cierre): sus fechas
          son previsiones. Un Admin puede completarlos con &#9998; Corregir desde la lista de completados.
        </div>
      )}
    </>
  );
}

function TablaGrupos({ titulo, columna, grupos, etiqueta }: { titulo: string; columna: string; grupos: Grupo[]; etiqueta: (k: string) => string }) {
  return (
    <div className="card" style={{ overflow: 'auto' }}>
      <h3 style={{ margin: '0 0 8px' }}>{titulo}</h3>
      <table className="dash util-full">
        <thead>
          <tr>
            <th style={{ textAlign: 'left', width: '18%' }}>{columna}</th><th style={{ width: '14%' }}>Lanzados</th>
            <th style={{ width: '14%' }}>En plazo</th><th style={{ width: '14%' }}>En retraso</th><th style={{ width: '40%' }}>% retraso</th>
          </tr>
        </thead>
        <tbody>
          {grupos.map((o) => {
            const p = porcentaje(o.delayed, o.total);
            return (
              <tr key={o.clave}>
                <td className="type">{etiqueta(o.clave)}</td><td><b>{o.total}</b></td>
                <td style={{ color: colorEstado('running') }}>{o.ok}</td>
                <td style={{ color: o.delayed ? colorEstado('delayed') : 'var(--muted)', fontWeight: 700 }}>{o.delayed}</td>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <b style={{ minWidth: 44, textAlign: 'right', color: p > 0 ? colorEstado('delayed') : 'var(--muted)' }}>{p}%</b>
                    <div className="bar" style={{ flex: 1, height: 12 }}><i style={{ width: `${p}%`, background: colorEstado('delayed') }} /></div>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
