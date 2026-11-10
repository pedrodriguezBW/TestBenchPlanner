package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Presencia, registro de actividad y versión publicada: las listas «de
 * servicio» que no forman parte de la planificación.
 */
public interface Colaboracion {

    /** Latido de presencia y lista de conectados: {me, online:[…]}. */
    ObjectNode latidoPresencia(AlmacenUsuarios.Identidad identidad);

    /** Añade un evento al registro (connect, edit, res_block…). Nunca debe fallar. */
    boolean anotarActividad(String email, String nombre, String accion, String detalle);

    /**
     * Igual que anotarActividad, pero sin hacer esperar a quien lo pide (un
     * guardado o la identificación no deben tardar más por el registro). Por
     * defecto se anota en el acto; SharePoint lo hace en segundo plano.
     */
    default void anotarActividadSinEsperar(String email, String nombre, String accion, String detalle) {
        anotarActividad(email, nombre, accion, detalle);
    }

    /** Últimos eventos: {rows:[{email, name, action, detail, when}], columns:[…]}. */
    ObjectNode leerActividad(int maximo);

    /** Versión publicada en TestBenchAppConfig (null si no hay). */
    String versionPublicada();

    /** Publica la versión solo si es estrictamente mayor que la publicada. */
    boolean publicarVersion(String version);
}
