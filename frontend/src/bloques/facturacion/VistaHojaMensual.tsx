import { useEffect, useMemo, useRef, useState } from 'react';
import { apiFacturacion, descargarArchivo, euros, NIVELES_HOJA, type HojaMensual, type TablaHoja } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';
import { fmt } from '../../utilidades/fechas';
import { usePreferencia } from '../../utilidades/preferencias';

/*
 * Billing › Monthly sheet: la hoja del mes como la tabla dinámica del Excel. El mes de
 * facturación es un periodo del día 21 del mes anterior al día 20 del mes; Inicio y Fin
 * son el tramo de cada tarea dentro de ese periodo.
 * Filas: los campos elegidos, siempre en el orden Billing Order → Planta → Tipo →
 * Equipo → HV → Inicio → Fin → Coste/hora; columnas: el Group de cada Equipment
 * Type y «Total general». Cada fila con detalle se pliega y despliega (⊟ / ⊞).
 * La vista previa incluye las tareas a las que les falta Equipment Type, HV o
 * tarifa (marcadas); la exportación (Excel o HTML) solo lleva las completas.
 */

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];
const num = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(2));
const estiloSelect = { background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6, padding: '5px 8px', fontSize: 13 };

function sumarMeses(mes: string, n: number) {
  const a = Number(mes.slice(0, 4));
  const m = Number(mes.slice(5, 7)) - 1 + n;
  const anio = a + Math.floor(m / 12);
  return `${anio}-${String(((m % 12) + 12) % 12 + 1).padStart(2, '0')}`;
}

