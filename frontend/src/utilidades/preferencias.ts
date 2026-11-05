import { useCallback, useState } from 'react';

/*
 * Preferencias de presentación guardadas solo en este navegador (orden y ancho
 * de columnas, reparto tabla/Gantt, zoom del Gantt…), como en la 4.9.4.
 * No viajan al backend ni afectan a los demás.
 */
export function usePreferencia<T>(clave: string, porDefecto: T): [T, (v: T) => void] {
  const [valor, setValor] = useState<T>(() => {
    try {
      const guardado = localStorage.getItem(clave);
      return guardado === null ? porDefecto : (JSON.parse(guardado) as T);
    } catch {
      return porDefecto;
    }
  });
  const guardar = useCallback((v: T) => {
    setValor(v);
    try {
      localStorage.setItem(clave, JSON.stringify(v));
    } catch {
      // sin almacenamiento local: la preferencia dura hasta recargar
    }
  }, [clave]);
  return [valor, guardar];
}
