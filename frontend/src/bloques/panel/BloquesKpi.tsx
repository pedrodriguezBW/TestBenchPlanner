import { api } from '../../api/cliente';
import type { BloqueKpi } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { iso, semanaIso, sumarDias } from '../../utilidades/fechas';
import { useConsulta } from './comunes';

/*
 * «Esta semana» y «Semana que viene» (kpiTwoBlocksHTML de la 4.9.4). Los
 * números los calcula el backend; con `responsable` solo cuentan sus ensayos.
 */
export function BloquesKpi({ responsable = null }: { responsable?: string | null }) {
  const { semana, organizacion } = usePanel();
  const lunes = iso(semana);
  const datos = useConsulta(() => api.bloquesKpi(lunes, organizacion, responsable), [lunes, organizacion, responsable]);
  if (!datos) return <div className="kpi-blocks" />;
  return (
    <div className="kpi-blocks">
      <Bloque titulo="Esta semana" cw={semanaIso(semana)} k={datos.estaSemana} />
      <Bloque titulo="Semana que viene" cw={semanaIso(sumarDias(semana, 7))} k={datos.siguiente} />
    </div>
  );
}

function Bloque({ titulo, cw, k }: { titulo: string; cw: number; k: BloqueKpi }) {
  return (
    <div className="kpi-block">
      <div className="kpi-block-h">{titulo} &middot; <span style={{ color: 'var(--turq2)' }}>CW{cw}</span></div>
      <div className="kpis">
        <div className="kpi"><div className="n">{k.benches}<span style={{ fontSize: 16, color: 'var(--muted)' }}>/{k.total}</span></div><div className="l">Bancos con carga</div></div>
        <div className="kpi green"><div className="n">{k.accr}</div><div className="l">Corriendo / acumulando horas</div></div>
        <div className="kpi bad"><div className="n">{k.delayed}</div><div className="l">En retraso (declarado)</div></div>
      </div>
      {k.sug > 0 && (
        <div className="kpi-pend" title="La app ha detectado ensayos que quiza merezcan otra calificacion. Tu decides: abre el banco y pulsa la chincheta de la fila.">
          &#9888;&#65039; {k.sug} ensayo(s) con sugerencia de la app
        </div>
      )}
    </div>
  );
}