/** Periodo de facturación del mes: del 21 del mes anterior al 20 del mes (HojaMensual.inicioPeriodo / finPeriodo). */
function periodoDelMes(mes: string): [string, string] {
  return [`${sumarMeses(mes, -1)}-21`, `${mes}-20`];
}
const fmtPeriodo = (iso: string) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`;

function TablaMensual({ tabla, id, plegadas, onPlegar }: {
  tabla: TablaHoja; id: string; plegadas: Set<string>; onPlegar: (clave: string) => void;
}) {
  if (!tabla.filas.length) return <div className="empty">No hay nada que facturar.</div>;
  const total = tabla.niveles.length;
  // Clave de cada fila = camino de etiquetas; una fila se oculta si algún antecesor está plegado.
  const camino: string[] = [];
  const filas = tabla.filas.map((f) => {
    if (f.nivel < total) { camino.length = f.nivel; camino.push(f.etiqueta); }
    const clave = f.nivel < total ? camino.join('\u0001') : '__total__';
    const oculta = f.nivel < total && camino.slice(0, -1).some((_, i) => plegadas.has(camino.slice(0, i + 1).join('\u0001')));
    return { f, clave, oculta };
  });
  return (
    <table className="fac fac-hoja" id={id}>
      <thead><tr><th>{tabla.niveles.length ? tabla.niveles.join(' \u203a ') : 'Etiquetas de fila'}</th>
        {tabla.columnas.map((c) => <th key={c} className="n">{c}</th>)}<th className="n">Total general</th></tr></thead>
      <tbody>{filas.filter((x) => !x.oculta).map(({ f, clave }) => {
        const esTotal = f.nivel >= total;
        const conHijos = !esTotal && f.nivel < total - 1;
        return (
          <tr key={clave} className={`${esTotal ? 'total' : `nivel${Math.min(f.nivel, 2)}`}${f.incompleta ? ' incompleta' : ''}`} data-nivel={f.nivel}>
            <td style={{ paddingLeft: 8 + (esTotal ? 0 : f.nivel) * 18 }}>
              {conHijos
                ? <button className="fac-plegar" title={plegadas.has(clave) ? 'Desplegar' : 'Plegar'} onClick={() => onPlegar(clave)}>
                  {plegadas.has(clave) ? '\u229e' : '\u229f'}</button>
                : <span className="fac-plegar-hueco" />}
              {f.incompleta && <span title="Incluye tareas incompletas: no se exportan">&#9888; </span>}{f.etiqueta}
            </td>
            {tabla.columnas.map((c) => <td key={c} className="n">{euros(f.valores[c])}</td>)}
            <td className="n">{euros(f.total)}</td>
          </tr>
        );
      })}</tbody>
    </table>
  );
}

export function VistaHojaMensual() {
  const { vista, mostrarMensaje } = usePlanificador();
  const [mes, setMes] = useState(() => (vista?.hoy ?? new Date().toISOString()).slice(0, 7));
  const [niveles, setNiveles] = usePreferencia<string[]>('tbp_facturacion_niveles', NIVELES_HOJA.map((n) => n.id));
  const [hoja, setHoja] = useState<HojaMensual | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cargando, setCargando] = useState(false);
  const [comoExportada, setComoExportada] = useState(false);
  const [plegadas, setPlegadas] = useState<Set<string>>(new Set());
  const elegidos = useMemo(() => NIVELES_HOJA.map((n) => n.id).filter((id) => niveles.includes(id)), [niveles]);

  // Solo cuenta la respuesta de la última petición (al marcar varios campos seguidos, una respuesta lenta no pisa a la siguiente).
  const peticion = useRef(0);
  const nivelesActuales = useRef(niveles);
  nivelesActuales.current = niveles;
  const cambiarNivel = (id: string, marcado: boolean) => {
    const actual = nivelesActuales.current;
    const nuevo = marcado ? [...actual.filter((x) => x !== id), id] : actual.filter((x) => x !== id);
    nivelesActuales.current = nuevo;
    setNiveles(nuevo);
  };
  const cargar = () => {
    const n = ++peticion.current;
    setCargando(true);
    setError(null);
    apiFacturacion.hoja(mes, elegidos)
      .then((h) => { if (n === peticion.current) setHoja(h); })
      .catch((e: Error) => { if (n === peticion.current) { setHoja(null); setError(e.message); } })
      .finally(() => { if (n === peticion.current) setCargando(false); });
  };
  useEffect(cargar, [mes, elegidos.join(',')]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => setPlegadas(new Set()), [mes, elegidos.join(',')]);

  const exportar = async (formato: 'excel' | 'html') => {
    if (hoja && hoja.incompletas > 0
      && !window.confirm(`${hoja.incompletas} tarea(s) incompletas no saldrán en el archivo (les falta Equipment Type, HV o tarifa). ¿Exportar igualmente?`)) return;
    try {
      const r = await apiFacturacion.exportar(mes, formato, elegidos);
      descargarArchivo(r.filename, r.b64, formato === 'html' ? 'text/html'
        : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };

  const tabla = hoja ? (comoExportada ? hoja.exportable : hoja.vistaPrevia) : null;
  /** Pliega todas las filas de un nivel (y por tanto las más profundas quedan ocultas). */
  const plegarHasta = (nivel: number | null) => {
    if (!tabla || nivel === null) { setPlegadas(new Set()); return; }
    const claves = new Set<string>();
    const camino: string[] = [];
    tabla.filas.forEach((f) => {
      if (f.nivel >= tabla.niveles.length) return;
      camino.length = f.nivel; camino.push(f.etiqueta);
      if (f.nivel === nivel) claves.add(camino.join('\u0001'));
    });
    setPlegadas(claves);
  };
  const alternar = (clave: string) => setPlegadas((p) => { const n = new Set(p); if (n.has(clave)) n.delete(clave); else n.add(clave); return n; });

  const incompletas = hoja?.lineas.filter((l) => !l.completa) ?? [];
  const totalExportable = hoja?.exportable.filas.find((f) => f.nivel >= hoja.exportable.niveles.length)?.total ?? 0;
  const anioActual = Number((vista?.hoy ?? new Date().toISOString()).slice(0, 4));
  const anios = Array.from(new Set([...Array.from({ length: anioActual - 2023 + 2 }, (_, i) => 2023 + i), Number(mes.slice(0, 4))])).sort();

  return (
    <div>
      <div className="fac-head">
        <h2>&#128182; Billing &middot; Monthly sheet</h2>
        <div className="fac-selector-mes">
          <button className="btn ghost sm" id="fac-mes-anterior" title="Mes anterior" onClick={() => setMes(sumarMeses(mes, -1))}>&#9664;</button>
          <select id="fac-mes-nombre" style={estiloSelect} value={Number(mes.slice(5, 7))}
            onChange={(e) => setMes(`${mes.slice(0, 4)}-${String(e.target.value).padStart(2, '0')}`)}>
            {MESES.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
          </select>
          <select id="fac-mes-anio" style={estiloSelect} value={Number(mes.slice(0, 4))} onChange={(e) => setMes(`${e.target.value}-${mes.slice(5, 7)}`)}>
            {anios.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
          <button className="btn ghost sm" id="fac-mes-siguiente" title="Mes siguiente" onClick={() => setMes(sumarMeses(mes, 1))}>&#9654;</button>
        </div>
        <span className="fac-periodo" id="fac-periodo" title="El mes de facturación va del día 21 del mes anterior al día 20 del mes">
          Periodo {fmtPeriodo(periodoDelMes(mes)[0])} &ndash; {fmtPeriodo(periodoDelMes(mes)[1])}</span>
        <button className="btn ghost sm" onClick={cargar} disabled={cargando}>&#8635; Recalcular</button>
        <div className="fac-acciones">
          <button className="btn ghost" id="fac-exportar-html" disabled={!hoja} onClick={() => void exportar('html')}>&#11015; HTML</button>
          <button className="btn" id="fac-exportar-excel" disabled={!hoja} onClick={() => void exportar('excel')}>&#11015; Excel</button>
        </div>
      </div>

      <div className="card fac-campos" id="fac-campos">
        <span className="fac-nota">Filas:</span>
        {NIVELES_HOJA.map((n, i) => (
          <label key={n.id} className={niveles.includes(n.id) ? 'activo' : ''}>
            <input type="checkbox" data-nivel={n.id} checked={niveles.includes(n.id)}
              onChange={(e) => cambiarNivel(n.id, e.target.checked)} />
            {n.nombre}{i < NIVELES_HOJA.length - 1 && <span className="fac-flecha"> &rsaquo;</span>}
          </label>
        ))}
        <button className="btn ghost sm" onClick={() => setNiveles(NIVELES_HOJA.map((n) => n.id))}>Todos</button>
      </div>

      {error && <div className="fac-error" id="fac-error-hoja">{error}</div>}
      {cargando && !hoja && <div className="empty">Calculando&hellip;</div>}
      {hoja && tabla && (
        <>
          <div className="fac-resumen" id="fac-resumen">
            <span><b>{hoja.lineas.length}</b> tarea(s) en el mes</span>
            <span className={incompletas.length ? 'aviso' : ''}><b>{incompletas.length}</b> incompleta(s), no se exportan</span>
            <span>Total exportable: <b>{euros(totalExportable)}</b></span>
          </div>
          <div className="card">
            <div className="fac-filtros">
              <label><input type="checkbox" id="fac-como-exportada" checked={comoExportada} onChange={(e) => setComoExportada(e.target.checked)} />
                Ver solo lo que se exporta</label>
              {tabla.niveles.length > 1 && (
                <label>Mostrar hasta
                  <select id="fac-plegar-nivel" value="" onChange={(e) => plegarHasta(e.target.value === 'todo' ? null : Number(e.target.value))}>
                    <option value="">&hellip;</option>
                    {tabla.niveles.slice(0, -1).map((n, i) => <option key={n} value={i}>{n}</option>)}
                    <option value="todo">Todo desplegado</option>
                  </select>
                </label>
              )}
              <span className="fac-nota" style={{ marginLeft: 'auto' }}>Las filas con &#9888; incluyen tareas incompletas.</span>
            </div>
            <div className="fac-tabla-wrap">
              <TablaMensual tabla={tabla} id="fac-tabla-mes" plegadas={plegadas} onPlegar={alternar} />
            </div>
          </div>

          {incompletas.length > 0 && (
            <div className="card" style={{ marginTop: 12 }}>
              <h3 style={{ marginTop: 0 }}>&#9888; Incompletas (no se exportan)</h3>
              <table className="fac" id="fac-incompletas">
                <thead><tr><th>Bench</th><th>Project</th><th>Test type</th><th>Responsible</th><th>Falta</th></tr></thead>
                <tbody>{incompletas.map((l) => (
                  <tr key={`${l.banco}|${l.ensayoId}`}><td>{l.banco}</td><td>{l.proyecto || '\u2014'}</td><td>{l.tipoEnsayo || '\u2014'}</td>
                    <td>{l.requester || '\u2014'}</td><td className="fac-error-texto">{l.faltas.join(' · ')}</td></tr>
                ))}</tbody>
              </table>
            </div>
          )}

          <details className="fac-plegable" style={{ marginTop: 12 }}>
            <summary>Detalle de tareas ({hoja.lineas.length})</summary>
            <div className="fac-tabla-wrap">
              <table className="fac" id="fac-lineas">
                <thead><tr><th>Bench</th><th>Project</th><th>Responsible</th><th>Product Line</th><th>Billing Order</th><th>Equipment Type</th>
                  <th>HV</th><th title="Tramo de la tarea dentro del periodo">Inicio</th><th title="Tramo de la tarea dentro del periodo">Fin</th>
                  <th>Tarea (completa)</th><th className="n">Horas netas</th><th className="n">Setup</th><th className="n">Horas totales / piezas</th>
                  <th className="n">Coste/h</th><th className="n">Coste total</th><th>Avisos</th></tr></thead>
                <tbody>{hoja.lineas.map((l) => (
                  <tr key={`${l.banco}|${l.ensayoId}`} className={l.completa ? '' : 'incompleta'}>
                    <td>{l.banco}</td><td>{l.proyecto || '\u2014'}</td><td>{l.requester || '\u2014'}</td><td>{l.productLine || '\u2014'}</td>
                    <td>{l.orden || '\u2014'}</td><td>{l.equipmentType || '\u2014'}</td><td>{l.hv === 'yes' ? 'Sí' : l.hv === 'no' ? 'No' : '\u2014'}</td>
                    <td>{fmt(l.inicio)}</td><td>{fmt(l.fin)}</td><td className="fac-nota">{fmt(l.inicioTarea)} &rarr; {fmt(l.finTarea)}</td><td className="n">{num(l.horasNetas)}</td><td className="n">{num(l.horasSetup)}</td>
                    <td className="n">{num(l.cantidad)}{l.unidad === 'PIEZAS' ? ' pz' : ''}</td><td className="n">{euros(l.tarifa)}</td>
                    <td className="n">{euros(l.coste)}</td><td className="fac-nota">{[...l.faltas, ...l.avisos].join(' · ')}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </details>
        </>
      )}
    </div>
  );
}
