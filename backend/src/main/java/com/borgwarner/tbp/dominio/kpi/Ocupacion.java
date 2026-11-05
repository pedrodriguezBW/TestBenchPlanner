package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Duraciones;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ocupación por horas (occupancyOf / benchBusyHours de la 4.9.4).
 *
 *   % = horas ocupadas / horas disponibles
 *   disponibles = días del periodo × 24 h × nº de bancos (fines de semana incluidos)
 *   ocupadas    = Σ por banco y día de las horas con algún ensayo, con tope de 24 h/día
 *
 * Weekend = No solo ocupa días laborables; un ensayo de menos de un día solo
 * cuenta sus horas en su día de inicio; los cerrados cuentan con sus fechas
 * históricas; los tipos especiales no cuentan.
 */
public final class Ocupacion {

    public static final int HORAS_DIA = 24;

    /** Resultado con los nombres de la 4.9.4: horas ocupadas, disponibles y porcentaje. */
    public record Horas(long busy, long cap, long pct) {
    }

    private Ocupacion() {
    }

    public static Horas de(Planificacion planificacion, ResultadoPlan plan, List<String> bancos, LocalDate desde, LocalDate hasta) {
        long capacidad = capacidad(bancos.size(), desde, hasta);
        long ocupadas = 0;
        for (String banco : bancos) {
            ocupadas += horasBanco(planificacion, plan, banco, desde, hasta);
        }
        long porcentaje = capacidad != 0 ? (long) ValorJs.redondear(100.0 * ocupadas / capacidad) : 0;
        return new Horas(ocupadas, capacidad, porcentaje);
    }

    public static long capacidad(int numeroBancos, LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) {
            return 0;
        }
        return (Fechas.diasEntre(desde, hasta) + 1) * HORAS_DIA * numeroBancos;
    }

    public static long horasBanco(Planificacion planificacion, ResultadoPlan plan, String banco, LocalDate desde, LocalDate hasta) {
        if (!planificacion.existeBanco(banco)) {
            return 0;
        }
        Calendario calendario = plan.contexto().calendario();
        Map<LocalDate, Double> porDia = new LinkedHashMap<>();
        for (Ensayo e : planificacion.activos(banco)) {
            if (e.esTipoEspecial()) {
                continue;
            }
            PlanEnsayo p = plan.de(e);
            sumar(porDia, e, p.inicio(), p.fin(), desde, hasta, calendario);
        }
        for (Ensayo e : planificacion.cerrados(banco)) {
            if (e.esTipoEspecial()) {
                continue;
            }
            if (e.verdadero("histStart") && e.verdadero("histEnd")) {
                sumar(porDia, e, Fechas.dia(e.texto("histStart")), Fechas.dia(e.texto("histEnd")), desde, hasta, calendario);
            }
        }
        double horas = 0;
        for (double valor : porDia.values()) {
            horas += Math.min(HORAS_DIA, valor);
        }
        return (long) ValorJs.redondear(horas);
    }

    private static void sumar(Map<LocalDate, Double> porDia, Ensayo e, LocalDate inicio, LocalDate fin,
                              LocalDate desde, LocalDate hasta, Calendario calendario) {
        if (inicio == null || fin == null) {
            return;
        }
        double dias = Duraciones.diasNominales(e);
        if (dias > 0 && dias < 1) {
            if (!inicio.isBefore(desde) && !inicio.isAfter(hasta)) {
                porDia.merge(inicio, dias * HORAS_DIA, Double::sum);
            }
            return;
        }
        boolean finDeSemana = e.finDeSemana();
        LocalDate dia = inicio.isBefore(desde) ? desde : inicio;
        LocalDate ultimo = fin.isAfter(hasta) ? hasta : fin;
        while (!dia.isAfter(ultimo)) {
            if (finDeSemana || calendario.esLaborable(dia)) {
                porDia.merge(dia, (double) HORAS_DIA, Double::sum);
            }
            dia = dia.plusDays(1);
        }
    }
}
