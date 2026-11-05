package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.listas.ReconciliacionBloqueada;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D11: reglas que impiden vaciar o reescribir una lista compartida por error. */
class ProteccionListasTest {

    @Test
    void lecturaVaciaBloquea() {
        ReconciliacionBloqueada e = assertThrows(ReconciliacionBloqueada.class,
                () -> ProteccionListas.comprobar("TestBenchBenches", List.of(), List.of("MD1"), false));
        assertTrue(e.getMessage().contains("vacía"));
    }

    @Test
    void dejarLaListaVaciaBloquea() {
        ReconciliacionBloqueada e = assertThrows(ReconciliacionBloqueada.class,
                () -> ProteccionListas.comprobar("TestBenchProjects", List.of("P1", "P2"), List.of(), false));
        assertTrue(e.getMessage().contains("2 elementos"));
    }

    @Test
    void cambiosNormalesPasan() {
        assertDoesNotThrow(() -> ProteccionListas.comprobar("TestBenchProjects", List.of("P1", "P2"), List.of("P1"), false));
        assertDoesNotThrow(() -> ProteccionListas.comprobar("TestBenchProjects", List.of("P1"), List.of("P1", "P3"), false));
    }

    @Test
    void instalacionNuevaConPermiso() {
        assertDoesNotThrow(() -> ProteccionListas.comprobar("TestBenchResources", List.of(), List.of("Cilindro"), true));
    }
}
