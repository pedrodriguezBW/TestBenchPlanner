package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.JsonPython;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Conversión de una foto semanal a la fila de TestBenchKPIHistory (_kpi_body
 * del backend 4.9.4). La comparten el almacén local y el de SharePoint.
 */
public final class FilaKpi {

    private FilaKpi() {
    }

    /** Clave de la fila: «2026-CW45-ALL». */
    public static String titulo(JsonNode foto) {
        String semana = foto.path("cw").asText();
        if (semana.length() < 2) {
            semana = "0" + semana;
        }
        return foto.path("year").asText() + "-CW" + semana + "-" + foto.path("org").asText();
    }

    public static ObjectNode desdeFoto(JsonNode s) {
        ObjectNode fila = JsonJs.objetoVacio();
        fila.put("Title", titulo(s));
        fila.set("Year", numero(s.get("year")));
        fila.set("CW", numero(s.get("cw")));
        String fecha = s.path("date").asText("");
        fila.set("SnapDate", fecha.isEmpty() ? NullNode.getInstance() : TextNode.valueOf(fecha.length() == 10 ? fecha + "T00:00:00Z" : fecha));
        fila.put("Org", s.hasNonNull("org") && !s.path("org").asText().isEmpty() ? s.path("org").asText() : "ALL");
        fila.set("Benches", numero(s.get("benches")));
        fila.set("TotalBenches", numero(s.get("total")));
        fila.set("Launches", numero(s.get("starts")));
        fila.set("Finishing", numero(s.get("ends")));
        fila.set("AccruingHours", numero(s.get("running")));
        // Columnas retiradas en la 4.9.3: se vacían para no arrastrar valores antiguos.
        fila.set("NoApprovedID", NullNode.getInstance());
        fila.set("Behind", NullNode.getInstance());
        fila.set("RiskBehind", NullNode.getInstance());
        // NotStarted repite el retraso declarado solo como respaldo.
        fila.set("NotStarted", numero(s.get("delayed")));
        fila.set("DelayedDeclared", numero(s.has("delayedDeclared") ? s.get("delayedDeclared") : s.get("delayed")));
        fila.set("OccHoursBusy", numero(s.get("occBusy")));
        fila.set("OccHoursCap", numero(s.get("occCap")));
        fila.set("OccPct", numero(s.get("occPct")));
        fila.set("Unplanned", numero(s.get("unplanned")));
        JsonNode motivos = s.path("reasons");
        for (String columna : new String[]{"ReasonResources", "ReasonParts", "ReasonBreakdown", "ReasonTestDrop", "ReasonPriority", "ReasonOther"}) {
            fila.set(columna, numero(motivos.get(columna)));
        }
        fila.put("KPIJson", JsonPython.escribir(s));
        return fila;
    }

    /** _num del backend: '' o null -> null; texto numérico -> número. */
    private static JsonNode numero(JsonNode valor) {
        if (valor == null || valor.isNull() || (valor.isTextual() && valor.textValue().isEmpty())) {
            return NullNode.getInstance();
        }
        if (valor.isTextual()) {
            try {
                return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(Double.parseDouble(valor.textValue()));
            } catch (NumberFormatException e) {
                return NullNode.getInstance();
            }
        }
        return valor;
    }
}
