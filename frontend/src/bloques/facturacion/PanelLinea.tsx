import { useState } from 'react';
import { apiFacturacion, type LineaFacturacion, type MaestrosFacturacion, type VistaPeriodo } from '../../api/facturacion';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { VentanaFacturacionEnsayo } from './FichaEnsayo';
import { ESTADO_LINEA, euros, fechaHora, horas, hvTexto, NOMBRE_CAMPO, nombrePeriodo, tarifaTexto, valorTexto } from './textos';

/*
 * Panel de detalle de una línea: origen, desglose temporal, datos económicos y
 * ajustes con historial; acciones Editar, Excluir, Aplazar, Restaurar,
 * Proponer tarifa y, para Admin, validar ajustes y resolver tarifas.
 */

export function ChipLinea({ estado }: { estado: LineaFacturacion['estado'] }) {
  const e = ESTADO_LINEA[estado];
  return <span className={`fac-chip ${e.clase}`} title={e.ayuda}>{e.icono} {e.texto}</span>;
}

const NIVEL_TARIFA: Record<number, string> = { 1: 'específica del proyecto', 2: 'específica de planta / línea', 3: 'general de la categoría' };

type Ejecutar = (accion: (version: string) => Promise<VistaPeriodo>, textoOk?: string) => Promise<boolean>;

