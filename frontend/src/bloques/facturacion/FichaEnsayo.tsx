import { useEffect, useRef, useState } from 'react';
import { apiFacturacion, type DatosFacturacionEnsayo, type FichaFacturacion, type Parada } from '../../api/facturacion';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { duracionHoras, fechaHora, horas, numero } from './textos';

/*
 * «Billing Data» del ensayo: hora real de inicio y fin, paradas con fecha y
 * hora (motivo y condición facturable), facturable, orden, alta tensión, setup
 * y observación. Se usa en la ficha (botón «€» de la tabla y de los cerrados)
 * y, plegado, en la ventana de cierre. El backend valida y calcula la vista
 * previa de horas netas por periodo; aquí solo se muestra.
 */

export const BILLING_VACIO: DatosFacturacionEnsayo = {
  startTime: '', endTime: '', stops: [], setupHours: '', setupConfirmed: false, hv: '', billable: true, order: '', orderException: null, pieces: '', note: '',
};

function normalizar(b: Partial<DatosFacturacionEnsayo> | undefined): DatosFacturacionEnsayo {
  return { ...BILLING_VACIO, ...(b ?? {}), stops: (b?.stops ?? []).map((p) => ({ ...p })), orderException: b?.orderException ?? null };
}

const campo = { background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6, padding: '5px 7px' };

