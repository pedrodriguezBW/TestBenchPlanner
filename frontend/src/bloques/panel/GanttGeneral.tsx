import { useEffect, useLayoutEffect, useMemo, useRef, useState, type MouseEvent as EventoRaton, type ReactElement } from 'react';
import type { FilaEnsayo } from '../../api/tipos';
import { usePanel } from '../../estado/Panel';
import { usePlanificador } from '../../estado/Planificador';
import { dia, diasEntre, esFinDeSemana, fmt, iso, lunesDe, semanaIso, sumarDias } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';
import { colorProyecto, exportarGanttExcel, matrizGantt } from '../exportaciones/descargas';
import { colorEstado, etiquetaEstado } from '../../utilidades/textos';
import { cumpleFiltro, nombresArea, normalizarArea, type CodigoArea } from '../../utilidades/areas';
import { asignarCarriles, enlacesTrazabilidad, flechasVisibles, MAX_FLECHAS_A_LA_VEZ, maximoSimultaneo, ocupacionPeriodo, tramoVisible, type Enlace } from '../../utilidades/ganttModerno';
import { TarjetaEnsayo, type BarraGantt, type DatosTarjeta } from './TarjetaEnsayo';
import { BarraHorizontalFlotante, useCabeceraFlotante } from './desplazamientoGantt';

/*
 * Gantt de todos los bancos (overviewGantt de la 4.9.4) con el rediseño
 * acordado (doc 09): base «carriles apilados + color por proyecto» (A),
 * modo foco con trazabilidad (D) y tarjeta de detalle al pasar el ratón.
 *
 *  - Año completo a resolución semanal (9 px/día) o una semana a resolución
 *    diaria (30 px/día) con fila de días y círculos del compromiso.
 *  - El COLOR identifica el proyecto (siempre el mismo, como en el Excel) y la
 *    FORMA el estado: completado translúcido con ✓, en curso con marca blanca,
 *    confirmado relleno, en cola discontinuo, en retraso con borde rojo y
 *    bloqueos rayados. «Color: Estado» vuelve a los colores de la 4.9.4.
 *  - Los ensayos que coinciden en un banco van en carriles separados, con el
 *    aviso «N en paralelo»; cada banco muestra su ocupación del periodo.
 *  - Bancos agrupados por categoría (y por área si se ven varias), plegables;
 *    el pasado aparece atenuado.
 *  - Foco de proyecto: ficha resumen, bancos del proyecto resaltados, el resto
 *    atenuado pero con su color, categorías sin ensayos del proyecto plegadas y
 *    flechas de trazabilidad (botón on/off) entre bancos.
 *  - Con `responsable` solo se pintan sus ensayos y se omiten los bancos vacíos.
 * Mismas herramientas que el Gantt del banco: zoom −/+, foco de proyecto,
 * ancho de la 1ª columna (compartido con el del banco) y línea de HOY.
 */

const ANCHO_ETIQUETA = 170;
const ALTO_CARRIL = 22;
const ALTO_BARRA = 18;
const MARGEN_FILA = 4;
const MAX_LEYENDA = 14;
/** Hueco máximo entre un ensayo y el siguiente de la misma muestra para enlazarlos. */
const HUECO_TRAZABILIDAD_DIAS = 21;

type ModoColor = 'proyecto' | 'estado';

interface Props {
  /** null = todos los responsables. */
  responsable: string | null;
  /** En «By Responsible» el Excel se titula «Team plan» (como la 4.9.4). */
  enResponsable?: boolean;
}

interface Trazo { d: string; clave: string }

function rgba(hex: string, alfa: number): string {
  const h = hex.replace('#', '');
  if (h.length !== 6) return hex;
  return `rgba(${parseInt(h.slice(0, 2), 16)},${parseInt(h.slice(2, 4), 16)},${parseInt(h.slice(4, 6), 16)},${alfa})`;
}

/** Forma del estado (clase CSS gm-…). */
function forma(b: BarraGantt): string {
  if (b.especial) return 'gm-esp';
  if (b.cerrado) return 'gm-hecho';
  if (b.estado === 'running') return 'gm-curso';
  if (b.estado === 'delayed') return 'gm-retraso';
  if (b.estado === 'confirmed') return 'gm-conf';
  return 'gm-cola';
}

