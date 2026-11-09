import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/cliente';
import type { BancoCatalogo } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';

/*
 * 📁 Projects (renderProjects de la 4.9.4, Admin y Editor): lista compartida
 * de proyectos que alimenta el desplegable Project. Alta manual, importar un
 * .csv/.txt o pegar una columna; borrar uno o todos. La búsqueda es solo de
 * consulta y funciona también en solo lectura.
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

export function ConfigProyectos() {
  const { soloLectura, recargarListas, mostrarMensaje } = usePlanificador();
  const [proyectos, setProyectos] = useState<string[] | null>(null);
  const [bancos, setBancos] = useState<BancoCatalogo[]>([]);
  const [nuevo, setNuevo] = useState('');
  const [busqueda, setBusqueda] = useState('');
  const [pegado, setPegado] = useState('');
  const [guardando, setGuardando] = useState(false);
  const fichero = useRef<HTMLInputElement>(null);

  const leer = async () => {
    try {
      const c = await api.catalogos();
      setProyectos(c.projects ?? []);
      setBancos(c.benches ?? []);
    } catch (e) {
      setProyectos((p) => p ?? []);
      mostrarMensaje(e instanceof Error ? e.message : String(e));
    }
  };
  useEffect(() => { void leer(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** Guarda la lista (la de bancos va tal como se leyó). Devuelve si se ha guardado. */
  const guardar = async (lista: string[], aviso?: string) => {
    setGuardando(true);
    try {
      await api.guardarCatalogos(lista, bancos);
      setProyectos(lista);
      await recargarListas();
      if (aviso) window.alert(aviso);
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
      await leer();
    } finally {
      setGuardando(false);
    }
  };
  const anadirVarios = (nuevos: string[], aviso: (n: number) => string) => {
    const lista = [...(proyectos ?? [])];
    let n = 0;
    nuevos.forEach((v) => { if (!lista.includes(v)) { lista.push(v); n++; } });
    void guardar(lista, aviso(n));
  };

  if (proyectos === null) return <div className="sec"><h2>&#128193; Projects</h2><div className="hint">Loading&hellip;</div></div>;
  const q = busqueda.toLowerCase();
  const visibles = q ? proyectos.filter((p) => p.toLowerCase().includes(q)) : proyectos;
  const bloqueado = soloLectura || guardando;

  return (
    <div className="sec" id="config-proyectos">
      <h2>&#128193; Projects</h2>
      <div className="hint">Shared project list feeding the Project dropdown in every bench. Add manually, import a .csv/.txt or paste a column.</div>
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
          void f.text().then((t) => anadirVarios(leerLineas(t), (n) => `${n} items imported.`));
        }} />
        <input className="f" id="proj-search" placeholder="Search..." value={busqueda} onChange={(e) => setBusqueda(e.target.value)}
          style={{ marginLeft: 'auto', minWidth: 200 }} />
        <button className="btn ghost sm" disabled={bloqueado} onClick={() => { if (window.confirm('Clear the whole list?')) void guardar([]); }}>&#128465; Clear all</button>
      </div>
      <textarea id="proj-paste" placeholder="Paste a table/column here (one project per line) then press Add pasted" value={pegado}
        disabled={bloqueado} onChange={(e) => setPegado(e.target.value)} />
      <div className="tools">
        <button className="btn sm" disabled={bloqueado} onClick={() => { const t = pegado; setPegado(''); anadirVarios(leerLineas(t), (n) => `${n} added.`); }}>&#65291; Add pasted</button>
        <span className="hint">{proyectos.length} projects{q ? ` \u00b7 ${visibles.length} shown` : ''}</span>
      </div>
      <div className="card" style={{ maxHeight: '52vh', overflow: 'auto', padding: 0 }}>
        <table className="lst">
          <thead><tr><th>#</th><th>Project</th><th /></tr></thead>
          <tbody>
            {visibles.length ? visibles.map((p, i) => (
              <tr key={p}>
                <td style={{ color: 'var(--muted)' }}>{i + 1}</td><td>{p}</td>
                <td style={{ textAlign: 'right' }}>
                  <button className="icbtn del" title="Delete" disabled={bloqueado} onClick={() => void guardar(proyectos.filter((x) => x !== p))}>&#128465;</button>
                </td>
              </tr>
            )) : <tr><td colSpan={3} className="empty">No projects.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
