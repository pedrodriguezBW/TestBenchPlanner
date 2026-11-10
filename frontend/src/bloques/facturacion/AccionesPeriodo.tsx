import { useState } from 'react';
import { apiFacturacion, descargarArchivo, type VistaPeriodo } from '../../api/facturacion';
import { Dialogo } from '../../componentes/Dialogo';
import { usePlanificador } from '../../estado/Planificador';
import { ESTADO_PERIODO, euros, fechaHora, numero } from './textos';

/*
 * Acciones de un periodo según permisos y estado (Recalcular, Enviar a
 * revisión, Aprobar, Reabrir, Exportar) con sus ventanas de confirmación.
 * Las comprobaciones las hace el backend; aquí se muestran antes de pedirlo.
 */

export function ChipPeriodo({ estado }: { estado: keyof typeof ESTADO_PERIODO }) {
  const e = ESTADO_PERIODO[estado];
  return <span className={`fac-chip ${e.clase}`}>{e.icono} {e.texto}</span>;
}

export function CabeceraPeriodo({ vista }: { vista: VistaPeriodo }) {
  const p = vista.periodo;
  const ind = vista.resumen.indicadores;
  return (
    <div>
      <div className="fac-head">
        <h2>Facturación de {p.nombre.toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}</h2>
        <ChipPeriodo estado={p.estado} />
        <span className="sub">Periodo: {fechaHora(p.inicio)} &ndash; {fechaHora(p.fin)}</span>
        <span className="sub">&middot; Último cálculo: {fechaHora(p.ultimoCalculo)}{p.calculadoPor ? ` (${p.calculadoPor})` : ''}</span>
        <span className="sub">&middot; Preparado por: {p.creadoPor || '\u2014'}</span>
        {p.versionAprobada > 0 && <span className="sub">&middot; Versión aprobada: v{p.versionAprobada}</span>}
      </div>
      <div className="fac-nota" style={{ marginBottom: 8 }}>
        Ensayos: <b>{ind.ensayos}</b> &middot; Listos: <b>{ind.preparadas}</b> &middot; Advertencias: <b>{ind.advertencias}</b> &middot;
        Bloqueados: <b style={{ color: ind.bloqueadas ? 'var(--danger)' : undefined }}>{ind.bloqueadas}</b> &middot; Pendientes: <b>{ind.pendientes}</b> &middot;
        Total: <b>{euros(ind.coste)}</b>
      </div>
    </div>
  );
}

