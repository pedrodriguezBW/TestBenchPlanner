import { useEffect, useRef, useState, type CSSProperties } from 'react';

/*
 * Controles de celda. Cada uno conserva lo que el usuario está escribiendo
 * mientras tiene el foco, aunque llegue una relectura del servidor; al perder
 * el foco vuelve a mostrar el valor recibido.
 */

function useValorLocal(externo: string): [string, (v: string) => void, { onFocus: () => void; onBlur: () => void }] {
  const [local, setLocal] = useState(externo);
  const conFoco = useRef(false);
  useEffect(() => {
    if (!conFoco.current) setLocal(externo);
  }, [externo]);
  return [local, setLocal, {
    onFocus: () => { conFoco.current = true; },
    onBlur: () => { conFoco.current = false; setLocal(externo); },
  }];
}

export function texto(valor: unknown): string {
  return valor === undefined || valor === null ? '' : String(valor);
}

/** Caja de texto o número que avisa de cada cambio (como el evento «input» de la 4.9.4). */
export function Entrada({ campo, valor, onCambio, tipo = 'text', lista, marcador, titulo, desactivado, clase, min, paso, estilo }: {
  campo: string;
  valor: unknown;
  onCambio: (v: string) => void;
  tipo?: 'text' | 'number';
  lista?: string;
  marcador?: string;
  titulo?: string;
  desactivado?: boolean;
  clase?: string;
  min?: string;
  paso?: string;
  estilo?: CSSProperties;
}) {
  const [local, setLocal, foco] = useValorLocal(texto(valor));
  return (
    <input
      type={tipo} data-f={campo} list={lista} placeholder={marcador} title={titulo} disabled={desactivado}
      className={clase} min={min} step={paso} style={estilo} value={local} {...foco}
      onChange={(e) => { setLocal(e.target.value); onCambio(e.target.value); }}
    />
  );
}

export function Selector({ campo, valor, opciones, onCambio, desactivado, titulo, estilo }: {
  campo: string;
  valor: string;
  opciones: [string, string][];
  onCambio: (v: string) => void;
  desactivado?: boolean;
  titulo?: string;
  estilo?: CSSProperties;
}) {
  return (
    <select data-f={campo} value={valor} disabled={desactivado} title={titulo} style={estilo}
      onChange={(e) => onCambio(e.target.value)}>
      {opciones.map(([v, etiqueta]) => <option key={v} value={v}>{etiqueta}</option>)}
    </select>
  );
}
