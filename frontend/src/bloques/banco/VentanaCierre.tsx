import { useEffect, useState } from 'react';
import { api } from '../../api/cliente';
import type { PropuestaCierre, ValidacionCierre, ValoresCierre } from '../../api/tipos';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { fmt } from '../../utilidades/fechas';
import { COLOR_ESTADO, ESTADOS_DECLARABLES, etiquetaMotivo, MOTIVOS_RETRASO } from '../../utilidades/textos';

/*
 * Ventana de cierre (openCloseModal / closeRecalc / confirmClose de la 4.9.4).
 * Los valores propuestos y TODA la validación los da el backend; aquí solo se
 * muestran y se envían. «Corregir» un cerrado es solo para Admin.
 */
export interface DatosCierre {
  banco: string;
  id: string;
  correccion: boolean;
  titulo: string;
}

const estiloCampo = { width: '100%', padding: '6px 8px', borderRadius: 6, background: 'var(--navy)', color: 'var(--text)', border: '1px solid var(--line)' };

export function VentanaCierre({ datos, onCerrar }: { datos: DatosCierre | null; onCerrar: () => void }) {
  const { cerrarEnsayo, mostrarMensaje } = usePlanificador();
  const [propuesta, setPropuesta] = useState<PropuestaCierre | null>(null);
  const [valores, setValores] = useState<ValoresCierre | null>(null);
  const [validacion, setValidacion] = useState<ValidacionCierre | null>(null);
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    setPropuesta(null);
    setValores(null);
    setValidacion(null);
    if (!datos) return;
    api.propuestaCierre(datos.banco, datos.id, datos.correccion)
      .then((p) => { setPropuesta(p); setValores(p.valores); setValidacion(p.validacion); })
      .catch((e: Error) => { onCerrar(); mostrarMensaje(e.message); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [datos]);

  // Validación en vivo: el backend recalcula desviación, facturables y errores.
  useEffect(() => {
    if (!datos || !valores) return;
    const t = window.setTimeout(() => {
      api.validarCierre(datos.banco, datos.id, valores).then(setValidacion).catch(() => undefined);
    }, 150);
    return () => window.clearTimeout(t);
  }, [datos, valores]);

  if (!datos || !propuesta || !valores) return null;

  const cambiar = (campo: keyof ValoresCierre, valor: string) => setValores({ ...valores, [campo]: valor });
  const v = validacion ?? propuesta.validacion;
  const parada = valores.diasParada === '' ? 0 : Number(valores.diasParada);

  let desviacion = <span style={{ color: 'var(--muted)' }}>&mdash;</span>;
  if (v.desviacion !== null) {
    desviacion = v.desviacion > 0 ? <span style={{ color: COLOR_ESTADO.delayed }}>+{v.desviacion} d tarde</span>
      : v.desviacion < 0 ? <span style={{ color: COLOR_ESTADO.running }}>{-v.desviacion} d antes</span>
        : <span style={{ color: COLOR_ESTADO.running }}>En la fecha</span>;
  }
  const facturables = v.diasCalendario
    ? `${Math.max(0, v.diasCalendario - (parada || 0))} d  ( ${v.diasCalendario} d de calendario \u2212 ${parada || 0} d de parada )`
    : '\u2014';

  const confirmar = async () => {
    if (v.error) return;
    setEnviando(true);
    const ok = await cerrarEnsayo(datos.banco, datos.id, valores, datos.correccion);
    setEnviando(false);
    if (ok) onCerrar();
  };

  const comprometido = propuesta.comprometidoInicio
    ? `${fmt(propuesta.comprometidoInicio)} \u2192 ${propuesta.comprometidoFin ? fmt(propuesta.comprometidoFin) : '?'}` : 'Sin compromiso';

  return (
    <Dialogo abierto onCerrar={onCerrar}>
      <h3 id="cl-title">{datos.correccion ? 'Corregir ensayo cerrado' : 'Cerrar ensayo'} &middot; {datos.banco}</h3>
      <div className="hint" id="cl-sub">{datos.titulo}</div>
      <div className="tbp-cierre">
        <label>Compromiso</label><div id="cl-conf">{comprometido}</div>
        <label htmlFor="cl-ts">Arranque real</label>
        <input id="cl-ts" type="date" style={estiloCampo} value={valores.arranqueReal} onChange={(e) => cambiar('arranqueReal', e.target.value)} />
        <label htmlFor="cl-te">Fin real</label>
        <input id="cl-te" type="date" style={estiloCampo} value={valores.finReal} onChange={(e) => cambiar('finReal', e.target.value)} />
        <label>Desviación</label><div id="cl-dev">{desviacion}</div>
        <label htmlFor="cl-st">Estado final</label>
        <div>
          <select id="cl-st" style={estiloCampo} value={valores.estadoFinal} onChange={(e) => cambiar('estadoFinal', e.target.value)}>
            {ESTADOS_DECLARABLES.map((e) => <option key={e.valor} value={e.valor}>{e.etiqueta}</option>)}
          </select>
          <div id="cl-sug" style={{ color: 'var(--warn)', fontSize: 12 }}>
            {v.sugerirRetraso ? '\u26a0 El fin real supera el comprometido: \u00bfEn retraso?' : ''}
          </div>
        </div>
        <label htmlFor="cl-rs">Motivo</label>
        <select id="cl-rs" value={valores.motivo} onChange={(e) => cambiar('motivo', e.target.value)}
          style={{ ...estiloCampo, borderColor: valores.estadoFinal === 'delayed' && !valores.motivo ? 'var(--danger)' : 'var(--line)' }}>
          {MOTIVOS_RETRASO.map((m) => <option key={m} value={m}>{m ? etiquetaMotivo(m) : '\u2014'}</option>)}
        </select>
        <label htmlFor="cl-cm">Explicación</label>
        <textarea id="cl-cm" rows={3} placeholder="Opcional (obligatoria con el motivo Otro)" value={valores.explicacion}
          onChange={(e) => cambiar('explicacion', e.target.value)}
          style={{ ...estiloCampo, resize: 'vertical', borderColor: valores.motivo === 'Otro' && !valores.explicacion.trim() ? 'var(--danger)' : 'var(--line)' }} />
        <label htmlFor="cl-dd">Días de parada</label>
        <input id="cl-dd" type="number" min="0" step="0.5" style={{ ...estiloCampo, width: 90 }} value={valores.diasParada}
          onChange={(e) => cambiar('diasParada', e.target.value)} />
        <label htmlFor="cl-dn">Nota de parada</label>
        <input id="cl-dn" placeholder="Opcional" style={estiloCampo} value={valores.notaParada} onChange={(e) => cambiar('notaParada', e.target.value)} />
        <label>Días facturables</label><div id="cl-bill">{facturables}</div>
      </div>
      <div id="cl-err" style={{ color: 'var(--danger)', fontWeight: 700, minHeight: 18, marginTop: 8 }}>{v.error}</div>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
        <button className="btn ghost" onClick={onCerrar}>Cancelar</button>
        <button className="btn" id="cl-ok" disabled={!!v.error || enviando} onClick={() => void confirmar()}>
          {datos.correccion ? 'Guardar corrección' : 'Confirmar y cerrar'}
        </button>
      </div>
    </Dialogo>
  );
}
