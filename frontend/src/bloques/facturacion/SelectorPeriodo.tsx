import { useEffect, useState } from 'react';
import { apiFacturacion, type PeriodoListado } from '../../api/facturacion';
import { ESTADO_PERIODO } from './textos';

/** Desplegable con los periodos creados (el elegido se comparte entre Review y Overview). */
export function SelectorPeriodo({ id, onCambio }: { id: string | null; onCambio: (id: string | null) => void }) {
  const [lista, setLista] = useState<PeriodoListado[]>([]);
  useEffect(() => {
    apiFacturacion.periodos().then((l) => {
      setLista(l);
      if ((!id || !l.some((p) => p.id === id)) && l.length) onCambio(l[0].id);
    }).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <select className="fac-campo" id="fac-periodo" value={id ?? ''} onChange={(e) => onCambio(e.target.value || null)}>
      {!lista.length && <option value="">(sin periodos)</option>}
      {lista.map((p) => <option key={p.id} value={p.id}>{p.nombre} &middot; {ESTADO_PERIODO[p.estado].texto}</option>)}
    </select>
  );
}
