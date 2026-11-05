package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;

import java.util.List;

/**
 * Persistencia de los ensayos (lista TestBenchPlanner): una fila por ensayo con
 * su uid y su texto DataJson. Hoy hay dos implementaciones (local y SharePoint);
 * una base de datos SQL sería una tercera.
 */
public interface AlmacenEnsayos {

    /** Lee todas las filas (lectura autoritativa). */
    List<FilaGuardada> leer();

    /**
     * Guarda los cambios.
     *
     * @param comprobarVersion si es true, cada fila solo se escribe si su texto
     *                         actual sigue siendo el textoAnterior del cambio; si
     *                         alguna no lo es, se lanza ConflictoEdicion y NO se
     *                         escribe ninguna de las que estaban en conflicto.
     *                         Si es false, el último que guarda gana (contrato de
     *                         la 4.9.4, solo para la interfaz clásica).
     * @return resumen con el nº de altas, modificaciones y bajas
     */
    ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor);

    /** Quién hace el cambio (para el registro de actividad). */
    record Actor(String email, String nombre) {
    }

    record ResumenGuardado(int creados, int actualizados, int borrados) {
    }
}
