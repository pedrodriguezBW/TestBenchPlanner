import { usePlanificador } from '../estado/Planificador';
import { Dialogo } from './Dialogo';
import { fmt } from '../utilidades/fechas';

/** Avisos breves (abajo a la derecha) y mensajes que hay que aceptar. */
export function Avisos() {
  const { avisos, mensajeModal, cerrarMensajeModal } = usePlanificador();
  return (
    <>
      <div className="tbp-avisos">
        {avisos.map((a) => <div key={a.id} className={`tbp-aviso ${a.tipo}`}>{a.texto}</div>)}
      </div>
      <Dialogo abierto={mensajeModal !== null} onCerrar={cerrarMensajeModal}>
        <div className="tbp-mensaje" style={{ whiteSpace: 'pre-line' }}>{mensajeModal}</div>
        <div style={{ textAlign: 'right', marginTop: 14 }}>
          <button className="btn" autoFocus onClick={cerrarMensajeModal}>OK</button>
        </div>
      </Dialogo>
    </>
  );
}

/** «Otra persona ha cambiado este ensayo»: ver su versión o guardar la mía encima. */
export function DialogoConflicto() {
  const { conflicto } = usePlanificador();
  if (!conflicto) return null;
  return (
    <Dialogo abierto>
      <h3>Otra persona ha cambiado este ensayo</h3>
      <p className="hint">
        Desde que abriste la pantalla, alguien ha guardado otra versión. Si guardas la tuya, sus cambios en este ensayo se perderán.
      </p>
      {conflicto.filas.map((f) => (
        <div key={f.id} className="tbp-conflicto">
          {f.borrado ? (
            <b>El ensayo ha sido borrado por otra persona.</b>
          ) : (
            <table className="done">
              <tbody>
                <tr><td>Proyecto</td><td>{String(f.datos?.project ?? '\u2014')}</td></tr>
                <tr><td>Tipo</td><td>{String(f.datos?.type ?? '\u2014')}</td></tr>
                <tr><td>Comentarios</td><td>{String(f.datos?.comments ?? '\u2014')}</td></tr>
                <tr><td>Arranque real / Fin real</td><td>{fmt(f.datos?.actualStart as string)} / {fmt(f.datos?.trueEnd as string)}</td></tr>
                <tr><td>Avance</td><td>{String(f.datos?.progressActual ?? '\u2014')}</td></tr>
              </tbody>
            </table>
          )}
        </div>
      ))}
      <p className="hint">Tu cambio: {conflicto.descripcion}</p>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 14 }}>
        <button className="btn ghost" onClick={conflicto.verSuVersion}>Ver su versión</button>
        <button className="btn" style={{ background: 'var(--danger)', color: '#2a0b0b' }} onClick={conflicto.guardarLaMia}>
          Guardar la mía encima
        </button>
      </div>
    </Dialogo>
  );
}
