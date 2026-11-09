import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { FilaActividad } from '../../api/tipos';

/*
 * 📜 Activity log (buildActivityHTML de la 4.9.4, solo Admin): quién se conecta
 * y quién hace cambios, más recientes primero. Es solo consulta.
 */
const ACCIONES: Record<string, string> = { connect: 'Conexión', edit: 'Cambios', self_update: 'Actualización', event: 'Evento' };

function sello(ts: number): string {
  const d = new Date(ts), ahora = new Date();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d.toDateString() === ahora.toDateString()) return hm;
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')} ${hm}`;
}

export function RegistroActividad() {
  const [filas, setFilas] = useState<FilaActividad[]>([]);
  const [leyendo, setLeyendo] = useState(false);
  const [leidoEn, setLeidoEn] = useState(0);
  const leer = useCallback(async () => {
    setLeyendo(true);
    try {
      const r = await api.actividad();
      if (Array.isArray(r.rows)) { setFilas(r.rows); setLeidoEn(Date.now()); }
    } catch {
      // se queda lo último leído
    } finally {
      setLeyendo(false);
    }
  }, []);
  useEffect(() => { void leer(); }, [leer]);

  return (
    <div className="sec" style={{ maxWidth: '100%' }} id="registro-actividad">
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <h2 style={{ margin: 0 }}>&#128220; Registro de actividad</h2>
        <button className="btn ghost sm" onClick={() => void leer()}>&#8635; Recargar</button>
        <span id="act-fresh" className={`data-fresh ${leyendo ? 'updating' : leidoEn ? 'ok' : 'stale'}`}>
          <span className="rf-dot" />{leyendo ? 'Actualizando\u2026' : leidoEn ? `Actualizado ${sello(leidoEn)}` : 'Sin datos aún'}
        </span>
        <span className="hint" style={{ margin: 0 }}>{filas.length} eventos (más recientes primero)</span>
      </div>
      <div className="hint">
        Quién se conecta y quién hace cambios (lista <b>TestBenchActivityLog</b>). Se muestra la última copia guardada y se actualiza en segundo plano.
      </div>
      <div className="card" style={{ padding: 0, maxHeight: '66vh', overflow: 'auto' }}>
        <table className="act-log">
          <thead><tr><th>Cuándo (UTC)</th><th>Usuario</th><th>Acción</th><th>Detalle</th></tr></thead>
          <tbody>
            {!filas.length && <tr><td colSpan={4} className="empty">Sin eventos todavía (o backend/lista no disponible).</td></tr>}
            {filas.map((r, i) => (
              <tr key={i}>
                <td>{String(r.when || '').replace('T', ' ').replace('Z', '') || '\u2014'}</td>
                <td><b>{r.name || r.email || '\u2014'}</b>{r.email && <><br /><span style={{ color: 'var(--muted)', fontSize: 11 }}>{r.email}</span></>}</td>
                <td className={`a-${r.action || ''}`}>{ACCIONES[r.action || ''] || r.action || ''}</td>
                <td>{r.detail || ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
