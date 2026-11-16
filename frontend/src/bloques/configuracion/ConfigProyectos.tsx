import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api/cliente';
import { colaConfiguracion } from '../../estado/colaConfiguracion';
import { usePlanificador } from '../../estado/Planificador';
import { useAlVaciarConfiguracion } from '../../estado/useColaConfiguracion';

/*
 * 📁 Projects (renderProjects de la 4.9.4, Admin y Editor): lista compartida
 * de proyectos que alimenta el desplegable Project. Alta manual, importar un
 * .csv/.txt o pegar una columna; borrar uno o todos. La búsqueda es solo de
 * consulta y funciona también en solo lectura.
 *
 * Facturación: columna «Billing order» (BillingOrder en TestBenchProjects) con
 * la orden contable del proyecto; varias, separadas por «;». Solo la cambia un
 * Admin. Requester y línea de producto no van aquí: son de cada ensayo.
 */

/** parseLines: primera columna de cada línea, sin cabeceras típicas. */
export function leerLineas(texto: string): string[] {
  const salida: string[] = [];
  (texto || '').split(/\r?\n/).forEach((linea) => {
    if (!linea.trim()) return;
    const primera = linea.split(/[\t;,]/)[0].trim().replace(/^"|"$/g, '').trim();
    if (!primera) return;
    if (['project', 'projects', 'responsible', 'responsibles', 'name', 'id'].includes(primera.toLowerCase())) return;
    salida.push(primera);
  });
  return salida;
}

/** Líneas «proyecto<TAB>orden» o «proyecto;orden» (la orden es opcional). */
export function leerLineasConOrden(texto: string): { proyecto: string; orden: string }[] {
  const salida: { proyecto: string; orden: string }[] = [];
  (texto || '').split(/\r?\n/).forEach((linea) => {
    if (!linea.trim()) return;
    const sep = linea.includes('\t') ? '\t' : linea.includes(';') ? ';' : null;
    const partes = sep ? linea.split(sep) : [linea.split(',')[0]];
    const proyecto = (partes[0] ?? '').trim().replace(/^"|"$/g, '').trim();
    if (!proyecto || ['project', 'projects', 'responsible', 'responsibles', 'name', 'id'].includes(proyecto.toLowerCase())) return;
    // Solo se toma como orden algo con forma de orden (letras y números, 6 o más); varias separadas por «;».
    const resto = sep === '\t' ? (partes[1] ?? '') : partes.slice(1).join(';');
    const ordenes = resto.split(';').map((x) => x.trim().replace(/^"|"$/g, '').trim()).filter((x) => /^[A-Za-z0-9]{6,}$/.test(x));
    salida.push({ proyecto, orden: ordenes.join('; ') });
  });
  return salida;
}

