import { useLayoutEffect, useRef, useState } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { diasEntre, fmt } from '../../utilidades/fechas';
import { colorEstado, etiquetaEstado } from '../../utilidades/textos';

/*
 * Tarjeta de detalle al pasar el ratón por una barra del Gantt general
 * (rediseño del Gantt: propuesta A + trazabilidad + tarjetas). Sustituye al
 * tooltip nativo: estado, ensayo, banco, fechas, responsable, avance,
 * compromiso e ID de aprobación; con el foco activo, también de dónde viene
 * y adónde va el ensayo. Atajos: clic = abrir el ensayo en su banco,
 * F = enfocar su proyecto.
 */

export interface BarraGantt {
  fila: FilaEnsayo;
  banco: string;
  categoria: string;
  cerrado: boolean;
  especial: boolean;
  estado: string;
  proyecto: string;
  inicio: Date;
  fin: Date;
  color: string;
}

export interface DatosTarjeta {
  barra: BarraGantt;
  /** Posición del ratón al entrar en la barra (coordenadas de la ventana). */
  x: number;
  y: number;
  /** Rectángulo de la barra, para colocar la tarjeta debajo (o encima). */
  arriba: number;
  abajo: number;
}

export function TarjetaEnsayo({ datos, origenes, destinos, conFoco }: {
  datos: DatosTarjeta;
  origenes: BarraGantt[];
  destinos: BarraGantt[];
  conFoco: boolean;
}) {
  const caja = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);
  const { barra } = datos;
  const d = barra.fila.datos;
  const c = barra.fila.calculo;

  // Debajo de la barra y sin salirse de la ventana (si no cabe, encima).
  useLayoutEffect(() => {
    const el = caja.current;
    if (!el) return;
    const w = el.offsetWidth, h = el.offsetHeight;
    const left = Math.max(8, Math.min(window.innerWidth - w - 8, datos.x - 24));
    let top = datos.abajo + 8;
    if (top + h > window.innerHeight - 8) top = Math.max(8, datos.arriba - h - 8);
    setPos({ left, top });
  }, [datos]);

  const estado = barra.cerrado ? 'completed' : barra.estado;
  const etiqueta = barra.especial ? `${d.type ?? ''} (bench blocked)` : etiquetaEstado(estado);
  const dias = diasEntre(barra.inicio, barra.fin) + 1;
  const pct = barra.cerrado ? 100 : Math.round((c.fraccionAvance ?? 0) * 100);
  const ensayo = [d.type, d.comments].filter(Boolean).join(' \u00b7 ');
  const titulo = barra.proyecto || String(d.type ?? '') || 'Test';
  const describir = (b: BarraGantt) => `${b.banco} \u00b7 ${b.fila.datos.type || '\u2014'} (${fmt(b.inicio)}\u2192${fmt(b.fin)})`;

  const filas: [string, string][] = [];
  if (ensayo) filas.push(['Ensayo', ensayo]);
  filas.push(['Banco', `${barra.banco}${barra.categoria ? ' \u00b7 ' + barra.categoria : ''}`]);
  filas.push(['Fechas', `${fmt(barra.inicio)} \u2192 ${fmt(barra.fin)}  (${dias} d)`]);
  if (d.resp) filas.push(['Responsable', String(d.resp)]);
  if (!barra.especial) filas.push(['Avance', `${pct}%`]);
  if (!barra.cerrado && d.confirmedStart && !barra.especial) {
    filas.push(['Comprometido', `${fmt(d.confirmedStart)} \u2192 ${d.confirmedEnd ? fmt(d.confirmedEnd) : '?'}`]);
  }
  if (d.approvalId && String(d.approvalId).trim() !== '-') filas.push(['ID aprobación', String(d.approvalId)]);
  if (conFoco) {
    filas.push(['Viene de', origenes.length ? origenes.map(describir).join(' \u00b7 ') : '\u2014']);
    filas.push(['Va a', destinos.length ? destinos.map(describir).join(' \u00b7 ') : '\u2014']);
  }

  return (
    <div ref={caja} className="gm-tarjeta" data-solo-nueva="" role="tooltip"
      style={{ left: pos?.left ?? -9999, top: pos?.top ?? -9999, borderColor: barra.color }}>
      <div className="gm-t-cab">
        <span className="gm-t-punto" style={{ background: barra.color }} />
        <b>{titulo}</b>
      </div>
      <span className="gm-t-estado" style={{ background: barra.especial ? '#6b7785' : colorEstado(estado) }}>{etiqueta}</span>
      <dl>
        {filas.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
      </dl>
      <div className="gm-t-pie">Clic: abrir ensayo{barra.proyecto ? ' \u00b7 F: enfocar este proyecto' : ''}</div>
    </div>
  );
}
