import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { porTipo, useRecursos, usoDe } from '../../estado/Recursos';
import { fmt } from '../../utilidades/fechas';

/*
 * Selector de equipos de un ensayo (openResPicker / renderResPick de la 4.9.4):
 * pestañas «Disponibles» / «En uso» en las fechas del ensayo, búsqueda, y
 * Añadir / ✓ Quitar. Asignar un equipo ocupado se permite, con un aviso que no
 * bloquea.
 */
export function SelectorRecursos({ banco, id, onCerrar }: { banco: string; id: string | null; onCerrar: () => void }) {
  const { vista, operar } = usePlanificador();
  const { recursos, bloqueos } = useRecursos();
  const [pestana, setPestana] = useState<'free' | 'busy'>('free');
  const [busqueda, setBusqueda] = useState('');
  const [aviso, setAviso] = useState<string[] | null>(null);
  const temporizador = useRef(0);

  useEffect(() => { if (id) { setPestana('free'); setBusqueda(''); } }, [id]);
  useEffect(() => () => window.clearTimeout(temporizador.current), []);

  const fila = id && vista ? (vista.bancos[banco] ?? []).find((f) => f.id === id) : undefined;
  const avisoFlotante = aviso && createPortal(
    <div id="res-toast" className="show">
      &#9888;&#65039; <b>{aviso[0]}</b> ya está en uso en esas fechas:<br />
      {aviso.slice(1).map((l, i) => <span key={i}>{l}<br /></span>)}
      <span style={{ color: 'var(--muted)' }}>Se ha asignado igualmente; tenlo en cuenta.</span>
    </div>, document.body);

  if (!fila || !vista) return avisoFlotante || null;
  const inicio = fila.calculo.inicio ?? null, fin = fila.calculo.fin ?? null;
  const elegidos = Array.isArray(fila.datos.resources) ? fila.datos.resources : [];
  const q = busqueda.toLowerCase().trim();

  const alternar = async (nombre: string) => {
    const anade = !elegidos.includes(nombre);
    const r = await operar(banco, 'alternarRecurso', { id: fila.id, recurso: nombre }, [fila.id]);
    if (r && anade && inicio && fin) {
      const usos = usoDe(r.vista, bloqueos, nombre, inicio, fin, fila.id);
      if (usos.length) {
        setAviso([nombre, ...usos.map((u) => `${u.motivo} (${fmt(u.inicio)}\u2192${fmt(u.fin)})`)]);
        window.clearTimeout(temporizador.current);
        temporizador.current = window.setTimeout(() => setAviso(null), 5500);
      }
    }
  };

  const estiloPestana = (p: 'free' | 'busy') => ({
    background: pestana === p ? 'var(--turq)' : 'var(--panel2)', color: pestana === p ? '#06231f' : 'var(--turq)',
  });

  const grupos = porTipo(recursos).map(({ tipo, equipos }) => {
    let lista = equipos.filter((it) => !q || `${it.name || ''} ${it.code || ''} ${tipo.name || ''} ${it.org || ''}`.toLowerCase().includes(q));
    if (inicio && fin) lista = lista.filter((it) => (usoDe(vista, bloqueos, it.name, inicio, fin, fila.id).length > 0) === (pestana === 'busy'));
    else if (pestana === 'busy') lista = [];
    return { tipo, lista };
  }).filter((g) => g.lista.length);

  return (
    <>
      <Dialogo abierto ancho="xwide" onCerrar={onCerrar}>
        <h3 id="resm-title">Recursos auxiliares</h3>
        <div className="hint" id="resm-sub">
          {inicio && fin
            ? <>Fechas del ensayo: <b>{fmt(inicio)}</b> &rarr; <b>{fmt(fin)}</b>. Un recurso está <b>en uso</b> si otro ensayo o un bloqueo lo ocupa en esas fechas.</>
            : 'Este ensayo aún no tiene fechas calculadas (añade una duración).'}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', margin: '8px 0', flexWrap: 'wrap' }}>
          <button className="btn ghost sm" id="resm-tab-free" style={estiloPestana('free')} onClick={() => setPestana('free')}>Disponibles</button>
          <button className="btn ghost sm" id="resm-tab-busy" style={estiloPestana('busy')} onClick={() => setPestana('busy')}>En uso</button>
          <input id="resm-search" className="done-search" placeholder="Buscar por nombre, código, tipo, BW/CTAG..." value={busqueda}
            onChange={(e) => setBusqueda(e.target.value)} style={{ margin: 0, flex: 1, minWidth: 220 }} />
        </div>
        <div id="resm-list" style={{ maxHeight: '56vh', overflow: 'auto' }}>
          {elegidos.length > 0 && (
            <div className="hint" style={{ marginBottom: 8 }}>Asignados a este ensayo: {elegidos.map((n) => <span key={n} className="res-badge">{n}</span>)}</div>
          )}
          {!grupos.length && (
            <div className="empty">
              No hay recursos {pestana === 'busy' ? 'en uso' : 'disponibles'}{q ? ' que coincidan con la búsqueda' : ''}.
              {!recursos.length && ' Crea equipos en Configuration → Equipments List.'}
            </div>
          )}
          {grupos.map(({ tipo, lista }) => (
            <div key={tipo.key}>
              <div style={{ margin: '10px 0 4px', fontWeight: 800, color: tipo.dot }}><span className="typedot" style={{ background: tipo.dot }} />{tipo.name}</div>
              <table className="done">
                <thead><tr><th>Recurso</th><th>Código</th><th>Org</th><th>Estado</th><th /></tr></thead>
                <tbody>
                  {lista.map((it) => {
                    const usos = inicio && fin ? usoDe(vista, bloqueos, it.name, inicio, fin, fila.id) : [];
                    const elegido = elegidos.includes(it.name);
                    return (
                      <tr key={it.name} data-recurso={it.name}>
                        <td><b>{it.name}</b></td><td>{it.code || '\u2014'}</td><td>{it.org || '\u2014'}</td>
                        <td>
                          {usos.length
                            ? <><span className="res-pill res-busy">En uso</span>
                              <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 3 }}>
                                {usos.map((u, i) => <span key={i}>{u.motivo} ({fmt(u.inicio)}&rarr;{fmt(u.fin)})<br /></span>)}
                              </div></>
                            : <span className="res-pill res-free">Libre</span>}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          {elegido
                            ? <button className="btn ghost sm" onClick={() => void alternar(it.name)}>&#10003; Quitar</button>
                            : <button className="ru" onClick={() => void alternar(it.name)}>Añadir</button>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ))}
        </div>
        <div style={{ textAlign: 'right', marginTop: 14 }}><button className="btn" onClick={onCerrar}>Cerrar</button></div>
      </Dialogo>
      {avisoFlotante}
    </>
  );
}
