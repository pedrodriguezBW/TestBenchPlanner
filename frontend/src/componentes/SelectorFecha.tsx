import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { usePlanificador } from '../estado/Planificador';
import { dia, esFinDeSemana, iso, lunesDe, semanaIso, sumarDias } from '../utilidades/fechas';

/*
 * Celda de fecha con el calendario propio de la 4.9.4: un campo de solo lectura
 * que al pulsarlo abre un calendario con la columna CW (semana ISO), y los
 * botones «Today» y «Clear».
 */
export function SelectorFecha({ valor, hoy, onCambio, desactivado, titulo, estilo, campo }: {
  valor: string;
  hoy: string;
  onCambio: (fecha: string) => void;
  desactivado?: boolean;
  titulo?: string;
  estilo?: CSSProperties;
  campo?: string;
}) {
  const { soloLectura } = usePlanificador();
  const inactivo = soloLectura || desactivado;
  const [abierto, setAbierto] = useState(false);
  const caja = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        type="text" readOnly className="datecal" value={valor || ''} placeholder={'\u2014'} data-f={campo}
        title={titulo ?? 'Click to pick a date (shows calendar week)'} disabled={inactivo} style={estilo}
        ref={caja} onClick={() => !inactivo && setAbierto(true)}
      />
      {abierto && caja.current && (
        <Calendario
          ancla={caja.current} valor={valor} hoy={hoy}
          onElegir={(f) => { setAbierto(false); if (f !== valor) onCambio(f); }}
          onCerrar={() => setAbierto(false)}
        />
      )}
    </>
  );
}

function Calendario({ ancla, valor, hoy, onElegir, onCerrar }: {
  ancla: HTMLElement; valor: string; hoy: string; onElegir: (f: string) => void; onCerrar: () => void;
}) {
  const inicial = dia(/^\d{4}-\d{2}-\d{2}$/.test(valor) ? valor : hoy) ?? new Date();
  const [anio, setAnio] = useState(inicial.getFullYear());
  const [mes, setMes] = useState(inicial.getMonth());
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const fuera = (e: MouseEvent) => {
      if (panel.current && !panel.current.contains(e.target as Node) && e.target !== ancla) onCerrar();
    };
    // Como la 4.9.4: también se cierra con Escape y al cambiar el tamaño de la ventana.
    const tecla = (e: KeyboardEvent) => { if (e.key === 'Escape') onCerrar(); };
    document.addEventListener('mousedown', fuera);
    document.addEventListener('keydown', tecla);
    window.addEventListener('resize', onCerrar);
    return () => {
      document.removeEventListener('mousedown', fuera);
      document.removeEventListener('keydown', tecla);
      window.removeEventListener('resize', onCerrar);
    };
  }, [ancla, onCerrar]);

  const mover = (d: number) => {
    let m = mes + d, a = anio;
    if (m < 0) { m = 11; a--; }
    if (m > 11) { m = 0; a++; }
    setMes(m); setAnio(a);
  };

  const primero = new Date(anio, mes, 1);
  const inicioRejilla = lunesDe(primero);
  const caja = ancla.getBoundingClientRect();
  let arriba = caja.bottom + 4, izquierda = caja.left;
  if (izquierda + 250 > window.innerWidth - 8) izquierda = Math.max(8, window.innerWidth - 258);
  if (arriba + 270 > window.innerHeight - 8) arriba = Math.max(8, caja.top - 274);

  return createPortal(
    <div className="cal-pop" ref={panel} style={{ top: arriba, left: izquierda }}>
      <div className="cal-head">
        <button className="btn ghost sm" onClick={() => mover(-1)}>&#9664;</button>
        <span className="mtitle">{primero.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })}</span>
        <button className="btn ghost sm" onClick={() => mover(1)}>&#9654;</button>
      </div>
      <table className="cal-grid">
        <thead><tr><th className="cwh">CW</th>{['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => <th key={i}>{d}</th>)}</tr></thead>
        <tbody>
          {Array.from({ length: 6 }, (_, semana) => {
            const lunes = sumarDias(inicioRejilla, semana * 7);
            return (
              <tr key={semana}>
                <td className="cw">{semanaIso(lunes)}</td>
                {Array.from({ length: 7 }, (_, d) => {
                  const fecha = sumarDias(lunes, d);
                  const texto = iso(fecha);
                  const clases = ['day', esFinDeSemana(fecha) && 'we', fecha.getMonth() !== mes && 'oth',
                    texto === hoy && 'today', texto === valor && 'sel'].filter(Boolean).join(' ');
                  return <td key={d} className={clases} onClick={() => onElegir(texto)}>{fecha.getDate()}</td>;
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="cal-foot">
        <button className="btn ghost sm" onClick={() => onElegir(hoy)}>Today</button>
        <button className="btn ghost sm" onClick={() => onElegir('')}>Clear</button>
      </div>
    </div>,
    document.body,
  );
}
