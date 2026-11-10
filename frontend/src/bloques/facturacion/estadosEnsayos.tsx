import { useEffect, useState } from 'react';
import { apiFacturacion, type EstadoDatos } from '../../api/facturacion';
import type { Vista } from '../../api/tipos';

/** Estado de los datos de facturación de cada ensayo (columna «Billing»); se relee cuando cambia la vista. */
export function useEstadosFacturacion(vista: Vista | null): Record<string, EstadoDatos> {
  const [estados, setEstados] = useState<Record<string, EstadoDatos>>({});
  useEffect(() => {
    if (!vista) return;
    const t = window.setTimeout(() => {
      apiFacturacion.estadosEnsayos().then(setEstados).catch(() => undefined);
    }, 600);
    return () => window.clearTimeout(t);
  }, [vista]);
  return estados;
}

/** Botón de la columna «Billing»: estado de los datos y acceso a la ficha. */
export function CeldaFacturacion({ estado, onAbrir }: { estado?: EstadoDatos; onAbrir: () => void }) {
  if (!estado) return <span className="fac-celda-billing"><button onClick={onAbrir} title="Billing Data">&#128182;</button></span>;
  if (estado.estado === 'NO_APLICA') return <span className="fac-nota">&mdash;</span>;
  const [clase, texto, ayuda] = estado.estado === 'COMPLETO' ? ['ok', '\u2714', 'Datos de facturación completos']
    : estado.estado === 'NO_FACTURABLE' ? ['nofac', '\u2298', 'Marcado como no facturable']
      : ['falta', '!', `BILLING_DATA_INCOMPLETE · faltan: ${estado.faltan.join(', ')}`];
  return (
    <span className="fac-celda-billing">
      <button className={clase} onClick={onAbrir} title={`${ayuda}. Abrir Billing Data.`}>&#128182; {texto}</button>
    </span>
  );
}
