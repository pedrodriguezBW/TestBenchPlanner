package com.borgwarner.tbp.aplicacion.puertos;

import java.util.List;

/**
 * Otra persona ha cambiado (o borrado) un ensayo desde que se leyó.
 * Lleva el texto actual de cada fila en conflicto para poder enseñarlo.
 */
public class ConflictoEdicion extends RuntimeException {

    /** Fila en conflicto: uid y texto DataJson actual (null si la han borrado). */
    public record FilaEnConflicto(String uid, String textoActual) {
    }

    private final List<FilaEnConflicto> filas;

    public ConflictoEdicion(List<FilaEnConflicto> filas) {
        super("Otra persona ha cambiado este ensayo");
        this.filas = List.copyOf(filas);
    }

    public List<FilaEnConflicto> filas() {
        return filas;
    }
}
