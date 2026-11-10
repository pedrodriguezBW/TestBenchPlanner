import { useEffect, useState } from 'react';
import { ErrorApi } from '../../api/cliente';
import { apiFacturacion, type EventoAuditoria, type MaestrosFacturacion, type Tarifa, type VistaMaestros } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';
import { euros, fechaHora } from './textos';

/*
 * Billing › Configuration (solo Admin): proyectos y órdenes, mapeo de bancos y
 * categorías, tarifas versionadas, reglas de cálculo (se asignan, no se
 * escriben fórmulas), parámetros de Controlling y auditoría. Se edita una copia
 * y se guarda todo junto; el backend valida y registra el cambio.
 */

type Apartado = 'projects' | 'mappings' | 'rates' | 'rules' | 'controlling' | 'audit';
const APARTADOS: [Apartado, string][] = [['projects', 'Projects and orders'], ['mappings', 'Equipment mappings'], ['rates', 'Rates'],
  ['rules', 'Calculation rules'], ['controlling', 'Controlling'], ['audit', 'Audit']];

const input = { background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 6px', fontSize: 12 };

export function VistaConfiguracionFacturacion() {
  const { mostrarMensaje, avisar, soloLectura } = usePlanificador();
  const [datos, setDatos] = useState<VistaMaestros | null>(null);
  const [m, setM] = useState<MaestrosFacturacion | null>(null);
  const [cambiado, setCambiado] = useState(false);
  const [apartado, setApartado] = useState<Apartado>('projects');
  const [buscar, setBuscar] = useState('');
  const [pegado, setPegado] = useState('');
  const [categoriaTarifas, setCategoriaTarifas] = useState('');
  const [auditoria, setAuditoria] = useState<EventoAuditoria[]>([]);
  const [guardando, setGuardando] = useState(false);

  const cargar = () => apiFacturacion.maestros().then((d) => { setDatos(d); setM(structuredClone(d.maestros)); setCambiado(false); })
    .catch((e: Error) => mostrarMensaje(e.message));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void cargar(); }, []);
  useEffect(() => { if (apartado === 'audit') apiFacturacion.auditoria().then(setAuditoria).catch(() => undefined); }, [apartado]);

  if (!datos || !m) return <div className="empty">Cargando&hellip;</div>;
  const cambiar = (c: Partial<MaestrosFacturacion>) => { setM({ ...m, ...c }); setCambiado(true); };
  const guardar = async () => {
    setGuardando(true);
    try {
      const d = await apiFacturacion.guardarMaestros(m, datos.version);
      setDatos(d); setM(structuredClone(d.maestros)); setCambiado(false);
      avisar('Maestros de facturación guardados');
    } catch (e) {
      if (e instanceof ErrorApi && e.estado === 409) { mostrarMensaje(`${e.message}`); void cargar(); } else mostrarMensaje(e instanceof Error ? e.message : String(e));
    } finally {
      setGuardando(false);
    }
  };
  const orgDe = (b: string) => datos.bancos.find((x) => x.nombre === b)?.organizacion ?? '';
  const categorias = m.categorias.map((c) => c.codigo);

  const importarPegado = () => {
    const proyectos = structuredClone(m.proyectos);
    let n = 0;
    pegado.split(/\r?\n/).map((l) => l.split(/[;\t]/).map((x) => x.trim())).filter((c) => c[0]).forEach(([numero, nombre, orden, requester, linea]) => {
      let p = proyectos.find((x) => x.numero === numero.replace(/^0+/, ''));
      if (!p) { p = { numero: numero.replace(/^0+/, ''), nombre: nombre ?? '', requester: requester ?? '', lineaProducto: linea ?? '', activo: true, desde: '', hasta: '', ordenes: [] }; proyectos.push(p); }
      if (orden && !p.ordenes.some((o) => o.codigo === orden)) { p.ordenes.push({ codigo: orden, descripcion: '', activa: true }); n++; }
    });
    cambiar({ proyectos });
    setPegado('');
    avisar(`${n} orden(es) añadidas; revisa y guarda`);
  };

  const tarifas = m.tarifas.map((t, i) => ({ t, i })).filter(({ t }) => !categoriaTarifas || t.categoria === categoriaTarifas);
  const ponerTarifa = (i: number, c: Partial<Tarifa>) => cambiar({ tarifas: m.tarifas.map((t, j) => (j === i ? { ...t, ...c } : t)) });

  return (
    <div>
      <div className="view-head" style={{ gap: 10 }}>
        <h2>&#128182; Billing &middot; Configuration</h2>
        <span className="fac-nota">Revisión {m.revision}{datos.guardados ? '' : ' · valores iniciales del Excel (aún no guardados)'}</span>
        <div className="fac-acciones">
          {cambiado && <span className="fac-aviso">Cambios sin guardar</span>}
          <button className="btn ghost" disabled={!cambiado || guardando} onClick={() => void cargar()}>Descartar</button>
          <button className="btn ed" id="fac-guardar-maestros" disabled={!cambiado || guardando || soloLectura} onClick={() => void guardar()}>Guardar cambios</button>
        </div>
      </div>
      <div className="hint">Solo un Admin mantiene estos maestros. Los cambios no alteran periodos aprobados (guardan su propia foto de tarifas y categorías).</div>
      <div className="fac-pestanas">
        {APARTADOS.map(([k, t]) => <button key={k} data-apartado={k} className={apartado === k ? 'activa' : ''} onClick={() => setApartado(k)}>{t}</button>)}
      </div>

      {apartado === 'projects' && (
        <div className="card">
          <div className="fac-filtros">
            <input placeholder="Buscar proyecto u orden…" value={buscar} onChange={(e) => setBuscar(e.target.value)} />
            <button className="btn ghost sm ed" onClick={() => cambiar({ proyectos: [{ numero: '', nombre: '', requester: '', lineaProducto: '', activo: true, desde: '', hasta: '', ordenes: [] }, ...m.proyectos] })}>&#10133; Proyecto</button>
            <span className="fac-nota" style={{ marginLeft: 'auto' }}>{m.proyectos.length} proyectos &middot; un proyecto puede tener varias órdenes; si tiene una, se precarga.</span>
          </div>
          <div className="fac-tabla-wrap">
            <table className="fac" id="fac-maestro-proyectos">
              <thead><tr><th>Número</th><th>Nombre</th><th>Requester</th><th>Línea de producto</th><th>Órdenes (separadas por ;)</th><th>Desde</th><th>Hasta</th><th>Activo</th><th /></tr></thead>
              <tbody>{m.proyectos.map((p, i) => ({ p, i })).filter(({ p }) => !buscar || `${p.numero} ${p.nombre} ${p.ordenes.map((o) => o.codigo).join(' ')}`.toLowerCase().includes(buscar.toLowerCase()))
                .slice(0, 300).map(({ p, i }) => {
                  const poner = (c: Partial<typeof p>) => cambiar({ proyectos: m.proyectos.map((x, j) => (j === i ? { ...x, ...c } : x)) });
                  return (
                    <tr key={i}>
                      <td><input style={{ ...input, width: 80 }} value={p.numero} onChange={(e) => poner({ numero: e.target.value })} /></td>
                      <td><input style={{ ...input, width: 220 }} value={p.nombre} onChange={(e) => poner({ nombre: e.target.value })} /></td>
                      <td><input style={{ ...input, width: 100 }} value={p.requester} onChange={(e) => poner({ requester: e.target.value })} /></td>
                      <td><input style={{ ...input, width: 100 }} value={p.lineaProducto} onChange={(e) => poner({ lineaProducto: e.target.value })} /></td>
                      <td><input style={{ ...input, width: 260 }} value={p.ordenes.map((o) => o.codigo).join('; ')}
                        onChange={(e) => poner({ ordenes: e.target.value.split(';').map((x) => x.trim()).filter(Boolean).map((codigo) => p.ordenes.find((o) => o.codigo === codigo) ?? { codigo, descripcion: '', activa: true }) })} /></td>
                      <td><input type="date" style={input} value={p.desde} onChange={(e) => poner({ desde: e.target.value })} /></td>
                      <td><input type="date" style={input} value={p.hasta} onChange={(e) => poner({ hasta: e.target.value })} /></td>
                      <td><input type="checkbox" checked={p.activo !== false} onChange={(e) => poner({ activo: e.target.checked })} /></td>
                      <td><button className="icbtn del ed" title="Borrar" onClick={() => cambiar({ proyectos: m.proyectos.filter((_, j) => j !== i) })}>&#128465;</button></td>
                    </tr>
                  );
                })}</tbody>
            </table>
          </div>
          <details style={{ marginTop: 10 }}>
            <summary>Pegar proyectos y órdenes</summary>
            <div className="fac-nota">Una línea por orden: número; nombre; orden; requester; línea de producto (separadas por ; o tabulador).</div>
            <textarea className="fac-campo" rows={4} style={{ width: '100%' }} value={pegado} onChange={(e) => setPegado(e.target.value)} />
            <button className="btn ghost sm ed" disabled={!pegado.trim()} onClick={importarPegado}>Añadir</button>
          </details>
        </div>
      )}

      {apartado === 'mappings' && (
        <div className="fac-grid2">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Banco &rarr; categoría de facturación</h3>
            <div className="fac-nota">La categoría económica depende del banco y, si se indica, del tipo de ensayo (gana el tipo concreto). Sin mapeo, las líneas del banco quedan bloqueadas.</div>
            <button className="btn ghost sm ed" style={{ margin: '6px 0' }} onClick={() => cambiar({ mapeos: [...m.mapeos, { id: `M-${Date.now()}`, banco: datos.bancos[0]?.nombre ?? '', tipoEnsayo: '', categoria: categorias[0] ?? '', regla: '', activo: true }] })}>&#10133; Mapeo</button>
            <table className="fac" id="fac-maestro-mapeos">
              <thead><tr><th>Banco</th><th>Tipo de ensayo</th><th>Categoría</th><th>Planta</th><th>Unidad</th><th>Regla</th><th>Activo</th><th /></tr></thead>
              <tbody>{m.mapeos.map((x, i) => {
                const poner = (c: Partial<typeof x>) => cambiar({ mapeos: m.mapeos.map((y, j) => (j === i ? { ...y, ...c } : y)) });
                const cat = m.categorias.find((c) => c.codigo === x.categoria);
                return (
                  <tr key={x.id || i}>
                    <td><select style={input} value={x.banco} onChange={(e) => poner({ banco: e.target.value })}>{datos.bancos.map((b) => <option key={b.nombre}>{b.nombre}</option>)}</select></td>
                    <td><input style={{ ...input, width: 130 }} list="fac-tipos" placeholder="(cualquiera)" value={x.tipoEnsayo} onChange={(e) => poner({ tipoEnsayo: e.target.value })} /></td>
                    <td><select style={input} value={x.categoria} onChange={(e) => poner({ categoria: e.target.value })}>{categorias.map((c) => <option key={c}>{c}</option>)}</select></td>
                    <td>{orgDe(x.banco) || '\u2014'}</td>
                    <td>{cat?.unidad === 'PIEZAS' ? 'Piezas' : 'Horas'}</td>
                    <td><select style={input} value={x.regla} onChange={(e) => poner({ regla: e.target.value })}>
                      <option value="">De la categoría ({cat?.regla ?? '\u2014'})</option>{datos.reglas.map((r) => <option key={r.codigo} value={r.codigo}>{r.codigo}</option>)}</select></td>
                    <td><input type="checkbox" checked={x.activo !== false} onChange={(e) => poner({ activo: e.target.checked })} /></td>
                    <td><button className="icbtn del ed" onClick={() => cambiar({ mapeos: m.mapeos.filter((_, j) => j !== i) })}>&#128465;</button></td>
                  </tr>
                );
              })}</tbody>
            </table>
            <datalist id="fac-tipos">{datos.tiposEnsayo.map((t) => <option key={t} value={t} />)}</datalist>
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Categorías de facturación</h3>
            <button className="btn ghost sm ed" style={{ margin: '6px 0' }} onClick={() => cambiar({ categorias: [...m.categorias, { codigo: 'Nueva categoría', grupo: '', unidad: 'H', regla: 'LINEAR', ordenControlling: 99, activa: true, nota: '' }] })}>&#10133; Categoría</button>
            <table className="fac" id="fac-maestro-categorias">
              <thead><tr><th>Categoría (Equipo)</th><th>Tipo</th><th>Unidad</th><th>Regla</th><th>Orden Controlling</th><th>Activa</th><th>Nota</th></tr></thead>
              <tbody>{m.categorias.map((c, i) => {
                const poner = (x: Partial<typeof c>) => cambiar({ categorias: m.categorias.map((y, j) => (j === i ? { ...y, ...x } : y)) });
                return (
                  <tr key={i}>
                    <td><input style={{ ...input, width: 170 }} value={c.codigo} onChange={(e) => poner({ codigo: e.target.value })} /></td>
                    <td><input style={{ ...input, width: 100 }} value={c.grupo} onChange={(e) => poner({ grupo: e.target.value })} /></td>
                    <td><select style={input} value={c.unidad} onChange={(e) => poner({ unidad: e.target.value as 'H' | 'PIEZAS' })}><option value="H">Horas</option><option value="PIEZAS">Piezas</option></select></td>
                    <td><select style={input} value={c.regla} onChange={(e) => poner({ regla: e.target.value })}>{datos.reglas.map((r) => <option key={r.codigo}>{r.codigo}</option>)}</select></td>
                    <td><input type="number" style={{ ...input, width: 60 }} value={c.ordenControlling} onChange={(e) => poner({ ordenControlling: Number(e.target.value) })} /></td>
                    <td><input type="checkbox" checked={c.activa !== false} onChange={(e) => poner({ activa: e.target.checked })} /></td>
                    <td><input style={{ ...input, width: 220 }} value={c.nota} onChange={(e) => poner({ nota: e.target.value })} /></td>
                  </tr>
                );
              })}</tbody>
            </table>
          </div>
        </div>
      )}

      {apartado === 'rates' && (
        <div className="card">
          <div className="fac-filtros">
            <select value={categoriaTarifas} onChange={(e) => setCategoriaTarifas(e.target.value)}><option value="">Todas las categorías</option>{['*', ...categorias].map((c) => <option key={c}>{c}</option>)}</select>
            <button className="btn ghost sm ed" onClick={() => cambiar({ tarifas: [...m.tarifas, { id: `T-${Date.now()}`, categoria: categoriaTarifas || categorias[0] || '', proyecto: '', planta: '', lineaProducto: '', hv: 'no', importe: '', unidad: 'H', desde: '', hasta: '', nota: '' }] })}>&#10133; Tarifa</button>
            <span className="fac-nota" style={{ marginLeft: 'auto' }}>Se aplica la vigente el día 20 del periodo. Jerarquía: proyecto &rarr; planta o línea de producto &rarr; general (categoría y HV). Para cambiar un precio, cierra la tarifa (Hasta) y crea una versión nueva.</span>
          </div>
          <div className="fac-tabla-wrap">
            <table className="fac" id="fac-maestro-tarifas">
              <thead><tr><th>Id</th><th>Categoría</th><th>Proyecto</th><th>Planta</th><th>Línea</th><th>HV</th><th className="n">Importe</th><th>Desde</th><th>Hasta</th><th>Nota</th><th /></tr></thead>
              <tbody>{tarifas.map(({ t, i }) => (
                <tr key={t.id || i}>
                  <td className="fac-nota">{t.id}</td>
                  <td><select style={input} value={t.categoria} onChange={(e) => ponerTarifa(i, { categoria: e.target.value })}>{['*', ...categorias].map((c) => <option key={c}>{c}</option>)}</select></td>
                  <td><input style={{ ...input, width: 70 }} value={t.proyecto} onChange={(e) => ponerTarifa(i, { proyecto: e.target.value })} /></td>
                  <td><select style={input} value={t.planta} onChange={(e) => ponerTarifa(i, { planta: e.target.value })}><option value="">&mdash;</option><option>BW</option><option>CTAG</option></select></td>
                  <td><input style={{ ...input, width: 80 }} value={t.lineaProducto} onChange={(e) => ponerTarifa(i, { lineaProducto: e.target.value })} /></td>
                  <td><select style={input} value={t.hv} onChange={(e) => ponerTarifa(i, { hv: e.target.value as Tarifa['hv'] })}><option value="no">No</option><option value="yes">Sí</option><option value="*">*</option></select></td>
                  <td className="n"><input type="number" step="0.01" style={{ ...input, width: 80 }} value={t.importe} onChange={(e) => ponerTarifa(i, { importe: e.target.value === '' ? '' : Number(e.target.value) })} /></td>
                  <td><input type="date" style={input} value={t.desde} onChange={(e) => ponerTarifa(i, { desde: e.target.value })} /></td>
                  <td><input type="date" style={input} value={t.hasta} onChange={(e) => ponerTarifa(i, { hasta: e.target.value })} /></td>
                  <td><input style={{ ...input, width: 200 }} value={t.nota} onChange={(e) => ponerTarifa(i, { nota: e.target.value })} /></td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn ghost sm ed" title="Nueva versión: copia la tarifa para una vigencia nueva" onClick={() => cambiar({ tarifas: [...m.tarifas, { ...t, id: `T-${Date.now()}`, desde: '', hasta: '', nota: `Nueva versión de ${t.id}` }] })}>Versión</button>
                    <button className="icbtn del ed" onClick={() => cambiar({ tarifas: m.tarifas.filter((_, j) => j !== i) })}>&#128465;</button>
                  </td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}

      {apartado === 'rules' && (
        <div className="card">
          <table className="fac" id="fac-maestro-reglas">
            <thead><tr><th>Regla</th><th>Nombre</th><th>Cálculo</th><th>Unidad</th><th>Categorías que la usan</th></tr></thead>
            <tbody>{datos.reglas.map((r) => (
              <tr key={r.codigo}><td><b>{r.codigo}</b></td><td>{r.nombre}</td><td>{r.descripcion}</td><td>{r.unidad === 'PIEZAS' ? 'Piezas' : 'Horas'}</td>
                <td>{m.categorias.filter((c) => c.regla === r.codigo).map((c) => c.codigo).join(', ') || '\u2014'}</td></tr>
            ))}</tbody>
          </table>
          <div className="fac-nota" style={{ marginTop: 6 }}>Las fórmulas no se editan como texto: se asigna una regla implementada y validada (en Equipment mappings). La regla reductora se aplica a cada línea mensual antes de agrupar.</div>
        </div>
      )}

      {apartado === 'controlling' && (
        <div className="fac-grid2">
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Parámetros de Controlling</h3>
            <div className="fac-form">
              {([['cctrOld', 'CCtr (Old)'], ['costElement', 'Cost Element'], ['moneda', 'Moneda (Crcy)'], ['unidadHoras', 'UM conceptos por horas'], ['unidadPiezas', 'UM conceptos por piezas']] as [keyof MaestrosFacturacion['controlling'], string][]).map(([k, t]) => (
                <label key={k} style={{ display: 'contents' }}><span style={{ color: 'var(--muted)', fontSize: 13 }}>{t}</span>
                  <input data-control={k} value={m.controlling[k]} onChange={(e) => cambiar({ controlling: { ...m.controlling, [k]: e.target.value } })} /></label>
              ))}
              <span style={{ color: 'var(--muted)', fontSize: 13 }}>Text</span><span>Nombre del equipo (categoría), editable por línea en Review</span>
              <span style={{ color: 'var(--muted)', fontSize: 13 }}>Columnas</span><span className="fac-nota">Equipo, Orden, Suma de Coste total, Sum of Horas totales, CCtr (Old), Order (old), Oper. (old), Cost Element, Amount, Crcy, CCtr (new), Order (new), Oper. (new), Total Qty, UM, Pers.No., Text</span>
              <label style={{ display: 'contents' }}><span style={{ color: 'var(--muted)', fontSize: 13 }}>Umbral de horas altas (h)</span>
                <input type="number" value={m.parametros.umbralHorasAltas} onChange={(e) => cambiar({ parametros: { ...m.parametros, umbralHorasAltas: Number(e.target.value) } })} /></label>
            </div>
          </div>
          <div className="card">
            <h3 style={{ marginTop: 0 }}>Setup recomendado</h3>
            <div className="fac-nota">Se precarga en la ficha del ensayo; el usuario lo confirma o lo cambia. Gana la regla más concreta (banco &gt; tipo &gt; categoría).</div>
            <button className="btn ghost sm ed" style={{ margin: '6px 0' }} onClick={() => cambiar({ setupRecomendado: [...m.setupRecomendado, { categoria: '', banco: '', tipoEnsayo: '', horas: 0 }] })}>&#10133; Setup</button>
            <table className="fac">
              <thead><tr><th>Categoría</th><th>Banco</th><th>Tipo de ensayo</th><th className="n">Horas</th><th /></tr></thead>
              <tbody>{m.setupRecomendado.map((s, i) => {
                const poner = (c: Partial<typeof s>) => cambiar({ setupRecomendado: m.setupRecomendado.map((y, j) => (j === i ? { ...y, ...c } : y)) });
                return (
                  <tr key={i}>
                    <td><select style={input} value={s.categoria} onChange={(e) => poner({ categoria: e.target.value })}><option value="">(cualquiera)</option>{categorias.map((c) => <option key={c}>{c}</option>)}</select></td>
                    <td><select style={input} value={s.banco} onChange={(e) => poner({ banco: e.target.value })}><option value="">(cualquiera)</option>{datos.bancos.map((b) => <option key={b.nombre}>{b.nombre}</option>)}</select></td>
                    <td><input style={{ ...input, width: 120 }} list="fac-tipos2" value={s.tipoEnsayo} onChange={(e) => poner({ tipoEnsayo: e.target.value })} /></td>
                    <td className="n"><input type="number" step="0.25" style={{ ...input, width: 70 }} value={s.horas} onChange={(e) => poner({ horas: e.target.value === '' ? '' : Number(e.target.value) })} /></td>
                    <td><button className="icbtn del ed" onClick={() => cambiar({ setupRecomendado: m.setupRecomendado.filter((_, j) => j !== i) })}>&#128465;</button></td>
                  </tr>
                );
              })}</tbody>
            </table>
            <datalist id="fac-tipos2">{datos.tiposEnsayo.map((t) => <option key={t} value={t} />)}</datalist>
          </div>
        </div>
      )}

      {apartado === 'audit' && (
        <div className="card">
          {!auditoria.length ? <div className="empty">Sin eventos de auditoría.</div> : (
            <div className="fac-tabla-wrap">
              <table className="fac" id="fac-auditoria-global">
                <thead><tr><th>Fecha</th><th>Usuario</th><th>Rol</th><th>Acción</th><th>Periodo</th><th>Línea</th><th>Detalle</th><th className="n">Antes</th><th className="n">Después</th><th>Justificación</th></tr></thead>
                <tbody>{auditoria.map((e) => (
                  <tr key={e.id}><td>{fechaHora(e.en)}</td><td>{e.por}</td><td>{e.rol}</td><td>{e.accion}</td><td>{e.periodo}</td><td>{e.linea}</td><td>{e.detalle}</td>
                    <td className="n">{euros(e.costeAntes)}</td><td className="n">{euros(e.costeDespues)}</td><td>{e.justificacion}</td></tr>
                ))}</tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
