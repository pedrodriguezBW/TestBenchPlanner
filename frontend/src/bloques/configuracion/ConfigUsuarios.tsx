import { useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { UsuarioLista } from '../../api/tipos';
import { usePlanificador } from '../../estado/Planificador';

/*
 * 👤 Users/Rights Management (renderUsers de la 4.9.4, solo Admin). El acceso
 * se identifica por el email; quien no está en la lista entra como Read only.
 * Los ResponsibleName alimentan el desplegable Responsible.
 */

const rolNormal = (r: string) => {
  const s = String(r || '').toLowerCase().replace(/\s/g, '');
  return s === 'admin' || s === 'administrator' ? 'admin' : (s === 'editor' || s === 'edit' || s === 'write' ? 'editor' : 'readonly');
};
// Orden de la tabla como en la 4.9.4: ({admin:0,editor:1,readonly:2}[rol] || 3), así que
// los Admin (0, que cuenta como «falso») salen al final junto a los roles desconocidos (D29).
const ORDEN_ROL: Record<string, number> = { admin: 0, editor: 1, readonly: 2 };
const orden = (r: string) => ORDEN_ROL[r] || 3;
const rolGuardado = (r: string) => (r === 'admin' ? 'Admin' : r === 'editor' ? 'Editor' : 'ReadOnly');

export function ConfigUsuarios() {
  const { soloLectura, recargarListas } = usePlanificador();
  const [usuarios, setUsuarios] = useState<UsuarioLista[] | null>(null);
  const [form, setForm] = useState({ title: '', email: '', role: 'Editor', responsibleName: '' });
  const [estado, setEstado] = useState('');

  useEffect(() => {
    api.usuarios().then((u) => setUsuarios(u.map((x) => ({ ...x, role: rolNormal(x.role) })))).catch(() => setUsuarios([]));
  }, []);

  const guardar = async (lista: UsuarioLista[]) => {
    setUsuarios(lista);
    setEstado('Guardando...');
    try {
      await api.guardarUsuarios(lista.map((u) => ({ title: u.title, email: u.email, role: rolGuardado(u.role), responsibleName: u.responsibleName })));
      setEstado('Guardado en SharePoint.');
      await recargarListas();
    } catch (e) {
      setEstado(e instanceof Error && e.message ? `No se pudo guardar: ${e.message}` : 'No se pudo guardar (backend).');
      api.usuarios().then((u) => setUsuarios(u.map((x) => ({ ...x, role: rolNormal(x.role) })))).catch(() => undefined);
    }
  };

  const busca = (email: string) => (usuarios ?? []).find((u) => (u.email || '').toLowerCase() === String(email || '').toLowerCase());
  const anadir = () => {
    const email = form.email.trim().toLowerCase();
    if (!email) { window.alert('Introduce el email.'); return; }
    const registro = { title: form.title.trim() || email, email, role: form.role.toLowerCase().replace(' ', ''), responsibleName: form.responsibleName.trim() };
    const lista = (usuarios ?? []).map((u) => ({ ...u }));
    const i = lista.findIndex((u) => (u.email || '').toLowerCase() === email);
    if (i >= 0) lista[i] = { ...lista[i], ...registro }; else lista.push(registro);
    setForm({ title: '', email: '', role: 'Editor', responsibleName: '' }); // la 4.9.4 vuelve a pintar el formulario con Editor
    void guardar(lista);
  };
  const editar = (email: string) => {
    const u = busca(email);
    if (u) setForm({ title: u.title || '', email: u.email || '', role: rolGuardado(rolNormal(u.role)), responsibleName: u.responsibleName || '' });
  };
  const quitar = (email: string) => {
    if (!window.confirm(`Quitar a ${email} de la lista de usuarios?`)) return;
    void guardar((usuarios ?? []).filter((u) => (u.email || '').toLowerCase() !== String(email).toLowerCase()));
  };

  const filas = [...(usuarios ?? [])].sort((a, b) => (orden(a.role) - orden(b.role)) || String(a.title || '').localeCompare(String(b.title || '')));
  return (
    <div className="sec" style={{ maxWidth: 1000 }} id="config-usuarios">
      <h2>&#128100; Users/Rights Management</h2>
      <div className="hint">
        El acceso se identifica por el <b>email corporativo</b> (SSO). La app muestra el <b>ResponsibleName</b> en vez del email. Cualquiera que no
        este en esta lista entra como <b>Read only</b>. Estos nombres alimentan tambien el desplegable de Responsible.
      </div>
      <div className="tools" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', margin: '12px 0' }}>
        <input className="f" id="u-name" placeholder="Nombre (Title)" style={{ minWidth: 170 }} value={form.title} disabled={soloLectura}
          onChange={(e) => setForm({ ...form, title: e.target.value })} />
        <input className="f" id="u-email" placeholder="email@borgwarner.com" style={{ minWidth: 220 }} value={form.email} disabled={soloLectura}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <select className="f" id="u-role" value={form.role} disabled={soloLectura} onChange={(e) => setForm({ ...form, role: e.target.value })}>
          <option value="Admin">Admin</option><option value="Editor">Editor</option><option value="ReadOnly">ReadOnly</option>
        </select>
        <input className="f" id="u-resp" placeholder="ResponsibleName (p. ej. A. Vide)" style={{ minWidth: 190 }} value={form.responsibleName}
          disabled={soloLectura} onChange={(e) => setForm({ ...form, responsibleName: e.target.value })} />
        <button className="btn sm" disabled={soloLectura} onClick={anadir}>&#65291; Add / Update</button>
        <span className="hint" id="u-status" style={{ marginLeft: 'auto' }}>{estado}</span>
      </div>
      <div className="card" style={{ padding: 0 }}>
        <table className="lst users-tbl">
          <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>ResponsibleName</th><th /></tr></thead>
          <tbody>
            {usuarios !== null && !filas.length && (
              <tr><td colSpan={5} className="empty">No users loaded (backend/list missing). Se sembraran los usuarios por defecto al primer guardado.</td></tr>
            )}
            {filas.map((u) => (
              <tr key={u.email}>
                <td><b>{u.title || ''}</b></td><td>{u.email || ''}</td>
                <td><span className={`rl-badge ${u.role === 'admin' ? 'rl-admin' : u.role === 'editor' ? 'rl-editor' : 'rl-readonly'}`}>{u.role || 'readonly'}</span></td>
                <td>{u.responsibleName || ''}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  <button className="btn ghost sm" disabled={soloLectura} onClick={() => editar(u.email)}>&#9998;</button>
                  <button className="icbtn del" disabled={soloLectura} onClick={() => quitar(u.email)}>&#128465;</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="hint" style={{ marginTop: 10 }}>
        Los nombres de esta tabla (<b>ResponsibleName</b>) son los que aparecen en el desplegable <b>Responsible</b> de los ensayos y en <b>By
        Responsible</b>. Ya no hay un catalogo de responsables aparte: esta lista es la unica fuente. Aun asi, en un ensayo puedes escribir
        cualquier nombre libremente.
      </div>
    </div>
  );
}
