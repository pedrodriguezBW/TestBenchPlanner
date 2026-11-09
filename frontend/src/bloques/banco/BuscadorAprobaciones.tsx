import { useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { Aprobacion } from '../../api/tipos';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { estiloPildoraAprobacion } from '../../utilidades/textos';

/*
 * Buscador de Approval ID (base de aprobaciones, solo lectura). La búsqueda se
 * rellena con el nombre del usuario, salvo para un Admin, como en la 4.9.4.
 */
export function BuscadorAprobaciones({ abierto, onElegir, onCerrar }: {
  abierto: boolean;
  onElegir: (id: string) => void;
  onCerrar: () => void;
}) {
  const { sesion, soloLectura } = usePlanificador();
  const [lista, setLista] = useState<Aprobacion[] | null>(null);
  const [busqueda, setBusqueda] = useState('');
  const [cargando, setCargando] = useState(false);

  const cargar = () => {
    setCargando(true);
    api.aprobaciones().then(setLista).catch(() => setLista((l) => l ?? [])).finally(() => setCargando(false));
  };

  useEffect(() => {
    if (!abierto) return;
    setBusqueda(sesion?.esAdmin ? '' : sesion?.responsable ?? '');
    if (!lista) cargar();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [abierto]);

  if (!abierto) return null;
  const q = busqueda.toLowerCase().trim();
  const todas = lista ?? [];
  const filtradas = q ? todas.filter((a) => `${a.id} ${a.raw ?? ''} ${a.requester ?? ''} ${a.service ?? ''}`.toLowerCase().includes(q)) : todas;
  const clase = (a: Aprobacion) => (a.status === 'approved' ? 'idok' : a.status === 'pending' ? 'idwait' : 'idbad');

  return (
    <Dialogo abierto ancho="xwide" onCerrar={onCerrar}>
      <h3 id="id-title">Approval IDs ({filtradas.length}{q ? ` of ${todas.length}` : ''})</h3>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10 }}>
        <input id="id-search" className="done-search" placeholder="Search by ID or status..." style={{ marginBottom: 0 }}
          value={busqueda} onChange={(e) => setBusqueda(e.target.value)} />
        <button className="btn ghost sm" style={{ whiteSpace: 'nowrap' }} onClick={cargar} disabled={cargando}>
          {cargando ? '\u23F3 Loading\u2026' : '\u{1F504} Reload live'}
        </button>
      </div>
      <div id="id-list" style={{ maxHeight: '60vh', overflow: 'auto' }}>
        {!filtradas.length ? <div className="empty">{cargando ? 'Loading\u2026' : 'No matching IDs.'}</div> : (
          <table className="done">
            <thead><tr><th>ID</th><th>Requester name</th><th>Service details</th><th>Global status</th><th /></tr></thead>
            <tbody>
              {filtradas.map((a) => (
                <tr key={a.id}>
                  <td><b>{a.id}</b></td>
                  <td>{a.requester || '\u2014'}</td>
                  <td>{a.service || '\u2014'}</td>
                  <td><span className="idpill" style={estiloPildoraAprobacion(clase(a))}>{a.raw || '\u2014'}</span></td>
                  <td style={{ textAlign: 'right' }}><button className="ru ed" disabled={soloLectura} onClick={() => onElegir(String(a.id))}>Select</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div style={{ textAlign: 'right', marginTop: 14 }}><button className="btn" onClick={onCerrar}>Close</button></div>
    </Dialogo>
  );
}
