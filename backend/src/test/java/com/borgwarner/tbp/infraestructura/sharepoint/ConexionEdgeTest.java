package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regresión de los bloqueos entre consultas: las lecturas ya no esperan detrás
 * de un guardado ni de otra consulta larga, y las de una pestaña cerrada se
 * descartan. Con conexiones simuladas (sin Edge).
 */
class ConexionEdgeTest {

    private final AtomicInteger enlacesCreados = new AtomicInteger();
    private ConexionEdge conexion;
    private final ExecutorService hilos = Executors.newFixedThreadPool(6);

    private ConexionEdge conexion(int carrilesLectura) {
        conexion = new ConexionEdge(carrilesLectura, () -> {
            enlacesCreados.incrementAndGet();
            BrowserContext contexto = mock(BrowserContext.class);
            return new ConexionEdge.Enlace() {
                @Override
                public BrowserContext contexto() {
                    return contexto;
                }

                @Override
                public void reconectar() {
                }

                @Override
                public void cerrar() {
                }
            };
        });
        return conexion;
    }

    @AfterEach
    void cerrar() {
        hilos.shutdownNow();
        if (conexion != null) {
            conexion.close();
        }
    }

    private static void esperar(CountDownLatch cerrojo) {
        try {
            cerrojo.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void unaConsultaNoEsperaDetrasDeUnGuardadoLargo() throws Exception {
        ConexionEdge c = conexion(2);
        CountDownLatch guardando = new CountDownLatch(1);
        CountDownLatch terminarGuardado = new CountDownLatch(1);
        Future<String> guardado = hilos.submit(() -> c.ejecutar(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> {
            guardando.countDown();
            esperar(terminarGuardado);
            return "guardado";
        }));
        assertTrue(guardando.await(5, TimeUnit.SECONDS));
        // Mientras el carril principal guarda, la consulta se atiende en un carril de lectura.
        String leido = hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> "planificacion"))
                .get(2, TimeUnit.SECONDS);
        assertEquals("planificacion", leido);
        assertFalse(guardado.isDone());
        terminarGuardado.countDown();
        assertEquals("guardado", guardado.get(5, TimeUnit.SECONDS));
    }

    @Test
    void dosConsultasLargasSeAtiendenALaVez() throws Exception {
        ConexionEdge c = conexion(2);
        CountDownLatch ambas = new CountDownLatch(2);
        Future<Boolean> a = hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_SECUNDARIA, contexto -> {
            ambas.countDown();
            return ambasDentro(ambas);
        }));
        Future<Boolean> b = hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            ambas.countDown();
            return ambasDentro(ambas);
        }));
        assertTrue(a.get(5, TimeUnit.SECONDS));
        assertTrue(b.get(5, TimeUnit.SECONDS));
    }

    private static boolean ambasDentro(CountDownLatch ambas) {
        try {
            return ambas.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void lasEscriturasVanSiempreAlCarrilPrincipalYLasConsultasNo() throws Exception {
        ConexionEdge c = conexion(2);
        assertFalse(hilos.submit(() -> c.ejecutar(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> ConexionEdge.enCarrilDeLectura()))
                .get(5, TimeUnit.SECONDS));
        assertTrue(hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> ConexionEdge.enCarrilDeLectura()))
                .get(5, TimeUnit.SECONDS));
        assertEquals("sp-principal", hilos.submit(() -> c.ejecutar(ConexionEdge.PRIORIDAD_GUARDADO,
                contexto -> Thread.currentThread().getName())).get(5, TimeUnit.SECONDS));
    }

    @Test
    void sinCarrilesDeLecturaTodoVaPorElPrincipalComoAntes() throws Exception {
        ConexionEdge c = conexion(0);
        assertEquals(0, c.carrilesLectura());
        assertEquals("sp-principal", hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ,
                contexto -> Thread.currentThread().getName())).get(5, TimeUnit.SECONDS));
        assertEquals(1, enlacesCreados.get());
    }

    @Test
    void laConsultaDeUnaPestanaCerradaSeDescartaSiAunNoHaEmpezado() throws Exception {
        ConexionEdge c = conexion(1);
        CountDownLatch ocupado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            ocupado.countDown();
            esperar(soltar);
            return null;
        }));
        assertTrue(ocupado.await(5, TimeUnit.SECONDS));
        Cancelacion.Testigo pestana = new Cancelacion.Testigo();
        AtomicBoolean ejecutada = new AtomicBoolean();
        Future<String> pendiente = hilos.submit(() -> Cancelacion.con(pestana, () -> c.consultar(ConexionEdge.PRIORIDAD_SECUNDARIA,
                contexto -> {
                    ejecutada.set(true);
                    return "estados";
                })));
        Thread.sleep(100);
        long antes = System.nanoTime();
        pestana.cancelar(); // la página se recarga
        java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pendiente.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof PeticionCancelada);
        assertTrue(System.nanoTime() - antes < TimeUnit.SECONDS.toNanos(2), "deja de esperar enseguida");
        soltar.countDown();
        // La página nueva sí se atiende y la consulta abandonada no llega a ejecutarse.
        assertEquals("nueva", hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> "nueva"))
                .get(5, TimeUnit.SECONDS));
        assertFalse(ejecutada.get());
        assertEquals(1, c.consultasDescartadas());
    }

    @Test
    void unaEscrituraNoSeCancelaNuncaAunqueLaPestanaSeCierre() throws Exception {
        ConexionEdge c = conexion(1);
        Cancelacion.Testigo pestana = new Cancelacion.Testigo();
        pestana.cancelar();
        String r = hilos.submit(() -> Cancelacion.con(pestana, () -> c.ejecutar(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> "guardado")))
                .get(5, TimeUnit.SECONDS);
        assertEquals("guardado", r);
    }

    @Test
    void lasConsultasDeLaInterfazPasanDelanteDeLasSecundarias() throws Exception {
        ConexionEdge c = conexion(1);
        CountDownLatch ocupado = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        StringBuffer orden = new StringBuffer();
        hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            ocupado.countDown();
            esperar(soltar);
            return null;
        }));
        assertTrue(ocupado.await(5, TimeUnit.SECONDS));
        Future<?> secundaria = hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_SECUNDARIA, contexto -> orden.append("S")));
        Thread.sleep(100);
        Future<?> interfaz = hilos.submit(() -> c.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> orden.append("I")));
        Thread.sleep(100);
        soltar.countDown();
        secundaria.get(5, TimeUnit.SECONDS);
        interfaz.get(5, TimeUnit.SECONDS);
        assertEquals("IS", orden.toString());
    }
}
