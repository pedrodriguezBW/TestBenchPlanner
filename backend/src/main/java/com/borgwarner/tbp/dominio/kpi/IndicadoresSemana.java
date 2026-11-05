package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.ensayo.MotivosRetraso;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.planificacion.Sugerencias;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Indicadores de una semana (lunes a domingo) sobre los ensayos ACTIVOS,
 * excluyendo los tipos especiales. Funciones blockKPIs y weekKPIs de la 4.9.4.
 */
public final class IndicadoresSemana {

    /** Bloque del dashboard («Esta semana», «Semana que viene»). Nombres como en la 4.9.4. */
    public record Bloque(int total, int benches, int accr, int delayed, int sug) {
    }

    /** KPI de la semana que se guardan en la foto semanal. */
    public record Semana(int benches, int starts, int ends, int running, int delayed, int unplanned,
                         Map<String, Integer> reasons) {
    }

    private IndicadoresSemana() {
    }

    public static Bloque bloque(Planificacion planificacion, ResultadoPlan plan, LocalDate lunes, Predicate<String> filtroBancos) {
        return bloque(planificacion, plan, lunes, filtroBancos, e -> true);
    }

    /**
     * Con filtro de filas (vista «By Responsible»: solo los ensayos de una
     * persona). El total de bancos no depende del filtro de filas.
     */
    public static Bloque bloque(Planificacion planificacion, ResultadoPlan plan, LocalDate lunes, Predicate<String> filtroBancos,
                                Predicate<Ensayo> filtroFilas) {
        LocalDate domingo = lunes.plusDays(6);
        int total = 0;
        Set<String> bancosConCarga = new HashSet<>();
        int corriendo = 0, enRetraso = 0, conSugerencia = 0;
        for (String banco : planificacion.bancos()) {
            if (!filtroBancos.test(banco)) {
                continue;
            }
            total++;
            for (Ensayo e : planificacion.activos(banco)) {
                if (!filtroFilas.test(e)) {
                    continue;
                }
                boolean especial = e.esTipoEspecial();
                PlanEnsayo p = plan.de(e);
                boolean solapa = solapa(p, lunes, domingo);
                if (solapa && !especial) {
                    bancosConCarga.add(banco);
                    corriendo++;
                }
                if (especial) {
                    continue;
                }
                // Un retraso cuenta si la barra solapa la semana o si su COMPROMISO cae en ella.
                if (!solapa && !compromisoEnSemana(e, lunes, domingo)) {
                    continue;
                }
                if (EstadosEnsayo.EN_RETRASO.equals(EstadosEnsayo.estado(e, plan.contexto().hoy()))) {
                    enRetraso++;
                }
                if (Sugerencias.abierta(e, p, plan.contexto())) {
                    conSugerencia++;
                }
            }
        }
        return new Bloque(total, bancosConCarga.size(), corriendo, enRetraso, conSugerencia);
    }

    public static Semana semana(Planificacion planificacion, ResultadoPlan plan, LocalDate lunes, Predicate<String> filtroBancos) {
        LocalDate domingo = lunes.plusDays(6);
        Set<String> bancosConCarga = new HashSet<>();
        int inician = 0, acaban = 0, corriendo = 0, enRetraso = 0, enCola = 0;
        Map<String, Integer> motivos = new LinkedHashMap<>();
        MotivosRetraso.COLUMNAS.forEach(columna -> motivos.put(columna, 0));
        LocalDate hoy = plan.contexto().hoy();
        for (String banco : planificacion.bancos()) {
            if (!filtroBancos.test(banco)) {
                continue;
            }
            for (Ensayo e : planificacion.activos(banco)) {
                boolean especial = e.esTipoEspecial();
                PlanEnsayo p = plan.de(e);
                boolean solapa = solapa(p, lunes, domingo);
                if (solapa && !especial) {
                    bancosConCarga.add(banco);
                }
                if (!especial && dentro(p.inicio(), lunes, domingo)) {
                    inician++;
                }
                if (!especial && dentro(p.fin(), lunes, domingo)) {
                    acaban++;
                }
                if (solapa && !especial) {
                    corriendo++;
                }
                String estado = EstadosEnsayo.estado(e, hoy);
                if (EstadosEnsayo.EN_COLA.equals(estado)) {
                    enCola++;
                }
                if (!especial && EstadosEnsayo.EN_RETRASO.equals(estado)
                        && (solapa || compromisoEnSemana(e, lunes, domingo))) {
                    enRetraso++;
                    if (e.verdadero("delayReason")) {
                        String columna = MotivosRetraso.columnaKpi(e.texto("delayReason"));
                        if (columna != null) {
                            motivos.merge(columna, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return new Semana(bancosConCarga.size(), inician, acaban, corriendo, enRetraso, enCola, motivos);
    }

    /** La barra (inicio → fin) toca la semana. */
    static boolean solapa(PlanEnsayo p, LocalDate desde, LocalDate hasta) {
        return p.tieneFechas() && !p.inicio().isAfter(hasta) && !p.fin().isBefore(desde);
    }

    private static boolean dentro(LocalDate dia, LocalDate desde, LocalDate hasta) {
        return dia != null && !dia.isBefore(desde) && !dia.isAfter(hasta);
    }

    /** Compromiso (Conf. start → Conf. end) dentro de la semana; sin Conf. end, basta el inicio. */
    private static boolean compromisoEnSemana(Ensayo e, LocalDate desde, LocalDate hasta) {
        LocalDate inicio = e.verdadero("confirmedStart") ? Fechas.dia(e.texto("confirmedStart")) : null;
        LocalDate fin = e.verdadero("confirmedEnd") ? Fechas.dia(e.texto("confirmedEnd")) : null;
        if (!e.verdadero("confirmedStart")) {
            return false;
        }
        if (e.verdadero("confirmedEnd")) {
            return Fechas.noPosterior(inicio, hasta) && Fechas.noPosterior(desde, fin);
        }
        return Fechas.noPosterior(desde, inicio) && Fechas.noPosterior(inicio, hasta);
    }
}
