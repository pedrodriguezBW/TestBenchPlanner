package com.borgwarner.tbp.dominio.planificacion;

import com.borgwarner.tbp.dominio.calendario.Calendario;

import java.time.LocalDate;

/** Lo que el motor necesita además de los ensayos: el día de hoy y el calendario. */
public record ContextoPlan(LocalDate hoy, Calendario calendario) {
}
