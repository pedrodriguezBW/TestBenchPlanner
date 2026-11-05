package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Fechas;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Reloj del sistema o, si se configura planner.fecha-fija, un reloj que empieza
 * ese día a las 10:00 (hora de Madrid) y avanza con normalidad. Sirve para
 * comparar con la 4.9.4 y para reproducir un día concreto.
 */
public class RelojConfigurable implements Reloj {

    private final Instant arranqueReal = Instant.now();
    private final Instant arranqueSimulado;

    public RelojConfigurable(String fechaFija) {
        if (fechaFija == null || fechaFija.isBlank()) {
            this.arranqueSimulado = null;
        } else {
            this.arranqueSimulado = LocalDate.parse(fechaFija.trim()).atTime(LocalTime.of(10, 0))
                    .atZone(Fechas.ZONA).toInstant();
        }
    }

    @Override
    public Instant ahora() {
        if (arranqueSimulado == null) {
            return Instant.now();
        }
        return arranqueSimulado.plus(Duration.between(arranqueReal, Instant.now()));
    }

    @Override
    public LocalDate hoy() {
        return ahora().atZone(Fechas.ZONA).toLocalDate();
    }
}