export function AccionesPeriodo({ vista, ejecutar, ocupado }: {
  vista: VistaPeriodo;
  ejecutar: (accion: (version: string) => Promise<VistaPeriodo>, textoOk?: string) => Promise<boolean>;
  ocupado: boolean;
}) {
  const { sesion, soloLectura, mostrarMensaje } = usePlanificador();
  const [dialogo, setDialogo] = useState<'' | 'enviar' | 'aprobar' | 'reabrir' | 'exportar'>('');
  const [confirmado, setConfirmado] = useState(false);
  const [justificacion, setJustificacion] = useState('');
  const [tipos, setTipos] = useState<Record<string, boolean>>({ excel: true, controlling: true, html: false });
  const p = vista.periodo;
  const id = p.id;
  const esAdmin = !!sesion?.esAdmin;
  const puede = !soloLectura;
  const editable = p.estado === 'BORRADOR' || p.estado === 'EN_REVISION';
  const aprobado = p.estado === 'APROBADO' || p.estado === 'EXPORTADO';
  const ind = vista.resumen.indicadores;
  const ajustes = p.lineas.reduce((n, l) => n + Object.keys(l.ajustes || {}).length, 0);
  const cerrar = () => { setDialogo(''); setConfirmado(false); setJustificacion(''); };

  const exportar = async () => {
    const elegidos = Object.entries(tipos).filter(([, v]) => v).map(([k]) => k);
    if (!elegidos.length) return;
    let archivos: { nombre: string; b64: string }[] = [];
    const ok = await ejecutar(async (version) => {
      const r = await apiFacturacion.exportar(id, elegidos, version);
      archivos = r.exportaciones;
      return r.periodo;
    }, 'Exportación generada');
    if (ok) {
      archivos.forEach((a) => descargarArchivo(a));
      cerrar();
    }
  };

  return (
    <div className="fac-acciones no-print">
      {puede && editable && <button className="btn ghost ed" disabled={ocupado} id="fac-recalcular"
        onClick={() => void ejecutar((v) => apiFacturacion.recalcular(id, v), 'Periodo recalculado')}>&#8635; Recalcular</button>}
      {puede && p.estado === 'BORRADOR' && <button className="btn ed" disabled={ocupado} id="fac-enviar" onClick={() => setDialogo('enviar')}>&#10148; Enviar a revisión</button>}
      {puede && esAdmin && p.estado === 'EN_REVISION' && <button className="btn ed" disabled={ocupado} id="fac-aprobar" onClick={() => setDialogo('aprobar')}>&#128274; Aprobar</button>}
      {puede && esAdmin && aprobado && <button className="btn ghost ed" disabled={ocupado} id="fac-reabrir" onClick={() => setDialogo('reabrir')}>&#128275; Reabrir</button>}
      {puede && esAdmin && aprobado && <button className="btn ed" disabled={ocupado} id="fac-exportar" onClick={() => setDialogo('exportar')}>&#128228; Exportar</button>}

      <Dialogo abierto={dialogo === 'enviar'} onCerrar={cerrar}>
        <h3>Enviar a revisión &middot; {p.nombre}</h3>
        {vista.comprobacionesEnvio.length ? (
          <>
            <div className="fac-error">No se puede enviar todavía:</div>
            <ul>{vista.comprobacionesEnvio.map((t) => <li key={t}>{t}</li>)}</ul>
            <div className="fac-nota">Revisa las incidencias en Review (filtro «Con incidencias»).</div>
          </>
        ) : <p>Se comprobará que no hay errores bloqueantes, que hay al menos una línea facturable y que setups, órdenes, tarifas y reglas están completos.
          El periodo pasará a <b>En revisión</b>.</p>}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrar}>Cancelar</button>
          <button className="btn" disabled={ocupado || vista.comprobacionesEnvio.length > 0}
            onClick={() => void ejecutar((v) => apiFacturacion.enviar(id, v), 'Periodo enviado a revisión').then((ok) => ok && cerrar())}>Enviar a revisión</button>
        </div>
      </Dialogo>

      <Dialogo abierto={dialogo === 'aprobar'} ancho="wide" onCerrar={cerrar}>
        <h3>Aprobar &middot; {p.nombre}</h3>
        <div className="fac-kv">
          <span>Líneas incluidas</span><span>{ind.lineasContadas}</span>
          <span>Líneas excluidas</span><span>{ind.excluidas}</span>
          <span>Líneas aplazadas</span><span>{ind.aplazadas}</span>
          <span>Líneas pendientes (sin arrancar)</span><span>{ind.pendientes}</span>
          <span>Ajustes económicos</span><span>{ajustes} en {ind.ajustadas} línea(s)</span>
          <span>Horas netas / setup</span><span>{numero(ind.horasNetas)} h / {numero(ind.horasSetup)} h</span>
          <span>Importe total</span><span><b>{euros(ind.coste)}</b></span>
        </div>
        {vista.comprobacionesAprobacion.length > 0 && (
          <><div className="fac-error" style={{ marginTop: 10 }}>No se puede aprobar todavía:</div>
            <ul>{vista.comprobacionesAprobacion.map((t) => <li key={t}>{t}</li>)}</ul></>
        )}
        <label style={{ display: 'block', marginTop: 12 }}>
          <input type="checkbox" id="fac-confirmo" checked={confirmado} onChange={(e) => setConfirmado(e.target.checked)} /> He revisado las líneas, las incidencias y los ajustes.
        </label>
        <div className="fac-nota">Al aprobar se guarda la foto definitiva, se bloquean las líneas y se habilita la exportación.</div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrar}>Cancelar</button>
          <button className="btn" id="fac-aprobar-ok" disabled={ocupado || !confirmado || vista.comprobacionesAprobacion.length > 0}
            onClick={() => void ejecutar((v) => apiFacturacion.aprobar(id, v, true), 'Periodo aprobado').then((ok) => ok && cerrar())}>Aprobar periodo</button>
        </div>
      </Dialogo>

      <Dialogo abierto={dialogo === 'reabrir'} onCerrar={cerrar}>
        <h3>Reabrir &middot; {p.nombre}</h3>
        <p>El periodo volverá a <b>En revisión</b>. Se conserva la foto aprobada v{p.versionAprobada} y las exportaciones existentes quedarán marcadas como
          <b> obsoletas</b> (no se borran). Para obtener ficheros válidos habrá que aprobar y exportar de nuevo.</p>
        <textarea className="fac-campo" id="fac-reabrir-just" rows={3} style={{ width: '100%' }} placeholder="Justificación (obligatoria)"
          value={justificacion} onChange={(e) => setJustificacion(e.target.value)} />
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrar}>Cancelar</button>
          <button className="btn" id="fac-reabrir-ok" disabled={ocupado || !justificacion.trim()}
            onClick={() => void ejecutar((v) => apiFacturacion.reabrir(id, v, justificacion), 'Periodo reabierto').then((ok) => ok && cerrar())}>Reabrir</button>
        </div>
      </Dialogo>

      <Dialogo abierto={dialogo === 'exportar'} onCerrar={cerrar}>
        <h3>Exportar &middot; {p.nombre} (v{p.versionAprobada})</h3>
        {[['excel', 'Excel completo del periodo'], ['controlling', 'Excel reducido de Controlling'], ['html', 'HTML con formato de presentación']].map(([k, t]) => (
          <label key={k} style={{ display: 'block', margin: '6px 0' }}>
            <input type="checkbox" data-tipo={k} checked={!!tipos[k]} onChange={(e) => setTipos({ ...tipos, [k]: e.target.checked })} /> {t}
          </label>
        ))}
        <button className="btn ghost sm" onClick={() => setTipos({ excel: true, controlling: true, html: true })}>Generar todos</button>
        <div className="fac-nota" style={{ marginTop: 8 }}>Los ficheros reflejan la versión aprobada v{p.versionAprobada}. Cada exportación queda registrada con su identificador.</div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
          <button className="btn ghost" onClick={cerrar}>Cancelar</button>
          <button className="btn" id="fac-exportar-ok" disabled={ocupado || !Object.values(tipos).some(Boolean)} onClick={() => void exportar().catch((e: Error) => mostrarMensaje(e.message))}>Exportar</button>
        </div>
      </Dialogo>
    </div>
  );
}
