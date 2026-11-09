import type { ReactNode } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { SelectorFecha } from '../../componentes/SelectorFecha';
import { fmt } from '../../utilidades/fechas';
import {
  ayudaSugerencias, claseAprobacion, colorEstado, CRITERIO_INICIO, ESTADOS_DECLARABLES, etiquetaEstado, etiquetaMotivo,
  MOTIVOS_RETRASO, objetivoAvance, unidadDuracion,
} from '../../utilidades/textos';
import { Entrada, Selector, texto } from './Celdas';
import { CeldaRecursos } from '../recursos/CeldaRecursos';

/*
 * Columnas de la tabla de un banco: mismas claves, cabeceras, controles y
 * orden por defecto que COLDEFS / DEFAULT_COLS de la 4.9.4. Todo lo calculado
 * (fechas, estado, sugerencias…) viene del backend en fila.calculo.
 */

/** Lo que necesita una celda para pintarse y avisar de cambios. */
export interface ContextoCelda {
  banco: string;
  fila: FilaEnsayo;
  indice: number;
  hoy: string;
  estadosAprobacion: Record<string, string>;
  soloLectura: boolean;
  editar: (campo: string, valor: string | boolean | null) => void;
  comprometer: () => void;
  deshacerCompromiso: () => void;
  cerrar: () => void;
  borrar: () => void;
  declararEstado: (estado: string) => void;
  aplicarSugerencia: () => void;
  abrirBuscadorAprobaciones: () => void;
  abrirRecursos: () => void;
}

export interface Columna {
  cabecera: string;
  clase?: string;
  celda: (c: ContextoCelda) => ReactNode;
}

const sinOpciones = (pares: [string, string][]) => pares;

function insigniaCriterio(criterio?: string) {
  const t = criterio ? CRITERIO_INICIO[criterio] : undefined;
  return t ? <span className="start-why" title={t[1]}>{t[0]}</span> : null;
}

function avisoSolape(fila: FilaEnsayo) {
  const solapes = fila.calculo.solapes ?? [];
  if (!solapes.length) return null;
  const t = solapes.map((o) => `${o.etiqueta} (${fmt(o.inicio)}\u2192${fmt(o.fin)})`).join(' | ');
  return <span className="np-warn" title={`Solape en el mismo banco con: ${t}`}>&#9888;</span>;
}

