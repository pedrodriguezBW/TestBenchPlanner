package com.borgwarner.tbp.aplicacion;

/** El usuario no tiene permiso para la operación (p. ej. rol de solo lectura). */
public class SinPermiso extends RuntimeException {

    public SinPermiso(String mensaje) {
        super(mensaje);
    }
}
