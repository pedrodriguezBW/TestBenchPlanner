package com.borgwarner.tbp.dominio.planificacion;

import java.time.LocalDate;
import java.util.List;

/**
 * Resultado del motor de fechas para un ensayo activo.
 *
 * @param inicio   inicio previsto (null si no tiene duración)
 * @param fin      fin previsto (null si no tiene duración)
 * @param criterio qué fija el inicio: real, asumido, forzado, cola, hoy, muestras
 * @param solapes  otros ensayos del banco que coinciden en el tiempo (aviso ⚠)
 */
public record PlanEnsayo(LocalDate inicio, LocalDate fin, String criterio, List<Solape> solapes) {

    /** Ensayo con el que se solapa: nombre (proyecto o tipo) y sus fechas. */
    public record Solape(String etiqueta, LocalDate inicio, LocalDate fin) {
    }

    public static final PlanEnsayo SIN_FECHAS = new PlanEnsayo(null, null, "", List.of());

    public boolean tieneFechas() {
        return inicio != null && fin != null;
    }
}
