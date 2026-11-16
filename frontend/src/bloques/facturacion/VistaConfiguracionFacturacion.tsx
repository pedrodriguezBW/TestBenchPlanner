import { useEffect, useMemo, useState } from 'react';
import { apiFacturacion, type ConfiguracionFacturacion, type TipoEquipo } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';

/*
 * Billing › Configuration (solo Admin): una única tabla de Equipment Types por
 * año (lista TestBenchEquipmentTypes): Equipment Type, Group (columna de la hoja
 * mensual), With HV, Without HV, Units y Billing Rule. Qué Equipment Type usa
 * cada banco se indica en Configuration › Test Benches.
 */

const estiloEntrada = { background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 6px', fontSize: 12 };

function numeroONulo(texto: string): number | null {
  if (texto.trim() === '') return null;
  const n = Number(texto.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

export function VistaConfiguracionFacturacion() {
  const { mostrarMensaje } = usePlanificador();
  const [datos, setDatos] = useState<ConfiguracionFacturacion | null>(null);
  const [tipos, setTipos] = useState<TipoEquipo[]>([]);
  const [cambiado, setCambiado] = useState(false);
  const [guardando, setGuardando] = useState(false);
  const [anio, setAnio] = useState<number>(new Date().getFullYear());
  const [error, setError] = useState<string | null>(null);

  const cargar = () => {
    setError(null);
    apiFacturacion.tiposEquipo().then((d) => {
      setDatos(d);
      setTipos(d.tipos);
      setCambiado(false);
      const anios = [...new Set(d.tipos.map((t) => t.anio))].sort((a, b) => a - b);
      if (anios.length && !anios.includes(anio)) setAnio(anios.includes(new Date().getFullYear()) ? new Date().getFullYear() : anios[anios.length - 1]);
    }).catch((e: Error) => setError(e.message));
  };
  useEffect(cargar, []); // eslint-disable-line react-hooks/exhaustive-deps

  const anios = useMemo(() => [...new Set([...tipos.map((t) => t.anio), anio])].sort((a, b) => a - b), [tipos, anio]);
  const delAnio = tipos.map((t, i) => ({ t, i })).filter((x) => x.t.anio === anio);
  const ultimoAnio = tipos.length ? Math.max(...tipos.map((t) => t.anio)) : null;
  const grupos = [...new Set([...(datos?.grupos ?? []), ...tipos.map((t) => t.grupo).filter(Boolean)])];

  if (error) return <div className="card"><h3 style={{ marginTop: 0 }}>Billing &middot; Configuration</h3><div className="fac-error">{error}</div></div>;
  if (!datos) return <div className="empty">Cargando&hellip;</div>;

  const cambiar = (nuevos: TipoEquipo[]) => { setTipos(nuevos); setCambiado(true); };
  const poner = (i: number, x: Partial<TipoEquipo>) => cambiar(tipos.map((t, j) => (j === i ? { ...t, ...x } : t)));
  const bancosDe = (titulo: string) => datos.bancos.filter((b) => b.equipmentType && b.equipmentType.toLowerCase() === titulo.trim().toLowerCase()).map((b) => b.nombre);
  const nombresDelAnio = new Set(delAnio.map((x) => x.t.titulo.trim().toLowerCase()));
  const sinTipo = datos.bancos.filter((b) => !b.equipmentType).map((b) => b.nombre);
  const desconocidos = datos.bancos.filter((b) => b.equipmentType && !nombresDelAnio.has(b.equipmentType.toLowerCase()));

  const nuevoAnio = () => {
    const destino = ultimoAnio === null ? anio : ultimoAnio + 1;
    const copia = ultimoAnio === null ? [] : tipos.filter((t) => t.anio === ultimoAnio).map((t) => ({ ...t, anio: destino }));
    cambiar([...tipos, ...copia]);
    setAnio(destino);
  };
  const borrarAnio = () => {
    if (!window.confirm(`¿Borrar todos los Equipment Types de ${anio}? Los meses de ${anio} quedarán sin tarifa.`)) return;
    cambiar(tipos.filter((t) => t.anio !== anio));
  };
  const guardar = async () => {
    setGuardando(true);
    try {
      const d = await apiFacturacion.guardarTiposEquipo(tipos);
      setDatos(d);
      setTipos(d.tipos);
      setCambiado(false);
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    } finally {
      setGuardando(false);
    }
  };

  return (
    <div>
      <div className="fac-head">
        <h2>&#128182; Billing &middot; Configuration</h2>
        <span className="sub">Equipment Types por año (lista TestBenchEquipmentTypes de SharePoint)</span>
        <div className="fac-acciones">
          <button className="btn ghost" disabled={!cambiado || guardando} onClick={cargar}>Descartar</button>
          <button className="btn" id="fac-guardar-tipos" disabled={!cambiado || guardando || !!datos.error} onClick={() => void guardar()}>
            {guardando ? 'Guardando…' : 'Guardar cambios'}</button>
        </div>
      </div>
      {datos.error && <div className="fac-error" id="fac-error-lista">{datos.error}
        <div className="fac-nota" style={{ marginTop: 6 }}>Hay que crear en el sitio la lista <b>TestBenchEquipmentTypes</b> con las columnas
          EquipmentGroup (texto), Year (número), WithoutHV (número), WithHV (número), Units (texto) y BillingRule (texto).</div></div>}

      <div className="card">
        <div className="fac-filtros">
          <span>Año</span>
          <select id="fac-anio" value={anio} onChange={(e) => setAnio(Number(e.target.value))}>
            {anios.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
          <button className="btn ghost sm" id="fac-nuevo-anio" onClick={nuevoAnio}>
            &#10133; Año {ultimoAnio === null ? anio : ultimoAnio + 1}{ultimoAnio === null ? '' : ` (copia de ${ultimoAnio})`}</button>
          {delAnio.length > 0 && <button className="btn ghost sm" onClick={borrarAnio}>&#128465; Borrar {anio}</button>}
          <span className="fac-nota" style={{ marginLeft: 'auto' }}>Se aplica la tarifa del año del mes facturado. Vacío = no tiene esa tarifa.
            Product Line STC &rarr; coste 0; proyecto 0 &rarr; coste 0 (reglas del Excel).</span>
        </div>
        <div className="fac-tabla-wrap">
          <table className="fac" id="fac-tipos" data-anio={anio}>
            <thead><tr><th>Equipment Type</th><th>Group</th><th className="n">With HV</th><th className="n">Without HV</th><th>Units</th>
              <th>Billing Rule</th><th>Benches</th><th /></tr></thead>
            <tbody>{delAnio.map(({ t, i }) => (
              <tr key={i} data-tipo={t.titulo}>
                <td><input style={{ ...estiloEntrada, width: 190 }} value={t.titulo} data-campo="titulo" onChange={(e) => poner(i, { titulo: e.target.value })} /></td>
                <td><input style={{ ...estiloEntrada, width: 120 }} list="fac-grupos" value={t.grupo} data-campo="grupo" onChange={(e) => poner(i, { grupo: e.target.value })} /></td>
                <td className="n"><input type="number" step="0.01" min="0" style={{ ...estiloEntrada, width: 80 }} data-campo="conHv"
                  value={t.conHv ?? ''} onChange={(e) => poner(i, { conHv: numeroONulo(e.target.value) })} /></td>
                <td className="n"><input type="number" step="0.01" min="0" style={{ ...estiloEntrada, width: 80 }} data-campo="sinHv"
                  value={t.sinHv ?? ''} onChange={(e) => poner(i, { sinHv: numeroONulo(e.target.value) })} /></td>
                <td><select style={estiloEntrada} value={t.unidad} data-campo="unidad" onChange={(e) => poner(i, { unidad: e.target.value as TipoEquipo['unidad'] })}>
                  <option value="HORAS">Hours</option><option value="PIEZAS">Pieces</option></select></td>
                <td><select style={estiloEntrada} value={t.regla} data-campo="regla" onChange={(e) => {
                  const r = datos.reglas.find((x) => x.codigo === e.target.value);
                  poner(i, { regla: e.target.value, ...(r && r.codigo === 'PER_PIECE' ? { unidad: 'PIEZAS' as const } : {}) });
                }}>{datos.reglas.map((r) => <option key={r.codigo} value={r.codigo}>{r.nombre}</option>)}</select></td>
                <td className="fac-nota">{bancosDe(t.titulo).join(', ') || '\u2014'}</td>
                <td><button className="icbtn del" title="Borrar Equipment Type de este año" onClick={() => {
                  const usados = bancosDe(t.titulo);
                  if (usados.length && !window.confirm(`«${t.titulo}» lo usan ${usados.join(', ')}. Sus tareas de ${anio} quedarán sin tarifa. ¿Borrarlo?`)) return;
                  cambiar(tipos.filter((_, j) => j !== i));
                }}>&#128465;</button></td>
              </tr>
            ))}</tbody>
          </table>
          <datalist id="fac-grupos">{grupos.map((g) => <option key={g} value={g} />)}</datalist>
        </div>
        <button className="btn ghost sm" id="fac-nuevo-tipo" style={{ marginTop: 8 }}
          onClick={() => cambiar([...tipos, { titulo: '', grupo: '', anio, sinHv: null, conHv: null, unidad: 'HORAS', regla: 'LINEAR' }])}>
          &#10133; Equipment Type</button>
        {!delAnio.length && <div className="empty">No hay Equipment Types en {anio}.</div>}
        <div className="fac-nota" style={{ marginTop: 8 }}>Group es la columna de la hoja mensual (Shakers, Climático, HVCH (STC)&hellip;).
          Qué Equipment Type usa cada banco se indica en <b>Configuration &rsaquo; Test Benches</b>; si renombras uno, vuelve a asignarlo allí.</div>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <h3 style={{ marginTop: 0 }}>Bancos</h3>
        <div id="fac-bancos-sin-tipo"><b>Sin Equipment Type:</b> {sinTipo.length ? sinTipo.join(', ') : 'ninguno.'}</div>
        {desconocidos.length > 0 && <div className="fac-aviso" id="fac-bancos-desconocidos" style={{ marginTop: 6 }}>
          Equipment Type que no existe en {anio}: {desconocidos.map((b) => `${b.nombre} → «${b.equipmentType}»`).join(', ')}</div>}
        <div className="fac-nota" style={{ marginTop: 6 }}>Sus tareas salen en la vista previa de la hoja mensual, pero no se exportan.</div>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <h3 style={{ marginTop: 0 }}>Billing Rules</h3>
        <table className="fac" id="fac-reglas">
          <thead><tr><th>Regla</th><th>Nombre</th><th>Cálculo</th><th>Unidad</th></tr></thead>
          <tbody>{datos.reglas.map((r) => (
            <tr key={r.codigo}><td><b>{r.codigo}</b></td><td>{r.nombre}</td><td>{r.calculo}</td><td>{r.unidad === 'PIEZAS' ? 'Pieces' : 'Hours'}</td></tr>
          ))}</tbody>
        </table>
        <div className="fac-nota" style={{ marginTop: 6 }}>Horas totales = horas netas del mes + setup (el setup, en el mes en que empieza la tarea).
          En los Equipment Types por pieza, las piezas se escriben en la columna Setup de la tarea.</div>
      </div>
    </div>
  );
}
