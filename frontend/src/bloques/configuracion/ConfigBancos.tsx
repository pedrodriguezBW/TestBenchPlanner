import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import { apiFacturacion } from '../../api/facturacion';
import type { BancoCatalogo } from '../../api/tipos';
import { colaConfiguracion } from '../../estado/colaConfiguracion';
import { usePlanificador } from '../../estado/Planificador';
import { useAlVaciarConfiguracion } from '../../estado/useColaConfiguracion';
import { CODIGOS_AREA, nombresArea, normalizarArea, ORGANIZACION_FIJA, type CodigoArea } from '../../utilidades/areas';

/*
 * 🔧 Test Benches (renderBenchesCfg de la 4.9.4, solo Admin): categorías y
 * bancos. Renombrar un banco conserva sus ensayos; borrar un banco o una
 * categoría con bancos borra sus ensayos (con confirmación, como en la 4.9.4).
 * Cada cambio se ve en el acto y se guarda en segundo plano (cola de configuración):
 * lista de bancos y, si hace falta, ensayos. Si el guardado falla se avisa y la
 * pantalla vuelve a lo guardado.
 *
 * Facturación: columna «Equipment Type» (EquipmentType en TestBenchBenches): el
 * Equipment Type del banco, que da la tarifa y la regla de cálculo de sus tareas
 * (los Equipment Types se mantienen en Billing › Configuration).
 */

interface Categoria { key: string; name: string; dot: string; area: CodigoArea; benches: { name: string; org: string }[] }

/** slugKey de la 4.9.4: clave a partir del nombre con un sufijo aleatorio. */
const claveDe = (s: string) => `${(s || 'grp').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')}_${Math.random().toString(36).slice(2, 5)}`;

/** Organización que se guarda: la fija de un área HVCH o la elegida (BW / CTAG) en Thermal Benches. */
const orgDe = (area: CodigoArea, org: string) => ORGANIZACION_FIJA[area] ?? (org === 'CTAG' ? 'CTAG' : 'BW');

/** Categorías juntas por área, en el orden de las áreas (dentro de cada área, el orden que tienen). */
const ordenarPorArea = (categorias: Categoria[]) =>
  CODIGOS_AREA.flatMap((a) => categorias.filter((g) => g.area === a));

/** cfgSnapshot: una fila por banco, en orden, con su categoría, área y organización (y su categoría de facturación). */
function instantanea(categorias: Categoria[], facturacion?: Record<string, string>): BancoCatalogo[] {
  let seq = 0;
  return ordenarPorArea(categorias).flatMap((g) => g.benches.map((b) => ({ name: b.name, groupKey: g.key, groupName: g.name, dot: g.dot,
    org: orgDe(g.area, b.org), area: g.area, seq: ++seq,
    ...(facturacion ? { equipmentType: facturacion[b.name] ?? '' } : {}) })));
}

const estiloSelect = { padding: 6, borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)', fontSize: 12 };

