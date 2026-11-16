/*
 * Áreas de bancos: nivel por encima de las categorías (Thermal Benches,
 * HVCH - CTAG, HVCH - STC). Son fijas y van en este orden; el nombre visible
 * viene del backend (un Admin puede renombrarlas en Test Benches).
 *
 * Filtro del Dashboard, «By Responsible» y KPI: dos desplegables (área y, solo
 * con Thermal Benches, organización) que se guardan como UN código, el mismo
 * que entiende el backend:
 *   ALL · THERMAL · BW / CTAG (Thermal Benches de esa organización, los códigos
 *   de siempre) · HVCH-CTAG · HVCH-STC.
 */
import type { Vista } from '../api/tipos';

export const AREA_TERMICOS = 'THERMAL';
export const CODIGOS_AREA = ['THERMAL', 'HVCH-CTAG', 'HVCH-STC'] as const;
export type CodigoArea = (typeof CODIGOS_AREA)[number];

export const NOMBRES_AREA_DE_SERIE: Record<CodigoArea, string> = {
  THERMAL: 'Thermal Benches',
  'HVCH-CTAG': 'HVCH - CTAG',
  'HVCH-STC': 'HVCH - STC',
};

/** Organización fija de los bancos de un área HVCH (null = se elige banco a banco). */
export const ORGANIZACION_FIJA: Record<CodigoArea, string | null> = { THERMAL: null, 'HVCH-CTAG': 'CTAG', 'HVCH-STC': 'STC' };

export type FiltroBancos = 'ALL' | 'THERMAL' | 'BW' | 'CTAG' | 'HVCH-CTAG' | 'HVCH-STC';
export const FILTROS: FiltroBancos[] = ['ALL', 'BW', 'CTAG', 'THERMAL', 'HVCH-CTAG', 'HVCH-STC'];

/** Código de área a partir de lo guardado: vacío o desconocido = Thermal Benches. */
export function normalizarArea(valor: unknown): CodigoArea {
  const v = String(valor ?? '').trim().toUpperCase().replace(/[\s_]+/g, '-').replace(/-+/g, '-');
  return (CODIGOS_AREA as readonly string[]).includes(v) ? (v as CodigoArea) : 'THERMAL';
}

export function normalizarFiltro(valor: unknown): FiltroBancos {
  const v = String(valor ?? '').trim().toUpperCase();
  return (FILTROS as string[]).includes(v) ? (v as FiltroBancos) : 'ALL';
}

/** Los dos desplegables que forman un filtro. */
export function partesFiltro(filtro: FiltroBancos): { area: 'ALL' | CodigoArea; org: 'ALL' | 'BW' | 'CTAG' } {
  switch (filtro) {
    case 'BW': case 'CTAG': return { area: 'THERMAL', org: filtro };
    case 'THERMAL': case 'HVCH-CTAG': case 'HVCH-STC': return { area: filtro, org: 'ALL' };
    default: return { area: 'ALL', org: 'ALL' };
  }
}

export function filtroDe(area: 'ALL' | CodigoArea, org: 'ALL' | 'BW' | 'CTAG'): FiltroBancos {
  if (area === 'THERMAL') return org === 'ALL' ? 'THERMAL' : org;
  return area;
}

/** ¿El banco entra en el filtro? */
export function cumpleFiltro(banco: { area?: string; organizacion: string }, filtro: string): boolean {
  const f = normalizarFiltro(filtro);
  if (f === 'ALL') return true;
  const area = normalizarArea(banco.area);
  if (f === 'BW' || f === 'CTAG') return area === 'THERMAL' && (banco.organizacion || 'BW') === f;
  return area === f;
}

/** Nombre de cada área (el guardado o el de serie), en el orden fijo. */
export function nombresArea(vista: Vista | null | undefined): Record<CodigoArea, string> {
  const nombres = { ...NOMBRES_AREA_DE_SERIE };
  (vista?.areas ?? []).forEach((a) => {
    const c = normalizarArea(a.clave);
    if (a.nombre) nombres[c] = a.nombre;
  });
  return nombres;
}

/** Texto del filtro para títulos («Thermal Benches · BW», «HVCH - STC»…). */
export function etiquetaFiltro(filtro: string, vista: Vista | null | undefined): string {
  const f = normalizarFiltro(filtro);
  if (f === 'ALL') return 'ALL';
  const nombres = nombresArea(vista);
  const { area, org } = partesFiltro(f);
  return area === 'ALL' ? 'ALL' : nombres[area] + (org !== 'ALL' ? ` \u00b7 ${org}` : '');
}
