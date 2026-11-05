package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.JsonPython;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reglas de conversión que deben coincidir exactamente con JavaScript / Python. */
class ValoresYJsonTest {

    @Test
    void numerosComoLosEscribeJavaScript() {
        assertEquals("3", ValorJs.numeroComoTexto(3.0));
        assertEquals("1.2", ValorJs.numeroComoTexto(1.2));
        assertEquals("0.30000000000000004", ValorJs.numeroComoTexto(0.1 + 0.2));
        assertEquals("2.25", ValorJs.numeroComoTexto(2.25));
        assertEquals("100000000000000000000", ValorJs.numeroComoTexto(1e20));
        assertEquals("1e-7", ValorJs.numeroComoTexto(1e-7));
        assertEquals("null", ValorJs.numeroComoTexto(Double.NaN));
    }

    @Test
    void conversionesDeJavaScript() {
        assertEquals(0, ValorJs.numeroDeTexto(""));
        assertEquals(12, ValorJs.numeroDeTexto(" 12 "));
        assertTrue(Double.isNaN(ValorJs.numeroDeTexto("1,5")));
        assertTrue(Double.isNaN(ValorJs.numeroDeTexto("1d")));
        assertTrue(Double.isNaN(ValorJs.numero(null)));           // Number(undefined)
        assertFalse(ValorJs.verdadero(TextNode.valueOf("")));
        assertTrue(ValorJs.verdadero(TextNode.valueOf("0")));     // '0' es verdadero en JavaScript
        assertEquals(3, ValorJs.redondear(2.5));                 // Math.round
        assertEquals(-2, ValorJs.redondear(-2.5));
    }

    @Test
    void jsonComoJsonStringify() {
        String original = "{\"_bench\":\"MD1\",\"_seq\":1,\"cf\":1.2,\"durManual\":3,\"comments\":\"Línea\\nnueva \\\"x\\\"\",\"downtime\":[],\"x\":null}";
        assertEquals(original, JsonJs.escribir(JsonJs.leer(original)));
        assertEquals("\"\\u001f\"", JsonJs.escribir(TextNode.valueOf("\u001f")));
    }

    @Test
    void jsonComoPython() {
        assertEquals("{\"a\": 1, \"b\": [1.5, \"á\"], \"c\": null}", JsonPython.escribir(JsonJs.leer("{\"a\":1,\"b\":[1.5,\"á\"],\"c\":null}")));
    }

    @Test
    void festivosDeVigo() {
        // Semana Santa 2026: domingo 5 de abril -> jueves 2 y viernes 3 festivos.
        Calendario c = Calendario.porDefecto(LocalDate.of(2026, 1, 1));
        assertTrue(c.esFestivo(LocalDate.of(2026, 4, 2)));
        assertTrue(c.esFestivo(LocalDate.of(2026, 4, 3)));
        assertTrue(c.esFestivo(LocalDate.of(2026, 8, 17)));
        assertFalse(c.esFestivo(LocalDate.of(2027, 1, 1)));       // D6: solo el año del calendario
        assertEquals(LocalDate.of(2026, 10, 13), c.siguienteLaborable(LocalDate.of(2026, 10, 10)));
        assertEquals(LocalDate.of(2026, 10, 14), c.sumarLaborables(LocalDate.of(2026, 10, 9), 2));
    }

    @Test
    void fechasYSemanas() {
        assertEquals(LocalDate.of(2026, 11, 2), Fechas.lunesDe(LocalDate.of(2026, 11, 5)));
        assertEquals(53, Fechas.semanaIso(LocalDate.of(2027, 1, 1)));
        assertEquals(2026, Fechas.anioDeSemanaIso(LocalDate.of(2027, 1, 1)));   // D8
        assertEquals(LocalDate.of(2026, 10, 19), Fechas.dia("2026-10-19T10:47:38.679Z"));
        assertEquals("2026-11-05T09:00:00.100Z", Fechas.instanteIso(Instant.parse("2026-11-05T09:00:00.100Z")));
        assertEquals(null, Fechas.dia("no es fecha"));
    }
}
