import { useState } from 'react';
import { api } from '../../api/cliente';
import type { Recurso } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';
import { porTipo, useRecursos } from '../../estado/Recursos';

/*
 * 🔧 Equipments List (renderResourcesCfg de la 4.9.4, solo Admin): catálogo de
 * equipos auxiliares por tipo. Cada cambio guarda el catálogo completo, como
 * resSaveCatalog. Renombrar un equipo cambia también su nombre en todos los
 * ensayos que lo usan (primero los ensayos y después el catálogo).
 */

/** resSlug: clave del tipo a partir de su nombre, con un sufijo aleatorio. */
const clave = (s: string) => `${(s || 'tipo').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}_${Math.random().toString(36).slice(2, 5)}`;

export function ListaEquipos() {
  const { recargar: recargarPlan, mostrarMensaje, soloLectura } = usePlanificador();
  const { recursos, guardarCatalogo } = useRecursos();
  const [nuevoTipo, setNuevoTipo] = useState('');
  const [colorTipo, setColorTipo] = useState('#F2A64B');
  const [nuevos, setNuevos] = useState<Record<string, string>>({});
  // Fuerza a volver a pintar las cajas con el valor guardado cuando un cambio se rechaza.
  const [version, setVersion] = useState(0);
  const repintar = () => setVersion((v) => v + 1);

  const grupos = porTipo(recursos);
  const guardar = (lista: Recurso[]) => { if (!soloLectura) void guardarCatalogo(lista); };
  const busca = (nombre: string) => recursos.find((r) => r.name === nombre);

  const anadirTipo = () => {
    const v = nuevoTipo.trim();
    if (!v) return;
    guardar([...recursos, { name: `${v} 1`, groupKey: clave(v), groupName: v, dot: colorTipo || '#F2A64B', code: '', org: 'BW', seq: recursos.length + 1 }]);
    setNuevoTipo('');
  };
  const delTipo = (k: string) => (r: Recurso) => (r.groupKey || 'otros') === k;
  const renombrarTipo = (k: string, v: string) => {
    v = v.trim();
    if (!v) return;
    guardar(recursos.map((r) => (delTipo(k)(r) ? { ...r, groupName: v } : r)));
  };
  const colorDeTipo = (k: string, v: string) => guardar(recursos.map((r) => (delTipo(k)(r) ? { ...r, dot: v } : r)));
  const borrarTipo = (k: string) => {
    const n = recursos.filter(delTipo(k)).length;
    if (!window.confirm(`Borrar el tipo y sus ${n} equipo(s)?`)) return;
    guardar(recursos.filter((r) => !delTipo(k)(r)));
  };
  const anadirEquipo = (k: string) => {
    const v = (nuevos[k] || '').trim();
    if (!v) return;
    if (busca(v)) { window.alert(`Ya existe un recurso "${v}".`); return; }
    const modelo = recursos.find(delTipo(k)) || { groupName: k, dot: '#8CA0AD' };
    guardar([...recursos, { name: v, groupKey: k, groupName: modelo.groupName || k, dot: modelo.dot || '#8CA0AD', code: '', org: 'BW', seq: recursos.length + 1 }]);
    setNuevos({ ...nuevos, [k]: '' });
  };
  const renombrarEquipo = async (anterior: string, v: string) => {
    if (soloLectura) { repintar(); return; }
    v = v.trim();
    if (!v || v === anterior) { repintar(); return; }
    if (busca(v)) { window.alert(`Ya existe "${v}".`); repintar(); return; }
    try {
      await api.renombrarRecurso(anterior, v);
      await recargarPlan();
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
      repintar();
      return;
    }
    guardar(recursos.map((r) => (r.name === anterior ? { ...r, name: v } : r)));
  };
  const codigoEquipo = (nombre: string, v: string) => {
    v = v.trim();
    if (v) {
      const dup = recursos.find((r) => r.code === v && r.name !== nombre);
      if (dup) { window.alert(`El código "${v}" ya lo usa "${dup.name}". Debe ser único.`); repintar(); return; }
    }
    guardar(recursos.map((r) => (r.name === nombre ? { ...r, code: v } : r)));
  };
  const orgEquipo = (nombre: string, v: string) => guardar(recursos.map((r) => (r.name === nombre ? { ...r, org: v } : r)));
  const borrarEquipo = (nombre: string) => {
    if (!window.confirm(`Borrar el recurso "${nombre}"?`)) return;
    guardar(recursos.filter((r) => r.name !== nombre));
  };

  return (
    <div className="sec" style={{ maxWidth: 1100 }} id="lista-equipos">
      <h2>&#128296; Equipments List</h2>
      <div className="hint">
        Catálogo de equipos auxiliares (cilindros hidráulicos, generadores de pulsos y, en el futuro, adquisidores de señal, osciloscopios,
        fuentes de alimentación…). Se organizan por <b>tipo de equipo</b> (configurable). Cada recurso tiene un <b>código de equipo único</b> y
        pertenece a <b>BW</b> o <b>CTAG</b>.
      </div>
      <div className="tools">
        <input className="f" id="newrestype" disabled={soloLectura} placeholder="Nuevo tipo de equipo..." style={{ minWidth: 240 }} value={nuevoTipo} onChange={(e) => setNuevoTipo(e.target.value)} />
        <input type="color" id="newrestypecol" disabled={soloLectura} value={colorTipo} onChange={(e) => setColorTipo(e.target.value)}
          style={{ width: 44, height: 36, padding: 0, border: '1px solid var(--line)', borderRadius: 6, background: 'var(--navy)' }} />
        <button className="btn sm" disabled={soloLectura} onClick={anadirTipo}>&#65291; Añadir tipo</button>
      </div>
      {!grupos.length && <div className="hint">Aún no hay tipos. Crea el primero (por ejemplo «Cilindros hidráulicos»).</div>}
      {grupos.map(({ tipo, equipos }) => (
        <div className="cfg-cat" key={tipo.key + version} data-tipo={tipo.name}>
          <div className="catrow">
            <input type="color" disabled={soloLectura} defaultValue={tipo.dot} onChange={(e) => colorDeTipo(tipo.key, e.target.value)} />
            <input className="f" disabled={soloLectura} defaultValue={tipo.name} style={{ minWidth: 240, fontWeight: 700 }} onBlur={(e) => { if (e.target.value !== tipo.name) renombrarTipo(tipo.key, e.target.value); }}
              onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
            <span className="hint" style={{ margin: 0 }}>{equipos.length} equipo(s)</span>
            <span style={{ marginLeft: 'auto' }}>
              <button className="btn sm" disabled={soloLectura} style={{ background: 'var(--danger)', color: '#2a0b0b' }} onClick={() => borrarTipo(tipo.key)}>&#128465; Borrar tipo</button>
            </span>
          </div>
          <table className="lst">
            <thead><tr><th>Nombre</th><th>Código equipo (único)</th><th>Org</th><th /></tr></thead>
            <tbody>
              {equipos.map((it) => (
                <tr key={it.name} data-recurso={it.name}>
                  <td><input className="f nombre" disabled={soloLectura} defaultValue={it.name} style={{ minWidth: 180 }}
                    onBlur={(e) => { if (e.target.value !== it.name) void renombrarEquipo(it.name, e.target.value); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} /></td>
                  <td><input className="f codigo" disabled={soloLectura} defaultValue={it.code || ''} placeholder="EQ-000"
                    onBlur={(e) => { if (e.target.value !== (it.code || '')) codigoEquipo(it.name, e.target.value); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} /></td>
                  <td>
                    <select value={it.org || 'BW'} disabled={soloLectura} onChange={(e) => orgEquipo(it.name, e.target.value)}
                      style={{ padding: 6, borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 }}>
                      <option>BW</option><option>CTAG</option>
                    </select>
                  </td>
                  <td style={{ textAlign: 'right' }}><button className="icbtn del" disabled={soloLectura} onClick={() => borrarEquipo(it.name)}>&#128465;</button></td>
                </tr>
              ))}
              <tr>
                <td colSpan={4}>
                  <input className="f" disabled={soloLectura} placeholder={`Nuevo equipo en ${tipo.name}...`} style={{ minWidth: 220 }} value={nuevos[tipo.key] || ''}
                    onChange={(e) => setNuevos({ ...nuevos, [tipo.key]: e.target.value })} />
                  <button className="btn sm" disabled={soloLectura} style={{ marginLeft: 8 }} onClick={() => anadirEquipo(tipo.key)}>&#65291; Añadir equipo</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}