export function ConfigBancos() {
  const { vista, soloLectura } = usePlanificador();
  // Caja «New category» de cada área.
  const [nuevaCategoria, setNuevaCategoria] = useState<Record<string, string>>({});
  const [colorCategoria, setColorCategoria] = useState<Record<string, string>>({});
  // Nombres de área mientras se guardan (si no, los de la vista).
  const [nombresLocales, setNombresLocales] = useState<Record<string, string> | null>(null);
  const [nuevos, setNuevos] = useState<Record<string, string>>({});
  const [version, setVersion] = useState(0); // vuelve a pintar las cajas si un cambio no se aplica
  // Categorías recién creadas sin bancos: como en la 4.9.4, solo existen en esta
  // pantalla hasta que se les añade un banco (la lista compartida es de bancos).
  const [vacias, setVacias] = useState<Categoria[]>([]);
  // Lo que se ve mientras el guardado va en segundo plano (como pushConfig de la 4.9.4).
  // alias: nombre nuevo → nombre con el que el servidor conoce el banco (para contar sus ensayos).
  const [local, setLocal] = useState<{ categorias: Categoria[]; alias: Record<string, string> } | null>(null);
  // Categoría de facturación de cada banco (lista compartida) y categorías disponibles (Billing › Configuration).
  const [facturacion, setFacturacion] = useState<Record<string, string> | null>(null);
  const [categoriasFact, setCategoriasFact] = useState<string[]>([]);
  const leerFacturacion = () => {
    api.catalogos().then((c) => setFacturacion(Object.fromEntries((c.benches ?? []).map((b) => [b.name, String(b.equipmentType ?? '')]))))
      .catch(() => setFacturacion(null));
    apiFacturacion.tiposEquipo().then((d) => setCategoriasFact([...new Set(d.tipos.map((t) => t.titulo))].sort((a, b) => a.localeCompare(b))))
      .catch(() => setCategoriasFact([]));
  };
  useEffect(leerFacturacion, []); // eslint-disable-line react-hooks/exhaustive-deps
  // Guardado terminado (la vista ya está recargada): se suelta la copia local y se relee la facturación.
  const alVaciar = useCallback(() => {
    setLocal(null);
    setNombresLocales(null);
    setVersion((v) => v + 1);
    leerFacturacion();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useAlVaciarConfiguracion(alVaciar);
  if (!vista) return null;

  const delServidor: Categoria[] = vista.grupos.map((g) => ({
    key: g.clave, name: g.nombre, dot: g.color, area: normalizarArea(g.area), benches: g.bancos.map((b) => ({ name: b.nombre, org: b.organizacion })),
  }));
  const nombres = { ...nombresArea(vista), ...(nombresLocales ?? {}) } as Record<CodigoArea, string>;
  const categorias: Categoria[] = local?.categorias
    ?? [...delServidor, ...vacias.filter((v) => !delServidor.some((g) => g.key === v.key))];
  const alias = local?.alias ?? {};
  const todos = new Set(categorias.flatMap((g) => g.benches.map((b) => b.name)));
  const ensayos = (b: string) => vista.bancos[alias[b] ?? b] ?? [];
  const activos = (b: string) => ensayos(b).filter((f) => !f.cerrado).length;
  const total = (b: string) => ensayos(b).length;
  const bloqueado = soloLectura;

  /**
   * La pantalla cambia en el acto y el guardado va a la cola (en segundo plano,
   * en orden). Si falla, se avisa y la pantalla vuelve a lo guardado.
   */
  const guardar = (nuevas: Categoria[], renombrados: Record<string, string> = {}, nuevaFacturacion?: Record<string, string>,
    areaNames?: Record<string, string>) => {
    nuevas = ordenarPorArea(nuevas);
    setVacias(nuevas.filter((g) => !g.benches.length).map((g) => ({ ...g, benches: [] })));
    if (JSON.stringify(instantanea(nuevas)) === JSON.stringify(instantanea(categorias))
      && !Object.keys(renombrados).length && !nuevaFacturacion && !areaNames) {
      setLocal(local ? { ...local, categorias: nuevas } : null);
      return; // solo cambian categorías vacías: no hay nada que guardar
    }
    // Categoría de facturación: un banco renombrado conserva la suya.
    let fact: Record<string, string> | undefined = nuevaFacturacion ?? (facturacion ? { ...facturacion } : undefined);
    if (fact) {
      fact = { ...fact };
      Object.entries(renombrados).forEach(([a, n]) => { if (fact && fact[a] !== undefined) { fact[n] = fact[a]; delete fact[a]; } });
      setFacturacion(fact);
    }
    const nuevoAlias = { ...alias };
    Object.entries(renombrados).forEach(([a, n]) => { nuevoAlias[n] = alias[a] ?? a; delete nuevoAlias[a]; });
    setLocal({ categorias: nuevas, alias: nuevoAlias });
    const bancos = instantanea(nuevas, fact);
    colaConfiguracion.encolar({
      clave: 'bancos',
      fusionable: !Object.keys(renombrados).length && !areaNames,
      ejecutar: async () => {
        // Los proyectos se guardan tal como están en ese momento: aquí solo cambian los bancos.
        const catalogo = await api.catalogos();
        await api.guardarBancos(catalogo.projects ?? [], bancos, renombrados, areaNames);
      },
    });
  };
  const copia = () => categorias.map((g) => ({ ...g, benches: g.benches.map((b) => ({ ...b })) }));

  const anadirCategoria = (area: CodigoArea) => {
    const v = (nuevaCategoria[area] || '').trim();
    if (!v) return;
    setNuevaCategoria({ ...nuevaCategoria, [area]: '' });
    void guardar([...copia(), { key: claveDe(v), name: v, dot: colorCategoria[area] || '#23BCA3', area, benches: [] }]);
  };
  /** Pasa la categoría (con sus bancos) a otra área; va al final de esa área. */
  const moverCategoriaAArea = (gi: number, area: CodigoArea) => {
    const g = categorias[gi];
    if (g.area === area) return;
    const fija = ORGANIZACION_FIJA[area];
    const aviso = fija ? `Its benches will be organisation ${fija}.` : 'Its benches will be BW unless they were CTAG; you can change it afterwards.';
    if (!window.confirm(`Move category "${g.name}" (${g.benches.length} bench(es)) to ${nombres[area]}? ${aviso}`)) { setVersion((n) => n + 1); return; }
    const c = copia();
    const [movida] = c.splice(gi, 1);
    movida.area = area;
    movida.benches = movida.benches.map((b) => ({ ...b, org: orgDe(area, b.org) }));
    c.push(movida);
    void guardar(c);
  };
  /** Renombra un área (solo el nombre visible; el código no cambia). */
  const renombrarArea = (area: CodigoArea, v: string) => {
    v = v.trim();
    if (!v || v === nombres[area]) { setVersion((n) => n + 1); return; }
    setNombresLocales({ ...nombres, [area]: v });
    // Solo el nombre que cambia: los demás se conservan tal como estén guardados.
    void guardar(copia(), {}, undefined, { [area]: v });
  };
  const cambiarCategoria = (gi: number, cambio: Partial<Categoria>) => {
    const c = copia();
    c[gi] = { ...c[gi], ...cambio };
    void guardar(c);
  };
  /** ▲/▼ de categoría: dentro de su área (salta las categorías de otras áreas). */
  const moverCategoria = (gi: number, d: number) => {
    const c = copia();
    let ni = gi + d;
    while (ni >= 0 && ni < c.length && c[ni].area !== c[gi].area) ni += d;
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
    c[gi].benches.push({ name: v, org: orgDe(c[gi].area, 'BW') });
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
  const cambiarCategoriaFacturacion = (banco: string, valor: string) => {
    if (!facturacion) return;
    void guardar(copia(), {}, { ...facturacion, [banco]: valor });
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
    c[gj].benches.push({ ...b, org: orgDe(c[gj].area, b.org) });
    void guardar(c);
  };

  return (
    <div className="sec" style={{ maxWidth: 1100 }} id="config-bancos">
      <h2>&#128296; Test Benches</h2>
      <div className="hint">
        Create and organise your bench categories and benches. Rename or recolour categories, add/rename/move/delete benches. Renaming a bench
        keeps its tests; deleting a bench or a non-empty category asks for confirmation.
      </div>
      {/* data-solo-nueva: lo que la 4.9.4 no tiene (facturación, áreas); el laboratorio de paridad lo deja fuera (D38, D39). */}
      <div className="hint" data-solo-nueva="">
        <b>Areas</b>: every category belongs to an area (<b>{nombres.THERMAL}</b>, <b>{nombres['HVCH-CTAG']}</b> or <b>{nombres['HVCH-STC']}</b>).
        Only {nombres.THERMAL} are split by organisation (BW / CTAG); the benches of the HVCH areas have no Org. Use the area selector of a category
        to move it, with its benches, to another area. Click an area name to rename it.
      </div>
      <div className="hint" data-solo-nueva="">
        <b>Equipment Type</b>: how the bench&rsquo;s tests are billed (rate per hour or per piece and calculation rule, kept in Billing &rsaquo; Configuration).
      </div>
      {CODIGOS_AREA.map((area, ai) => {
        const enArea = categorias.map((g, gi) => ({ g, gi })).filter((x) => x.g.area === area);
        const conOrg = area === 'THERMAL';
        const primera = ai === 0; // la caja «New category» de la primera área conserva los id de la 4.9.4
        return (
          <div className="cfg-area" key={area + version} data-area={area} {...(primera ? {} : { 'data-solo-nueva': '' })}>
            <div className="area-head" data-solo-nueva="">
              <input className="f" defaultValue={nombres[area]} style={{ minWidth: 240, fontWeight: 800, fontSize: 15 }} disabled={bloqueado}
                title="Area name" data-nombre-area={area}
                onBlur={(e) => renombrarArea(area, e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
              <span className="hint" style={{ margin: 0 }}>
                {enArea.length} categor{enArea.length === 1 ? 'y' : 'ies'} &middot; {enArea.reduce((n, x) => n + x.g.benches.length, 0)} bench(es)
                {conOrg ? ' \u00b7 split by Org (BW / CTAG)' : ` \u00b7 Org ${ORGANIZACION_FIJA[area]}`}
              </span>
            </div>
            <div className="tools">
              <input className="f" {...(primera ? { id: 'newcat' } : {})} placeholder="New category name..." style={{ minWidth: 260 }}
                value={nuevaCategoria[area] || ''} disabled={bloqueado} onChange={(e) => setNuevaCategoria({ ...nuevaCategoria, [area]: e.target.value })} />
              <input type="color" {...(primera ? { id: 'newcatcol' } : {})} value={colorCategoria[area] || '#23BCA3'} disabled={bloqueado}
                onChange={(e) => setColorCategoria({ ...colorCategoria, [area]: e.target.value })}
                style={{ width: 44, height: 36, padding: 0, border: '1px solid var(--line)', borderRadius: 6, background: 'var(--navy)' }} />
              <button className="btn sm" disabled={bloqueado} onClick={() => anadirCategoria(area)}>&#65291; Add category</button>
            </div>
            {!enArea.length && <div className="area-empty" data-solo-nueva="">No categories in this area yet.</div>}
            {enArea.map(({ g, gi }, indiceEnArea) => (
            <div className="cfg-cat" key={g.key + version} data-categoria={g.name}>
              <div className="catrow">
                <input type="color" defaultValue={g.dot} title="Category color" disabled={bloqueado}
                  onChange={(e) => cambiarCategoria(gi, { dot: e.target.value })} />
                <input className="f" defaultValue={g.name} style={{ minWidth: 260, fontWeight: 700 }} disabled={bloqueado}
                  onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== g.name) cambiarCategoria(gi, { name: v }); }}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
                <span className="hint" style={{ margin: 0 }}>{g.benches.length} bench(es)</span>
                <span style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                  <select value={g.area} style={estiloSelect} disabled={bloqueado} title="Move this category to another area" data-solo-nueva=""
                    data-area-categoria={g.name} onChange={(e) => moverCategoriaAArea(gi, e.target.value as CodigoArea)}>
                    {CODIGOS_AREA.map((a) => <option key={a} value={a}>{nombres[a]}</option>)}
                  </select>
                  <button className="btn ghost sm" disabled={bloqueado || indiceEnArea === 0} onClick={() => moverCategoria(gi, -1)}>&#9650;</button>
                  <button className="btn ghost sm" disabled={bloqueado || indiceEnArea === enArea.length - 1} onClick={() => moverCategoria(gi, 1)}>&#9660;</button>
                  <button className="btn sm" disabled={bloqueado} style={{ background: 'var(--danger)', color: '#2a0b0b' }} onClick={() => borrarCategoria(gi)}>&#128465; Delete</button>
                </span>
              </div>
              <table className="lst">
                <thead><tr><th>#</th><th>Bench</th>{conOrg && <th>Org</th>}<th data-solo-nueva="">Equipment Type</th><th>Active tests</th><th>Move to category</th><th /></tr></thead>
                <tbody>
                  {g.benches.map((b, bi) => (
                    <tr key={b.name} data-banco={b.name}>
                      <td style={{ color: 'var(--muted)' }}>{bi + 1}</td>
                      <td><input className="f" defaultValue={b.name} style={{ minWidth: 200 }} disabled={bloqueado}
                        onBlur={(e) => renombrarBanco(gi, bi, e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} /></td>
                      {conOrg && (
                        <td>
                          <select value={b.org} style={estiloSelect} disabled={bloqueado} onChange={(e) => orgBanco(gi, bi, e.target.value)}>
                            <option>BW</option><option>CTAG</option>
                          </select>
                        </td>
                      )}
                      <td data-categoria-facturacion={b.name} data-solo-nueva="">
                        {facturacion ? (
                          <select value={facturacion[b.name] ?? ''} style={estiloSelect} disabled={bloqueado}
                            onChange={(e) => cambiarCategoriaFacturacion(b.name, e.target.value)}>
                            <option value="">&mdash;</option>
                            {[...new Set([...categoriasFact, ...(facturacion[b.name] ? [facturacion[b.name]] : [])])].map((c) => (
                              <option key={c} value={c}>{c}{categoriasFact.includes(c) ? '' : ' (no existe)'}</option>))}
                          </select>
                        ) : <span className="hint" style={{ margin: 0 }}>&hellip;</span>}
                      </td>
                      <td>{activos(b.name)}</td>
                      <td>
                        <select value="" style={estiloSelect} disabled={bloqueado} onChange={(e) => moverACategoria(gi, bi, parseInt(e.target.value, 10))}>
                          <option value="">move&hellip;</option>
                          {categorias.map((g2, gj) => (gj === gi ? null : (
                            <option key={g2.key} value={gj}>{g2.name}{g2.area !== g.area ? ` (${nombres[g2.area]})` : ''}</option>)))}
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
                    <td colSpan={conOrg ? 5 : 4}>
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
      })}
    </div>
  );
}
