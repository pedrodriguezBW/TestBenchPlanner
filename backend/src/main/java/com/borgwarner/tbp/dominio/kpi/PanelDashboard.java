package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Las dos tarjetas de ocupación del Dashboard de la 4.9.4:
 *
 *  - «Ocupación por tipo de banco» (utilCardHTML): por grupo, bancos, bancos
 *    trabajando, horas ocupadas/disponibles e inicios/fines en el periodo.
 *  - «% Ocupación por CW» (trendData): la misma ocupación, semana a semana.
 *
 * Las horas son las de {@link Ocupacion} (occupancyOf), igual que la foto semanal.
 */
public final class PanelDashboard {

    /** Una fila de la tabla de ocupación. */
    public record FilaOcupacion(String clave, String nombre, String color, int bancos, int trabajando,
                                int inicia, int acaba, long busy, long cap, long pct) {
    }

    /** Tabla completa con la fila TOTAL. */
    public record TablaOcupacion(List<FilaOcupacion> grupos, long busy, long cap, long pct) {
    }

    /** Serie de un tipo de banco en la gráfica: un valor por semana. */
    public record Serie(String clave, String nombre, String color, int bancos, List<Long> busy, List<Long> pct) {
    }

    /**
     * Gráfica del año: semanas CW{desde}..CW{hasta} (ya acotadas como en la 4.9.4),
     * el número de semanas ISO del año y el lunes de cada semana.
     */
    public record Tendencia(int anio, int semanasDelAnio, int desde, int hasta, List<String> lunes,
                            List<Integer> cw, List<Serie> series) {
    }

    private PanelDashboard() {
    }

    /** Lunes de la semana ISO 1 del año (weekStart(new Date(año, 0, 4))). */
    public static LocalDate lunesSemanaUno(int anio) {
        return Fechas.lunesDe(LocalDate.of(anio, 1, 4));
    }

    /** Semanas ISO del año (cw(new Date(año, 11, 28))). */
    public static int semanasDelAnio(int anio) {
        return Fechas.semanaIso(LocalDate.of(anio, 12, 28));
    }

    public static TablaOcupacion tabla(Planificacion planificacion, ResultadoPlan plan, LocalDate desde, LocalDate hasta,
                                       Predicate<String> filtroBancos) {
        List<FilaOcupacion> filas = new ArrayList<>();
        long ocupadas = 0, disponibles = 0;
        for (Grupo grupo : planificacion.configuracion().grupos()) {
            List<String> bancos = grupo.benches().stream().filter(filtroBancos).toList();
            if (bancos.isEmpty()) {
                continue;
            }
            int trabajando = 0, inicia = 0, acaba = 0;
            for (String banco : bancos) {
                // Aquí cuentan TODOS los activos, también los tipos especiales (como en la 4.9.4).
                boolean algunoSolapa = false;
                for (Ensayo e : planificacion.activos(banco)) {
                    PlanEnsayo p = plan.de(e);
                    if (IndicadoresSemana.solapa(p, desde, hasta)) {
                        algunoSolapa = true;
                    }
                    if (dentro(p.inicio(), desde, hasta)) {
                        inicia++;
                    }
                    if (dentro(p.fin(), desde, hasta)) {
                        acaba++;
                    }
                }
                if (algunoSolapa) {
                    trabajando++;
                }
            }
            Ocupacion.Horas horas = Ocupacion.de(planificacion, plan, bancos, desde, hasta);
            ocupadas += horas.busy();
            disponibles += horas.cap();
            filas.add(new FilaOcupacion(grupo.key(), grupo.name(), grupo.dot(), bancos.size(), trabajando, inicia, acaba,
                    horas.busy(), horas.cap(), horas.pct()));
        }
        long porcentaje = disponibles != 0 ? (long) ValorJs.redondear(100.0 * ocupadas / disponibles) : 0;
        return new TablaOcupacion(filas, ocupadas, disponibles, porcentaje);
    }

    /**
     * Serie semanal. {@code cwDesde}/{@code cwHasta} pueden venir vacíos (0) o
     * fuera de rango: se acotan igual que trendData de la 4.9.4.
     */
    public static Tendencia tendencia(Planificacion planificacion, ResultadoPlan plan, int anio, int cwDesde, int cwHasta,
                                      Predicate<String> filtroBancos) {
        LocalDate inicio = lunesSemanaUno(anio);
        int semanas = semanasDelAnio(anio);
        int desde = Math.max(1, Math.min(cwDesde != 0 ? cwDesde : 1, semanas));
        int hasta = Math.max(desde, Math.min(cwHasta != 0 ? cwHasta : semanas, semanas));
        List<LocalDate> lunes = new ArrayList<>();
        for (int i = desde - 1; i < hasta; i++) {
            lunes.add(inicio.plusDays(7L * i));
        }
        List<Serie> series = new ArrayList<>();
        for (Grupo grupo : planificacion.configuracion().grupos()) {
            List<String> bancos = grupo.benches().stream().filter(filtroBancos).toList();
            if (bancos.isEmpty()) {
                continue;
            }
            List<Long> ocupadas = new ArrayList<>();
            List<Long> porcentajes = new ArrayList<>();
            for (LocalDate l : lunes) {
                Ocupacion.Horas h = Ocupacion.de(planificacion, plan, bancos, l, l.plusDays(6));
                ocupadas.add(h.busy());
                porcentajes.add(h.pct());
            }
            series.add(new Serie(grupo.key(), grupo.name(), grupo.dot(), bancos.size(), ocupadas, porcentajes));
        }
        return new Tendencia(anio, semanas, desde, hasta, lunes.stream().map(Fechas::iso).toList(),
                lunes.stream().map(Fechas::semanaIso).toList(), series);
    }

    private static boolean dentro(LocalDate dia, LocalDate desde, LocalDate hasta) {
        return dia != null && !dia.isBefore(desde) && !dia.isAfter(hasta);
    }
}
