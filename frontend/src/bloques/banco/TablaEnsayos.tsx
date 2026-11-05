import { useRef, useState, type MouseEvent as EventoRaton } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { usePreferencia } from '../../utilidades/preferencias';
import { COLUMNAS, ordenColumnas, type ContextoCelda } from './columnas';

/*
 * Tabla de prioridades de un banco. El orden de las filas es la prioridad: se
 * cambia arrastrando el asa ⠿ (si la fila arrastrada está seleccionada, se
 * mueven todas las seleccionadas). Las columnas se pueden reordenar y
 * redimensionar; esa preferencia se guarda solo en este navegador.
 */

const VERSION_COLUMNAS = 'v4.9.3c'; // misma versión que COLORDER_VER: si cambia, se restablece el orden

export interface AccionesFila {
  editar: (fila: FilaEnsayo, campo: string, valor: string | boolean | null) => void;
  comprometer: (fila: FilaEnsayo) => void;
  deshacerCompromiso: (fila: FilaEnsayo) => void;
  cerrar: (fila: FilaEnsayo) => void;
  borrar: (fila: FilaEnsayo) => void;
  declararEstado: (fila: FilaEnsayo, estado: string) => void;
  aplicarSugerencia: (fila: FilaEnsayo) => void;
  abrirBuscadorAprobaciones: (fila: FilaEnsayo) => void;
  soltar: (origen: string, destino: string) => void;
}

