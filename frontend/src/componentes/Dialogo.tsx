import type { ReactNode } from 'react';

/** Ventana modal con el estilo de la 4.9.4 (.modal-bg / .modal). */
export function Dialogo({ abierto, ancho, onCerrar, children }: {
  abierto: boolean;
  ancho?: 'wide' | 'xwide';
  onCerrar?: () => void;
  children: ReactNode;
}) {
  if (!abierto) return null;
  return (
    <div className="modal-bg show" onMouseDown={(e) => { if (e.target === e.currentTarget) onCerrar?.(); }}>
      <div className={`modal${ancho ? ' ' + ancho : ''}`}>{children}</div>
    </div>
  );
}
