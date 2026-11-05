package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.calendario.Calendario;

import java.time.LocalDate;

/**
 * Calendario de festivos DEL USUARIO (de este PC), como en la 4.9.4, donde se
 * guardaba en el navegador (discrepancia D6, se conserva).
 */
public interface AlmacenCalendario {

    /** Calendario guardado; si no hay ninguno, el de un PC nuevo para el año de `hoy`. */
    Calendario leer(LocalDate hoy);

    void guardar(Calendario calendario);
}
