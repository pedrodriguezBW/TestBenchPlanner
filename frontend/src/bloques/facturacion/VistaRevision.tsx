import { useEffect, useMemo, useState } from 'react';
import { apiFacturacion, type LineaFacturacion, type MaestrosFacturacion } from '../../api/facturacion';
import { AccionesPeriodo, CabeceraPeriodo } from './AccionesPeriodo';
import { usePeriodoElegido, useVistaPeriodo } from './estadoPeriodo';
import { ChipLinea, PanelLinea } from './PanelLinea';
import { SelectorPeriodo } from './SelectorPeriodo';
import { ESTADO_LINEA, euros, fechaCorta, horas, hvTexto, tarifaTexto } from './textos';

/*
 * Billing › Review: líneas del periodo con filtros, estados (texto e icono) e
 * incidencias; al elegir una línea se abre su panel de detalle.
 */

interface Filtros {
  estado: string; planta: string; proyecto: string; orden: string; requester: string; banco: string; categoria: string; hv: string;
  abierto: string; facturable: string; ajustes: boolean; incidencias: boolean; texto: string;
}
const SIN_FILTROS: Filtros = { estado: '', planta: '', proyecto: '', orden: '', requester: '', banco: '', categoria: '', hv: '', abierto: '', facturable: '',
  ajustes: false, incidencias: false, texto: '' };

/** Filtro que llega desde el Overview (fila de un resumen). */
let filtroPendiente: { lineas: string[]; etiqueta: string } | null = null;
export function verLineasEnRevision(lineas: string[], etiqueta: string) {
  filtroPendiente = { lineas, etiqueta };
}

