package com.borgwarner.tbp.aplicacion;

/**
 * La pestaña que pidió una consulta se ha cerrado o recargado: nadie espera ya
 * la respuesta. No es un error; no se registra ni se contesta.
 */
public class PeticionCancelada extends RuntimeException {

    public PeticionCancelada() {
        super("La pestaña que pidió esta consulta ya no existe", null, false, false);
    }
}
