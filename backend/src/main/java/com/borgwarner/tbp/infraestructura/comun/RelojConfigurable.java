package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Fechas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Reloj de la aplicación.
 *
 * Decisión D7: «hoy» es SIEMPRE la fecha actual de Madrid en el momento de cada
 * petición, de modo que cambia a medianoche aunque la aplicación siga abierta.
 * La 4.9.4 congelaba la fecha al abrir la pestaña; con el backend Java ese
 * comportamiento ya no tiene sentido (el backend no se apaga al recargar, D20,
 * y varias pestañas comparten el mismo backend) y provocaba planificar y
 * anotar «último reporte» o «fecha de cierre» con el día anterior.
 *
 * Con planner.fecha-fija el reloj empieza ese día a las 10:00 y avanza con
 * normalidad (también cruza la medianoche), para reproducir un día concreto.
 */
public class RelojConfigurable implements Reloj {

    private final Clock base;
    private volatile Instant arranqueReal;
    private volatile Instant arranqueSimulado;

    public RelojConfigurable(String fechaFija) {
        this(fechaFija, Clock.systemUTC());
    }

    /** Constructor con reloj base inyectable (pruebas). */
    public RelojConfigurable(String fechaFija, Clock base) {
        this.base = base;
        this.arranqueReal = base.instant();
        if (fechaFija == null || fechaFija.isBlank()) {
            this.arranqueSimulado = null;
        } else {
            this.arranqueSimulado = LocalDate.parse(fechaFija.trim()).atTime(LocalTime.of(10, 0))
                    .atZone(Fechas.ZONA).toInstant();
        }
    }

    /**
     * Cambia la fecha simulada (solo lo usa el reinicio del modo local para las
     * pruebas de paridad; vacío = volver a la fecha real).
     */
    public void fijarFecha(String fechaFija) {
        this.arranqueReal = base.instant();
        this.arranqueSimulado = (fechaFija == null || fechaFija.isBlank()) ? null
                : LocalDate.parse(fechaFija.trim()).atTime(LocalTime.of(10, 0)).atZone(Fechas.ZONA).toInstant();
    }

    @Override
    public Instant ahora() {
        Instant real = base.instant();
        if (arranqueSimulado == null) {
            return real;
        }
        return arranqueSimulado.plus(Duration.between(arranqueReal, real));
    }

    @Override
    public LocalDate hoy() {
        return ahora().atZone(Fechas.ZONA).toLocalDate();
    }
}
