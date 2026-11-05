package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.dominio.planificacion.ContextoPlan;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;

/** Estado leído del almacén más sus fechas calculadas para hoy. */
public record Vista(Planificacion planificacion, ResultadoPlan plan, ContextoPlan contexto) {
}
