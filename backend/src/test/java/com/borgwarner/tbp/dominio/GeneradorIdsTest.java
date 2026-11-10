package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.operaciones.GeneradorIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ids propuestos por la interfaz para las filas que ya enseña mientras se guardan. */
class GeneradorIdsTest {

    @Test
    void usaLosPropuestosValidosEnOrdenYDespuesElRespaldo() {
        Set<String> existentes = Set.of("rexiste1");
        GeneradorIds ids = GeneradorIds.conPropuestos(
                List.of("rnuevo01", "MAL", "rexiste1", "rnuevo01", "rnuevo02"), id -> !existentes.contains(id), () -> "rrespal1");
        assertEquals("rnuevo01", ids.nuevo());
        assertEquals("rnuevo02", ids.nuevo(), "se saltan el mal formado, el que ya existe y el repetido");
        assertEquals("rrespal1", ids.nuevo());
    }

    @Test
    void sinPropuestosEsElGeneradorDeSiempre() {
        GeneradorIds ids = GeneradorIds.conPropuestos(List.of(), id -> true, GeneradorIds.aleatorio());
        for (int i = 0; i < 50; i++) {
            assertTrue(GeneradorIds.formatoValido(ids.nuevo()));
        }
    }

    @Test
    void elRespaldoNoRepiteUnIdYaDado() {
        String[] respaldo = {"rmismo01", "rmismo01", "rotro001"};
        int[] i = {0};
        GeneradorIds ids = GeneradorIds.conPropuestos(null, id -> true, () -> respaldo[i[0]++]);
        assertEquals("rmismo01", ids.nuevo());
        assertEquals("rotro001", ids.nuevo());
    }
}
