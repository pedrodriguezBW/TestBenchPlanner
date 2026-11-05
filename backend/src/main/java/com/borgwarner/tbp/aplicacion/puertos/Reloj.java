package com.borgwarner.tbp.aplicacion.puertos;

import java.time.Instant;
import java.time.LocalDate;

/** Fecha y hora actuales (se pueden fijar para pruebas y para reproducir un día concreto). */
public interface Reloj {

    LocalDate hoy();

    Instant ahora();
}
