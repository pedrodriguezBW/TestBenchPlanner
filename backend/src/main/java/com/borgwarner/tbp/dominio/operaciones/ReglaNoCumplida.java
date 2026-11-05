package com.borgwarner.tbp.dominio.operaciones;

/**
 * Una operación que la regla de negocio no permite. El mensaje es el aviso que
 * ve el usuario (el mismo texto que mostraba la 4.9.4).
 */
public class ReglaNoCumplida extends RuntimeException {

    public ReglaNoCumplida(String mensaje) {
        super(mensaje);
    }
}