/** Abre el banco del ensayo; VistaBanco lo busca, lo centra y lo resalta. */
function abrirEnsayo(b: BarraGantt) {
  try {
    sessionStorage.setItem('tbp_abrir_ensayo', JSON.stringify({ banco: b.banco, id: b.fila.id, cerrado: b.cerrado }));
  } catch {
    // sin almacenamiento de sesión: se abre el banco sin resaltar la fila
  }
  window.location.hash = '/' + encodeURIComponent(b.banco);
}

export function GanttGeneral({ responsable, enResponsable = false }: Props) {
  const { vista, proyectos } = usePlanificador();
  const { semana, zoom, organizacion, anchoDia, setAnchoDia } = usePanel();
  const [foco, setFoco] = usePreferencia<string>('tbp_gantt_foco', '');
  const [anchoEtiqueta, setAnchoEtiqueta] = usePreferencia<number | null>('bw_gantt_lw', null);
  const [modoColor, setModoColor] = usePreferencia<ModoColor>('tbp_gantt_color', 'proyecto');
  const [traza, setTraza] = usePreferencia<boolean>('tbp_gantt_traza', true);
  const [plegadas, setPlegadas] = usePreferencia<string[]>('tbp_gantt_cat_plegadas', []);
  const [abiertasEnFoco, setAbiertasEnFoco] = useState<string[]>([]);
  const [tarjeta, setTarjeta] = useState<DatosTarjeta | null>(null);
  const [trazos, setTrazos] = useState<Trazo[]>([]);
  const caja = useRef<HTMLDivElement>(null);
  const interior = useRef<HTMLDivElement>(null);
  const enlacesActuales = useRef<Enlace[]>([]);
  const base = zoom ? 30 : 9;
  const W = anchoDia || base;
  const LW = anchoEtiqueta ?? ANCHO_ETIQUETA;
  const hoy = dia(vista?.hoy)!;
  const festivos = useMemo(() => new Set((vista?.calendario.festivos ?? []).map((f) => f.fecha)), [vista]);

  let min: Date, max: Date;
  if (zoom) {
    min = semana;
    max = sumarDias(semana, 6);
  } else {
    min = lunesDe(new Date(vista!.calendario.anio, 0, 4));
    max = sumarDias(min, 53 * 7 - 1);
  }
  const n = diasEntre(min, max) + 1;
  const dias = Array.from({ length: n }, (_, i) => sumarDias(min, i));
  const desplazamientoHoy = hoy >= min && hoy <= max ? diasEntre(min, hoy) * W : -9999;
  const anchoPasado = hoy < min ? 0 : hoy > max ? n * W : diasEntre(min, hoy) * W;

  // Al cambiar de proyecto en foco, las categorías vuelven a plegarse solas.
  useEffect(() => { setAbiertasEnFoco([]); }, [foco]);

  // Centrar HOY al dibujar (scrollGantt) y fijar las cabeceras al hacer scroll vertical.
  useEffect(() => {
    if (!caja.current) return;
    caja.current.scrollLeft = Math.max(0, LW + (desplazamientoHoy || 0) - caja.current.clientWidth / 2);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [min.getTime(), W, zoom]);
  useLayoutEffect(() => fijarCabeceras(caja.current));
  // H9: el Gantt ocupa todo el alto que necesita; la cabecera acompaña al scroll de la página.
  useCabeceraFlotante(caja);

  // Flechas de trazabilidad: se miden las barras ya dibujadas (carriles y filas plegadas incluidos).
  useLayoutEffect(() => {
    const cont = interior.current;
    const nuevos: Trazo[] = [];
    if (cont && enlacesActuales.current.length) {
      const r0 = cont.getBoundingClientRect();
      const rect = (id: string) => {
        const el = cont.querySelector<HTMLElement>(`[data-gid="${CSS.escape(id)}"]`);
        const r = el?.getBoundingClientRect();
        return r && r.width > 0 && r.height > 0 ? r : null;
      };
      enlacesActuales.current.forEach((e) => {
        const a = rect(e.de), b = rect(e.a);
        if (!a || !b) return;
        const x1 = a.right - r0.left, y1 = a.top + a.height / 2 - r0.top;
        const x2 = b.left - r0.left, y2 = b.top + b.height / 2 - r0.top;
        const dx = Math.max(24, Math.abs(x2 - x1) * 0.5);
        nuevos.push({ clave: e.de + '>' + e.a, d: `M${x1.toFixed(1)},${y1.toFixed(1)} C${(x1 + dx).toFixed(1)},${y1.toFixed(1)} ${(x2 - dx).toFixed(1)},${y2.toFixed(1)} ${x2.toFixed(1)},${y2.toFixed(1)}` });
      });
    }
    if (JSON.stringify(nuevos) !== JSON.stringify(trazos)) setTrazos(nuevos);
  });

  // F con la tarjeta abierta = enfocar ese proyecto.
  useEffect(() => {
    if (!tarjeta?.barra.proyecto) return;
    const alPulsar = (e: KeyboardEvent) => {
      const destino = e.target as HTMLElement | null;
      if (destino && /^(INPUT|TEXTAREA|SELECT)$/.test(destino.tagName)) return;
      if (e.key === 'f' || e.key === 'F') { setFoco(tarjeta.barra.proyecto); setTarjeta(null); }
    };
    window.addEventListener('keydown', alPulsar);
    return () => window.removeEventListener('keydown', alPulsar);
  }, [tarjeta, setFoco]);

  const zoomPaso = (direccion: number) => {
    const paso = Math.max(2, Math.round(W * 0.2));
    setAnchoDia(Math.max(6, Math.min(90, W + direccion * paso)));
  };

  if (!vista) return null;
  const filtro = (f: FilaEnsayo) => responsable === null || String(f.datos.resp ?? '') === responsable;
  const claseFoco = (proyecto: string) => (foco ? (proyecto === foco ? ' g-foc' : ' g-dim') : '');
  const colorDe = (proyecto: string, estado: string) => (modoColor === 'estado' ? colorEstado(estado) : colorProyecto(proyecto));

  // ---- Datos: barras visibles por banco, en el orden de la 4.9.4 (activos y luego completados) ----
  interface BancoVisible { nombre: string; colorGrupo: string; area: CodigoArea; grupo: string; nombreGrupo: string; barras: BarraGantt[] }
  const visibles: BancoVisible[] = [];
  vista.grupos.forEach((g) => g.bancos.forEach((b) => {
    if (!cumpleFiltro({ ...b, area: g.area }, organizacion)) return;
    const todas = vista.bancos[b.nombre] ?? [];
    const activas = todas.filter((f) => !f.cerrado && f.calculo.inicio && f.calculo.fin && filtro(f));
    const cerradas = todas.filter((f) => f.cerrado && f.datos.histStart && f.datos.histEnd && filtro(f));
    if (!activas.length && !cerradas.length && responsable !== null) return;
    const barras: BarraGantt[] = [];
    const meter = (f: FilaEnsayo, inicio: Date, fin: Date, cerrado: boolean) => {
      if (fin < min || inicio > max) return;
      const proyecto = String(f.datos.project || '');
      const estado = cerrado ? 'completed' : f.calculo.estado;
      barras.push({ fila: f, banco: b.nombre, categoria: g.nombre, cerrado, especial: !cerrado && !!f.calculo.tipoEspecial, estado, proyecto, inicio, fin, color: colorDe(proyecto, estado) });
    };
    activas.forEach((f) => meter(f, dia(f.calculo.inicio)!, dia(f.calculo.fin)!, false));
    cerradas.forEach((f) => meter(f, dia(String(f.datos.histStart))!, dia(String(f.datos.histEnd))!, true));
    visibles.push({ nombre: b.nombre, colorGrupo: g.color, area: normalizarArea(g.area), grupo: g.clave, nombreGrupo: g.nombre, barras });
  }));

  // Sin barras no hay Gantt ni barra de herramientas (enhanceGantts solo actúa si existe #ganttbox).
  if (!visibles.length) return <div className="empty">No scheduled tests in this range.</div>;

  // ---- Foco: bancos y categorías del proyecto, ficha resumen y trazabilidad ----
  const barrasFoco = foco ? visibles.flatMap((v) => v.barras.filter((b) => b.proyecto === foco)) : [];
  const bancosFoco = new Set(barrasFoco.map((b) => b.banco));
  const gruposFoco = new Set(visibles.filter((v) => bancosFoco.has(v.nombre)).map((v) => v.grupo));
  const porId = new Map(barrasFoco.map((b) => [b.fila.id, b]));
  const enlacesFoco = foco
    ? enlacesTrazabilidad(barrasFoco.filter((b) => !b.especial).map((b) => ({ id: b.fila.id, banco: b.banco, inicio: b.inicio.getTime(), fin: b.fin.getTime() })),
      HUECO_TRAZABILIDAD_DIAS * 86400000)
    : [];
  const muchasFlechas = enlacesFoco.length > MAX_FLECHAS_A_LA_VEZ;
  enlacesActuales.current = traza ? flechasVisibles(enlacesFoco, tarjeta?.barra.fila.id ?? null) : [];

  const ficha = (() => {
    if (!foco) return null;
    // Todo el proyecto (no solo lo visible), con los mismos filtros de área y responsable.
    const todas: FilaEnsayo[] = [];
    const bancos = new Set<string>();
    vista.grupos.forEach((g) => g.bancos.forEach((b) => {
      if (!cumpleFiltro({ ...b, area: g.area }, organizacion)) return;
      (vista.bancos[b.nombre] ?? []).forEach((f) => {
        if (String(f.datos.project || '') !== foco || !filtro(f)) return;
        todas.push(f);
        bancos.add(b.nombre);
      });
    }));
    const contables = todas.filter((f) => f.cerrado || !f.calculo.tipoEspecial);
    const avance = contables.length ? contables.reduce((s, f) => s + (f.cerrado ? 1 : Math.min(1, f.calculo.fraccionAvance || 0)), 0) / contables.length : 0;
    const fines = todas.filter((f) => !f.cerrado && f.calculo.fin).map((f) => f.calculo.fin as string).sort();
    const responsables = [...new Set(todas.map((f) => String(f.datos.resp || '')).filter(Boolean))];
    return {
      ensayos: todas.length,
      bancos: bancos.size,
      completados: todas.filter((f) => f.cerrado).length,
      avance: Math.round(avance * 100),
      fin: fines.length ? fmt(fines[fines.length - 1]) : '\u2014',
      retraso: todas.filter((f) => !f.cerrado && f.calculo.estado === 'delayed').length,
      responsables,
    };
  })();

  // ---- Leyenda de proyectos visibles (los que más barras tienen) ----
  const cuenta = new Map<string, number>();
  visibles.forEach((v) => v.barras.forEach((b) => { if (b.proyecto) cuenta.set(b.proyecto, (cuenta.get(b.proyecto) ?? 0) + 1); }));
  const leyenda = [...cuenta.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));

  const barra = (
    <div className="g-toolbar no-print gm-toolbar">
      <span>Zoom:</span>
      <button className="zbtn" onClick={() => zoomPaso(-1)} title="Alejar">&#8722;</button>
      <span className="zlvl" id="gw-zlvl">{Math.round((W / base) * 100)}%</span>
      <button className="zbtn" onClick={() => zoomPaso(1)} title="Acercar">&#43;</button>
      <button className="zbtn" onClick={() => setAnchoDia(0)} title="Restablecer zoom" style={{ fontSize: 11 }}>100%</button>
      <select className="g-focsel" id="gw-focsel" value={foco} onChange={(e) => setFoco(e.target.value)}
        title="Resalta un proyecto: ficha resumen, sus bancos resaltados y el recorrido entre bancos">
        <option value="">Focus proyecto: (todos)</option>
        {proyectos.map((p) => <option key={p} value={p}>{p.length > 46 ? p.slice(0, 46) + '\u2026' : p}</option>)}
      </select>
      <span className="gm-ctl" data-solo-nueva="">
        <select className="gm-color" value={modoColor} onChange={(e) => setModoColor(e.target.value as ModoColor)}
          title="Qué indica el color de las barras (el estado se ve siempre por la forma)">
          <option value="proyecto">Color: Proyecto</option>
          <option value="estado">Color: Estado</option>
        </select>
        <button className={`gm-traza${foco && traza ? ' on' : ''}`} disabled={!foco} onClick={() => setTraza(!traza)}
          title={foco ? 'Muestra u oculta las flechas del recorrido del proyecto entre bancos' : 'Elige un proyecto en «Focus proyecto» para ver su recorrido entre bancos'}>
          {traza ? '\u2611' : '\u2610'} Trazabilidad
        </button>
      </span>
      <button className="xbtn" onClick={() => void exportarGanttExcel(vista ? matrizGantt(vista, { tipo: 'general', responsable: enResponsable, organizacion }, foco) : null)} title="Exportar este Gantt a un Excel editable">&#128202; Excel</button>
      <span className="hint" style={{ margin: '0 0 0 8px' }}>
        Pasa el rat&oacute;n por una barra para ver el detalle. Arrastra el borde de la 1&ordf; columna para ensancharla.
      </span>
      {ficha && (
        <div className="gm-ficha" data-solo-nueva="">
          <span className="gm-t-punto" style={{ background: colorProyecto(foco) }} />
          <b className="gm-ficha-nombre" title={foco}>{foco}</b>
          <span><b>{ficha.ensayos}</b> ensayos</span>
          <span><b>{ficha.bancos}</b> bancos</span>
          <span><b>{ficha.avance}%</b> completado ({ficha.completados} cerrados)</span>
          <span>Fin previsto: <b>{ficha.fin}</b></span>
          {ficha.retraso > 0 && <span className="gm-ficha-retraso">{ficha.retraso} en retraso</span>}
          {traza && enlacesFoco.length > 0 && (
            <span className="gm-ficha-traza" title="Recorrido deducido de las fechas: cada ensayo viene del último ensayo del proyecto que terminó antes en otro banco (máx. 21 días antes).">
              &#8618; {enlacesFoco.length} {enlacesFoco.length === 1 ? 'paso' : 'pasos'} entre bancos{muchasFlechas ? ' · pasa el ratón por un ensayo para ver su recorrido' : ''}
            </span>
          )}
          {ficha.responsables.length > 0 && <span title={ficha.responsables.join(', ')}>Resp.: {ficha.responsables.slice(0, 3).join(', ')}{ficha.responsables.length > 3 ? ` +${ficha.responsables.length - 3}` : ''}</span>}
          <span className="gm-ficha-cerrar" role="button" tabIndex={0} title="Quitar el foco" onClick={() => setFoco('')}
            onKeyDown={(e) => { if (e.key === 'Enter') setFoco(''); }}>&#10005;</span>
        </div>
      )}
      <div className="gm-leyenda" data-solo-nueva="">
        <span className="gm-ley-estado" title="El estado se ve por la forma de la barra">
          <i className="gm-m gm-hecho" />Completado <i className="gm-m gm-curso" />En curso <i className="gm-m gm-conf" />Confirmado
          <i className="gm-m gm-cola" />En cola <i className="gm-m gm-retraso" />En retraso <i className="gm-m gm-esp" />Bloqueo
        </span>
        {modoColor === 'proyecto' && leyenda.slice(0, MAX_LEYENDA).map(([p]) => (
          <span key={p} className={`gm-chip${foco === p ? ' on' : ''}`} title={`${p} \u00b7 clic para enfocar`} onClick={() => setFoco(foco === p ? '' : p)}>
            <i style={{ background: colorProyecto(p) }} />{p.length > 26 ? p.slice(0, 26) + '\u2026' : p}
          </span>
        ))}
        {modoColor === 'proyecto' && leyenda.length > MAX_LEYENDA && (
          <span className="gm-chip gm-mas" title={leyenda.slice(MAX_LEYENDA).map(([p]) => p).join('\n')}>+{leyenda.length - MAX_LEYENDA} m&aacute;s</span>
        )}
      </div>
    </div>
  );

  // ---- Filas: área › categoría (plegable) › banco con carriles ----
  const nombresAreas = nombresArea(vista);
  const areasVistas = [...new Set(visibles.map((v) => v.area))];
  const plegada = (grupo: string) => (foco && !gruposFoco.has(grupo) ? !abiertasEnFoco.includes(grupo) : plegadas.includes(grupo));
  const alternar = (grupo: string) => {
    if (foco && !gruposFoco.has(grupo)) {
      setAbiertasEnFoco(abiertasEnFoco.includes(grupo) ? abiertasEnFoco.filter((x) => x !== grupo) : [...abiertasEnFoco, grupo]);
    } else {
      setPlegadas(plegadas.includes(grupo) ? plegadas.filter((x) => x !== grupo) : [...plegadas, grupo]);
    }
  };
  const fondoSemanas = `repeating-linear-gradient(to right, transparent 0, transparent ${7 * W - 1}px, rgba(255,255,255,.045) ${7 * W - 1}px, rgba(255,255,255,.045) ${7 * W}px)`;

  const filas: ReactElement[] = [];
  let areaActual = '', grupoActual = '';
  visibles.forEach((v) => {
    if (v.area !== areaActual) {
      areaActual = v.area;
      grupoActual = '';
      if (areasVistas.length > 1) {
        filas.push(
          <div key={'area:' + v.area} className="g-area" data-area={v.area} data-solo-nueva="">
            <div className="g-label">{nombresAreas[v.area]}</div>
            <div className="g-track" style={{ width: n * W, height: 'auto' }} />
          </div>,
        );
      }
    }
    const estaPlegada = plegada(v.grupo);
    if (v.grupo !== grupoActual) {
      grupoActual = v.grupo;
      const delGrupo = visibles.filter((x) => x.grupo === v.grupo);
      const sinFoco = !!foco && !gruposFoco.has(v.grupo);
      filas.push(
        <div key={'cat:' + v.grupo} className={`g-cat${sinFoco ? ' gm-sinfoco' : ''}`} data-categoria={v.grupo} data-solo-nueva=""
          onClick={() => alternar(v.grupo)} title={estaPlegada ? 'Desplegar' : 'Plegar'}>
          <div className="g-label" style={{ borderLeftColor: v.colorGrupo }}>
            <span className="gm-flecha">{estaPlegada ? '\u25B8' : '\u25BE'}</span>
            <span className="gm-cat-nombre" style={{ color: v.colorGrupo }}>{v.nombreGrupo}</span>
            <span className="gm-cat-info">
              {' \u00b7 '}{delGrupo.length} {delGrupo.length === 1 ? 'banco' : 'bancos'}
              {sinFoco ? ' \u00b7 sin ensayos del proyecto' : ''}{estaPlegada ? ' (plegado)' : ''}
            </span>
          </div>
          <div className="g-track" style={{ width: n * W }} />
        </div>,
      );
    }

    const tramos = v.barras.map((b) => ({ id: b.fila.id, ...tramoVisible(b.inicio, b.fin, min, n)! }));
    const { carril, carriles } = asignarCarriles(tramos);
    const paralelo = maximoSimultaneo(tramos);
    const ocupacion = ocupacionPeriodo(tramos.filter((_, i) => !v.barras[i].especial), n);
    const alto = carriles * ALTO_CARRIL + MARGEN_FILA * 2 - (ALTO_CARRIL - ALTO_BARRA);
    const altoFila = Math.max(26, alto);
    const usa = !!foco && bancosFoco.has(v.nombre);

    const elementos: ReactElement[] = [];
    if (anchoPasado > 0) elementos.push(<div key="pasado" className="gm-pasado" style={{ width: anchoPasado }} />);
    v.barras.forEach((b, i) => {
      const f = b.fila, d = f.datos, c = f.calculo;
      const t = tramos[i];
      const izquierda = t.d0 * W;
      const ancho = Math.max((t.d1 - t.d0 + 1) * W - 2, 6);
      const top = MARGEN_FILA + carril[f.id] * ALTO_CARRIL;
      const comprometido = !b.cerrado && !!d.confirmedStart && !b.especial;
      const texto = b.cerrado
        ? '\u2713 ' + (d.project || d.type || '')
        : (d.project || d.type || 'test') + (d.type ? ' \u00b7 ' + d.type : '') + (d.comments ? ' \u00b7 ' + d.comments : '');
      // El texto de la 4.9.4 se conserva en data-titulo (pruebas de paridad); la tarjeta lo sustituye.
      const titulo = b.cerrado
        ? `${v.nombre} \u00b7 Completed \u00b7 ${d.project || ''} ${d.type || ''}`
        : `${v.nombre} \u00b7 ${b.especial ? `${d.type} (bench blocked)` : etiquetaEstado(c.estado)} \u00b7 ${d.project || ''} ${d.type || ''} \u00b7 ${fmt(b.inicio)}\u2192${fmt(b.fin)}`;
      const fondo = b.especial ? undefined
        : b.cerrado ? rgba(b.color, 0.38)
          : b.estado === 'queued' ? rgba(b.color, 0.16)
            : b.color;
      elementos.push(
        <div key={f.id} data-gid={f.id} className={`g-bar ${b.especial ? 'striped' : 'solid'}${comprometido ? ' haloq' : ''}${claseFoco(b.proyecto)} ${forma(b)}`}
          data-proj={d.project || ''} data-titulo={titulo} data-estado={b.estado}
          style={{ left: izquierda, width: ancho, top, height: ALTO_BARRA, ['--c' as string]: b.color, ...(fondo ? { background: fondo } : {}) }}
          onMouseEnter={(e) => {
            const r = e.currentTarget.getBoundingClientRect();
            setTarjeta({ barra: b, x: e.clientX, y: e.clientY, arriba: r.top, abajo: r.bottom });
          }}
          onMouseLeave={() => setTarjeta(null)}
          onClick={() => abrirEnsayo(b)}>
          <span className="g-lab" style={{ fontSize: 10 }}>{texto}</span>
        </div>,
      );
      if (comprometido && zoom) {
        const centro = top + ALTO_BARRA / 2;
        const cs = dia(d.confirmedStart);
        if (cs && cs >= min && cs <= max) elementos.push(<div key={f.id + 's'} className="g-circ s" style={{ left: diasEntre(min, cs) * W + W / 2, top: centro, borderColor: colorEstado(c.estado) }} />);
        const ce = dia(d.confirmedEnd);
        if (ce && ce >= min && ce <= max) elementos.push(<div key={f.id + 'e'} className="g-circ e" style={{ left: diasEntre(min, ce) * W + W / 2, top: centro, borderColor: colorEstado(c.estado) }} />);
      }
    });

    filas.push(
      <div key={v.nombre} className={`g-row gm-row${usa ? ' gm-usa' : ''}${foco && !usa ? ' gm-nousa' : ''}${estaPlegada ? ' gm-oculta' : ''}`}
        data-banco={v.nombre} style={{ height: altoFila }}>
        <div className="g-label gm-label" style={{ height: altoFila }}>
          <div className="gm-l1">
            <span className="dot" style={{ background: v.colorGrupo, width: 8, height: 8, borderRadius: '50%', display: 'inline-block', marginRight: 6, flex: 'none' }} />
            <span className="g-bname">{v.nombre}</span>
            <span className="gm-ocup" data-solo-nueva="" title={`Ocupación del periodo visible: ${ocupacion}% de los días con algún ensayo (sin bloqueos).`}>
              <i><b style={{ width: `${ocupacion}%`, background: ocupacion >= 90 ? 'var(--danger)' : ocupacion >= 70 ? 'var(--warn)' : 'var(--turq)' }} /></i>{ocupacion}%
            </span>
          </div>
          {paralelo > 1 && <span className="gm-paralelo" data-solo-nueva="" title="Ensayos que coinciden en el tiempo en este banco">{paralelo} en paralelo</span>}
        </div>
        <div className="g-track" style={{ width: n * W, height: altoFila, backgroundImage: fondoSemanas }}>{elementos}</div>
      </div>,
    );
  });

  const meses: { etiqueta: string; dias: number }[] = [];
  for (let i = 0; i < n;) {
    const m = dias[i].getMonth();
    let sp = 0;
    while (i + sp < n && dias[i + sp].getMonth() === m) sp++;
    meses.push({ etiqueta: dias[i].toLocaleDateString('en-GB', { month: 'short', year: '2-digit' }), dias: sp });
    i += sp;
  }
  const semanas: { cw: number; dias: number }[] = [];
  for (let i = 0; i < n; i += 7) semanas.push({ cw: semanaIso(dias[i]), dias: Math.min(7, n - i) });

  const empezarAnchoEtiqueta = (e: EventoRaton) => {
    e.preventDefault();
    const x0 = e.clientX, w0 = LW;
    const mover = (ev: MouseEvent) => setAnchoEtiqueta(Math.max(110, Math.min(640, Math.round(w0 + ev.clientX - x0))));
    const soltar = () => { document.removeEventListener('pointermove', mover); document.removeEventListener('pointerup', soltar); };
    document.addEventListener('pointermove', mover);
    document.addEventListener('pointerup', soltar);
  };

  const idTarjeta = tarjeta?.barra.fila.id;
  const conFoco = !!foco && !!tarjeta && tarjeta.barra.proyecto === foco;

  return (
    <>
      {barra}
      <div className={`gantt g-mini gm gm-alto-libre${foco ? ' gm-foco' : ''}${modoColor === 'estado' ? ' gm-color-estado' : ''}`} id="ganttbox" ref={caja}
        style={{ ['--lw' as string]: `${LW}px`, position: 'relative' }}>
        <div className="g-lwgrip no-print" style={{ left: LW - 4 }} onPointerDown={empezarAnchoEtiqueta}
          onDoubleClick={() => setAnchoEtiqueta(null)} />
        <div ref={interior} style={{ width: LW + n * W, position: 'relative' }}>
          <div className="g-months"><div className="g-label" />{meses.map((m, i) => <div key={i} className="g-month" style={{ width: m.dias * W, minWidth: m.dias * W }}>{m.etiqueta}</div>)}</div>
          <div className="g-months"><div className="g-label" />{semanas.map((s, i) => <div key={i} className="g-month" style={{ width: s.dias * W, minWidth: s.dias * W }}>CW{s.cw}</div>)}</div>
          {zoom && (
            <div className="g-days"><div className="g-label" />{dias.map((d) => (
              <div key={d.getTime()} className={`g-day ${esFinDeSemana(d) ? 'we' : ''} ${festivos.has(iso(d)) ? 'hol' : ''} ${d.getTime() === hoy.getTime() ? 'today' : ''}`}
                style={{ width: W, minWidth: W }}>{'MTWTFSS'[(d.getDay() + 6) % 7]}<br />{d.getDate()}</div>
            ))}</div>
          )}
          {filas}
          {desplazamientoHoy >= 0 && <div className="g-todayline" style={{ left: LW + desplazamientoHoy }} />}
          {trazos.length > 0 && (
            <svg className="gm-trazas no-print" data-solo-nueva="" width={LW + n * W} height="100%" aria-hidden="true">
              <defs>
                <marker id="gm-punta" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M0,0 L10,5 L0,10 z" fill="#f2ecff" />
                </marker>
              </defs>
              {trazos.map((t) => <path key={t.clave} d={t.d} markerEnd="url(#gm-punta)" />)}
            </svg>
          )}
        </div>
      </div>
      <BarraHorizontalFlotante caja={caja} />
      {tarjeta && (
        <TarjetaEnsayo datos={tarjeta} conFoco={conFoco}
          origenes={conFoco ? enlacesFoco.filter((e) => e.a === idTarjeta).map((e) => porId.get(e.de)!).filter(Boolean) : []}
          destinos={conFoco ? enlacesFoco.filter((e) => e.de === idTarjeta).map((e) => porId.get(e.a)!).filter(Boolean) : []} />
      )}
    </>
  );
}

/** Cabeceras fijas al hacer scroll vertical (stickyHeaders de la 4.9.4). */
export function fijarCabeceras(caja: HTMLElement | null) {
  const interior = caja?.firstElementChild?.nextElementSibling ?? caja?.firstElementChild;
  if (!caja || !interior) return;
  caja.classList.add('stickyhdr');
  let arriba = 0;
  [...interior.children].forEach((fila) => {
    if (!(fila instanceof HTMLElement) || !(fila.classList.contains('g-months') || fila.classList.contains('g-days'))) return;
    fila.style.top = arriba + 'px';
    arriba += fila.offsetHeight || 24;
  });
}
