package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión de «catalogos.json.tmp está siendo utilizado por otro proceso»:
 * varias lecturas que terminan a la vez ya no escriben el mismo temporal.
 */
class CopiaSinConexionConcurrenciaTest {

    @TempDir
    Path carpeta;

    /** Reloj que se puede adelantar. */
    static final class RelojMovil extends Clock {
        final AtomicLong ms = new AtomicLong(Instant.parse("2026-11-10T08:00:00Z").toEpochMilli());

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms.get());
        }
    }

    static final class CatalogosFalsos implements AlmacenCatalogos {
        volatile String proyecto = "P1";
        volatile RuntimeException fallo;

        @Override
        public ObjectNode leer() {
            if (fallo != null) {
                throw fallo;
            }
            ObjectNode c = JsonJs.objetoVacio();
            c.putArray("projects").add(proyecto);
            c.putArray("responsibles").add("Ana");
            c.putArray("benches").addObject().put("name", "MD2");
            return c;
        }

        @Override
        public ObjectNode guardar(JsonNode catalogos) {
            return JsonJs.objetoVacio();
        }
    }

    private List<Path> temporales() throws Exception {
        Path copias = carpeta.resolve("copia-sin-conexion");
        if (!Files.exists(copias)) {
            return List.of();
        }
        try (Stream<Path> ficheros = Files.list(copias)) {
            return ficheros.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    @Test
    void muchasLecturasALaVezNoChocanAlGuardarLaCopia() throws Exception {
        RelojMovil reloj = new RelojMovil();
        CopiaSinConexion copia = new CopiaSinConexion(carpeta, new EstadoConexion(), reloj);
        CatalogosFalsos real = new CatalogosFalsos();
        AlmacenCatalogos catalogos = copia.catalogos(real);
        ExecutorService hilos = Executors.newFixedThreadPool(16);
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<ObjectNode>> lecturas = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                lecturas.add(hilos.submit(() -> {
                    salida.await(5, TimeUnit.SECONDS);
                    return catalogos.leer();
                }));
            }
            salida.countDown();
            for (Future<ObjectNode> f : lecturas) {
                assertEquals("P1", f.get(10, TimeUnit.SECONDS).path("projects").get(0).asText());
            }
        } finally {
            hilos.shutdownNow();
        }
        assertEquals(List.of(), temporales(), "no queda ningún temporal");
        assertEquals(1, copia.escrituras(), "mismo contenido: se escribe una sola vez");
        JsonNode guardada = JsonJs.mapper().readTree(carpeta.resolve("copia-sin-conexion/catalogos.json").toFile());
        assertEquals("P1", guardada.path("datos").path("projects").get(0).asText());
    }

    @Test
    void soloSeReescribeSiCambiaElContenidoORenuevaLaFechaCadaMinuto() {
        RelojMovil reloj = new RelojMovil();
        CopiaSinConexion copia = new CopiaSinConexion(carpeta, new EstadoConexion(), reloj);
        CatalogosFalsos real = new CatalogosFalsos();
        AlmacenCatalogos catalogos = copia.catalogos(real);
        catalogos.leer();
        catalogos.leer();
        assertEquals(1, copia.escrituras());
        real.proyecto = "P2";
        catalogos.leer();
        assertEquals(2, copia.escrituras());
        reloj.ms.addAndGet(CopiaSinConexion.RENOVAR_FECHA_MS + 1);
        catalogos.leer();
        assertEquals(3, copia.escrituras(), "la fecha de la copia se renueva");
    }

    @Test
    void unaConsultaCanceladaNoPoneLaAplicacionSinConexion() {
        EstadoConexion estado = new EstadoConexion();
        CopiaSinConexion copia = new CopiaSinConexion(carpeta, estado, new RelojMovil());
        CatalogosFalsos real = new CatalogosFalsos();
        AlmacenCatalogos catalogos = copia.catalogos(real);
        catalogos.leer(); // hay copia
        real.fallo = new PeticionCancelada();
        assertThrows(PeticionCancelada.class, catalogos::leer);
        assertFalse(estado.sinConexion(), "cerrar una pestaña no es perder SharePoint");
        real.fallo = new IllegalStateException("SharePoint no responde");
        assertEquals("P1", catalogos.leer().path("projects").get(0).asText());
        assertTrue(estado.sinConexion());
    }
}
