package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regresión de las lecturas repetidas: varias peticiones a la vez leen SharePoint UNA sola vez. */
class LecturaCompartidaTest {

    private static ObjectNode catalogo(String proyecto) {
        ObjectNode c = JsonJs.objetoVacio();
        c.putArray("projects").add(proyecto);
        return c;
    }

    @Test
    void variasPeticionesALaVezCompartenUnaSolaLectura() throws Exception {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(0, ObjectNode::deepCopy);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        AtomicInteger cargas = new AtomicInteger();
        ExecutorService hilos = Executors.newFixedThreadPool(6);
        try {
            List<Future<ObjectNode>> resultados = new ArrayList<>();
            resultados.add(hilos.submit(() -> lectura.leer(() -> {
                cargas.incrementAndGet();
                dentro.countDown();
                esperar(soltar);
                return catalogo("P1");
            })));
            assertTrue(dentro.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 5; i++) {
                resultados.add(hilos.submit(() -> lectura.leer(() -> {
                    cargas.incrementAndGet();
                    return catalogo("otra lectura");
                })));
            }
            Thread.sleep(150); // los cinco ya esperan a la lectura en curso
            soltar.countDown();
            for (Future<ObjectNode> r : resultados) {
                assertEquals("P1", r.get(5, TimeUnit.SECONDS).path("projects").get(0).asText());
            }
            assertEquals(1, cargas.get());
            assertEquals(1, lectura.lecturasReales());
        } finally {
            hilos.shutdownNow();
        }
    }

    @Test
    void dentroDeLaVigenciaSeReutilizaYDespuesSeVuelveALeer() {
        AtomicLong ahora = new AtomicLong(1_000);
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(15_000, ObjectNode::deepCopy, v -> true, ahora::get);
        AtomicInteger cargas = new AtomicInteger();
        lectura.leer(() -> catalogo("P" + cargas.incrementAndGet()));
        ahora.addAndGet(14_000);
        assertEquals("P1", lectura.leer(() -> catalogo("P" + cargas.incrementAndGet())).path("projects").get(0).asText());
        ahora.addAndGet(2_000);
        assertEquals("P2", lectura.leer(() -> catalogo("P" + cargas.incrementAndGet())).path("projects").get(0).asText());
        assertEquals(2, cargas.get());
    }

    @Test
    void cadaPeticionRecibeSuPropiaCopia() {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(15_000, ObjectNode::deepCopy);
        ObjectNode primera = lectura.leer(() -> catalogo("P1"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) primera.get("projects")).add("cambio local");
        ObjectNode segunda = lectura.leer(() -> catalogo("no debería leerse"));
        assertNotSame(primera, segunda);
        assertEquals(1, segunda.path("projects").size());
    }

    @Test
    void trasEscribirNoSeReutilizaLoLeidoAntes() {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(15_000, ObjectNode::deepCopy);
        lectura.leer(() -> catalogo("antes"));
        lectura.invalidar();
        assertEquals("despues", lectura.leer(() -> catalogo("despues")).path("projects").get(0).asText());
    }

    @Test
    void unaLecturaEmpezadaAntesDeEscribirNoSeReparteDespues() throws Exception {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(0, ObjectNode::deepCopy);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<ObjectNode> vieja = hilos.submit(() -> lectura.leer(() -> {
                dentro.countDown();
                esperar(soltar);
                return catalogo("antes de guardar");
            }));
            assertTrue(dentro.await(5, TimeUnit.SECONDS));
            lectura.invalidar(); // se guarda mientras la lectura sigue en curso
            Future<ObjectNode> nueva = hilos.submit(() -> lectura.leer(() -> catalogo("despues de guardar")));
            assertEquals("despues de guardar", nueva.get(5, TimeUnit.SECONDS).path("projects").get(0).asText());
            soltar.countDown();
            assertEquals("antes de guardar", vieja.get(5, TimeUnit.SECONDS).path("projects").get(0).asText());
        } finally {
            hilos.shutdownNow();
        }
    }

    @Test
    void unFalloNoSeGuardaYUnaLecturaParcialTampoco() {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(15_000, ObjectNode::deepCopy, c -> !c.has("projects_error"));
        assertThrows(IllegalStateException.class, () -> lectura.leer(() -> {
            throw new IllegalStateException("SharePoint no responde");
        }));
        ObjectNode parcial = catalogo("P1").put("projects_error", "403");
        lectura.leer(() -> parcial);
        assertEquals("P2", lectura.leer(() -> catalogo("P2")).path("projects").get(0).asText());
        assertEquals("P2", lectura.leer(() -> catalogo("P3")).path("projects").get(0).asText());
    }

    @Test
    void siLaPestanaQueEsperaSeCierraDejaDeEsperarPeroLaLecturaSigueParaLasDemas() throws Exception {
        LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(0, ObjectNode::deepCopy);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        Cancelacion.Testigo pestanaAntigua = new Cancelacion.Testigo();
        ExecutorService hilos = Executors.newFixedThreadPool(3);
        try {
            // La primera petición pertenece a la pestaña que se recarga: su lectura no se cancela.
            Future<ObjectNode> lector = hilos.submit(() -> Cancelacion.con(pestanaAntigua, () -> lectura.leer(() -> {
                dentro.countDown();
                esperar(soltar);
                return catalogo("P1");
            })));
            assertTrue(dentro.await(5, TimeUnit.SECONDS));
            Future<ObjectNode> esperaAntigua = hilos.submit(() -> Cancelacion.con(pestanaAntigua, () -> lectura.leer(() -> catalogo("x"))));
            Future<ObjectNode> paginaNueva = hilos.submit(() -> lectura.leer(() -> catalogo("x")));
            Thread.sleep(100);
            pestanaAntigua.cancelar();
            java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> esperaAntigua.get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof PeticionCancelada);
            soltar.countDown();
            assertEquals("P1", paginaNueva.get(5, TimeUnit.SECONDS).path("projects").get(0).asText());
            assertEquals("P1", lector.get(5, TimeUnit.SECONDS).path("projects").get(0).asText());
            assertEquals(1, lectura.lecturasReales());
        } finally {
            hilos.shutdownNow();
        }
    }

    private static void esperar(CountDownLatch cerrojo) {
        try {
            cerrojo.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