export function VistaRevision() {
  const [id, setId] = usePeriodoElegido();
  const { vista, ocupado, ejecutar, cargando } = useVistaPeriodo(id);
  const [filtros, setFiltros] = useState<Filtros>(SIN_FILTROS);
  const [soloLineas, setSoloLineas] = useState<{ lineas: string[]; etiqueta: string } | null>(() => { const f = filtroPendiente; filtroPendiente = null; return f; });
  const [elegida, setElegida] = useState<string | null>(null);
  const [maestros, setMaestros] = useState<MaestrosFacturacion | null>(null);

  useEffect(() => { apiFacturacion.maestros().then((m) => setMaestros(m.maestros)).catch(() => undefined); }, []);
  useEffect(() => { setElegida(null); }, [id]);

  const lineas = vista?.periodo.lineas ?? [];
  const opciones = useMemo(() => {
    const unicos = (f: (l: LineaFacturacion) => string) => [...new Set(lineas.map(f).filter(Boolean))].sort((a, b) => a.localeCompare(b));
    return {
      planta: unicos((l) => l.organizacion), proyecto: unicos((l) => l.aplicado?.proyecto ?? ''), orden: unicos((l) => l.aplicado?.orden ?? ''),
      requester: unicos((l) => l.aplicado?.requester ?? ''), banco: unicos((l) => l.banco), categoria: unicos((l) => l.aplicado?.categoria ?? ''),
    };
  }, [lineas]);

  const visibles = lineas.filter((l) => {
    const a = l.aplicado;
    if (soloLineas && !soloLineas.lineas.includes(l.id)) return false;
    if (filtros.estado && l.estado !== filtros.estado) return false;
    if (filtros.planta && l.organizacion !== filtros.planta) return false;
    if (filtros.proyecto && a?.proyecto !== filtros.proyecto) return false;
    if (filtros.orden && a?.orden !== filtros.orden) return false;
    if (filtros.requester && a?.requester !== filtros.requester) return false;
    if (filtros.banco && l.banco !== filtros.banco) return false;
    if (filtros.categoria && a?.categoria !== filtros.categoria) return false;
    if (filtros.hv && a?.hv !== filtros.hv) return false;
    if (filtros.abierto === 'abierto' && !l.abierto) return false;
    if (filtros.abierto === 'cerrado' && l.abierto) return false;
    if (filtros.facturable === 'facturable' && l.exclusion) return false;
    if (filtros.facturable === 'excluido' && !l.exclusion) return false;
    if (filtros.ajustes && !Object.keys(l.ajustes || {}).length) return false;
    if (filtros.incidencias && !l.incidencias.length) return false;
    if (filtros.texto) {
      const t = `${l.ensayoId} ${l.proyectoTexto} ${l.tipoEnsayo} ${l.banco} ${a?.orden ?? ''}`.toLowerCase();
      if (!t.includes(filtros.texto.toLowerCase())) return false;
    }
    return true;
  });
  const linea = lineas.find((l) => l.id === elegida) ?? null;
  const total = visibles.filter((l) => !l.exclusion && !l.aplazamiento && !l.pendiente).reduce((s, l) => s + (l.aplicado?.coste ?? 0), 0);
  const sel = (k: keyof Filtros, lista: string[], etiqueta: string, nombres?: Record<string, string>) => (
    <select data-filtro={k} value={String(filtros[k])} onChange={(e) => setFiltros({ ...filtros, [k]: e.target.value })} title={etiqueta}>
      <option value="">{etiqueta}</option>
      {lista.map((v) => <option key={v} value={v}>{nombres?.[v] ?? v}</option>)}
    </select>
  );

  return (
    // Con el panel de detalle abierto se deja sitio a la derecha: la tabla y las acciones siguen visibles.
    <div style={{ paddingRight: linea ? 'min(570px, 45vw)' : 0 }}>
      <div className="view-head" style={{ gap: 10 }}>
        <h2>&#128182; Billing &middot; Review</h2>
        <SelectorPeriodo id={id} onCambio={setId} />
      </div>
      {!id && <div className="empty">Crea un periodo en Periods.</div>}
      {id && !vista && <div className="empty">{cargando ? 'Cargando\u2026' : 'No se ha podido cargar el periodo.'}</div>}
      {vista && (
        <>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap' }}>
            <div style={{ flex: 1 }}><CabeceraPeriodo vista={vista} /></div>
            <AccionesPeriodo vista={vista} ejecutar={ejecutar} ocupado={ocupado} />
          </div>
          {vista.alertas.length > 0 && <div className="fac-aviso">&#9888; {vista.alertas.length} ensayo(s) han cambiado después de aprobar el periodo. La facturación aprobada
            no se recalcula: un Admin decide si reabre el periodo o incorpora una corrección posterior.</div>}
          <div className="fac-filtros no-print">
            {sel('estado', Object.keys(ESTADO_LINEA), 'Estado')}
            {sel('planta', opciones.planta, 'Planta')}
            {sel('proyecto', opciones.proyecto, 'Proyecto')}
            {sel('orden', opciones.orden, 'Orden')}
            {sel('requester', opciones.requester, 'Requester')}
            {sel('banco', opciones.banco, 'Equipo')}
            {sel('categoria', opciones.categoria, 'Categoría')}
            {sel('hv', ['yes', 'no'], 'HV', { yes: 'HV: Sí', no: 'HV: No' })}
            {sel('abierto', ['abierto', 'cerrado'], 'Abierto / cerrado', { abierto: 'Ensayo abierto', cerrado: 'Ensayo cerrado' })}
            {sel('facturable', ['facturable', 'excluido'], 'Facturable / excluido', { facturable: 'Facturable', excluido: 'Excluido' })}
            <label><input type="checkbox" data-filtro="ajustes" checked={filtros.ajustes} onChange={(e) => setFiltros({ ...filtros, ajustes: e.target.checked })} /> Con ajustes</label>
            <label><input type="checkbox" data-filtro="incidencias" checked={filtros.incidencias} onChange={(e) => setFiltros({ ...filtros, incidencias: e.target.checked })} /> Con incidencias</label>
            <input placeholder="Buscar test, proyecto…" value={filtros.texto} onChange={(e) => setFiltros({ ...filtros, texto: e.target.value })} />
            <button className="btn ghost sm" onClick={() => { setFiltros(SIN_FILTROS); setSoloLineas(null); }}>Quitar filtros</button>
            {soloLineas && <span className="fac-chip">Solo: {soloLineas.etiqueta} &#10005;</span>}
            <span className="fac-nota" style={{ marginLeft: 'auto' }}>{visibles.length} de {lineas.length} líneas &middot; suma {euros(total)}</span>
          </div>
          <div className="fac-tabla-wrap">
            <table className="fac" id="fac-lineas">
              <thead><tr><th>Estado</th><th>Test</th><th>Tramo</th><th>Proyecto</th><th>Orden</th><th>Banco</th><th>Categoría</th><th>HV</th>
                <th className="n">Horas netas</th><th className="n">Setup</th><th className="n">Tarifa</th><th className="n">Coste</th></tr></thead>
              <tbody>
                {visibles.map((l) => {
                  const a = l.aplicado;
                  return (
                    <tr key={l.id} data-linea={l.id} className={`clic${l.id === elegida ? ' sel' : ''}`} onClick={() => setElegida(l.id)}
                      title={l.incidencias.map((i) => i.mensaje).join('\n')}>
                      <td><ChipLinea estado={l.estado} />{l.incidencias.some((i) => i.nivel === 'BLOQUEANTE') && l.estado !== 'BLOCKED' ? ' \u26D4' : ''}</td>
                      <td><b>{l.ensayoId}</b><div className="fac-nota">{l.tipoEnsayo}</div></td>
                      <td className="nw">{l.tramoInicio ? `${fechaCorta(l.tramoInicio)}–${fechaCorta(l.tramoFin)}` : 'sin arrancar'}{l.origen === 'APLAZADA' ? <div className="fac-nota">del periodo anterior</div> : null}</td>
                      <td className="recorte" title={l.proyectoTexto}>{a?.proyecto || l.proyectoTexto || '\u2014'}</td>
                      <td>{a?.orden || '\u2014'}</td>
                      <td>{l.banco}</td>
                      <td>{a?.categoria || '\u2014'}</td>
                      <td>{hvTexto(a?.hv)}</td>
                      <td className="n">{a ? horas(a.horasNetas) : '\u2014'}</td>
                      <td className="n">{a ? horas(a.horasSetup) : '\u2014'}</td>
                      <td className="n">{a ? tarifaTexto(a.tarifa, a.unidad) : '\u2014'}</td>
                      <td className="n">{a ? euros(a.coste) : '\u2014'}</td>
                    </tr>
                  );
                })}
                {!visibles.length && <tr><td colSpan={12}><div className="empty">No hay líneas con estos filtros.</div></td></tr>}
              </tbody>
            </table>
          </div>
          {linea && <PanelLinea linea={linea} vista={vista} maestros={maestros} ejecutar={ejecutar} ocupado={ocupado} onCerrar={() => setElegida(null)} />}
        </>
      )}
    </div>
  );
}
