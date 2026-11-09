package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.RegistroLecturas;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ReconciliacionBloqueada;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modo sin conexión (H-04): última copia buena para consultar, nunca para guardar. */
class CopiaSinConexionTest {

    private static final Instant MOMENTO = Instant.parse("2026-11-09T08:30:00Z");

    @TempDir
    Path carpeta;

    /** Ensayos «de SharePoint» que se pueden hacer fallar. */
    static class EnsayosFalsos implements AlmacenEnsayos {
        List<FilaGuardada> filas = List.of(new FilaGuardada("a1", "{\"id\":\"a1\"}"), new FilaGuardada("b2", "{\"id\":\"b2\"}"));
        boolean caido;

        @Override
        public List<FilaGuardada> leer() {
            if (caido) {
                throw new SinSesionAlmacen();
            }
            return filas;
        }

        @Override
        public ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor) {
            if (caido) {
                throw new SinSesionAlmacen();
            }
            return new ResumenGuardado(0, cambios.size(), 0);
        }
    }

    static class UsuariosFalsos implements AlmacenUsuarios {
        boolean caido;
        boolean vacia;

        @Override
        public Identidad identidadActual() {
            return caido ? new Identidad("", "") : new Identidad("ana@ejemplo.local", "Ana");
        }

        @Override
        public ArrayNode leer() {
            ArrayNode lista = JsonJs.mapper().createArrayNode();
            if (caido) {
                throw new SinSesionAlmacen();
            }
            if (!vacia) {
                lista.addObject().put("email", "ana@ejemplo.local").put("role", "admin");
            }
            return lista;
        }

        @Override
        public ObjectNode guardar(JsonNode usuarios) {
            return JsonJs.objetoVacio();
        }
    }

    private CopiaSinConexion copia(EstadoConexion estado) {
        return new CopiaSinConexion(carpeta, estado, Clock.fixed(MOMENTO, ZoneOffset.UTC));
    }

    @Test
    void conConexionSeLeeDelServidorYSeGuardaLaCopia() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        AlmacenEnsayos ensayos = copia(estado).ensayos(real);
        assertEquals(2, ensayos.leer().size());
        assertFalse(estado.sinConexion());
        assertTrue(carpeta.resolve("copia-sin-conexion/ensayos.json").toFile().exists());
    }

    @Test
    void sinConexionSeMuestraLaUltimaCopiaYSeAnota() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        AlmacenEnsayos ensayos = copia(estado).ensayos(real);
        ensayos.leer();
        real.caido = true;
        List<FilaGuardada> filas = ensayos.leer();
        assertEquals(List.of("a1", "b2"), filas.stream().map(FilaGuardada::uid).toList());
        assertEquals("{\"id\":\"a1\"}", filas.get(0).texto());
        assertTrue(estado.sinConexion());
        assertEquals(MOMENTO, estado.copiaDe());
        // Al volver la conexión, se lee otra vez del servidor.
        real.caido = false;
        ensayos.leer();
        assertFalse(estado.sinConexion());
        assertNull(estado.copiaDe());
    }

    @Test
    void unaFilaSinDataJsonSigueSinTexto() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        real.filas = List.of(new FilaGuardada("c3", null));
        AlmacenEnsayos ensayos = copia(estado).ensayos(real);
        assertNull(ensayos.leer().get(0).texto());
        real.caido = true;
        assertNull(ensayos.leer().get(0).texto());
    }

    @Test
    void sinConexionYSinCopiaElFalloSigue() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        real.caido = true;
        assertThrows(SinSesionAlmacen.class, () -> copia(estado).ensayos(real).leer());
        assertFalse(estado.sinConexion());
    }

    @Test
    void guardarNoPasaNuncaPorLaCopia() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        AlmacenEnsayos ensayos = copia(estado).ensayos(real);
        ensayos.leer();
        real.caido = true;
        ensayos.leer();
        assertThrows(SinSesionAlmacen.class, () -> ensayos.guardar(List.of(), true, new AlmacenEnsayos.Actor("a", "b")));
    }

    @Test
    void unaListaVaciaNoPisaLaCopiaBuena() {
        EstadoConexion estado = new EstadoConexion();
        EnsayosFalsos real = new EnsayosFalsos();
        AlmacenEnsayos ensayos = copia(estado).ensayos(real);
        ensayos.leer();
        real.filas = List.of();
        assertEquals(0, ensayos.leer().size());
        real.caido = true;
        assertEquals(2, ensayos.leer().size());
    }

    @Test
    void usuariosEIdentidadSalenDeLaCopiaSiSharePointNoResponde() {
        EstadoConexion estado = new EstadoConexion();
        UsuariosFalsos real = new UsuariosFalsos();
        AlmacenUsuarios usuarios = copia(estado).usuarios(real);
        usuarios.identidadActual();
        usuarios.leer();
        real.caido = true;
        assertEquals("ana@ejemplo.local", usuarios.identidadActual().email());
        assertEquals(1, usuarios.leer().size());
        assertTrue(estado.sinConexion());
    }

    @Test
    void unaListaDeUsuariosVaciaSeTrataComoFalloSiHayCopia() {
        EstadoConexion estado = new EstadoConexion();
        UsuariosFalsos real = new UsuariosFalsos();
        AlmacenUsuarios usuarios = copia(estado).usuarios(real);
        usuarios.leer();
        real.vacia = true;
        assertEquals(1, usuarios.leer().size());
        assertTrue(estado.sinConexion());
    }

    @Test
    void conLaCopiaNoSeHabilitaNingunGuardado() {
        EstadoConexion estado = new EstadoConexion();
        RegistroLecturas lecturas = new RegistroLecturas(false, estado);
        lecturas.anotar(RegistroLecturas.USUARIOS, true, 3);
        lecturas.exigir(RegistroLecturas.USUARIOS);
        estado.servidaDesdeCopia("usuarios", MOMENTO);
        assertThrows(ReconciliacionBloqueada.class, () -> lecturas.exigir(RegistroLecturas.USUARIOS));
        lecturas.anotar(RegistroLecturas.USUARIOS, true, 3);
        estado.leidaDelServidor("usuarios");
        assertThrows(ReconciliacionBloqueada.class, () -> lecturas.exigir(RegistroLecturas.USUARIOS));
    }
}
