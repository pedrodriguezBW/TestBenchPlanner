import { Fragment, useEffect, useMemo, useState } from 'react';
import { apiFacturacion, descargarArchivo, type EventoAuditoria, type Grupo, type LineaFacturacion } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';
import { AccionesPeriodo, CabeceraPeriodo } from './AccionesPeriodo';
import { usePeriodoElegido, useVistaPeriodo } from './estadoPeriodo';
import { ChipLinea } from './PanelLinea';
import { SelectorPeriodo } from './SelectorPeriodo';
import { verLineasEnRevision } from './VistaRevision';
import { euros, fechaHora, horas, numero, tarifaTexto } from './textos';

/*
 * Billing › Overview / Reports: comprobación visual del resultado completo del
 * periodo (indicadores, resúmenes por proyecto y orden, equipo y planta,
 * desgloses, incidencias y vista previa de Controlling) antes de enviarlo a
 * revisión, aprobarlo o exportarlo. Los importes y agrupaciones los calcula el
 * backend (los mismos que exporta el Excel y el HTML).
 */

type Seccion = 'overview' | 'projects' | 'equipment' | 'plants' | 'incidents' | 'controlling' | 'exports';
const SECCIONES: [Seccion, string][] = [['overview', 'Overview'], ['projects', 'Projects'], ['equipment', 'Equipment'], ['plants', 'Plants'],
  ['incidents', 'Incidents'], ['controlling', 'Controlling'], ['exports', 'Exports & audit']];
const DIMENSIONES: [string, string][] = [['proyecto', 'Proyecto'], ['orden', 'Orden'], ['banco', 'Banco'], ['categoria', 'Categoría de facturación'],
  ['tipoEnsayo', 'Tipo de ensayo'], ['planta', 'Planta'], ['lineaProducto', 'Línea de producto'], ['requester', 'Requester'], ['hv', 'Condición HV']];

function Barras({ grupos, onElegir }: { grupos: Grupo[]; onElegir: (g: Grupo) => void }) {
  const max = Math.max(1, ...grupos.map((g) => g.coste));
  return (
    <div className="fac-barras">
      {grupos.slice(0, 20).map((g) => (
        <div key={g.clave} className="fac-barra" onClick={() => onElegir(g)} title="Ver las líneas">
          <span>{g.etiqueta}</span>
          <span className="pista"><span className="relleno" style={{ width: `${(100 * g.coste) / max}%` }} /></span>
          <span className="va">{euros(g.coste)}</span>
        </div>
      ))}
    </div>
  );
}

