import { useState } from 'react';
import { api } from '../../api/cliente';
import type { BancoCatalogo } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';

/*
 * 🔧 Test Benches (renderBenchesCfg de la 4.9.4, solo Admin): categorías y
 * bancos. Renombrar un banco conserva sus ensayos; borrar un banco o una
 * categoría con bancos borra sus ensayos (con confirmación, como en la 4.9.4).
 * Cada cambio se guarda en el acto: lista de bancos y, si hace falta, ensayos.
 */

interface Categoria { key: string; name: string; dot: string; benches: { name: string; org: string }[] }

/** slugKey de la 4.9.4: clave a partir del nombre con un sufijo aleatorio. */
const claveDe = (s: string) => `${(s || 'grp').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}_${Math.random().toString(36).slice(2, 5)}`;

/** cfgSnapshot: una fila por banco, en orden, con su categoría y organización. */
function instantanea(categorias: Categoria[]): BancoCatalogo[] {
  let seq = 0;
  return categorias.flatMap((g) => g.benches.map((b) => ({ name: b.name, groupKey: g.key, groupName: g.name, dot: g.dot, org: b.org, seq: ++seq })));
}

const estiloSelect = { padding: 6, borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };

export function ConfigBancos() {
  const { vista, soloLectura, recargar, recargarListas, mostrarMensaje, proyectos } = usePlanificador();
  const [nuevaCategoria, setNuevaCategoria] = useState('');
  const [colorCategoria, setColorCategoria] = useState('#23BCA3');
  const [nuevos, setNuevos] = useState<Record<string, string>>({});
  const [guardando, setGuardando] = useState(false);
  const [version, setVersion] = useState(0); // vuelve a pintar las cajas si un cambio no se aplica
  // Categorías recién creadas sin bancos: como en la 4.9.4, solo existen en esta
  // pantalla hasta que se les añade un banco (la lista compartida es de bancos).
  const [vacias, setVacias] = useState<Categoria[]>([]);
  if (!vista) return null;

  const delServidor: Categoria[] = vista.grupos.map((g) => ({
    key: g.clave, name: g.nombre, dot: g.color, benches: g.bancos.map((b) => ({ name: b.nombre, org: b.organizacion })),
  }));
  const categorias: Categoria[] = [...delServidor, ...vacias.filter((v) => !delServidor.some((g) => g.key === v.key))];
  const todos = new Set(categorias.flatMap((g) => g.benches.map((b) => b.name)));
  const activos = (b: string) => (vista.bancos[b] ?? []).filter((f) => !f.cerrado).length;
  const total = (b: string) => (vista.bancos[b] ?? []).length;
  const bloqueado = soloLectura || guardando;

  const guardar = async (nuevas: Categoria[], renombrados: Record<string, string> = {}) => {
    setVacias(nuevas.filter((g) => !g.benches.length).map((g) => ({ ...g, benches: [] })));
    if (instantanea(nuevas).length === instantanea(categorias).length && JSON.stringify(instantanea(nuevas)) === JSON.stringify(instantanea(categorias))
      && !Object.keys(renombrados).length) {
      return; // solo cambian categorías vacías: no hay nada que guardar
    }
    setGuardando(true);
    try {
      // Los proyectos se guardan tal como están: aquí solo cambian los bancos.
      const catalogo = await api.catalogos();
      await api.guardarBancos(catalogo.projects ?? proyectos, instantanea(nuevas), renombrados);
      await Promise.all([recargar(), recargarListas()]);
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    } finally {
      setGuardando(false);
      setVersion((v) => v + 1);
    }
  };
  const copia = () => categorias.map((g) => ({ ...g, benches: g.benches.map((b) => ({ ...b })) }));

  const anadirCategoria = () => {
    const v = nuevaCategoria.trim();
    if (!v) return;
    setNuevaCategoria('');
    void guardar([...copia(), { key: claveDe(v), name: v, dot: colorCategoria || '#23BCA3', benches: [] }]);
  };
  const cambiarCategoria = (gi: number, cambio: Partial<Categoria>) => {
    const c = copia();
    c[gi] = { ...c[gi], ...cambio };
    void guardar(c);
  };
  const moverCategoria = (gi: number, d: number) => {
    const c = copia(), ni = gi + d;
    if (ni < 0 || ni >= c.length) return;
    [c[gi], c[ni]] = [c[ni], c[gi]];
    void guardar(c);
  };
  const borrarCategoria = (gi: number) => {
    const g = categorias[gi];
    const nt = g.benches.reduce((s, b) => s + total(b.name), 0);
    if (!window.confirm(`Delete category "${g.name}"${g.benches.length ? ` and its ${g.benches.length} bench(es)${nt ? ` with ${nt} test(s) \u2014 THIS DELETES DATA` : ''}` : ''}?`)) return;
    const c = copia();
    c.splice(gi, 1);
    void guardar(c);
  };
  const anadirBanco = (gi: number) => {
    const v = (nuevos[categorias[gi].key] || '').trim();
    if (!v) return;
    if (todos.has(v)) {
      window.alert(`A bench named "${v}" already exists.`);
      setNuevos({ ...nuevos, [categorias[gi].key]: '' }); // la 4.9.4 vuelve a pintar la pantalla y la caja queda vacía
      return;
    }
    const c = copia();
    c[gi].benches.push({ name: v, org: 'BW' });
    setNuevos({ ...nuevos, [categorias[gi].key]: '' });
    void guardar(c);
  };
  const renombrarBanco = (gi: number, bi: number, v: string) => {
    v = v.trim();
    const antiguo = categorias[gi].benches[bi].name;
    if (!v || v === antiguo) { setVersion((n) => n + 1); return; }
    if (todos.has(v)) { window.alert(`A bench named "${v}" already exists.`); setVersion((n) => n + 1); return; }
    if (!window.confirm(`Rename bench "${antiguo}" to "${v}"? Its tests will be kept.`)) { setVersion((n) => n + 1); return; }
    const c = copia();
    c[gi].benches[bi].name = v;
    void guardar(c, { [antiguo]: v });
  };
  const orgBanco = (gi: number, bi: number, org: string) => {
    const c = copia();
    c[gi].benches[bi].org = org;
    void guardar(c);
  };
  const borrarBanco = (gi: number, bi: number) => {
    const bn = categorias[gi].benches[bi].name;
    const nt = total(bn);
    if (!window.confirm(`Delete bench "${bn}"${nt ? ` and its ${nt} test(s) \u2014 THIS DELETES DATA` : ''}?`)) return;
    const c = copia();
    c[gi].benches.splice(bi, 1);
    void guardar(c);
  };
  const moverBanco = (gi: number, bi: number, d: number) => {
    const c = copia(), lista = c[gi].benches, ni = bi + d;
    if (ni < 0 || ni >= lista.length) return;
    [lista[bi], lista[ni]] = [lista[ni], lista[bi]];
    void guardar(c);
  };
  const moverACategoria = (gi: number, bi: number, gj: number) => {
    if (Number.isNaN(gj)) return;
    const c = copia();
    const [b] = c[gi].benches.splice(bi, 1);
    c[gj].benches.push(b);
    void guardar(c);
  };

  return (
    <div className="sec" style={{ maxWidth: 1100 }} id="config-bancos">
      <h2>&#128296; Test Benches</h2>
      <div className="hint">
        Create and organise your bench categories and benches. Rename or recolour categories, add/rename/move/delete benches. Renaming a bench
        keeps its tests; deleting a bench or a non-empty category asks for confirmation.
      </div>
      <div className="tools">
        <input className="f" id="newcat" placeholder="New category name..." style={{ minWidth: 260 }} value={nuevaCategoria}
          disabled={bloqueado} onChange={(e) => setNuevaCategoria(e.target.value)} />
        <input type="color" id="newcatcol" value={colorCategoria} disabled={bloqueado} onChange={(e) => setColorCategoria(e.target.value)}
          style={{ width: 44, height: 36, padding: 0, border: '1px solid var(--line)', borderRadius: 6, background: 'var(--navy)' }} />
        <button className="btn sm" disabled={bloqueado} onClick={anadirCategoria}>&#65291; Add category</button>
      </div>
      {categorias.map((g, gi) => (
        <div className="cfg-cat" key={g.key + version} data-categoria={g.name}>
          <div className="catrow">
            <input type="color" defaultValue={g.dot} title="Category color" disabled={bloqueado}
              onChange={(e) => cambiarCategoria(gi, { dot: e.target.value })} />
            <input className="f" defaultValue={g.name} style={{ minWidth: 260, fontWeight: 700 }} disabled={bloqueado}
              onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== g.name) cambiarCategoria(gi, { name: v }); }}
              onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
            <span className="hint" style={{ margin: 0 }}>{g.benches.length} bench(es)</span>
            <span style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
              <button className="btn ghost sm" disabled={bloqueado || gi === 0} onClick={() => moverCategoria(gi, -1)}>&#9650;</button>
              <button className="btn ghost sm" disabled={bloqueado || gi === categorias.length - 1} onClick={() => moverCategoria(gi, 1)}>&#9660;</button>
              <button className="btn sm" disabled={bloqueado} style={{ background: 'var(--danger)', color: '#2a0b0b' }} onClick={() => borrarCategoria(gi)}>&#128465; Delete</button>
            </span>
          </div>
          <table className="lst">
            <thead><tr><th>#</th><th>Bench</th><th>Org</th><th>Active tests</th><th>Move to category</th><th /></tr></thead>
            <tbody>
              {g.benches.map((b, bi) => (
                <tr key={b.name} data-banco={b.name}>
                  <td style={{ color: 'var(--muted)' }}>{bi + 1}</td>
                  <td><input className="f" defaultValue={b.name} style={{ minWidth: 200 }} disabled={bloqueado}
                    onBlur={(e) => renombrarBanco(gi, bi, e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} /></td>
                  <td>
                    <select value={b.org} style={estiloSelect} disabled={bloqueado} onChange={(e) => orgBanco(gi, bi, e.target.value)}>
                      <option>BW</option><option>CTAG</option>
                    </select>
                  </td>
                  <td>{activos(b.name)}</td>
                  <td>
                    <select value="" style={estiloSelect} disabled={bloqueado} onChange={(e) => moverACategoria(gi, bi, parseInt(e.target.value, 10))}>
                      <option value="">move&hellip;</option>
                      {categorias.map((g2, gj) => (gj === gi ? null : <option key={g2.key} value={gj}>{g2.name}</option>))}
                    </select>
                  </td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className="btn ghost sm" disabled={bloqueado || bi === 0} onClick={() => moverBanco(gi, bi, -1)}>&#9650;</button>
                    <button className="btn ghost sm" disabled={bloqueado || bi === g.benches.length - 1} onClick={() => moverBanco(gi, bi, 1)}>&#9660;</button>
                    <button className="icbtn del" disabled={bloqueado} onClick={() => borrarBanco(gi, bi)}>&#128465;</button>
                  </td>
                </tr>
              ))}
              <tr>
                <td />
                <td colSpan={4}>
                  <input className="f" placeholder={`New bench in ${g.name}...`} style={{ minWidth: 220 }} value={nuevos[g.key] || ''} disabled={bloqueado}
                    onChange={(e) => setNuevos({ ...nuevos, [g.key]: e.target.value })} />
                  <button className="btn sm" style={{ marginLeft: 8 }} disabled={bloqueado} onClick={() => anadirBanco(gi)}>&#65291; Add bench</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}