export const COLUMNAS: Record<string, Columna> = {
  prio: {
    cabecera: 'Prio',
    celda: ({ indice }) => <><span className="h">&#10287;</span>{indice + 1}</>,
  },
  type: {
    cabecera: 'Test type', clase: 'wideid',
    celda: ({ fila, editar }) => <Entrada campo="type" lista="dl-types" valor={fila.datos.type} marcador="type..." onCambio={(v) => editar('type', v)} />,
  },
  responsible: {
    cabecera: 'Responsible',
    celda: ({ fila, editar }) => <Entrada campo="resp" lista="dl-resp" valor={fila.datos.resp} marcador="name..." onCambio={(v) => editar('resp', v)} />,
  },
  project: {
    cabecera: 'Project', clase: 'wideproj',
    celda: ({ fila, editar }) => <Entrada campo="project" lista="dl-proj" valor={fila.datos.project} marcador="project..." onCambio={(v) => editar('project', v)} />,
  },
  comments: {
    cabecera: 'Comments', clase: 'widecomm',
    celda: ({ fila, editar }) => <Entrada campo="comments" valor={fila.datos.comments} marcador="notes..." onCambio={(v) => editar('comments', v)} />,
  },
  approvalId: {
    cabecera: 'Approval ID', clase: 'wideid',
    celda: ({ fila, editar, estadosAprobacion, abrirBuscadorAprobaciones }) => {
      if (fila.calculo.tipoEspecial) {
        return <span style={{ color: 'var(--muted)', fontSize: 12 }} title={`No approval ID needed for ${fila.datos.type}`}>&#8212; n/a &#8212;</span>;
      }
      const id = texto(fila.datos.approvalId);
      const clase = claseAprobacion(id, estadosAprobacion);
      return (
        <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          <Entrada campo="approvalId" lista="dl-ids" clase={clase} valor={id} marcador="ID..."
            titulo={clase === 'idok' ? 'Globally approved' : (id ? 'Not globally approved yet' : '')}
            onCambio={(v) => editar('approvalId', v)} />
          <button className="icbtn" title="Search / pick approval ID" onClick={abrirBuscadorAprobaciones}>&#128269;</button>
        </div>
      );
    },
  },
  weekend: {
    cabecera: 'Weekend', clase: 'numcol',
    celda: ({ fila, editar }) => <Selector campo="weekend" valor={fila.datos.weekend ? 'yes' : 'no'}
      opciones={sinOpciones([['no', 'No'], ['yes', 'Yes']])} onCambio={(v) => editar('weekend', v)} />,
  },
  inputs: {
    cabecera: 'Inputs', clase: 'numcol',
    celda: ({ fila, editar }) => <Selector campo="inputs" valor={fila.datos.inputs !== false ? 'yes' : 'no'}
      opciones={[['yes', 'Yes'], ['no', 'No']]} onCambio={(v) => editar('inputs', v)} />,
  },
  avail: {
    cabecera: 'Samples avail.', clase: 'datecol',
    celda: ({ fila, editar, hoy }) => <SelectorFecha campo="avail" valor={texto(fila.datos.avail)} hoy={hoy} onCambio={(v) => editar('avail', v)} />,
  },
  confirmedStart: {
    cabecera: 'Conf. start', clase: 'datecol',
    celda: ({ fila }) => <span style={{ whiteSpace: 'nowrap' }}>{fila.datos.confirmedStart ? fmt(fila.datos.confirmedStart) : '\u2014'}</span>,
  },
  confirmedEnd: {
    cabecera: 'Conf. end', clase: 'datecol',
    celda: ({ fila }) => <span style={{ whiteSpace: 'nowrap' }}>{fila.datos.confirmedEnd ? fmt(fila.datos.confirmedEnd) : '\u2014'}</span>,
  },
  actualStart: {
    cabecera: 'Arranque real', clase: 'datecol',
    celda: ({ fila, editar, hoy }) => <SelectorFecha campo="actualStart" valor={texto(fila.datos.actualStart)} hoy={hoy}
      titulo="Fecha en que el ensayo arranco de verdad. Vacio = aun no ha empezado." onCambio={(v) => editar('actualStart', v)} />,
  },
  trueEnd: {
    cabecera: 'Fin real', clase: 'datecol',
    celda: ({ fila, editar, hoy }) => <SelectorFecha campo="trueEnd" valor={texto(fila.datos.trueEnd)} hoy={hoy}
      titulo="Fecha en que el ensayo termino de verdad. Ancla el fin de la barra. Se confirma en la ventana de cierre."
      onCambio={(v) => editar('trueEnd', v)} />,
  },
  start: {
    cabecera: 'Test start',
    celda: ({ fila, editar, hoy, soloLectura }) => {
      const bloqueado = !!fila.datos.startLock;
      return (
        <div style={{ display: 'flex', gap: 5, alignItems: 'center', justifyContent: 'center' }}>
          <input type="checkbox" className="startlock" data-f="startLock" checked={bloqueado} disabled={soloLectura} title="Force start on a fixed date"
            onChange={(e) => editar('startLock', e.target.checked)} />
          {bloqueado
            ? <SelectorFecha campo="startOverride" valor={texto(fila.datos.startOverride) || texto(fila.calculo.inicio)} hoy={hoy}
                estilo={{ maxWidth: 120 }} onCambio={(v) => editar('startOverride', v)} />
            : <span data-c="startdisp">{fmt(fila.calculo.inicio)}</span>}
          {insigniaCriterio(fila.calculo.criterioInicio)}
          {avisoSolape(fila)}
        </div>
      );
    },
  },
  end: {
    cabecera: 'Test end',
    celda: ({ fila, editar, hoy, soloLectura }) => {
      const bloqueado = !!fila.datos.endLock;
      return (
        <div style={{ display: 'flex', gap: 5, alignItems: 'center', justifyContent: 'center' }}>
          <input type="checkbox" className="endlock" data-f="endLock" checked={bloqueado} disabled={soloLectura} title="Force end on a fixed date"
            onChange={(e) => editar('endLock', e.target.checked)} />
          {bloqueado
            ? <SelectorFecha campo="endOverride" valor={texto(fila.datos.endOverride) || texto(fila.calculo.fin)} hoy={hoy}
                estilo={{ maxWidth: 120 }} onCambio={(v) => editar('endOverride', v)} />
            : <span data-c="enddisp">{fmt(fila.calculo.fin)}</span>}
        </div>
      );
    },
  },
  planned: {
    cabecera: 'Planned', clase: 'numcol',
    celda: ({ fila }) => <span title="Duracion REALMENTE planificada = nominal x contingencia. Es la que mide la barra del Gantt.">
      {fila.calculo.diasPlanificados ? `${fila.calculo.diasPlanificados} d` : '\u2014'}</span>,
  },
  progressActual: {
    cabecera: 'Progress', clase: 'numcol',
    celda: ({ fila, editar }) => <Entrada campo="progressActual" tipo="number" min="0" marcador="0" valor={fila.datos.progressActual}
      titulo={`of ${objetivoAvance(fila.datos)} ${unidadDuracion(fila.datos)}`} onCambio={(v) => editar('progressActual', v)} />,
  },
  progPct: {
    cabecera: 'Prog %', clase: 'numcol',
    celda: ({ fila, cerrar }) => {
      const c = fila.calculo;
      const dias = c.diasSinAvance ?? 0;
      return (
        <span data-c="prog">
          {Math.round(c.fraccionAvance * 100)}%
          {dias > 0 && <> <span className="np-warn" title={`${c.arranqueAsumido ? `Se da por arrancado el ${fmt(c.arranqueEfectivo)} (fecha comprometida). ` : ''}En marcha desde hace ${dias} dia(s) sin reportar avance. Es solo un recordatorio: el estado no cambia solo.`}>&#9888;{dias}d</span></>}
          {c.fraccionAvance >= 1 && !fila.cerrado && <> <button className="icbtn done ed" title="Progreso al 100 %: cerrar el ensayo y guardar su estado final." onClick={cerrar}>&#10004;</button></>}
        </span>
      );
    },
  },
  status: {
    cabecera: 'Estado', clase: 'reasoncol',
    celda: ({ fila, declararEstado, aplicarSugerencia, soloLectura }) => {
      const c = fila.calculo;
      if (fila.cerrado || c.tipoEspecial || c.estado === 'queued') {
        return <span style={{ color: colorEstado(c.estado), fontWeight: 700 }}>{etiquetaEstado(c.estado)}</span>;
      }
      return (
        <>
          <select data-mstate="1" value={c.estadoDeclarado} disabled={soloLectura} title="Lo decides tu. Compara el fin previsto con el comprometido."
            style={{ width: '100%', borderColor: colorEstado(c.estado) }} onChange={(e) => declararEstado(e.target.value)}>
            {ESTADOS_DECLARABLES.map((e) => <option key={e.valor} value={e.valor}>{e.etiqueta}</option>)}
          </select>
          {c.sugerenciaAbierta && <> <button className="sug-badge ed" disabled={soloLectura} title={ayudaSugerencias(c)} onClick={aplicarSugerencia}>&#9888; En retraso?</button></>}
        </>
      );
    },
  },
  reason: {
    cabecera: 'Delay reason', clase: 'reasoncol',
    celda: ({ fila, editar }) => {
      const activo = fila.calculo.estado === 'delayed';
      return <Selector campo="delayReason" valor={texto(fila.datos.delayReason)} desactivado={!activo}
        titulo={activo ? 'Motivo del retraso' : 'Solo se habilita cuando declaras el ensayo En retraso'}
        opciones={MOTIVOS_RETRASO.map((v) => [v, v ? etiquetaMotivo(v) : '\u2014'])} onCambio={(v) => editar('delayReason', v)} />;
    },
  },
  lastUpdate: {
    cabecera: 'Last upd.',
    celda: ({ fila }) => <span style={{ color: 'var(--muted)', whiteSpace: 'nowrap' }}>{fila.datos.lastUpdate ? fmt(fila.datos.lastUpdate) : '\u2014'}</span>,
  },
  durMode: {
    cabecera: 'Dur. mode', clase: 'modecol',
    celda: ({ fila, editar }) => <Selector campo="durMode" valor={texto(fila.datos.durMode) || 'manual'}
      opciones={[['manual', 'Manual'], ['cycles', 'Cycles']]} onCambio={(v) => editar('durMode', v)} />,
  },
  cycles: {
    cabecera: 'Cycles', clase: 'numcol',
    celda: ({ fila, editar }) => <Entrada campo="cycles" tipo="number" min="0" valor={fila.datos.cycles}
      desactivado={fila.datos.durMode !== 'cycles'} onCambio={(v) => editar('cycles', v)} />,
  },
  cycleVal: {
    cabecera: 'Cycle t.', clase: 'numcol',
    celda: ({ fila, editar }) => <Entrada campo="cycleVal" tipo="number" min="0" paso="any" valor={fila.datos.cycleVal}
      desactivado={fila.datos.durMode !== 'cycles'} onCambio={(v) => editar('cycleVal', v)} />,
  },
  cycleUnit: {
    cabecera: 'Unit', clase: 'unitcol',
    celda: ({ fila, editar }) => <Selector campo="cycleUnit" valor={texto(fila.datos.cycleUnit) || 'min'} desactivado={fila.datos.durMode !== 'cycles'}
      opciones={[['min', 'min'], ['sec', 'sec'], ['hz', 'Hz']]} onCambio={(v) => editar('cycleUnit', v)} />,
  },
  durManual: {
    cabecera: 'Duration', clase: 'numcol',
    celda: ({ fila, editar }) => fila.datos.durMode === 'cycles'
      ? <input type="number" data-f="durManual" value={fila.calculo.diasNominales.toFixed(2)} disabled readOnly />
      : <Entrada campo="durManual" tipo="number" min="0" paso="0.5" valor={fila.datos.durManual} onCambio={(v) => editar('durManual', v)} />,
  },
  manualUnit: {
    cabecera: 'Unit', clase: 'unitcol',
    celda: ({ fila, editar }) => <Selector campo="manualUnit" valor={texto(fila.datos.manualUnit) || 'days'} desactivado={fila.datos.durMode === 'cycles'}
      opciones={[['days', 'days'], ['hours', 'hours']]} onCambio={(v) => editar('manualUnit', v)} />,
  },
  cf: {
    cabecera: 'CF', clase: 'numcol',
    celda: ({ fila, editar }) => <Entrada campo="cf" tipo="number" min="1" paso="0.05"
      valor={fila.datos.cf !== undefined && fila.datos.cf !== null && fila.datos.cf !== '' ? fila.datos.cf : 1.2}
      titulo="Contingency Factor (default 1.2): multiplica la duracion solo para planificar, dejando margen a pequenas paradas."
      onCambio={(v) => editar('cf', v)} />,
  },
  downtime: {
    cabecera: 'Parada (d)', clase: 'numcol',
    celda: ({ fila, editar }) => <Entrada campo="downtimeDays" tipo="number" min="0" paso="0.5" marcador="0" valor={fila.datos.downtimeDays}
      titulo={`Dias de parada del ensayo (averia, espera...). Se restaran en la futura facturacion. No alarga el Gantt ni cambia la ocupacion.${fila.datos.downtimeNote ? ' Nota: ' + fila.datos.downtimeNote : ''}`}
      onCambio={(v) => editar('downtimeDays', v)} />,
  },
  actions: {
    cabecera: 'Actions', clase: 'act',
    celda: ({ fila, comprometer, deshacerCompromiso, cerrar, borrar }) => (
      <span style={{ whiteSpace: 'nowrap' }}>
        <button className="icbtn" style={{ color: 'var(--blue)' }} onClick={comprometer}
          title="Confirm dates (copy estimated/forced to Confirmed and set status Confirmed)">&#128204;</button>
        {fila.datos.confirmedStart && <button className="icbtn" style={{ color: 'var(--warn)' }} onClick={deshacerCompromiso}
          title="Undo date confirmation (back to Queued, remove Gantt circles)">&#8617;</button>}
        <button className="icbtn done" title="Mark complete" onClick={cerrar}>&#10004;</button>
        <button className="icbtn del" title="Delete" onClick={borrar}>&#128465;</button>
      </span>
    ),
  },
  // v4.5 de la 4.9.4: equipos auxiliares asignados, con aviso si alguno está ocupado en esas fechas.
  resources: {
    cabecera: 'Resources', clase: 'wideproj',
    celda: ({ fila, abrirRecursos }) => <CeldaRecursos fila={fila} onAbrir={abrirRecursos} />,
  },
};

/** Orden por defecto de las columnas (DEFAULT_COLS de la 4.9.4). */
export const COLUMNAS_POR_DEFECTO = ['actions', 'prio', 'responsible', 'project', 'comments', 'approvalId', 'type', 'weekend', 'inputs',
  'avail', 'confirmedStart', 'confirmedEnd', 'actualStart', 'trueEnd', 'start', 'end', 'planned', 'progressActual', 'progPct', 'status',
  'reason', 'lastUpdate', 'durMode', 'cycles', 'cycleVal', 'cycleUnit', 'durManual', 'manualUnit', 'cf', 'downtime', 'resources'];

/** Orden guardado, completado con las columnas nuevas y sin las que ya no existen (colOrder). */
export function ordenColumnas(guardado: string[] | null): string[] {
  const orden = (guardado ?? COLUMNAS_POR_DEFECTO).filter((k) => COLUMNAS[k]);
  COLUMNAS_POR_DEFECTO.forEach((k) => { if (!orden.includes(k)) orden.push(k); });
  return orden;
}