export function ConfigProyectos() {
  const { soloLectura, mostrarMensaje, sesion } = usePlanificador();
  const esAdmin = !!sesion?.esAdmin;
  const [proyectos, setProyectos] = useState<string[] | null>(null);
  const [ordenes, setOrdenes] = useState<Record<string, string>>({});
  const [nuevo, setNuevo] = useState('');
  const [busqueda, setBusqueda] = useState('');
  const [pegado, setPegado] = useState('');
  const fichero = useRef<HTMLInputElement>(null);

  const leer = async () => {
    try {
      const c = await api.catalogos();
      setProyectos(c.projects ?? []);
      setOrdenes(c.projectOrders ?? {});
    } catch (e) {
      setProyectos((p) => p ?? []);
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };
  useEffect(() => { void leer(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Guardado terminado: se relee la lista (si falló, vuelve a lo que de verdad está guardado).
  const alVaciar = useCallback(() => { void leer(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useAlVaciarConfiguracion(alVaciar);

  /**
   * La lista cambia en el acto y se guarda en segundo plano (cola de configuración).
   * Los bancos se envían como estén en ese momento y sin su categoría de facturación
   * (así se conserva la guardada). Las órdenes solo las envía un Admin; si no se
   * envían, el backend conserva las guardadas.
   */
  const guardar = (lista: string[], aviso?: string, nuevasOrdenes?: Record<string, string>) => {
    const base = nuevasOrdenes ?? ordenes;
    const filtradas = Object.fromEntries(lista.filter((p) => (base[p] ?? '').trim()).map((p) => [p, base[p].trim()]));
    setProyectos(lista);
    if (esAdmin) setOrdenes(filtradas);
    colaConfiguracion.encolar({
      clave: 'proyectos',
      fusionable: true,
      ejecutar: async () => {
        const c = await api.catalogos();
        const bancos = (c.benches ?? []).map(({ equipmentType: _sinCategoria, ...b }) => b);
        await api.guardarCatalogos(lista, bancos, esAdmin ? filtradas : undefined);
      },
    });
    if (aviso) window.alert(aviso);
  };
  const anadirVarios = (nuevos: { proyecto: string; orden: string }[], aviso: (n: number) => string) => {
    const lista = [...(proyectos ?? [])];
    const conOrdenes = { ...ordenes };
    let n = 0;
    nuevos.forEach(({ proyecto: v, orden }) => {
      if (!lista.includes(v)) { lista.push(v); n++; }
      if (esAdmin && orden) conOrdenes[v] = orden;
    });
    void guardar(lista, aviso(n), conOrdenes);
  };
  const cambiarOrden = (p: string, valor: string) => {
    const v = valor.split(/[;\n]+/).map((x) => x.trim()).filter(Boolean).join('; ');
    if (v === (ordenes[p] ?? '')) return;
    void guardar(proyectos ?? [], undefined, { ...ordenes, [p]: v });
  };

  if (proyectos === null) return <div className="sec"><h2>&#128193; Projects</h2><div className="hint">Loading&hellip;</div></div>;
  const q = busqueda.toLowerCase();
  const visibles = q ? proyectos.filter((p) => p.toLowerCase().includes(q)) : proyectos;
  const bloqueado = soloLectura;

  return (
    <div className="sec" id="config-proyectos">
      <h2>&#128193; Projects</h2>
      <div className="hint">Shared project list feeding the Project dropdown in every bench. Add manually, import a .csv/.txt or paste a column.</div>
      {/* data-solo-nueva: lo que la 4.9.4 no tiene (facturación); el laboratorio de paridad lo deja fuera (D38). */}
      <div className="hint" data-solo-nueva=""><b>Billing order</b>: accounting order used by Billing (several separated by ;). Only an Admin can
        change it; you can paste &laquo;project&raquo; and &laquo;order&raquo; as two columns (tab or ;).</div>
      <div className="tools">
        <input className="f" id="proj-new" placeholder="New project..." style={{ minWidth: 300 }} value={nuevo} disabled={bloqueado}
          onChange={(e) => setNuevo(e.target.value)} />
        <button className="btn sm" disabled={bloqueado} onClick={() => {
          const v = nuevo.trim();
          if (!v) return;
          setNuevo('');
          if (!proyectos.includes(v)) void guardar([v, ...proyectos]);
        }}>&#65291; Add</button>
        <button className="btn ghost sm" disabled={bloqueado} onClick={() => fichero.current?.click()}>&#11014; Import CSV/TXT</button>
        <input ref={fichero} id="proj-file" type="file" accept=".csv,.txt" style={{ display: 'none' }} onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = '';
          if (!f) return;
          if (/\.xlsx?$/i.test(f.name)) { window.alert('Please save the Excel as .csv first, then import it here.'); return; }
          void f.text().then((t) => anadirVarios(leerLineasConOrden(t), (n) => `${n} items imported.`));
        }} />
        <input className="f" id="proj-search" placeholder="Search..." value={busqueda} onChange={(e) => setBusqueda(e.target.value)}
          style={{ marginLeft: 'auto', minWidth: 200 }} />
        <button className="btn ghost sm" disabled={bloqueado} onClick={() => { if (window.confirm('Clear the whole list?')) void guardar([]); }}>&#128465; Clear all</button>
      </div>
      <textarea id="proj-paste" placeholder="Paste a table/column here (one project per line) then press Add pasted" value={pegado}
        disabled={bloqueado} onChange={(e) => setPegado(e.target.value)} />
      <div className="tools">
        <button className="btn sm" disabled={bloqueado} onClick={() => { const t = pegado; setPegado(''); anadirVarios(leerLineasConOrden(t), (n) => `${n} added.`); }}>&#65291; Add pasted</button>
        <span className="hint">{proyectos.length} projects{q ? ` \u00b7 ${visibles.length} shown` : ''}</span>
      </div>
      <div className="card" style={{ maxHeight: '52vh', overflow: 'auto', padding: 0 }}>
        <table className="lst">
          <thead><tr><th>#</th><th>Project</th><th data-solo-nueva="">Billing order</th><th /></tr></thead>
          <tbody>
            {visibles.length ? visibles.map((p, i) => (
              <tr key={p}>
                <td style={{ color: 'var(--muted)' }}>{i + 1}</td><td>{p}</td>
                <td data-orden={p} data-solo-nueva="">
                  {esAdmin && !soloLectura ? (
                    <input className="f" key={`${p}|${ordenes[p] ?? ''}`} defaultValue={ordenes[p] ?? ''} placeholder="463B…" disabled={bloqueado}
                      style={{ minWidth: 220 }} onBlur={(e) => cambiarOrden(p, e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }} />
                  ) : <span style={{ color: ordenes[p] ? undefined : 'var(--muted)' }}>{ordenes[p] || '\u2014'}</span>}
                </td>
                <td style={{ textAlign: 'right' }}>
                  <button className="icbtn del" title="Delete" disabled={bloqueado} onClick={() => void guardar(proyectos.filter((x) => x !== p))}>&#128465;</button>
                </td>
              </tr>
            )) : <tr><td colSpan={4} className="empty">No projects.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
