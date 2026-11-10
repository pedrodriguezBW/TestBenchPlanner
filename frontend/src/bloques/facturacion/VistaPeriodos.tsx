import { useCallback, useEffect, useState } from 'react';
import { apiFacturacion, type PeriodoListado } from '../../api/facturacion';
import { usePlanificador } from '../../estado/Planificador';
import { ChipPeriodo } from './AccionesPeriodo';
import { elegirPeriodo } from './estadoPeriodo';
import { euros, fechaHora, fechasPeriodo, MESES } from './textos';

/*
 * Billing › Periods: periodos de facturación (del 21 al 20). Crear un periodo
 * solo pide el mes nominal: las fechas salen solas y no se editan.
 */
export function VistaPeriodos({ onAbrir }: { onAbrir: (destino: '__billing_review__' | '__billing_overview__') => void }) {
  const { sesion, soloLectura, vista, mostrarMensaje, avisar } = usePlanificador();
  const [lista, setLista] = useState<PeriodoListado[] | null>(null);
  const hoy = vista?.hoy ?? new Date().toISOString().slice(0, 10);
  const [anioHoy, mesHoy, diaHoy] = hoy.split('-').map(Number);
  // Por defecto, el periodo del día de hoy (desde el 21, el del mes siguiente).
  const porDefecto = diaHoy > 20 ? (mesHoy === 12 ? [anioHoy + 1, 1] : [anioHoy, mesHoy + 1]) : [anioHoy, mesHoy];
  const [anio, setAnio] = useState(porDefecto[0]);
  const [mes, setMes] = useState(porDefecto[1]);
  const [creando, setCreando] = useState(false);

  const cargar = useCallback(() => {
    apiFacturacion.periodos().then(setLista).catch((e: Error) => { setLista([]); mostrarMensaje(e.message); });
  }, [mostrarMensaje]);
  useEffect(() => { cargar(); }, [cargar]);

  const crear = async () => {
    setCreando(true);
    try {
      const v = await apiFacturacion.crearPeriodo(anio, mes);
      avisar(`Periodo ${v.periodo.nombre} creado y calculado`);
      elegirPeriodo(v.periodo.id);
      onAbrir('__billing_review__');
    } catch (e) {
      mostrarMensaje(e instanceof Error ? e.message : String(e));
      cargar();
    } finally {
      setCreando(false);
    }
  };

  return (
    <div>
      <div className="view-head"><h2>&#128182; Billing &middot; Periods</h2></div>
      <div className="hint">Cada periodo va del día 21 del mes anterior al día 20 del mes de la facturación. Los ensayos que atraviesan varios periodos
        se dividen solos en tramos. Flujo: Borrador &rarr; En revisión &rarr; Aprobado (bloqueado) &rarr; Exportado.</div>
      {!soloLectura && (
        <div className="card" style={{ marginBottom: 14 }}>
          <div className="row-inline" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <b>Crear periodo</b>
            <select className="fac-campo" id="fac-mes" value={mes} onChange={(e) => setMes(Number(e.target.value))}>
              {MESES.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
            </select>
            <input className="fac-campo" id="fac-anio" type="number" min={2020} max={2100} style={{ width: 90 }} value={anio} onChange={(e) => setAnio(Number(e.target.value))} />
            <span className="fac-nota">{fechasPeriodo(anio, mes)}</span>
            <button className="btn ed" id="fac-crear" disabled={creando} onClick={() => void crear()}>&#10133; Crear y calcular</button>
          </div>
        </div>
      )}
      <div className="card">
        {!lista ? <div className="empty">Cargando&hellip;</div> : !lista.length ? <div className="empty">Todavía no hay periodos de facturación.</div> : (
          <div className="fac-tabla-wrap">
            <table className="fac" id="fac-periodos">
              <thead><tr><th>Periodo</th><th>Fechas</th><th>Estado</th><th className="n">Ensayos</th><th className="n">Incidencias</th><th className="n">Importe</th>
                <th>Creado por</th><th>Último cálculo</th><th /></tr></thead>
              <tbody>
                {lista.map((p) => (
                  <tr key={p.id} data-periodo={p.id} className="clic" onClick={() => { elegirPeriodo(p.id); onAbrir('__billing_review__'); }}>
                    <td><b>{p.nombre}</b></td>
                    <td>{fechaHora(p.inicio).slice(0, 5)}&ndash;{fechaHora(p.fin).slice(0, 5)}</td>
                    <td><ChipPeriodo estado={p.estado} />{p.versionAprobada > 0 && <span className="fac-nota"> v{p.versionAprobada}</span>}</td>
                    <td className="n">{p.resumenRapido.ensayos ?? '\u2014'}</td>
                    <td className="n">{p.resumenRapido.incidencias ?? '\u2014'}{p.resumenRapido.bloqueadas ? <span className="fac-nota"> ({p.resumenRapido.bloqueadas} bloq.)</span> : null}</td>
                    <td className="n">{euros(p.resumenRapido.importe ?? null)}</td>
                    <td>{p.creadoPor || '\u2014'}</td>
                    <td>{fechaHora(p.ultimoCalculo)}</td>
                    <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }} onClick={(e) => e.stopPropagation()}>
                      <button className="btn ghost sm" onClick={() => { elegirPeriodo(p.id); onAbrir('__billing_review__'); }}>Abrir</button>{' '}
                      <button className="btn ghost sm" onClick={() => { elegirPeriodo(p.id); onAbrir('__billing_overview__'); }}>Overview</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="fac-nota" style={{ marginTop: 8 }}>Recalcular, enviar a revisión{sesion?.esAdmin ? ', aprobar, reabrir y exportar' : ''} se hace desde Review u Overview.</div>
      </div>
    </div>
  );
}
