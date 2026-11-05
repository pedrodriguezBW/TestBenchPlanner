import { useEffect, useRef, useState, type MouseEvent as EventoRaton } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';
import { usePreferencia } from '../../utilidades/preferencias';
import { BarraSeleccion } from './BarraSeleccion';
import { BuscadorAprobaciones } from './BuscadorAprobaciones';
import { Gantt } from './Gantt';
import { ListaCerrados } from './ListaCerrados';
import { TablaEnsayos, type AccionesFila } from './TablaEnsayos';
import { VentanaCierre, type DatosCierre } from './VentanaCierre';

/*
 * Pantalla de un banco (renderBench): cabecera con Completed / Confirm all /
 * Add test, tabla de prioridades arriba y Gantt diario abajo, con un divisor
 * que reparte el espacio (se recuerda en este navegador).
 */
export function VistaBanco({ banco, onIrABanco }: { banco: string; onIrABanco: (banco: string) => void }) {
  const { vista, operar, editarCampo, estadosAprobacion, proyectos, mostrarMensaje } = usePlanificador();
  const [seleccion, setSeleccion] = useState<Set<string>>(new Set());
  const [cierre, setCierre] = useState<DatosCierre | null>(null);
  const [verCerrados, setVerCerrados] = useState(false);
  const [buscadorPara, setBuscadorPara] = useState<FilaEnsayo | null>(null);
  const [reparto, setReparto] = usePreferencia<number>('tbp_reparto_banco', 1);
  const contenedor = useRef<HTMLDivElement>(null);

  // Cambiar de banco limpia la selección (como en la 4.9.4).
  useEffect(() => { setSeleccion(new Set()); }, [banco]);

  if (!vista) return null;
  const grupo = vista.grupos.find((g) => g.bancos.some((b) => b.nombre === banco));
  const filas = vista.bancos[banco] ?? [];
  const activas = filas.filter((f) => !f.cerrado);
  const cerradas = filas.length - activas.length;
  const seleccionActiva = activas.filter((f) => seleccion.has(f.id)).map((f) => f.id);
  const proyectosBanco = proyectos.length ? proyectos : [...new Set(activas.map((f) => String(f.datos.project ?? '')).filter(Boolean))];

  const tituloEnsayo = (f: FilaEnsayo) => `${f.datos.project || f.datos.type || ''}${f.datos.type && f.datos.project ? ' \u00b7 ' + f.datos.type : ''}`;

  const acciones: AccionesFila = {
    editar: (f, campo, valor) => editarCampo(banco, f.id, campo, valor),
    comprometer: (f) => void operar(banco, 'comprometer', { id: f.id }, [f.id]),
    deshacerCompromiso: (f) => {
      if (!f.datos.confirmedStart && !f.datos.confirmedEnd) return;
      if (window.confirm('Undo date confirmation for this test? It will go back to Queued and the Gantt circles will be removed.')) {
        void operar(banco, 'deshacerCompromiso', { id: f.id }, [f.id]);
      }
    },
    cerrar: (f) => setCierre({ banco, id: f.id, correccion: false, titulo: tituloEnsayo(f) }),
    borrar: (f) => {
      if (window.confirm('Delete this test permanently?')) {
        void operar(banco, 'borrar', { id: f.id }, [f.id]).then(() => setSeleccion((s) => { const n = new Set(s); n.delete(f.id); return n; }));
      }
    },
    declararEstado: (f, estado) => void operar(banco, 'estado', { id: f.id, estado }, [f.id]),
    aplicarSugerencia: (f) => void operar(banco, 'aplicarSugerencia', { id: f.id }, [f.id]),
    abrirBuscadorAprobaciones: (f) => setBuscadorPara(f),
    soltar: (origen, destino) => {
      const mueve = seleccion.size && seleccion.has(origen) ? seleccionActiva : [origen];
      if (mueve.length === 1 && mueve[0] === destino) return;
      void operar(banco, 'reordenar', { id: origen, destino, ids: seleccionActiva }, mueve);
    },
  };

  const comprometerTodos = () => {
    if (window.confirm(`Confirm dates for ALL active tests in ${banco}? This copies current estimated/forced dates into Confirmed start/end.`)) {
      void operar(banco, 'comprometerTodos', {}, activas.map((f) => f.id));
    }
  };

  const duplicar = async () => {
    if (!seleccionActiva.length) { mostrarMensaje('Select at least one test to duplicate.'); return; }
    const r = await operar(banco, 'duplicar', { ids: seleccionActiva }, seleccionActiva);
    if (r?.resultado) setSeleccion(new Set(r.resultado.split(',')));   // la selección pasa a las copias
  };

  const mover = async (destino: string, posicion: number) => {
    if (!destino) { mostrarMensaje('Choose a destination bench.'); return; }
    if (!seleccionActiva.length) return;
    const r = await operar(banco, 'mover', { ids: seleccionActiva, bancoDestino: destino, posicion: Number.isNaN(posicion) ? null : posicion }, seleccionActiva);
    if (r) {
      setSeleccion(new Set());
      if (r.resultado) mostrarMensaje(r.resultado);
      onIrABanco(destino);
    }
  };

  const borrarSeleccion = async () => {
    if (!seleccionActiva.length) { mostrarMensaje('Select at least one test to delete.'); return; }
    if (!window.confirm(`Delete ${seleccionActiva.length} selected test(s) permanently?`)) return;
    if (await operar(banco, 'borrarSeleccion', { ids: seleccionActiva }, seleccionActiva)) setSeleccion(new Set());
  };

  const empezarReparto = (e: EventoRaton) => {
    e.preventDefault();
    const y0 = e.clientY, r0 = reparto;
    const alto = contenedor.current?.getBoundingClientRect().height || 1;
    let actual = r0;
    const mover = (ev: MouseEvent) => {
      actual = Math.max(0.3, Math.min(1.7, r0 + ((ev.clientY - y0) / alto) * 2));
      contenedor.current?.style.setProperty('--split', String(actual));
    };
    const soltar = () => {
      document.removeEventListener('mousemove', mover);
      document.removeEventListener('mouseup', soltar);
      document.body.classList.remove('bs-resizing');
      setReparto(actual);
    };
    document.body.classList.add('bs-resizing');
    document.addEventListener('mousemove', mover);
    document.addEventListener('mouseup', soltar);
  };

  return (
    <>
      <div className="view-head">
        <h2>{banco}</h2>
        {grupo && <span className="badge" style={{ background: grupo.color }}>{grupo.nombre}</span>}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <button className="btn ghost no-print" onClick={() => setVerCerrados(true)}>&#9989; Completed ({cerradas})</button>
          <button className="btn ghost no-print ed" onClick={comprometerTodos} title="Confirm dates for all active tests">&#128204; Confirm all</button>
          <button className="btn no-print ed" onClick={() => void operar(banco, 'anadir')}>&#10133; Add test</button>
        </div>
      </div>
      <div className="hint">
        Drag priority to reorder; tick to multi-select. Tick the Test start / Test end checkboxes to force exact dates. Write the
        <b> Arranque real</b> when the test actually starts. Press <b>&#128204;</b> (per row) or <b>Confirm all</b> to freeze the agreed dates
        (undo with &#8617;). Special types (Calibration/Maintenance/Breakdown/Not available) need no ID and show as a striped block.
        New tests are added as the last priority. Use &#128269; on Approval ID to search the live IDs table.
      </div>
      <BarraSeleccion banco={banco} vista={vista} cantidad={seleccionActiva.length} onDuplicar={() => void duplicar()}
        onMover={(d, p) => void mover(d, p)} onBorrar={() => void borrarSeleccion()} onLimpiar={() => setSeleccion(new Set())} />
      <div className="bench-split" id="benchsplit" ref={contenedor} style={{ ['--split' as string]: reparto }}>
        <div className="bs-pane top">
          <div className="card">
            <div className="tbl-wrap">
              <TablaEnsayos banco={banco} filas={activas} hoy={vista.hoy} estadosAprobacion={estadosAprobacion}
                seleccion={seleccion} onSeleccion={setSeleccion} acciones={acciones} />
            </div>
          </div>
        </div>
        <div className="bs-divider no-print" id="bsdiv" title="Drag to resize table / Gantt (double-click to reset)"
          onMouseDown={empezarReparto} onDoubleClick={() => setReparto(1)}>
          <div className="grip" />
        </div>
        <div className="bs-pane bot">
          <div className="card">
            <h3 style={{ margin: '0 0 10px' }}>Gantt &middot; daily resolution</h3>
            <Gantt filas={activas} vista={vista} proyectos={proyectosBanco} />
          </div>
        </div>
      </div>
      <ListaCerrados banco={banco} vista={vista} abierto={verCerrados} onCerrar={() => setVerCerrados(false)}
        onCorregir={(b, f) => setCierre({ banco: b, id: f.id, correccion: true, titulo: tituloEnsayo(f) })}
        onReutilizado={(b) => onIrABanco(b)} />
      {/* Va después de la lista de cerrados: «✎ Corregir» se abre encima de ella, como en la 4.9.4. */}
      <VentanaCierre datos={cierre} onCerrar={() => setCierre(null)} />
      <BuscadorAprobaciones abierto={!!buscadorPara} onCerrar={() => setBuscadorPara(null)}
        onElegir={(id) => { if (buscadorPara) editarCampo(banco, buscadorPara.id, 'approvalId', id); setBuscadorPara(null); }} />
    </>
  );
}
