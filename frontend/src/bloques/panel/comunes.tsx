import { useEffect, useRef, useState, type ReactNode, type MouseEvent as EventoRaton } from 'react';
import { usePlanificador } from '../../estado/Planificador';
import type { Organizacion } from '../../estado/Panel';
import { filtroDe, nombresArea, partesFiltro, CODIGOS_AREA, type CodigoArea } from '../../utilidades/areas';
import { usePreferencia } from '../../utilidades/preferencias';

/* Piezas comunes del Dashboard y de «By Responsible». */

/**
 * Lee del backend y vuelve a leer cuando cambian las claves o llega una vista
 * nueva (cada guardado y cada relectura de 20 s), para que los números estén
 * siempre al día con la tabla. Mientras llega la respuesta se mantiene la
 * anterior para que la pantalla no parpadee.
 */
export function useConsulta<T>(cargar: () => Promise<T>, claves: unknown[]): T | null {
  const { vista } = usePlanificador();
  const [datos, setDatos] = useState<T | null>(null);
  useEffect(() => {
    let vigente = true;
    cargar().then((d) => { if (vigente) setDatos(d); }).catch(() => undefined);
    return () => { vigente = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vista, ...claves]);
  return datos;
}

/**
 * Filtro de bancos en dos desplegables: «Area» (ALL o un área) y, solo con
 * Thermal Benches, «Org» (ALL / BW / CTAG, el orgSelectHTML / vpOrgSelect de la 4.9.4).
 */
export function SelectorOrganizacion({ valor, onCambiar, etiqueta = 'Org:', compacto = false }: {
  valor: Organizacion; onCambiar: (o: Organizacion) => void; etiqueta?: string; compacto?: boolean;
}) {
  const { vista } = usePlanificador();
  const nombres = nombresArea(vista);
  const { area, org } = partesFiltro(valor);
  const estilo = compacto
    ? { padding: '4px 6px', borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 }
    : { padding: '5px 8px', borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };
  const etiquetaArea = etiqueta.endsWith(':') ? 'Area:' : 'Area';
  return (
    <>
      <label className="hint" style={{ margin: compacto ? 0 : '0 0 0 8px' }} data-solo-nueva="">{etiquetaArea}</label>
      <select style={estilo} value={area} data-filtro-area="" data-solo-nueva=""
        onChange={(e) => onCambiar(filtroDe(e.target.value as 'ALL' | CodigoArea, 'ALL'))}>
        <option value="ALL">ALL</option>
        {CODIGOS_AREA.map((c) => <option key={c} value={c}>{nombres[c]}</option>)}
      </select>
      {area === 'THERMAL' && (
        <>
          <label className="hint" style={{ margin: compacto ? 0 : '0 0 0 4px' }}>{etiqueta}</label>
          <select style={estilo} value={org} data-filtro-org="" onChange={(e) => onCambiar(filtroDe('THERMAL', e.target.value as 'ALL' | 'BW' | 'CTAG'))}>
            {(['ALL', 'BW', 'CTAG'] as const).map((o) => <option key={o}>{o}</option>)}
          </select>
        </>
      )}
    </>
  );
}

/** Botones «% / Horas» (vpModeToggle). */
export function SelectorUnidad({ valor, onCambiar }: { valor: 'pct' | 'hours'; onCambiar: (v: 'pct' | 'hours') => void }) {
  const boton = (v: 'pct' | 'hours', texto: string) => (
    <button className={`btn ${valor === v ? '' : 'ghost'} sm`} style={{ padding: '3px 9px' }} onClick={() => onCambiar(v)}>{texto}</button>
  );
  return <span style={{ display: 'inline-flex', gap: 3 }}>{boton('pct', '%')}{boton('hours', 'Horas')}</span>;
}

/** Horas con separador de miles español (fmtH). */
export const horas = (v: number) => Math.round(v).toLocaleString('es-ES');

/**
 * Tarjeta con tirador para cambiar su alto (rzOpen / rzHandle de la 4.9.4).
 * El alto se recuerda en este navegador; doble clic en el tirador lo restablece.
 * Con `ajustable={false}` (H9, tarjetas del Gantt) no hay tirador: la tarjeta
 * mide lo que necesite su contenido.
 */
export function TarjetaRedimensionable({ clave, children, ajustable = true }: { clave: string; children: ReactNode; ajustable?: boolean }) {
  const [alturas, setAlturas] = usePreferencia<Record<string, number>>('tbp_alturas_tarjetas', {});
  const tarjeta = useRef<HTMLDivElement>(null);
  const [alto, setAlto] = useState<number | null>(null);
  const actual = alto ?? alturas[clave] ?? null;

  const empezar = (e: EventoRaton) => {
    e.preventDefault();
    const y0 = e.clientY;
    const h0 = tarjeta.current?.getBoundingClientRect().height ?? 0;
    let ultimo = h0;
    document.body.classList.add('bs-resizing');
    const mover = (ev: MouseEvent) => { ultimo = Math.max(140, Math.round(h0 + ev.clientY - y0)); setAlto(ultimo); };
    const soltar = () => {
      document.removeEventListener('pointermove', mover);
      document.removeEventListener('pointerup', soltar);
      document.body.classList.remove('bs-resizing');
      setAlturas({ ...alturas, [clave]: ultimo });
      setAlto(null);
    };
    document.addEventListener('pointermove', mover);
    document.addEventListener('pointerup', soltar);
  };
  const restablecer = () => {
    const resto = { ...alturas };
    delete resto[clave];
    setAlturas(resto);
    setAlto(null);
  };

  if (!ajustable) return <div ref={tarjeta} className="card rzcard gm-tarjeta-libre" data-rzk={clave}>{children}</div>;
  return (
    <>
      <div ref={tarjeta} className="card rzcard" data-rzk={clave} style={actual ? { height: actual, overflow: 'auto' } : undefined}>{children}</div>
      <div className="card-rz no-print" data-rzk={clave} title="Drag to resize this card (double-click to reset)"
        onPointerDown={empezar} onDoubleClick={restablecer}><div className="grip" /></div>
    </>
  );
}

/**
 * Casilla de número de semana que, como el onchange de la 4.9.4, solo aplica
 * el valor al confirmarlo (Intro, salir de la casilla o las flechas), no con
 * cada tecla.
 */
export function CampoSemana({ valor, maximo, onConfirmar, className }: {
  valor: number; maximo: number; onConfirmar: (v: number) => void; className?: string;
}) {
  const [texto, setTexto] = useState(String(valor));
  useEffect(() => setTexto(String(valor)), [valor]);
  const confirmar = (t: string) => {
    if (t !== '' && Number(t) !== valor) onConfirmar(Number(t));
    // Si el valor se corrige (p. ej. «hasta» menor que «desde») o no cambia, la casilla
    // vuelve a mostrar el valor vigente, como al redibujar la tarjeta en la 4.9.4.
    setTexto(String(valor));
  };
  return (
    <input type="number" className={className} min={1} max={maximo} value={texto}
      style={{ width: 54, padding: 4, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6 }}
      onChange={(e) => {
        setTexto(e.target.value);
        // Las flechas del campo no son tecleo: se aplican al momento, como el change nativo.
        if (!(e.nativeEvent as InputEvent).inputType) confirmar(e.target.value);
      }}
      onBlur={(e) => confirmar(e.target.value)}
      onKeyDown={(e) => { if (e.key === 'Enter') confirmar((e.target as HTMLInputElement).value); }} />
  );
}