export function VistaOverview() {
  const { sesion } = usePlanificador();
  const [id, setId] = usePeriodoElegido();
  const { vista, ocupado, ejecutar, cargando } = useVistaPeriodo(id);
  const [seccion, setSeccion] = useState<Seccion>('overview');
  const [dimension, setDimension] = useState('proyecto');
  const [grupo, setGrupo] = useState<{ etiqueta: string; lineas: string[] } | null>(null);
  const [abiertas, setAbiertas] = useState<Set<number>>(new Set());
  const [filtroCtrl, setFiltroCtrl] = useState({ planta: '', equipo: '', orden: '' });
  const [auditoria, setAuditoria] = useState<EventoAuditoria[]>([]);

  useEffect(() => { setGrupo(null); }, [id, seccion]);
  useEffect(() => {
    if (seccion === 'exports' && id) apiFacturacion.auditoria(id).then(setAuditoria).catch(() => setAuditoria([]));
  }, [seccion, id, vista]);

  const porId = useMemo(() => new Map((vista?.periodo.lineas ?? []).map((l) => [l.id, l])), [vista]);
  if (!vista) {
    return (
      <div>
        <div className="view-head" style={{ gap: 10 }}><h2>&#128182; Billing &middot; Overview / Reports</h2><SelectorPeriodo id={id} onCambio={setId} /></div>
        <div className="empty">{!id ? 'Crea un periodo en Periods.' : cargando ? 'Cargando\u2026' : 'No se ha podido cargar el periodo.'}</div>
      </div>
    );
  }
  const r = vista.resumen;
  const ind = r.indicadores;
  const elegir = (g: { etiqueta: string; lineas: string[] }) => setGrupo({ etiqueta: g.etiqueta, lineas: g.lineas });
  const irARevision = (lineas: string[], etiqueta: string) => { verLineasEnRevision(lineas, etiqueta); window.location.hash = '/__billing_review__'; };
  const kpi = (n: string, l: string, clase = '') => <div className={`kpi ${clase}`}><div className="n">{n}</div><div className="l">{l}</div></div>;

  const lineasGrupo: LineaFacturacion[] = grupo ? grupo.lineas.map((x) => porId.get(x)).filter((x): x is LineaFacturacion => !!x) : [];
  const detalleGrupo = grupo && (
    <div className="card" style={{ marginTop: 12 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <b>Líneas de «{grupo.etiqueta}»</b><span className="fac-nota">{lineasGrupo.length} línea(s)</span>
        <button className="btn ghost sm" style={{ marginLeft: 'auto' }} onClick={() => irARevision(grupo.lineas, grupo.etiqueta)}>Abrir en Review</button>
        <button className="btn ghost sm" onClick={() => setGrupo(null)}>&#10005;</button>
      </div>
      <table className="fac" style={{ marginTop: 8 }}>
        <thead><tr><th>Estado</th><th>Test</th><th>Banco</th><th>Proyecto</th><th>Orden</th><th>Categoría</th><th className="n">Horas</th><th className="n">Setup</th><th className="n">Coste</th></tr></thead>
        <tbody>{lineasGrupo.map((l) => (
          <tr key={l.id} className="clic" onClick={() => irARevision([l.id], l.ensayoId)}><td><ChipLinea estado={l.estado} /></td><td>{l.ensayoId}</td><td>{l.banco}</td>
            <td>{l.aplicado?.proyecto}</td><td>{l.aplicado?.orden}</td><td>{l.aplicado?.categoria}</td>
            <td className="n">{horas(l.aplicado?.horasNetas)}</td><td className="n">{horas(l.aplicado?.horasSetup)}</td><td className="n">{euros(l.aplicado?.coste)}</td></tr>
        ))}</tbody>
      </table>
    </div>
  );

  // Controlling con filtros por planta, equipo y orden (las sumas filtradas se rehacen con el desglose de cada fila).
  const plantas = [...new Set(vista.periodo.lineas.map((l) => l.organizacion).filter(Boolean))].sort();
  const filasCtrl = r.controlling.map((f) => {
    if (!filtroCtrl.planta) return f;
    const ls = f.lineas.filter((x) => porId.get(x.lineaId)?.organizacion === filtroCtrl.planta);
    const coste = ls.reduce((s, x) => s + x.coste, 0);
    const qty = ls.reduce((s, x) => s + (x.cantidad ?? 0), 0);
    return { ...f, lineas: ls, sumaCoste: coste, amount: Math.round(coste * 100) / 100, sumaHoras: qty, totalQty: qty };
  }).filter((f) => f.lineas.length && (!filtroCtrl.equipo || f.equipo === filtroCtrl.equipo) && (!filtroCtrl.orden || f.orden === filtroCtrl.orden));

  return (
    <div>
      <div className="view-head" style={{ gap: 10 }}><h2>&#128182; Billing &middot; Overview / Reports</h2><SelectorPeriodo id={id} onCambio={setId} /></div>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap' }}>
        <div style={{ flex: 1 }}><CabeceraPeriodo vista={vista} /></div>
        <AccionesPeriodo vista={vista} ejecutar={ejecutar} ocupado={ocupado} />
      </div>
      {vista.alertas.length > 0 && <div className="fac-aviso">&#9888; {vista.alertas.length} ensayo(s) han cambiado después de aprobar: {vista.alertas.map((a) => a.ensayoId).join(', ')}.</div>}
      <div className="fac-kpis" id="fac-kpis">
        {kpi(euros(ind.coste), 'Coste total')}
        {kpi(horas(ind.horasNetas), 'Horas netas')}
        {kpi(horas(ind.horasSetup), 'Horas de setup')}
        {kpi(String(ind.ensayos), 'Ensayos')}
        {kpi(String(ind.preparadas), 'Líneas preparadas')}
        {kpi(String(ind.bloqueadas), 'Líneas bloqueadas', ind.bloqueadas ? 'alerta' : '')}
        {kpi(String(ind.advertencias), 'Advertencias', ind.advertencias ? 'aviso' : '')}
        {kpi(String(ind.excluidas), 'Líneas excluidas')}
        {kpi(String(ind.aplazadas), 'Líneas aplazadas')}
        {kpi(String(ind.ajustesPendientes), 'Ajustes económicos pendientes', ind.ajustesPendientes ? 'aviso' : '')}
      </div>
      <div className="fac-pestanas no-print">
        {SECCIONES.map(([k, t]) => <button key={k} data-seccion={k} className={seccion === k ? 'activa' : ''} onClick={() => setSeccion(k)}>{t}</button>)}
      </div>

      {seccion === 'overview' && (
        <div className="card">
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <b>Desglose por</b>
            <select className="fac-campo" id="fac-dimension" value={dimension} onChange={(e) => { setDimension(e.target.value); setGrupo(null); }}>
              {DIMENSIONES.map(([k, t]) => <option key={k} value={k}>{t}</option>)}
            </select>
            <span className="fac-nota">Haz clic en una barra o una fila para ver las líneas que forman su importe.</span>
          </div>
          <Barras grupos={r.desgloses[dimension] ?? []} onElegir={elegir} />
          <table className="fac" style={{ marginTop: 10 }}>
            <thead><tr><th>{DIMENSIONES.find(([k]) => k === dimension)?.[1]}</th><th className="n">Ensayos</th><th className="n">Horas</th><th className="n">Setup</th><th className="n">Coste</th></tr></thead>
            <tbody>{(r.desgloses[dimension] ?? []).map((g) => (
              <tr key={g.clave} className="clic" onClick={() => elegir(g)}><td>{g.etiqueta}</td><td className="n">{g.ensayos}</td><td className="n">{horas(g.horas)}</td>
                <td className="n">{horas(g.setup)}</td><td className="n">{euros(g.coste)}</td></tr>
            ))}</tbody>
          </table>
          {detalleGrupo}
        </div>
      )}

      {seccion === 'projects' && (
        <div className="card">
          <table className="fac" id="fac-proyectos">
            <thead><tr><th>Proyecto</th><th>Nombre</th><th>Orden</th><th className="n">Ensayos</th><th className="n">Horas</th><th className="n">Setup</th><th className="n">Coste</th></tr></thead>
            <tbody>{r.porProyectoOrden.map((g) => (
              <tr key={g.clave} className="clic" onClick={() => elegir({ etiqueta: `${g.proyecto} · ${g.orden}`, lineas: g.lineas })}>
                <td>{g.proyecto || '\u2014'}</td><td>{g.proyectoNombre}</td><td>{g.orden || '\u2014'}</td><td className="n">{g.ensayos}</td>
                <td className="n">{horas(g.horas)}</td><td className="n">{horas(g.setup)}</td><td className="n">{euros(g.coste)}</td></tr>
            ))}
              <tr className="total"><td colSpan={4}>Total</td><td className="n">{horas(ind.horasNetas)}</td><td className="n">{horas(ind.horasSetup)}</td><td className="n">{euros(ind.coste)}</td></tr>
            </tbody>
          </table>
          {detalleGrupo}
        </div>
      )}

      {seccion === 'equipment' && (
        <div className="card">
          <table className="fac" id="fac-equipos">
            <thead><tr><th>Equipo (categoría)</th><th>Tipo</th><th>Bancos</th><th className="n">Horas</th><th className="n">Setup</th><th>Tarifa o regla</th><th className="n">Coste</th></tr></thead>
            <tbody>{r.porEquipo.map((g) => (
              <tr key={g.clave} className="clic" onClick={() => elegir({ etiqueta: g.categoria, lineas: g.lineas })}>
                <td>{g.categoria}</td><td>{g.grupo}</td><td>{g.bancos.join(', ')}</td><td className="n">{horas(g.horas)}</td><td className="n">{horas(g.setup)}</td>
                <td>{g.tarifas.map((t) => tarifaTexto(t, g.unidad)).join(' / ')}{g.noLineal && <b style={{ color: 'var(--warn)' }}> · {g.regla}: coste no lineal</b>}</td>
                <td className="n">{euros(g.coste)}</td></tr>
            ))}</tbody>
          </table>
          <div className="fac-nota" style={{ marginTop: 6 }}>Reglas reductoras (como en el Excel): R&amp;D = tarifa × ASINH(h/100) × 100; Heating Plates y HVCH Lubu = tarifa × ASINH(h/200) × 200, por línea.</div>
          {detalleGrupo}
        </div>
      )}

      {seccion === 'plants' && (
        <div className="fac-grid2">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>BW y CTAG</h3>
            <table className="fac" id="fac-plantas">
              <thead><tr><th>Planta</th><th className="n">Ensayos</th><th className="n">Horas</th><th className="n">Setup</th><th className="n">Coste</th></tr></thead>
              <tbody>{r.porPlanta.map((g) => (
                <tr key={g.clave} className="clic" onClick={() => elegir(g)}><td>{g.etiqueta}</td><td className="n">{g.ensayos}</td><td className="n">{horas(g.horas)}</td>
                  <td className="n">{horas(g.setup)}</td><td className="n">{euros(g.coste)}</td></tr>
              ))}</tbody>
            </table>
            <Barras grupos={r.porPlanta} onElegir={elegir} />
            {detalleGrupo}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Planta (si EGM) / product line</h3>
            <div className="fac-tabla-wrap">
              <table className="fac">
                <thead><tr><th>Línea / orden / requester</th>{r.porLineaProducto.columnas.map((c) => <th key={c} className="n">{c || '(sin tipo)'}</th>)}<th className="n">Total</th></tr></thead>
                <tbody>{r.porLineaProducto.filas.map((f, i) => (
                  <tr key={i} className={f.nivel < 0 ? 'total' : `nivel${f.nivel}`}><td>{f.etiqueta}</td>
                    {r.porLineaProducto.columnas.map((c) => <td key={c} className="n">{c in f.valores ? numero(f.valores[c]) : ''}</td>)}<td className="n">{numero(f.total)}</td></tr>
                ))}</tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {seccion === 'incidents' && (
        <div className="card">
          {!r.incidencias.length ? <div className="empty">Sin incidencias.</div> : (
            <table className="fac" id="fac-incidencias">
              <thead><tr><th>Nivel</th><th>Línea</th><th>Banco</th><th>Estado</th><th>Incidencia</th></tr></thead>
              <tbody>{r.incidencias.map((i, k) => (
                <tr key={k} className="clic" onClick={() => irARevision([i.lineaId], i.ensayoId)} title="Abrir la línea en Review">
                  <td><span className={`fac-inc ${i.nivel}`}>{i.nivel === 'BLOQUEANTE' ? '\u26D4 Bloqueante' : '\u26A0 Advertencia'}</span></td>
                  <td>{i.ensayoId}</td><td>{i.banco}</td><td><ChipLinea estado={i.estado} /></td><td>{i.mensaje}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      )}

      {seccion === 'controlling' && (
        <div className="card">
          <div className="fac-filtros">
            <b>Controlling - Internal Testing</b>
            <select value={filtroCtrl.planta} onChange={(e) => setFiltroCtrl({ ...filtroCtrl, planta: e.target.value })}><option value="">Planta</option>{plantas.map((p) => <option key={p}>{p}</option>)}</select>
            <select value={filtroCtrl.equipo} onChange={(e) => setFiltroCtrl({ ...filtroCtrl, equipo: e.target.value })}><option value="">Equipo</option>{[...new Set(r.controlling.map((f) => f.equipo))].map((p) => <option key={p}>{p}</option>)}</select>
            <select value={filtroCtrl.orden} onChange={(e) => setFiltroCtrl({ ...filtroCtrl, orden: e.target.value })}><option value="">Orden</option>{[...new Set(r.controlling.map((f) => f.orden))].sort().map((p) => <option key={p}>{p}</option>)}</select>
            <span className="fac-nota" style={{ marginLeft: 'auto' }}>Mismas columnas y agrupación que el Excel; Amount redondeado a 2 decimales.</span>
          </div>
          <div className="fac-tabla-wrap">
            <table className="fac" id="fac-controlling">
              <thead><tr>{['Equipo', 'Orden', 'Suma de Coste total', 'Sum of Horas totales', 'CCtr (Old)', 'Order (old)', 'Oper. (old)', 'Cost Element', 'Amount', 'Crcy',
                'CCtr (new)', 'Order (new)', 'Oper. (new)', 'Total Qty', 'UM', 'Pers.No.', 'Text'].map((t) => <th key={t}>{t}</th>)}</tr></thead>
              <tbody>{filasCtrl.map((f, i) => (
                <Fragment key={i}>
                  <tr className="clic" onClick={() => setAbiertas((s) => { const n = new Set(s); if (n.has(i)) n.delete(i); else n.add(i); return n; })}>
                    <td>{abiertas.has(i) ? '\u25BE' : '\u25B8'} {f.equipo}</td><td>{f.orden}</td><td className="n">{numero(f.sumaCoste, 4)}</td><td className="n">{numero(f.sumaHoras)}</td>
                    <td>{f.cctrOld}</td><td>{f.orderOld}</td><td>{f.operOld}</td><td>{f.costElement}</td><td className="n"><b>{numero(f.amount)}</b></td><td>{f.crcy}</td>
                    <td>{f.cctrNew}</td><td>{f.orderNew}</td><td>{f.operNew}</td><td className="n">{numero(f.totalQty)}</td><td>{f.um}</td><td>{f.persNo}</td><td>{f.text}</td>
                  </tr>
                  {abiertas.has(i) && f.lineas.map((x) => (
                    <tr key={x.lineaId} className="nivel2"><td colSpan={2} style={{ paddingLeft: 28 }}>&ndash; {x.ensayoId} &middot; {x.banco}{x.tipoEnsayo ? ` · ${x.tipoEnsayo}` : ''}</td>
                      <td className="n">{euros(x.coste)}</td><td className="n">{numero(x.cantidad ?? 0)}</td><td colSpan={13} /></tr>
                  ))}
                </Fragment>
              ))}
                <tr className="total"><td colSpan={8}>Total Controlling</td><td className="n">{numero(filasCtrl.reduce((s, f) => s + f.amount, 0))}</td><td colSpan={8} /></tr>
              </tbody>
            </table>
          </div>
        </div>
      )}

      {seccion === 'exports' && (
        <div className="fac-grid2">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Exportaciones</h3>
            {!vista.periodo.exportaciones.length ? <div className="empty">Sin exportaciones. Solo un Admin exporta un periodo aprobado.</div> : (
              <table className="fac" id="fac-exportaciones">
                <thead><tr><th>Identificador</th><th>Versión</th><th>Tipo</th><th>Usuario</th><th>Fecha</th><th>Estado</th><th /></tr></thead>
                <tbody>{vista.periodo.exportaciones.slice().reverse().map((x) => (
                  <tr key={x.id}><td>{x.id}</td><td>v{x.version}</td><td>{x.tipo}</td><td>{x.por}</td><td>{fechaHora(x.en)}</td>
                    <td><span className={`fac-chip ${x.estado === 'VALIDA' ? 'fac-l-ready' : 'fac-l-excluded'}`}>{x.estado === 'VALIDA' ? 'Válida' : 'Obsoleta'}</span></td>
                    <td>{sesion?.esAdmin && <button className="btn ghost sm" onClick={() => void apiFacturacion.descargar(vista.periodo.id, x.id).then(descargarArchivo)}>&#11015; Descargar</button>}</td></tr>
                ))}</tbody>
              </table>
            )}
            <h3>Versiones aprobadas</h3>
            {!vista.periodo.fotos.length ? <div className="fac-nota">Todavía no se ha aprobado.</div> : vista.periodo.fotos.map((f) => (
              <div key={f.version} className="fac-nota">v{f.version} &middot; aprobada por {f.aprobadoPor} el {fechaHora(f.aprobadoEn)} &middot; {euros(f.indicadores?.coste)}</div>
            ))}
            {vista.periodo.reaperturas.map((x, k) => <div key={k} className="fac-aviso">Reabierta v{x.version} por {x.por} el {fechaHora(x.en)}: «{x.justificacion}»</div>)}
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Auditoría del periodo</h3>
            {!auditoria.length ? <div className="empty">Sin eventos.</div> : (
              <div className="fac-tabla-wrap">
                <table className="fac" id="fac-auditoria">
                  <thead><tr><th>Fecha</th><th>Usuario</th><th>Acción</th><th>Detalle</th><th className="n">Antes</th><th className="n">Después</th><th>Justificación</th></tr></thead>
                  <tbody>{auditoria.map((e) => (
                    <tr key={e.id}><td>{fechaHora(e.en)}</td><td>{e.por}</td><td>{e.accion}</td><td>{e.detalle}</td><td className="n">{euros(e.costeAntes)}</td>
                      <td className="n">{euros(e.costeDespues)}</td><td>{e.justificacion}</td></tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
