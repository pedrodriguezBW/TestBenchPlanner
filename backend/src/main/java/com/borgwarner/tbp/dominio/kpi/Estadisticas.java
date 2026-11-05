package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Estadísticas de ensayos cerrados (statRows de la 4.9.4).
 *
 * Cada ensayo cerrado cuenta una vez, por el estado final declarado al
 * cerrarlo. La fecha de referencia es la de arranque: real confirmado, o el
 * comprometido, o el histórico, o el día del cierre.
 */
public final class Estadisticas {

    /** Una fila de la estadística. */
    public record Fila(String banco, Ensayo ensayo, LocalDate fecha, String estadoFinal, String motivo, boolean confirmado) {
    }

    private Estadisticas() {
    }

    public static List<Fila> filas(Planificacion planificacion, LocalDate desde, LocalDate hasta,
                                   String organizacion, String responsable) {
        List<Fila> filas = new ArrayList<>();
        for (String banco : planificacion.bancos()) {
            if (!"ALL".equals(organizacion) && !organizacion.equals(planificacion.configuracion().organizacion(banco))) {
                continue;
            }
            for (Ensayo e : planificacion.ensayos(banco)) {
                if (!e.cerrado() || e.esTipoEspecial()) {
                    continue;
                }
                if (responsable != null && !responsable.isEmpty() && !responsable.equals(e.texto("resp"))) {
                    continue;
                }
                LocalDate fecha = fechaDeReferencia(e);
                if (fecha == null || fecha.isBefore(desde) || fecha.isAfter(hasta)) {
                    continue;
                }
                String motivo = e.verdadero("finalReason") ? e.texto("finalReason") : e.texto("delayReason");
                filas.add(new Fila(banco, e, fecha, EstadosEnsayo.estadoFinal(e), motivo, EstadosEnsayo.cierreConfirmado(e)));
            }
        }
        filas.sort(Comparator.comparing(Fila::fecha)); // ordenación estable, como en la 4.9.4
        return filas;
    }

    /** statDateOf: arranque confirmado, comprometido, histórico o día del cierre. */
    public static LocalDate fechaDeReferencia(Ensayo e) {
        for (String campo : List.of("finalActualStart", "confirmedStart", "histStart", "doneDate")) {
            if (e.verdadero(campo)) {
                return Fechas.dia(e.texto(campo));
            }
        }
        return null;
    }
}
