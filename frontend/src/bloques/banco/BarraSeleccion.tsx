import { useState } from 'react';
import type { Vista } from '../../api/tipos';

/*
 * Barra de selección múltiple (updateSelBar): duplicar, mover a otro banco en
 * una prioridad (por defecto, la última del destino) y borrar.
 */
export function BarraSeleccion({ banco, vista, cantidad, onDuplicar, onMover, onBorrar, onLimpiar }: {
  banco: string;
  vista: Vista;
  cantidad: number;
  onDuplicar: () => void;
  onMover: (destino: string, posicion: number) => void;
  onBorrar: () => void;
  onLimpiar: () => void;
}) {
  const [destino, setDestino] = useState('');
  const [posicion, setPosicion] = useState('1');
  if (!cantidad) return null;

  const elegirDestino = (b: string) => {
    setDestino(b);
    const activos = b ? (vista.bancos[b] ?? []).filter((f) => !f.cerrado).length : 0;
    setPosicion(String(b ? activos + 1 : 1));
  };
  const estilo = { padding: '5px 8px', borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };

  return (
    <div className="selbar" id="selbar">
      <b>{cantidad}</b> selected &mdash; drag a selected priority handle to reorder.
      <button className="btn sm" onClick={onDuplicar}>&#10697; Duplicate</button>
      <select id="move-target" style={estilo} value={destino} onChange={(e) => elegirDestino(e.target.value)}>
        <option value="">Move to bench...</option>
        {vista.grupos.flatMap((g) => g.bancos).filter((b) => b.nombre !== banco)
          .map((b) => <option key={b.nombre} value={b.nombre}>{b.nombre}</option>)}
      </select>
      <input id="move-pos" type="number" min="1" title="Destination priority (defaults to last)" style={{ ...estilo, width: 64 }}
        value={posicion} onChange={(e) => setPosicion(e.target.value)} />
      <button className="btn sm" onClick={() => onMover(destino, parseInt(posicion, 10))}>&#8594; Move</button>
      <button className="btn sm" style={{ background: 'var(--danger)', color: '#2a0b0b' }} onClick={onBorrar}>&#128465; Delete</button>
      <button className="btn sm" onClick={onLimpiar}>Clear</button>
    </div>
  );
}