export function PanelLinea({ linea, vista, maestros, ejecutar, ocupado, onCerrar }: {
  linea: LineaFacturacion;
  vista: VistaPeriodo;
  maestros: MaestrosFacturacion | null;
  ejecutar: Ejecutar;
  ocupado: boolean;
  onCerrar: () => void;
}) {
  const { sesion, soloLectura } = usePlanificador();
  const [dialogo, setDialogo] = useState<'' | 'editar' | 'proponer' | 'resolver' | 'ficha'>('');
  const [campos, setCampos] = useState<Record<string, string>>({});
  const [justificacion, setJustificacion] = useState('');
  const [tarifa, setTarifa] = useState('');
  const [alcance, setAlcance] = useState<'LINEA' | 'MAESTRA'>('LINEA');
  const p = vista.periodo;
  const editable = !soloLectura && (p.estado === 'BORRADOR' || p.estado === 'EN_REVISION');
  const esAdmin = !!sesion?.esAdmin;
  const c = linea.calculado;
  const a = linea.aplicado;
  const accion = (nombre: string, datos: Record<string, unknown> = {}, texto?: string) =>
    ejecutar((v) => apiFacturacion.accionLinea(p.id, linea.id, nombre, datos, v), texto);
  const cerrarDialogo = () => { setDialogo(''); setCampos({}); setJustificacion(''); setTarifa(''); setAlcance('LINEA'); };
  const sinValidar = Object.values(linea.ajustes || {}).some((x) => x.economico && !x.validado);
  const prop = linea.propuestaTarifa;
  const proyectoMaestro = maestros?.proyectos.find((x) => x.numero === a?.proyecto);

  const abrirEditar = () => {
    if (!a) return;
    setCampos({
      horasNetas: String(a.horasNetas), horasSetup: String(a.horasSetup), piezas: a.piezas === null ? '' : String(a.piezas), proyecto: a.proyecto,
      orden: a.orden, hv: a.hv, categoria: a.categoria, coste: a.coste === null ? '' : String(a.coste), textoControlling: a.textoControlling,
    });
    setDialogo('editar');
  };
  const guardarEdicion = async () => {
    if (!a) return;
    const cambios: Record<string, unknown> = {};
    const actual: Record<string, unknown> = { ...a, piezas: a.piezas ?? '' };
    Object.entries(campos).forEach(([k, v]) => {
      const antes = actual[k] === null || actual[k] === undefined ? '' : String(actual[k]);
      if (v !== antes) cambios[k] = ['horasNetas', 'horasSetup', 'piezas', 'coste'].includes(k) ? Number(v) : v;
    });
    if (!Object.keys(cambios).length) { cerrarDialogo(); return; }
    if (await accion('ajustar', { campos: cambios, justificacion, excepcional: esAdmin && 'orden' in cambios }, 'Línea ajustada')) cerrarDialogo();
  };

  return (
    <div className="fac-panel" id="fac-panel-linea">
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <h3 style={{ flex: 1 }}>{linea.ensayoId} &middot; {linea.banco}</h3>
        <ChipLinea estado={linea.estado} />
        <button className="btn ghost sm" onClick={onCerrar}>&#10005;</button>
      </div>
      {linea.incidencias.map((i, k) => <div key={k} className={`fac-inc ${i.nivel}`}>{i.nivel === 'BLOQUEANTE' ? '\u26D4' : '\u26A0'} {i.mensaje}</div>)}
      {vista.alertas.filter((x) => x.lineaId === linea.id).map((x) => <div key={x.lineaId} className="fac-aviso">&#9888; {x.mensaje}
        {' '}(aprobado {euros(x.costeAprobado ?? null)}, ahora {euros(x.costeActual ?? null)}). Un Admin decide si reabre el periodo.</div>)}

      <div className="fac-bloque">
        <h4>Origen</h4>
        <div className="fac-kv">
          <span>Test</span><span>{linea.ensayoId}{linea.approvalId ? ` · ID ${linea.approvalId}` : ''}</span>
          <span>Banco</span><span>{linea.banco} ({linea.organizacion || '\u2014'})</span>
          <span>Tipo de ensayo</span><span>{linea.tipoEnsayo || '\u2014'}</span>
          <span>Proyecto (ensayo)</span><span>{linea.proyectoTexto || '\u2014'}</span>
          <span>Requester</span><span>{a?.requester || '\u2014'}</span>
          <span>Responsable</span><span>{linea.responsable || '\u2014'}</span>
          <span>Inicio y fin real</span><span>{fechaHora(linea.inicioReal)} &rarr; {linea.finReal ? fechaHora(linea.finReal) : 'abierto'}</span>
          <span>Tramo incluido</span><span>{fechaHora(linea.tramoInicio)} &rarr; {fechaHora(linea.tramoFin)}</span>
          <span>Periodo de ejecución</span><span>{nombrePeriodo(linea.periodoEjecucion)}</span>
          <span>Periodo de facturación</span><span>{nombrePeriodo(linea.aplazamiento ? linea.aplazamiento.a : linea.periodoFacturacion)}
            {linea.origen === 'APLAZADA' && <b> &middot; pendiente del periodo anterior</b>}</span>
        </div>
        <div style={{ marginTop: 8 }}>
          <a className="btn ghost sm" href={`#/${encodeURIComponent(linea.banco)}`}>Ir al banco</a>{' '}
          <button className="btn ghost sm" onClick={() => setDialogo('ficha')}>&#128182; Billing Data del ensayo</button>
        </div>
      </div>

      {c && a && (
        <div className="fac-bloque">
          <h4>Desglose temporal</h4>
          <div className="fac-desglose">
            <span>Tiempo dentro del periodo</span><span>{horas((c.minutosBrutos ?? 0) / 60)}</span>
            <span>Fines de semana no trabajados</span><span>{c.minutosFinDeSemana ? `\u2212${horas(c.minutosFinDeSemana / 60)}` : '\u2014'}</span>
            <span>Paradas no facturables</span><span>{c.minutosParadas ? `\u2212${horas(c.minutosParadas / 60)}` : '\u2014'}</span>
            <span>Horas netas calculadas (15 min)</span><span>{horas(c.horasNetas)}</span>
            {linea.ajustes.horasNetas && <><span>Horas netas aplicadas</span><span><b>{horas(a.horasNetas)}</b></span></>}
            <span>Setup</span><span>{horas(a.horasSetup)}</span>
            {a.unidad === 'PIEZAS' && <><span>Piezas</span><span>{a.piezas ?? '\u2014'}</span></>}
            <span className="total">Horas totales</span><span className="total">{horas(a.horasTotales)}</span>
          </div>
        </div>
      )}

      {a && (
        <div className="fac-bloque">
          <h4>Datos económicos</h4>
          <div className="fac-kv">
            <span>Proyecto</span><span>{a.proyecto || '\u2014'}{a.proyectoNombre ? ` · ${a.proyectoNombre}` : ''}</span>
            <span>Orden</span><span>{a.orden || '\u2014'}{a.ordenExcepcional ? ' (excepcional)' : ''}</span>
            <span>Categoría</span><span>{a.categoria || '\u2014'}{a.grupo ? ` · ${a.grupo}` : ''}</span>
            <span>Alta tensión</span><span>{hvTexto(a.hv)}</span>
            <span>Línea de producto</span><span>{a.lineaProducto || '\u2014'}</span>
            <span>Tarifa</span><span>{tarifaTexto(a.tarifa, a.unidad)}{a.tarifaId === 'EXCEPCIONAL' ? ' · excepcional aprobada' : a.nivelTarifa ? ` · ${NIVEL_TARIFA[a.nivelTarifa] ?? ''}` : ''}</span>
            <span>Regla</span><span>{a.regla || '\u2014'}{a.regla && a.regla !== 'LINEAR' && a.regla !== 'PER_PIECE' ? ' (reductora: coste no lineal)' : ''}</span>
            <span>Coste calculado</span><span>{euros(a.costeCalculado ?? null)}</span>
            <span>Coste aplicado</span><span><b>{euros(a.coste)}</b></span>
          </div>
        </div>
      )}

      <div className="fac-bloque">
        <h4>Ajustes e historial</h4>
        {!Object.keys(linea.ajustes || {}).length && !prop && <div className="fac-nota">Sin ajustes manuales.</div>}
        {Object.entries(linea.ajustes || {}).map(([campo, aj]) => (
          <div key={campo} className="fac-ajuste">
            <b>{NOMBRE_CAMPO[campo] ?? campo}</b>: valor original {valorTexto(campo, aj.original)} &rarr; aplicado <b>{valorTexto(campo, aj.valor)}</b>
            <div className="fac-nota">{aj.por} ({aj.rol}) &middot; {fechaHora(aj.en)}{aj.justificacion ? ` · «${aj.justificacion}»` : ''}
              {aj.economico ? (aj.validado ? ' · validado' : ' · pendiente de validar por un Admin') : ''}</div>
            {aj.conflicto && (
              <div className="fac-aviso">El ensayo ha cambiado desde el último cálculo. Antes: {valorTexto(campo, aj.conflicto.anterior)} &middot; ahora: {valorTexto(campo, aj.conflicto.nuevo)}.{' '}
                {editable && <>
                  <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('resolverCambio', { campo, opcion: 'conservar', justificacion: 'Se conserva el ajuste' })}>
                    Conservar {valorTexto(campo, aj.valor)}</button>{' '}
                  <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('resolverCambio', { campo, opcion: 'recalculado', justificacion: 'Se usa el valor recalculado' })}>
                    Usar {valorTexto(campo, aj.conflicto.nuevo)}</button>
                </>}
              </div>
            )}
            {editable && <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('restaurar', { campo, justificacion: 'Restaurar valor calculado' })}>
              Restaurar valor calculado</button>}
          </div>
        ))}
        {prop && (
          <div style={{ padding: '6px 0' }}>
            <b>Tarifa excepcional</b> ({prop.estado}{prop.alcance ? ` · ${prop.alcance === 'MAESTRA' ? 'nueva tarifa maestra' : 'solo esta línea'}` : ''}):
            calculada {tarifaTexto(prop.calculada)} &middot; propuesta {tarifaTexto(prop.propuesta)} &middot; diferencia {euros(prop.diferencia)}
            <div className="fac-nota">Propuesta por {prop.por} ({fechaHora(prop.en)}) &middot; «{prop.justificacion}»
              {prop.resueltaPor ? ` · resuelta por ${prop.resueltaPor} (${fechaHora(prop.resueltaEn)})` : ''}{prop.tarifaFinal !== null ? ` · final ${tarifaTexto(prop.tarifaFinal)}` : ''}</div>
          </div>
        )}
        {linea.exclusion && <div className="fac-nota">Excluida por {linea.exclusion.por || (linea.exclusion.origen === 'REGLA' ? 'la regla NOT_BILLABLE' : '\u2014')}
          {linea.exclusion.en ? ` el ${fechaHora(linea.exclusion.en)}` : ''}{linea.exclusion.origen === 'ENSAYO' ? ' (ficha del ensayo: no facturable)' : ''}</div>}
        {linea.aplazamiento && <div className="fac-nota">Aplazada a {nombrePeriodo(linea.aplazamiento.a)} por {linea.aplazamiento.por} ({fechaHora(linea.aplazamiento.en)})</div>}
        {linea.historial.length > 0 && (
          <details style={{ marginTop: 6 }}>
            <summary>Ver historial ({linea.historial.length})</summary>
            <table className="fac" style={{ marginTop: 6 }}>
              <thead><tr><th>Fecha</th><th>Usuario</th><th>Acción</th><th>Campo</th><th>De</th><th>A</th><th>Justificación</th></tr></thead>
              <tbody>{linea.historial.slice().reverse().map((h, k) => (
                <tr key={k}><td>{fechaHora(h.en)}</td><td>{h.por || '\u2014'}</td><td>{h.accion}</td><td>{NOMBRE_CAMPO[h.campo] ?? h.campo}</td>
                  <td>{valorTexto(h.campo, h.de)}</td><td>{valorTexto(h.campo, h.a)}</td><td>{h.justificacion}</td></tr>
              ))}</tbody>
            </table>
          </details>
        )}
      </div>

      {editable && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
          {a && <button className="btn sm ed" id="fac-editar" disabled={ocupado} onClick={abrirEditar}>&#9998; Editar</button>}
          {!linea.exclusion && <button className="btn ghost sm ed" id="fac-excluir" disabled={ocupado} onClick={() => void accion('excluir', {}, 'Línea excluida')}>&#8856; Excluir</button>}
          {linea.exclusion?.origen === 'LINEA' && <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('incluir', {}, 'Línea incluida')}>Volver a incluir</button>}
          {!linea.aplazamiento && !linea.pendiente && <button className="btn ghost sm ed" id="fac-aplazar" disabled={ocupado}
            onClick={() => void accion('aplazar', {}, `Línea aplazada a ${nombrePeriodo(vista.siguiente)}`)}>&#9197; Aplazar a {nombrePeriodo(vista.siguiente)}</button>}
          {linea.aplazamiento && <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('deshacerAplazamiento', {}, 'Aplazamiento deshecho')}>Deshacer aplazamiento</button>}
          {Object.keys(linea.ajustes || {}).length > 0 && <button className="btn ghost sm ed" disabled={ocupado}
            onClick={() => void accion('restaurar', { justificacion: 'Restaurar valores calculados' }, 'Valores calculados restaurados')}>&#8634; Restaurar valores calculados</button>}
          {a && a.tarifa !== null && prop?.estado !== 'PENDIENTE' && <button className="btn ghost sm ed" id="fac-proponer" disabled={ocupado}
            onClick={() => setDialogo('proponer')}>Proponer tarifa excepcional</button>}
          {esAdmin && sinValidar && <>
            <button className="btn sm ed" id="fac-validar" disabled={ocupado} onClick={() => void accion('validarAjuste', {}, 'Ajustes validados')}>&#10004; Validar ajustes</button>
            <button className="btn ghost sm ed" disabled={ocupado} onClick={() => void accion('rechazarAjuste', { justificacion: 'Ajuste rechazado por el Admin' }, 'Ajustes rechazados')}>Rechazar ajustes</button>
          </>}
          {esAdmin && prop?.estado === 'PENDIENTE' && <button className="btn sm ed" id="fac-resolver" disabled={ocupado} onClick={() => setDialogo('resolver')}>Resolver tarifa excepcional</button>}
        </div>
      )}

      <Dialogo abierto={dialogo === 'editar'} onCerrar={cerrarDialogo}>
        <h3>Editar línea &middot; {linea.ensayoId}</h3>
        <div className="fac-form">
          {([['horasNetas', 'Horas netas', 'number'], ['horasSetup', 'Setup (h)', 'number'], ...(a?.unidad === 'PIEZAS' ? [['piezas', 'Piezas', 'number']] : []),
            ['proyecto', 'Proyecto (número)', 'text'], ['orden', 'Orden', 'orden'], ['hv', 'Alta tensión', 'hv'], ['categoria', 'Categoría', 'categoria'],
            ['coste', 'Coste final (€)', 'number'], ['textoControlling', 'Texto de Controlling', 'text']] as [string, string, string][]).map(([k, t, tipo]) => (
            <label key={k} style={{ display: 'contents' }}>
              <span style={{ color: 'var(--muted)', fontSize: 13 }}>{t}</span>
              {tipo === 'hv' ? (
                <select data-campo={k} value={campos[k] ?? ''} onChange={(e) => setCampos({ ...campos, [k]: e.target.value })}>
                  <option value="no">No</option><option value="yes">Sí</option>
                </select>
              ) : tipo === 'categoria' ? (
                <select data-campo={k} value={campos[k] ?? ''} onChange={(e) => setCampos({ ...campos, [k]: e.target.value })}>
                  {(maestros?.categorias ?? []).filter((x) => x.activa !== false).map((x) => <option key={x.codigo} value={x.codigo}>{x.codigo}</option>)}
                </select>
              ) : tipo === 'orden' ? (
                <span>
                  <input data-campo={k} list="fac-ordenes" value={campos[k] ?? ''} onChange={(e) => setCampos({ ...campos, [k]: e.target.value })} />
                  <datalist id="fac-ordenes">{(proyectoMaestro?.ordenes ?? []).map((o) => <option key={o.codigo} value={o.codigo} />)}</datalist>
                </span>
              ) : (
                <input data-campo={k} type={tipo} step={k === 'horasNetas' || k === 'horasSetup' ? 0.25 : 'any'} value={campos[k] ?? ''}
                  onChange={(e) => setCampos({ ...campos, [k]: e.target.value })} />
              )}
            </label>
          ))}
          <span style={{ color: 'var(--muted)', fontSize: 13 }}>Justificación</span>
          <textarea id="fac-just" rows={2} value={justificacion} onChange={(e) => setJustificacion(e.target.value)} placeholder="Obligatoria si cambia el importe" />
        </div>
        <div className="fac-nota" style={{ marginTop: 6 }}>Se guardan el valor calculado y el aplicado, con usuario, fecha y justificación. Un cambio económico de un Usuario o
          Editor queda pendiente de validar por un Admin. Una orden fuera del proyecto solo la puede usar un Admin.</div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrarDialogo}>Cancelar</button>
          <button className="btn" id="fac-guardar-ajuste" disabled={ocupado} onClick={() => void guardarEdicion()}>Guardar</button>
        </div>
      </Dialogo>

      <Dialogo abierto={dialogo === 'proponer'} onCerrar={cerrarDialogo}>
        <h3>Proponer tarifa excepcional</h3>
        <p>Tarifa calculada: <b>{tarifaTexto(c?.tarifa ?? null, a?.unidad)}</b>. La línea quedará <b>PENDING_RATE_APPROVAL</b> hasta que un Admin la resuelva;
          el periodo no se puede aprobar mientras tanto.</p>
        <div className="fac-form">
          <label htmlFor="fac-tarifa">Tarifa propuesta (€)</label>
          <input id="fac-tarifa" type="number" min="0" step="0.01" value={tarifa} onChange={(e) => setTarifa(e.target.value)} />
          <label htmlFor="fac-tarifa-just">Justificación</label>
          <textarea id="fac-tarifa-just" rows={2} value={justificacion} onChange={(e) => setJustificacion(e.target.value)} placeholder="Obligatoria" />
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrarDialogo}>Cancelar</button>
          <button className="btn" id="fac-proponer-ok" disabled={ocupado || tarifa === '' || !justificacion.trim()}
            onClick={() => void accion('proponerTarifa', { tarifa: Number(tarifa), justificacion }, 'Propuesta registrada').then((ok) => ok && cerrarDialogo())}>Proponer</button>
        </div>
      </Dialogo>

      <Dialogo abierto={dialogo === 'resolver'} onCerrar={cerrarDialogo}>
        <h3>Resolver tarifa excepcional</h3>
        {prop && <div className="fac-kv">
          <span>Tarifa calculada</span><span>{tarifaTexto(prop.calculada)}</span>
          <span>Tarifa propuesta</span><span>{tarifaTexto(prop.propuesta)}</span>
          <span>Diferencia económica</span><span>{euros(prop.diferencia)}</span>
          <span>Propuesta por</span><span>{prop.por}</span>
          <span>Justificación</span><span>{prop.justificacion}</span>
        </div>}
        <div className="fac-form" style={{ marginTop: 10 }}>
          <label htmlFor="fac-alcance">Afecta a</label>
          <select id="fac-alcance" value={alcance} onChange={(e) => setAlcance(e.target.value as 'LINEA' | 'MAESTRA')}>
            <option value="LINEA">Solo a esta línea</option>
            <option value="MAESTRA">Nueva tarifa maestra (desde este periodo)</option>
          </select>
          <label htmlFor="fac-sustituta">Sustituir por (€)</label>
          <input id="fac-sustituta" type="number" min="0" step="0.01" value={tarifa} placeholder="Opcional" onChange={(e) => setTarifa(e.target.value)} />
          <label htmlFor="fac-res-just">Justificación</label>
          <textarea id="fac-res-just" rows={2} value={justificacion} onChange={(e) => setJustificacion(e.target.value)} />
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrarDialogo}>Cancelar</button>
          <button className="btn ghost" disabled={ocupado} onClick={() => void accion('resolverTarifa', { decision: 'rechazar', justificacion }, 'Propuesta rechazada').then((ok) => ok && cerrarDialogo())}>Rechazar</button>
          {tarifa !== '' ? (
            <button className="btn" disabled={ocupado} onClick={() => void accion('resolverTarifa', { decision: 'sustituir', tarifa: Number(tarifa), alcance, justificacion }, 'Tarifa sustituida').then((ok) => ok && cerrarDialogo())}>Sustituir</button>
          ) : (
            <button className="btn" id="fac-aprobar-tarifa" disabled={ocupado} onClick={() => void accion('resolverTarifa', { decision: 'aprobar', alcance, justificacion }, 'Tarifa aprobada').then((ok) => ok && cerrarDialogo())}>Aprobar</button>
          )}
        </div>
      </Dialogo>

      {dialogo === 'ficha' && <VentanaFacturacionEnsayo banco={linea.banco} id={linea.ensayoId} titulo={linea.proyectoTexto || linea.tipoEnsayo}
        onCerrar={() => setDialogo('')} />}
    </div>
  );
}
