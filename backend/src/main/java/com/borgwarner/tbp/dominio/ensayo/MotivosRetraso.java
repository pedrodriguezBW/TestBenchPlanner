package com.borgwarner.tbp.dominio.ensayo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motivos de retraso. El valor guardado es estable (p. ej. «Averia» sin tilde);
 * la etiqueta que se muestra puede cambiar sin tocar los datos.
 */
public final class MotivosRetraso {

    public static final List<String> VALORES = List.of(
            "", "Falta de recursos", "Piezas con retraso", "Averia", "Caida de ensayo", "Cambio de prioridad", "Otro");

    /** Motivo -> columna del histórico de KPI (reasonKey). */
    private static final Map<String, String> COLUMNA_KPI = new LinkedHashMap<>();

    static {
        COLUMNA_KPI.put("Falta de recursos", "ReasonResources");
        COLUMNA_KPI.put("Piezas con retraso", "ReasonParts");
        COLUMNA_KPI.put("Averia", "ReasonBreakdown");
        COLUMNA_KPI.put("Caida de ensayo", "ReasonTestDrop");
        COLUMNA_KPI.put("Cambio de prioridad", "ReasonPriority");
        COLUMNA_KPI.put("Otro", "ReasonOther");
    }

    /** Columnas de motivos en el orden en que se guardan en la foto semanal. */
    public static final List<String> COLUMNAS = List.of(
            "ReasonResources", "ReasonParts", "ReasonBreakdown", "ReasonTestDrop", "ReasonPriority", "ReasonOther");

    private MotivosRetraso() {
    }

    public static String columnaKpi(String motivo) {
        return COLUMNA_KPI.get(motivo);
    }
}
