import { useState } from 'react';
import type { FilaEnsayo, Vista } from '../../api/tipos';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { fmt } from '../../utilidades/fechas';
import { etiquetaDuracion } from '../../utilidades/textos';

/*
 * Ensayos cerrados (renderDoneList): búsqueda, ámbito «este banco / todos»,
 * y las acciones Reutilizar, ✎ Corregir (solo Admin), Restaurar y Borrar.
 */
export function ListaCerrados({ banco, vista, abierto, onCerrar, onCorregir, onReutilizado }: {
  banco: string;
  vista: Vista;
  abierto: boolean;
  onCerrar: () => void;
  onCorregir: (banco: string, fila: FilaEnsayo) => void;
  onReutilizado: (banco: string) => void;
}) {
  const { sesion, operar } = usePlanificador();
  const [busqueda, setBusqueda] = useState('');
  const [todos, setTodos] = useState(false);
  if (!abierto) return null;

  let entradas: { banco: string; fila: FilaEnsayo }[] = [];
  const bancos = todos ? vista.grupos.flatMap((g) => g.bancos.map((b) => b.nombre)) : [banco];
  bancos.forEach((b) => (vista.bancos[b] ?? []).filter((f) => f.cerrado).forEach((fila) => entradas.push({ banco: b, fila })));
  const total = entradas.length;
  const q = busqueda.toLowerCase().trim();
  if (q) {
    entradas = entradas.filter(({ banco: b, fila: { datos: d } }) =>
      `${d.type || ''} ${d.resp || ''} ${d.project || ''} ${d.approvalId || ''} ${b}`.toLowerCase().includes(q));
  }

  return (
    <Dialogo abierto ancho="wide" onCerrar={onCerrar}>
      <h3 id="done-title">Completed tests &middot; {todos ? 'All benches' : banco} ({entradas.length}{q ? ` of ${total}` : ''})</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10 }}>
        <input className="done-search" placeholder="Search by type, responsible, project or ID..." style={{ marginBottom: 0 }}
          value={busqueda} onChange={(e) => setBusqueda(e.target.value)} />
        <button className="btn ghost sm" style={{ whiteSpace: 'nowrap' }} onClick={() => setTodos(!todos)}>
          Scope: {todos ? 'All benches' : 'This bench'}
        </button>
      </div>
      <div style={{ maxHeight: '60vh', overflow: 'auto' }}>
        {!entradas.length ? <div className="empty">No matching completed tests.</div> : (
          <table className="done">
            <thead><tr>{todos && <th>Bench</th>}<th>Type</th><th>Responsible</th><th>Project</th><th>Duration</th><th>Ran</th><th>Done</th><th>ID</th><th /></tr></thead>
            <tbody>
              {entradas.map(({ banco: b, fila }) => {
                const d = fila.datos;
                return (
                  <tr key={fila.id} data-id={fila.id}>
                    {todos && <td><b style={{ color: 'var(--turq2)' }}>{b}</b></td>}
                    <td><b>{d.type || '\u2014'}</b></td>
                    <td>{d.resp || '\u2014'}</td>
                    <td>{d.project || '\u2014'}</td>
                    <td>{etiquetaDuracion(d)}</td>
                    <td>{d.histStart ? fmt(d.histStart) : '\u2014'}&rarr;{d.histEnd ? fmt(d.histEnd) : '\u2014'}</td>
                    <td>{d.doneDate ? fmt(d.doneDate) : '\u2014'}</td>
                    <td>{d.approvalId || '\u2014'}</td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button className="ru" title="Reuse: new active test copying type, responsible, project, duration/cycles (no ID, dates or progress)"
                        onClick={async () => { if (await operar(b, 'reutilizar', { id: fila.id }, [fila.id])) { onCerrar(); onReutilizado(b); } }}>
                        &#9851; Reuse</button>{' '}
                      {sesion?.esAdmin && <><button className="btn ghost sm" title="Corregir fechas reales, estado, motivo o parada (queda registrado)"
                        onClick={() => onCorregir(b, fila)}>&#9998; Corregir</button>{' '}</>}
                      <button className="btn ghost sm" onClick={() => void operar(b, 'restaurar', { id: fila.id }, [fila.id])}>&#8617; Restore</button>{' '}
                      <button className="dl" title="Delete permanently"
                        onClick={() => { if (window.confirm('Delete permanently?')) void operar(b, 'borrar', { id: fila.id }, [fila.id]); }}>&#128465;</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
      <div style={{ textAlign: 'right', marginTop: 14 }}><button className="btn" onClick={onCerrar}>Close</button></div>
    </Dialogo>
  );
}
