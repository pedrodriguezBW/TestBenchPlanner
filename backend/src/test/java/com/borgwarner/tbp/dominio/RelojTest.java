package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.infraestructura.comun.RelojConfigurable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D7: «hoy» cambia a medianoche (hora de Madrid) aunque la aplicación siga
 * abierta, y cada cálculo de la planificación usa la fecha del momento.
 */
class RelojTest {

    /** Reloj base que se puede adelantar a mano. */
    static final class RelojManual extends Clock {
        private Instant ahora;

        RelojManual(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @Test
    void elDiaCambiaAMedianocheDeMadrid() {
        // 5-nov-2026 23:59:30 en Madrid (CET = UTC+1)
        RelojManual base = new RelojManual(Instant.parse("2026-11-05T22:59:30Z"));
        RelojConfigurable reloj = new RelojConfigurable("", base);
        assertEquals(LocalDate.of(2026, 11, 5), reloj.hoy());
        base.avanzar(Duration.ofSeconds(31));
        assertEquals(LocalDate.of(2026, 11, 6), reloj.hoy());
    }

    @Test
    void laFechaFijaTambienAvanza() {
        RelojManual base = new RelojManual(Instant.parse("2026-01-01T00:00:00Z"));
        RelojConfigurable reloj = new RelojConfigurable("2026-11-05", base);   // empieza el 5-nov a las 10:00
        assertEquals(LocalDate.of(2026, 11, 5), reloj.hoy());
        base.avanzar(Duration.ofHours(14));                                   // 6-nov a las 00:00
        assertEquals(LocalDate.of(2026, 11, 6), reloj.hoy());
    }

    @Test
    void cadaCalculoUsaLaFechaDelMomento() {
        RelojManual base = new RelojManual(Instant.parse("2026-11-05T22:59:00Z"));
        RelojConfigurable reloj = new RelojConfigurable("", base);
        AlmacenCalendario calendario = new AlmacenCalendario() {
            @Override
            public Calendario leer(LocalDate hoy) {
                return Calendario.porDefecto(hoy);
            }

            @Override
            public void guardar(Calendario c) {
                // no se usa en esta prueba
            }
        };
        ServicioPlanificacion servicio = new ServicioPlanificacion(null, null, calendario, reloj, null);
        assertEquals(LocalDate.of(2026, 11, 5), servicio.contexto().hoy());
        base.avanzar(Duration.ofMinutes(2));
        assertEquals(LocalDate.of(2026, 11, 6), servicio.contexto().hoy());
    }
}
