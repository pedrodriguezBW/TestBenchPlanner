package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura del histórico semanal de KPI (kpiHistRows de la 4.9.4).
 *
 * Distingue semanas «nuevas» (guardadas desde la 4.9.3, con DelayedDeclared y
 * ocupación) de las antiguas, cuyo retraso se toma de NotStarted.
 */
public final class HistoricoKpi {

    /** Semana del histórico con los nombres que usa la interfaz de la 4.9.4. */
    public record Semana(double year, double cw, String org, double benches, Double total, double starts, double ends,
                         double running, double noid, double behind, double delayed, double risk, Double occPct,
                         Double occBusy, Double occCap, @JsonProperty("isNew") boolean isNew, double unplanned, Map<String, Double> reasons) {
    }

    private HistoricoKpi() {
    }

    public static List<Semana> semanas(List<JsonNode> filas, String organizacion) {
        List<Semana> semanas = new ArrayList<>();
        for (JsonNode r : filas) {
            String org = ValorJs.verdadero(r.get("Org")) ? r.get("Org").asText() : "ALL";
            Map<String, Double> motivos = new LinkedHashMap<>();
            for (String columna : List.of("ReasonResources", "ReasonParts", "ReasonBreakdown", "ReasonTestDrop", "ReasonPriority", "ReasonOther")) {
                motivos.put(columna, oCero(r.get(columna)));
            }
            boolean nueva = presente(r.get("DelayedDeclared"));
            semanas.add(new Semana(
                    unario(r.get("Year")), unario(r.get("CW")), org, oCero(r.get("Benches")),
                    presente(r.get("TotalBenches")) ? unario(r.get("TotalBenches")) : null,
                    oCero(r.get("Launches")), oCero(r.get("Finishing")), oCero(r.get("AccruingHours")),
                    oCero(r.get("NoApprovedID")), oCero(r.get("Behind")),
                    nueva ? oCero(r.get("DelayedDeclared")) : oCero(r.get("NotStarted")),
                    oCero(r.get("RiskBehind")),
                    presente(r.get("OccPct")) ? unario(r.get("OccPct")) : null,
                    presente(r.get("OccHoursBusy")) ? unario(r.get("OccHoursBusy")) : null,
                    presente(r.get("OccHoursCap")) ? unario(r.get("OccHoursCap")) : null,
                    nueva, oCero(r.get("Unplanned")), motivos));
        }
        return semanas.stream()
                .filter(s -> s.org().equals(organizacion))
                .sorted(Comparator.comparingDouble(Semana::year).thenComparingDouble(Semana::cw))
                .toList();
    }

    /** v != null && v !== '' */
    private static boolean presente(JsonNode v) {
        return v != null && !v.isNull() && !(v.isTextual() && v.textValue().isEmpty());
    }

    /** +v (un campo ausente da NaN, que en JSON se escribe null). */
    private static double unario(JsonNode v) {
        return ValorJs.numero(v);
    }

    /** +v || 0 */
    private static double oCero(JsonNode v) {
        double numero = ValorJs.numero(v);
        return (Double.isNaN(numero) || numero == 0) ? 0 : numero;
    }
}