/** Formulario compartido de los datos de facturación. */
export function EditorDatosFacturacion({ ficha, valor, onCambio, soloLectura, fechaInicio, fechaFin }: {
  ficha: FichaFacturacion;
  valor: DatosFacturacionEnsayo;
  onCambio: (v: DatosFacturacionEnsayo) => void;
  soloLectura: boolean;
  fechaInicio: string;
  fechaFin: string;
}) {
  const cambiar = (c: Partial<DatosFacturacionEnsayo>) => onCambio({ ...valor, ...c });
  const ordenes = ficha.proyecto?.ordenes.map((o) => o.codigo) ?? [];
  const ordenFuera = !!valor.order && !ordenes.includes(valor.order);
  const [otraOrden, setOtraOrden] = useState(ordenFuera);
  const cambiarParada = (i: number, c: Partial<Parada>) => cambiar({ stops: valor.stops.map((p, j) => (j === i ? { ...p, ...c } : p)) });
  const anadirParada = () => {
    const base = fechaFin || fechaInicio || '';
    cambiar({ stops: [...valor.stops, { from: base ? `${base}T08:00` : '', to: base ? `${base}T12:00` : '', reason: '', billable: false, note: '' }] });
  };
  const porPiezas = ficha.categoria?.unidad === 'PIEZAS';

  return (
    <div className="fac-ficha">
      <details className="fac-plegable" open>
        <summary>Ejecución real</summary>
        <div className="fac-form">
          <label htmlFor="bd-ti">Inicio real</label>
          <span>{fechaInicio ? fechaHora(fechaInicio) : <span className="fac-nota">Sin arranque real</span>}{' '}
            <input id="bd-ti" type="time" step={900} style={campo} value={valor.startTime} disabled={soloLectura || !fechaInicio}
              onChange={(e) => cambiar({ startTime: e.target.value })} /></span>
          <label htmlFor="bd-tf">Fin real</label>
          <span>{fechaFin ? fechaHora(fechaFin) : <span className="fac-nota">Abierto (se factura hasta el corte del periodo)</span>}{' '}
            {fechaFin && <input id="bd-tf" type="time" step={900} style={campo} value={valor.endTime} disabled={soloLectura}
              onChange={(e) => cambiar({ endTime: e.target.value })} />}</span>
          <label>Fines de semana</label>
          <span className="fac-nota">{ficha.finDeSemana ? 'El banco trabaja en fin de semana (cuentan).' : 'El banco no trabaja en fin de semana (se descuentan).'}
            {' '}Un festivo sin trabajo se indica como parada.</span>
        </div>
      </details>

      <details className="fac-plegable" open={valor.stops.length > 0}>
        <summary>Paradas ({valor.stops.length})</summary>
        <div>
          {valor.stops.length > 0 && (
            <table className="fac-paradas">
              <thead><tr><th>Desde</th><th>Hasta</th><th style={{ textAlign: 'right' }}>Duración</th><th>Motivo</th><th>Facturable</th><th>Nota</th><th /></tr></thead>
              <tbody>
                {valor.stops.map((p, i) => {
                  const d = p.from && p.to ? duracionHoras(p.from, p.to) : null;
                  return (
                    <tr key={p.id ?? i} data-parada={i}>
                      <td><input type="datetime-local" step={900} value={p.from} disabled={soloLectura} onChange={(e) => cambiarParada(i, { from: e.target.value })} /></td>
                      <td><input type="datetime-local" step={900} value={p.to} disabled={soloLectura} onChange={(e) => cambiarParada(i, { to: e.target.value })} /></td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{d === null ? '\u2014' : `${numero(d)} h`}</td>
                      <td>
                        <select value={p.reason} disabled={soloLectura} onChange={(e) => cambiarParada(i, { reason: e.target.value })}>
                          <option value="">&mdash;</option>
                          {ficha.motivosParada.map((m) => <option key={m} value={m}>{m === 'Averia' ? 'Avería' : m === 'Caida de ensayo' ? 'Caída de ensayo' : m}</option>)}
                        </select>
                      </td>
                      <td>
                        <select value={p.billable ? 'yes' : 'no'} disabled={soloLectura} onChange={(e) => cambiarParada(i, { billable: e.target.value === 'yes' })}>
                          <option value="no">No</option><option value="yes">Sí</option>
                        </select>
                      </td>
                      <td><input value={p.note ?? ''} placeholder="Opcional" disabled={soloLectura} onChange={(e) => cambiarParada(i, { note: e.target.value })} /></td>
                      <td><button className="icbtn del ed" title="Eliminar parada" disabled={soloLectura}
                        onClick={() => cambiar({ stops: valor.stops.filter((_, j) => j !== i) })}>&#128465;</button></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          <button className="btn ghost sm ed" disabled={soloLectura || !fechaInicio} onClick={anadirParada} style={{ marginTop: 6 }}>&#10133; Añadir parada</button>
          <div className="fac-nota" style={{ marginTop: 4 }}>Las paradas no facturables se restan de las horas netas; no cambian el Gantt ni la ocupación.</div>
        </div>
      </details>

      <details className="fac-plegable" open>
        <summary>Datos de facturación</summary>
        <div className="fac-form">
          <label htmlFor="bd-fact">Facturable</label>
          <span>
            <input id="bd-fact" type="checkbox" checked={valor.billable} disabled={soloLectura} onChange={(e) => cambiar({ billable: e.target.checked })} />
            {' '}{valor.billable ? 'Sí' : <b style={{ color: 'var(--warn)' }}>No: la línea será visible pero no sumará horas ni importe</b>}
            {!valor.billable && valor.billableBy && <span className="fac-nota"> &middot; marcado por {valor.billableBy} ({fechaHora(valor.billableAt)})</span>}
          </span>
          <label>Proyecto</label>
          <span>{ficha.proyectoTexto || '\u2014'}{ficha.proyecto ? <span className="fac-nota"> &middot; {ficha.proyecto.nombre}{ficha.proyecto.lineaProducto ? ` · ${ficha.proyecto.lineaProducto}` : ''}</span>
            : <span className="fac-aviso"> &middot; no está en los maestros de facturación</span>}</span>
          <label>Requester</label><span>{ficha.requester || '\u2014'}</span>
          <label>Planta</label><span>{ficha.organizacion || '\u2014'}</span>
          <label htmlFor="bd-ord">Orden contable</label>
          <span>
            {!otraOrden ? (
              <select id="bd-ord" style={campo} value={ordenes.includes(valor.order) ? valor.order : ''} disabled={soloLectura}
                onChange={(e) => { if (e.target.value === '__otra__') { setOtraOrden(true); cambiar({ order: '' }); } else cambiar({ order: e.target.value }); }}>
                <option value="">{ordenes.length === 1 ? `Automática (${ordenes[0]})` : ordenes.length ? '\u2014 Elegir \u2014' : 'El proyecto no tiene órdenes'}</option>
                {ordenes.map((o) => <option key={o} value={o}>{o}</option>)}
                {ficha.esAdmin && <option value="__otra__">Otra orden (excepcional)&hellip;</option>}
              </select>
            ) : (
              <>
                <input id="bd-ord" style={campo} value={valor.order} placeholder="Orden" disabled={soloLectura || !ficha.esAdmin}
                  onChange={(e) => cambiar({ order: e.target.value })} />{' '}
                <input style={{ ...campo, width: 260 }} placeholder="Justificación (obligatoria)" disabled={soloLectura || !ficha.esAdmin}
                  value={valor.orderException?.justification ?? ''}
                  onChange={(e) => cambiar({ orderException: e.target.value ? { ...(valor.orderException ?? {}), justification: e.target.value } : null })} />{' '}
                {ficha.esAdmin && <button className="btn ghost sm ed" onClick={() => { setOtraOrden(false); cambiar({ order: '', orderException: null }); }}>Quitar</button>}
              </>
            )}
          </span>
          <label>Categoría</label>
          <span>{ficha.categoria ? `${ficha.categoria.codigo}${ficha.categoria.grupo ? ` · ${ficha.categoria.grupo}` : ''} · ${ficha.categoria.regla}`
            : <span className="fac-aviso">El banco no tiene categoría (la asigna un Admin en Billing &rarr; Configuration)</span>}</span>
          <label htmlFor="bd-hv">Alta tensión</label>
          <select id="bd-hv" style={{ ...campo, width: 120 }} value={valor.hv} disabled={soloLectura} onChange={(e) => cambiar({ hv: e.target.value as DatosFacturacionEnsayo['hv'] })}>
            <option value="">&mdash;</option><option value="no">No</option><option value="yes">Sí</option>
          </select>
          <label htmlFor="bd-su">Setup (h)</label>
          <span>
            <input id="bd-su" type="number" min="0" step="0.25" style={{ ...campo, width: 90 }} value={valor.setupHours} disabled={soloLectura}
              onChange={(e) => cambiar({ setupHours: e.target.value === '' ? '' : Number(e.target.value) })} />{' '}
            {ficha.setupRecomendado !== null && (
              <button className="btn ghost sm ed" disabled={soloLectura} onClick={() => cambiar({ setupHours: ficha.setupRecomendado ?? '' })}>
                Usar recomendado ({horas(ficha.setupRecomendado)})</button>
            )}{' '}
            <label style={{ color: 'var(--text)' }}><input type="checkbox" checked={valor.setupConfirmed} disabled={soloLectura}
              onChange={(e) => cambiar({ setupConfirmed: e.target.checked })} /> Setup confirmado</label>
          </span>
          {porPiezas && <>
            <label htmlFor="bd-pz">Piezas</label>
            <input id="bd-pz" type="number" min="0" step="1" style={{ ...campo, width: 90 }} value={valor.pieces} disabled={soloLectura}
              onChange={(e) => cambiar({ pieces: e.target.value === '' ? '' : Number(e.target.value) })} />
          </>}
          <label htmlFor="bd-ob">Observación</label>
          <textarea id="bd-ob" rows={2} style={{ ...campo, resize: 'vertical' }} value={valor.note} placeholder="Opcional" disabled={soloLectura}
            onChange={(e) => cambiar({ note: e.target.value })} />
        </div>
      </details>
    </div>
  );
}

/** Estado de los datos y vista previa de horas por periodo (lo calcula el backend). */
export function VistaPreviaFacturacion({ ficha }: { ficha: FichaFacturacion }) {
  const e = ficha.estado;
  return (
    <div className="fac-bloque">
      <h4>Vista previa</h4>
      <div style={{ marginBottom: 6 }}>
        {e.estado === 'COMPLETO' && <span className="fac-chip fac-l-ready">&#10004; Datos de facturación completos</span>}
        {e.estado === 'BILLING_DATA_INCOMPLETE' && <span className="fac-chip fac-l-warning" title="El cierre operativo no se bloquea por esto">
          &#9888; BILLING_DATA_INCOMPLETE &middot; faltan: {e.faltan.join(', ')}</span>}
        {e.estado === 'NO_FACTURABLE' && <span className="fac-chip fac-l-excluded">&#8856; No facturable</span>}
        {e.estado === 'NO_APLICA' && <span className="fac-chip">Tipo especial: no se factura</span>}
      </div>
      {ficha.errores.map((t) => <div key={t} className="fac-error">{t}</div>)}
      {ficha.tramos.length > 0 ? (
        <table className="fac">
          <thead><tr><th>Periodo</th><th>Tramo</th><th className="n">Tiempo</th><th className="n">Fines de semana</th><th className="n">Paradas no fact.</th><th className="n">Horas netas</th></tr></thead>
          <tbody>
            {ficha.tramos.map((t) => (
              <tr key={t.periodo}>
                <td>{t.nombre}{t.abierto ? ' (abierto)' : ''}</td>
                <td>{fechaHora(t.tramoInicio)} &rarr; {fechaHora(t.tramoFin)}</td>
                <td className="n">{horas(t.minutosBrutos / 60)}</td>
                <td className="n">{t.minutosFinDeSemana ? `\u2212${horas(t.minutosFinDeSemana / 60)}` : '\u2014'}</td>
                <td className="n">{t.minutosParadas ? `\u2212${horas(t.minutosParadas / 60)}` : '\u2014'}</td>
                <td className="n"><b>{horas(t.horasNetas)}</b></td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <div className="fac-nota">Sin arranque real todavía: no hay horas que facturar.</div>}
      <div className="fac-nota" style={{ marginTop: 6 }}>Horas netas redondeadas a 15 min por tramo mensual (periodo del 21 al 20). El setup se suma en el tramo del arranque.</div>
    </div>
  );
}

/** Vista previa en vivo: pide al backend la ficha con los datos que se están escribiendo. */
function useVistaPrevia(banco: string, id: string, valor: DatosFacturacionEnsayo | null, fechas?: { fechaInicio?: string; fechaFin?: string }) {
  const [previa, setPrevia] = useState<FichaFacturacion | null>(null);
  const clave = JSON.stringify([valor, fechas]);
  useEffect(() => {
    if (!valor) return;
    const t = window.setTimeout(() => {
      apiFacturacion.vistaPrevia(banco, id, valor, fechas).then(setPrevia).catch(() => undefined);
    }, 250);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [banco, id, clave]);
  return previa;
}

/** Ficha «Billing Data» de un ensayo (ventana). */
export function VentanaFacturacionEnsayo({ banco, id, titulo, onCerrar }: { banco: string; id: string | null; titulo: string; onCerrar: () => void }) {
  const { guardarFacturacion, mostrarMensaje, soloLectura } = usePlanificador();
  const [ficha, setFicha] = useState<FichaFacturacion | null>(null);
  const [valor, setValor] = useState<DatosFacturacionEnsayo | null>(null);
  const [enviando, setEnviando] = useState(false);
  const previa = useVistaPrevia(banco, id ?? '', id ? valor : null);

  useEffect(() => {
    setFicha(null);
    setValor(null);
    if (!id) return;
    apiFacturacion.ficha(banco, id)
      .then((f) => { setFicha(f); setValor(normalizar(f.billing)); })
      .catch((e: Error) => { onCerrar(); mostrarMensaje(e.message); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [banco, id]);

  if (!id || !ficha || !valor) return null;
  const vista = previa ?? ficha;
  const bloqueado = soloLectura || !ficha.puedeEditar;
  const guardar = async () => {
    setEnviando(true);
    const ok = await guardarFacturacion(banco, id, valor);
    setEnviando(false);
    if (ok) onCerrar();
  };
  return (
    <Dialogo abierto ancho="wide" onCerrar={onCerrar}>
      <h3 id="bd-title">Billing Data &middot; {banco}</h3>
      <div className="hint">{titulo} &middot; {ficha.cerrado ? 'Cerrado' : 'Activo'} &middot; los datos económicos no bloquean el cierre operativo.</div>
      <EditorDatosFacturacion ficha={vista} valor={valor} onCambio={setValor} soloLectura={bloqueado} fechaInicio={ficha.fechaInicio} fechaFin={ficha.fechaFin} />
      <VistaPreviaFacturacion ficha={vista} />
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
        <button className="btn ghost" onClick={onCerrar}>Cerrar</button>
        {!bloqueado && <button className="btn ed" id="bd-ok" disabled={enviando || vista.errores.length > 0} onClick={() => void guardar()}>Guardar datos de facturación</button>}
      </div>
    </Dialogo>
  );
}

/**
 * Sección plegable de la ventana de cierre. Solo pide datos al backend cuando
 * se despliega (así no retrasa la validación del cierre). Informa al padre de
 * los datos y de si se pueden guardar; el cierre operativo se guarda igualmente.
 */
export function SeccionFacturacionCierre({ banco, id, fechaInicio, fechaFin, onCambio }: {
  banco: string;
  id: string;
  fechaInicio: string;
  fechaFin: string;
  onCambio: (estado: { valor: DatosFacturacionEnsayo; errores: string[]; tocado: boolean } | null) => void;
}) {
  const [abierta, setAbierta] = useState(false);
  return (
    <details className="fac-plegable" id="cl-billing" onToggle={(e) => { if ((e.target as HTMLDetailsElement).open) setAbierta(true); }}>
      <summary>&#128182; Billing Data (opcional)</summary>
      {abierta && <ContenidoFacturacionCierre banco={banco} id={id} fechaInicio={fechaInicio} fechaFin={fechaFin} onCambio={onCambio} />}
    </details>
  );
}

function ContenidoFacturacionCierre({ banco, id, fechaInicio, fechaFin, onCambio }: {
  banco: string;
  id: string;
  fechaInicio: string;
  fechaFin: string;
  onCambio: (estado: { valor: DatosFacturacionEnsayo; errores: string[]; tocado: boolean } | null) => void;
}) {
  const { soloLectura } = usePlanificador();
  const [ficha, setFicha] = useState<FichaFacturacion | null>(null);
  const [valor, setValor] = useState<DatosFacturacionEnsayo | null>(null);
  const tocado = useRef(false);
  const previa = useVistaPrevia(banco, id, valor, { fechaInicio, fechaFin });

  useEffect(() => {
    apiFacturacion.ficha(banco, id).then((f) => { setFicha(f); setValor(normalizar(f.billing)); }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [banco, id]);
  useEffect(() => {
    if (valor && tocado.current) onCambio({ valor, errores: (previa ?? ficha)?.errores ?? [], tocado: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valor, previa]);

  if (!ficha || !valor) return <div className="fac-nota">Cargando&hellip;</div>;
  const vista = previa ?? ficha;
  return (
    <div>
      <div className="fac-nota">Se puede cerrar sin completar estos datos: el ensayo quedará pendiente para facturación (BILLING_DATA_INCOMPLETE).</div>
      <EditorDatosFacturacion ficha={vista} valor={valor} onCambio={(v) => { tocado.current = true; setValor(v); }}
        soloLectura={soloLectura || !ficha.puedeEditar} fechaInicio={fechaInicio} fechaFin={fechaFin} />
      <VistaPreviaFacturacion ficha={vista} />
    </div>
  );
}
