import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { dia, fmt, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import type { ReactNode } from 'react';

/*
 * ◀ Prev · CWxx · fechas · Next ▶ · Today (shiftWeek / thisWeek). En el
 * Dashboard, moverse de semana activa el zoom a la semana (como en la 4.9.4);
 * en «By Responsible» no.
 */
export function NavegacionSemana({ activarZoom, children }: { activarZoom: boolean; children?: ReactNode }) {
  const { vista } = usePlanificador();
  const { semana, setSemana, setZoom } = usePanel();
  const mover = (n: number) => {
    setSemana(sumarDias(semana, 7 * n));
    if (activarZoom) setZoom(true);
  };
  return (
    <div className="week-nav no-print">
      <button className="btn ghost sm" onClick={() => mover(-1)}>&#9664; Prev</button>
      <b id="semana-vista">CW{semanaIso(semana)} &middot; {fmt(semana)} &rarr; {fmt(sumarDias(semana, 6))}</b>
      <button className="btn ghost sm" onClick={() => mover(1)}>Next &#9654;</button>
      <button className="btn ghost sm" onClick={() => setSemana(lunesDe(dia(vista!.hoy)!))}>Today</button>
      {children}
    </div>
  );
}

/** Leyenda de colores de estado (chips). */
export function Chip({ color, texto }: { color: string; texto: string }) {
  return <span className="chip"><span className="sw" style={{ background: color }} />{texto}</span>;
}