export function TablaEnsayos({ banco, filas, hoy, estadosAprobacion, seleccion, onSeleccion, acciones }: {
  banco: string;
  filas: FilaEnsayo[];
  hoy: string;
  estadosAprobacion: Record<string, string>;
  seleccion: Set<string>;
  onSeleccion: (s: Set<string>) => void;
  acciones: AccionesFila;
}) {
  const [prefs, setPrefs] = usePreferencia<{ version: string; orden: string[] | null; anchos: Record<string, number> }>(
    'tbp_columnas_v1', { version: VERSION_COLUMNAS, orden: null, anchos: {} });
  const orden = ordenColumnas(prefs.version === VERSION_COLUMNAS ? prefs.orden : null);
  const anchos = prefs.version === VERSION_COLUMNAS ? prefs.anchos : {};
  const [columnaArrastrada, setColumnaArrastrada] = useState<string | null>(null);
  const [columnaDestino, setColumnaDestino] = useState<string | null>(null);
  const [filaDestino, setFilaDestino] = useState<string | null>(null);
  const filaArrastrada = useRef<string | null>(null);

  const guardarPrefs = (cambio: Partial<typeof prefs>) => setPrefs({ version: VERSION_COLUMNAS, orden, anchos, ...cambio });

  const moverColumna = (desde: string, hasta: string) => {
    if (desde === hasta) return;
    const nuevo = orden.filter((k) => k !== desde);
    nuevo.splice(nuevo.indexOf(hasta), 0, desde);
    guardarPrefs({ orden: nuevo });
  };

  const empezarRedimension = (clave: string, e: EventoRaton) => {
    e.preventDefault();
    e.stopPropagation();
    const th = (e.target as HTMLElement).closest('th') as HTMLElement;
    const inicioX = e.clientX, inicioAncho = th.getBoundingClientRect().width;
    let ancho = inicioAncho;
    const mover = (ev: MouseEvent) => {
      ancho = Math.max(40, Math.round(inicioAncho + ev.clientX - inicioX));
      th.style.width = th.style.minWidth = th.style.maxWidth = `${ancho}px`;
    };
    const soltar = () => {
      document.removeEventListener('mousemove', mover);
      document.removeEventListener('mouseup', soltar);
      guardarPrefs({ anchos: { ...anchos, [clave]: ancho } });
    };
    document.addEventListener('mousemove', mover);
    document.addEventListener('mouseup', soltar);
  };

  const quitarAncho = (clave: string) => {
    const nuevos = { ...anchos };
    delete nuevos[clave];
    guardarPrefs({ anchos: nuevos });
  };

  const todasSeleccionadas = filas.length > 0 && filas.every((f) => seleccion.has(f.id));

  return (
    <table className="work">
      <thead>
        <tr>
          <th className="selcol">
            <input type="checkbox" id="selall" title="Select all" checked={todasSeleccionadas}
              onChange={(e) => onSeleccion(e.target.checked ? new Set(filas.map((f) => f.id)) : new Set())} />
          </th>
          {orden.map((clave) => {
            const c = COLUMNAS[clave];
            const ancho = anchos[clave];
            return (
              <th key={clave} data-col={clave}
                className={['colh', c.clase, columnaArrastrada === clave && 'coldrag', columnaDestino === clave && 'colover'].filter(Boolean).join(' ')}
                style={ancho ? { width: ancho, minWidth: ancho, maxWidth: ancho } : undefined}
                onDragOver={(e) => { if (columnaArrastrada) { e.preventDefault(); setColumnaDestino(clave); } }}
                onDragLeave={() => setColumnaDestino(null)}
                onDrop={(e) => { e.preventDefault(); if (columnaArrastrada) moverColumna(columnaArrastrada, clave); setColumnaArrastrada(null); setColumnaDestino(null); }}>
                <span className="colh-lbl" draggable title="Drag to reorder column"
                  onDragStart={(e) => { e.dataTransfer.setData('text/x-columna', clave); setColumnaArrastrada(clave); }}
                  onDragEnd={() => { setColumnaArrastrada(null); setColumnaDestino(null); }}>{c.cabecera}</span>
                <span className="colrz" title="Drag to resize (double-click to reset)"
                  onMouseDown={(e) => empezarRedimension(clave, e)} onDoubleClick={() => quitarAncho(clave)} />
              </th>
            );
          })}
        </tr>
      </thead>
      <tbody id="tb">
        {!filas.length && (
          <tr><td colSpan={orden.length + 1}><div className="empty">No active tests &mdash; click "Add test".</div></td></tr>
        )}
        {filas.map((fila, indice) => {
          const c = fila.calculo;
          const bloqueada = !c.tipoEspecial && (fila.datos.inputs === false || !fila.datos.approvalId);
          const clases = [
            bloqueada && 'blocked',
            (c.estado === 'running' || c.estado === 'confirmed') && 'conf',
            c.estado === 'delayed' && 'delayed',
            seleccion.has(fila.id) && 'sel',
            filaDestino === fila.id && 'dragover',
          ].filter(Boolean).join(' ');
          const contexto: ContextoCelda = {
            banco, fila, indice, hoy, estadosAprobacion,
            editar: (campo, valor) => acciones.editar(fila, campo, valor),
            comprometer: () => acciones.comprometer(fila),
            deshacerCompromiso: () => acciones.deshacerCompromiso(fila),
            cerrar: () => acciones.cerrar(fila),
            borrar: () => acciones.borrar(fila),
            declararEstado: (estado) => acciones.declararEstado(fila, estado),
            aplicarSugerencia: () => acciones.aplicarSugerencia(fila),
            abrirBuscadorAprobaciones: () => acciones.abrirBuscadorAprobaciones(fila),
          };
          return (
            <tr key={fila.id} data-id={fila.id} className={clases}
              onDragOver={(e) => { if (filaArrastrada.current) { e.preventDefault(); setFilaDestino(fila.id); } }}
              onDragLeave={() => setFilaDestino(null)}
              onDrop={(e) => {
                e.preventDefault();
                setFilaDestino(null);
                const origen = filaArrastrada.current;
                filaArrastrada.current = null;
                if (origen) acciones.soltar(origen, fila.id);
              }}>
              <td className="selcol">
                <input type="checkbox" className="rowsel" checked={seleccion.has(fila.id)} onChange={(e) => {
                  const nueva = new Set(seleccion);
                  if (e.target.checked) nueva.add(fila.id); else nueva.delete(fila.id);
                  onSeleccion(nueva);
                }} />
              </td>
              {orden.map((clave) => {
                const col = COLUMNAS[clave];
                const esPrioridad = clave === 'prio';
                return (
                  <td key={clave} className={[esPrioridad ? 'prio' : col.clase, ['start', 'end', 'planned', 'progPct', 'confirmedStart', 'confirmedEnd'].includes(clave) && 'calc',
                    clave === 'reason' && c.estado !== 'delayed' && 'shaded'].filter(Boolean).join(' ')}
                    data-drag={esPrioridad ? '1' : undefined} draggable={esPrioridad || undefined}
                    title={esPrioridad ? 'Drag to reorder row' : undefined}
                    onDragStart={esPrioridad ? (e) => { filaArrastrada.current = fila.id; e.dataTransfer.setData('text/plain', fila.id); } : undefined}
                    onDragEnd={esPrioridad ? () => setFilaDestino(null) : undefined}>
                    {col.celda(contexto)}
                  </td>
                );
              })}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
