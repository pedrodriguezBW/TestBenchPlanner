package com.borgwarner.tbp.dominio.listas;

/**
 * Se ha impedido reescribir una lista compartida (bancos, proyectos, usuarios…)
 * porque podría borrar datos por error. No se ha escrito nada.
 */
public class ReconciliacionBloqueada extends RuntimeException {

    public ReconciliacionBloqueada(String mensaje) {
        super(mensaje);
    }
}
