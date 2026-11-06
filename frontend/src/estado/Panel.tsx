import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { usePlanificador } from './Planificador';
import { dia, lunesDe } from '../utilidades/fechas';
import { usePreferencia } from '../utilidades/preferencias';

/*
 * Estado compartido por el Dashboard y «By Responsible», igual que las
 * variables globales de la 4.9.4 (viewWeek, DASH_ZOOM, DASH_ORG, DASH_GW,
 * RESP_SEL, TREND): dura mientras la pestaña está abierta y es común a las
 * dos pantallas. Las preferencias de las tarjetas (VIEWPREF) se guardan en el
 * navegador con la misma clave que la 4.9.4.
 */

export type Organizacion = 'ALL' | 'BW' | 'CTAG';
export const ORGANIZACIONES: Organizacion[] = ['ALL', 'BW', 'CTAG'];

export interface PreferenciasVista {
  trendOrg: Organizacion;
  trendMode: 'pct' | 'hours';
  utilOrg: Organizacion;
  utilMode: 'pct' | 'hours';
  utilFrom: number | null;
  utilTo: number | null;
}

const PREFERENCIAS_DE_SERIE: PreferenciasVista = { trendOrg: 'ALL', trendMode: 'pct', utilOrg: 'ALL', utilMode: 'pct', utilFrom: null, utilTo: null };

interface EstadoPanel {
  /** Lunes de la semana que se mira (viewWeek). */
  semana: Date;
  setSemana: (d: Date) => void;
  /** Zoom a la semana (true) o año completo (false). */
  zoom: boolean;
  setZoom: (z: boolean) => void;
  organizacion: Organizacion;
  setOrganizacion: (o: Organizacion) => void;
  /** Ancho de día elegido con −/+ (0 = el de serie según el zoom). */
  anchoDia: number;
  setAnchoDia: (w: number) => void;
  responsable: string;
  setResponsable: (r: string) => void;
  tendencia: { ocultas: Record<string, boolean>; desde: number; hasta: number };
  setTendencia: (t: { ocultas: Record<string, boolean>; desde: number; hasta: number }) => void;
  /** KPI History: Org (KPIHIST_ORG) y filtros de las estadísticas (STAT_*). */
  kpiOrg: Organizacion;
  setKpiOrg: (o: Organizacion) => void;
  estadisticas: { desde: string; hasta: string; responsable: string };
  setEstadisticas: (e: { desde: string; hasta: string; responsable: string }) => void;
  preferencias: PreferenciasVista;
  cambiarPreferencia: <K extends keyof PreferenciasVista>(clave: K, valor: PreferenciasVista[K]) => void;
  ponerPreferencias: (p: Partial<PreferenciasVista>) => void;
}

const ContextoPanel = createContext<EstadoPanel | null>(null);

export function usePanel(): EstadoPanel {
  const c = useContext(ContextoPanel);
  if (!c) throw new Error('usePanel fuera de ProveedorPanel');
  return c;
}

export function ProveedorPanel({ children }: { children: ReactNode }) {
  const { vista } = usePlanificador();
  const [semanaElegida, setSemana] = useState<Date | null>(null);
  const [zoom, setZoom] = useState(false);
  const [organizacion, setOrganizacion] = useState<Organizacion>('ALL');
  const [anchoDia, setAnchoDia] = useState(0);
  const [responsable, setResponsable] = useState('');
  const [tendencia, setTendencia] = useState({ ocultas: {} as Record<string, boolean>, desde: 1, hasta: 53 });
  const [kpiOrg, setKpiOrg] = useState<Organizacion>('ALL');
  // Vacíos = el año del calendario (statDefaults); se rellenan al abrir la pantalla.
  const [estadisticas, setEstadisticas] = useState({ desde: '', hasta: '', responsable: '' });
  const [guardadas, setGuardadas] = usePreferencia<Partial<PreferenciasVista>>('bw_tbp_viewprefs_v1', {});
  const preferencias = useMemo(() => ({ ...PREFERENCIAS_DE_SERIE, ...guardadas }), [guardadas]);

  // Hasta que el usuario se mueve, la semana es la de hoy (viewWeek = weekStart(TODAY)).
  const semana = semanaElegida ?? lunesDe(dia(vista?.hoy) ?? new Date());

  const valor: EstadoPanel = {
    semana, setSemana, zoom, setZoom, organizacion, setOrganizacion, anchoDia, setAnchoDia,
    responsable, setResponsable, tendencia, setTendencia, kpiOrg, setKpiOrg, estadisticas, setEstadisticas, preferencias,
    cambiarPreferencia: (clave, v) => setGuardadas({ ...preferencias, [clave]: v }),
    ponerPreferencias: (p) => setGuardadas({ ...preferencias, ...p }),
  };
  return <ContextoPanel.Provider value={valor}>{children}</ContextoPanel.Provider>;
}
