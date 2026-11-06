package com.borgwarner.tbp.api.comun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apagado del backend: solo al cerrarse de verdad la última pestaña. Una
 * pestaña minimizada u oculta deja de enviar latidos (o los espacia) y debe
 * seguir contando.
 */
class CicloDeVidaTest {

    private final AtomicLong ahora = new AtomicLong(1_000_000);
    private final AtomicInteger apagados = new AtomicInteger();
    // El temporizador real no se usa: las pruebas llaman a comprobarApagado() cuando toca.
    private final ScheduledExecutorService temporizador = Executors.newSingleThreadScheduledExecutor();
    private CicloDeVida ciclo;

    @BeforeEach
    void preparar() {
        ciclo = new CicloDeVida(apagados::incrementAndGet, ahora::get, temporizador);
    }

    private void pasan(long ms) {
        ahora.addAndGet(ms);
    }

    @Test
    void pestanaMinimizadaSinLatidosNoApagaAunqueSeCierreOtra() {
        ciclo.latido("A");
        ciclo.latido("B");
        // A se minimiza: deja de enviar latidos durante una hora.
        pasan(3_600_000);
        ciclo.solicitarApagado("B");
        pasan(CicloDeVida.ESPERA_APAGADO_MS);
        ciclo.comprobarApagado();
        assertEquals(0, apagados.get());
        assertEquals(1, ciclo.pestanasAbiertas());
    }

    @Test
    void unaSolaPestanaMinimizadaNoApagaNunca() {
        ciclo.latido("A");
        pasan(24 * 3_600_000L);
        ciclo.comprobarApagado();
        assertEquals(0, apagados.get());
        assertTrue(ciclo.hayPestanas());
    }

    @Test
    void cerrarLaUltimaApagaTrasLaEspera() {
        ciclo.latido("A");
        ciclo.solicitarApagado("A");
        assertFalse(ciclo.hayPestanas());
        pasan(CicloDeVida.ESPERA_APAGADO_MS);
        ciclo.comprobarApagado();
        assertEquals(1, apagados.get());
        // Un segundo aviso no vuelve a apagar.
        ciclo.comprobarApagado();
        assertEquals(1, apagados.get());
    }

    @Test
    void recargarNoApaga() {
        ciclo.latido("A");
        ciclo.solicitarApagado("A");      // pagehide de la recarga
        pasan(1500);
        ciclo.latido("A2");               // la página recargada tiene otro código
        pasan(CicloDeVida.ESPERA_APAGADO_MS);
        ciclo.comprobarApagado();
        assertEquals(0, apagados.get());
    }

    @Test
    void cierreDeUnaPestanaDesconocidaNoAfectaALasDemas() {
        ciclo.latido("A");
        ciclo.solicitarApagado("Z");
        ciclo.comprobarApagado();
        assertEquals(0, apagados.get());
        assertEquals(1, ciclo.pestanasAbiertas());
    }

    @Test
    void interfazClasicaOcultaConLatidosEspaciadosSigueContando() {
        ciclo.latido(null);               // /clasico
        ciclo.latido("A");
        pasan(60_000);                    // pestaña clásica oculta: 1 latido por minuto
        ciclo.latido(null);
        ciclo.solicitarApagado("A");
        pasan(CicloDeVida.ESPERA_APAGADO_MS);
        ciclo.comprobarApagado();
        assertEquals(0, apagados.get());
    }

    @Test
    void interfazClasicaCerradaApagaComoAntes() {
        ciclo.latido(null);
        ciclo.solicitarApagado(null);
        pasan(CicloDeVida.ESPERA_APAGADO_MS);
        ciclo.comprobarApagado();
        assertEquals(1, apagados.get());
    }
}
