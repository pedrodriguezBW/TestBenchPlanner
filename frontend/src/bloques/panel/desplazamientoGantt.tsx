import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';

/*
 * Gantt a altura libre (H9): el diagrama ya no tiene scroll vertical propio,
 * ocupa todo el alto que necesita y es la página la que se desplaza. Para no
 * perder lo que daba el scroll interno:
 *  - useCabeceraFlotante: la cabecera (meses / CW / días) acompaña al scroll de
 *    la página mientras el Gantt está a la vista (se traslada con transform; si
 *    el Gantt vuelve a tener scroll propio, manda el sticky de siempre).
 *  - BarraHorizontalFlotante: copia de la barra de scroll horizontal pegada al
 *    borde inferior de la pantalla cuando la del propio Gantt queda por debajo.
 */

/** Ventana visible del área que hace scroll (el <main> de la app). */
function ventana(el: HTMLElement): { arriba: number; abajo: number } {
  const main = el.closest('main');
  if (main) { const r = main.getBoundingClientRect(); return { arriba: r.top, abajo: r.bottom }; }
  return { arriba: 0, abajo: window.innerHeight };
}

/** Llama a `fn` (una vez por fotograma) con cualquier scroll o cambio de tamaño. */
function useAlDesplazar(fn: () => void) {
  const actual = useRef(fn);
  actual.current = fn;
  useEffect(() => {
    let pendiente = 0;
    const programar = () => {
      if (pendiente) return;
      pendiente = requestAnimationFrame(() => { pendiente = 0; actual.current(); });
    };
    // En captura: llega el scroll del <main>, el horizontal del propio Gantt y cualquier otro.
    document.addEventListener('scroll', programar, true);
    window.addEventListener('resize', programar);
    return () => {
      document.removeEventListener('scroll', programar, true);
      window.removeEventListener('resize', programar);
      if (pendiente) cancelAnimationFrame(pendiente);
    };
  }, []);
}

export function useCabeceraFlotante(caja: RefObject<HTMLElement | null>) {
  const aplicar = () => {
    const el = caja.current;
    if (!el) return;
    const filas = [...el.querySelectorAll<HTMLElement>(':scope > div > .g-months, :scope > div > .g-days')];
    if (!filas.length) return;
    const altoCabecera = filas.reduce((s, f) => s + f.offsetHeight, 0);
    let dy = 0;
    if (el.scrollHeight <= el.clientHeight + 1) {
      const r = el.getBoundingClientRect();
      dy = Math.max(0, Math.min(ventana(el).arriba - r.top, el.clientHeight - altoCabecera - 34));
    }
    const valor = dy > 0 ? `translateY(${Math.round(dy)}px)` : '';
    filas.forEach((f) => { if (f.style.transform !== valor) f.style.transform = valor; });
    el.classList.toggle('gm-cab-flota', dy > 0);
  };
  useAlDesplazar(aplicar);
  useLayoutEffect(aplicar);
}

export function BarraHorizontalFlotante({ caja }: { caja: RefObject<HTMLElement | null> }) {
  const barra = useRef<HTMLDivElement>(null);
  const relleno = useRef<HTMLDivElement>(null);
  const sincronizar = () => {
    const el = caja.current, b = barra.current, r = relleno.current;
    if (!el || !b || !r) return;
    const re = el.getBoundingClientRect();
    const v = ventana(el);
    const desborda = el.scrollWidth > el.clientWidth + 1;
    const mostrar = desborda && re.bottom > v.abajo + 2 && re.top < v.abajo - 60;
    if (b.hidden === mostrar) b.hidden = !mostrar;
    if (!mostrar) return;
    b.style.width = el.clientWidth + 'px';
    r.style.width = el.scrollWidth + 'px';
    if (Math.abs(b.scrollLeft - el.scrollLeft) > 1) b.scrollLeft = el.scrollLeft;
  };
  useAlDesplazar(sincronizar);
  useLayoutEffect(sincronizar);
  return (
    <div ref={barra} className="gm-hscroll no-print" data-solo-nueva="" hidden aria-hidden="true"
      onScroll={(e) => { const el = caja.current; if (el && Math.abs(el.scrollLeft - e.currentTarget.scrollLeft) > 1) el.scrollLeft = e.currentTarget.scrollLeft; }}>
      <div ref={relleno} style={{ height: 1 }} />
    </div>
  );
}
