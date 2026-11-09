import type { FilaEnsayo } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';
import { conflictosDeFila, useRecursos } from '../../estado/Recursos';

/** Celda «Resources» (resCellInner): equipos asignados, aviso «!» si alguno está ocupado y 🔍 para elegir. */
export function CeldaRecursos({ fila, onAbrir }: { fila: FilaEnsayo; onAbrir: () => void }) {
  const { vista } = usePlanificador();
  const { bloqueos } = useRecursos();
  const lista = Array.isArray(fila.datos.resources) ? fila.datos.resources : [];
  const conflictos = vista ? conflictosDeFila(vista, bloqueos, fila) : [];
  const aviso = conflictos.map((c) => `${c.nombre}: ${c.usos.map((u) => `${u.motivo} (${u.inicio}\u2192${u.fin})`).join('; ')}`).join(' | ');
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <div style={{ flex: 1, minWidth: 80 }}>
        {lista.length ? lista.map((n) => <span key={n} className="res-badge">{n}</span>) : <span style={{ color: 'var(--muted)', fontSize: 12 }}>&mdash;</span>}
        {conflictos.length > 0 && <span className="res-alert-badge" title={aviso}>!</span>}
      </div>
      <button className="icbtn ed" title="Elegir recursos" onClick={onAbrir}>&#128269;</button>
    </div>
  );
}
