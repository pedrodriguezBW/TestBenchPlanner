package com.borgwarner.tbp.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Entrada;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Fila;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Linea;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Tabla;
import com.borgwarner.tbp.dominio.facturacion.Regla;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hoja mensual de facturación. La prueba principal es la regresión con enero
 * de 2026 del Excel «2026 Ensayos interno.xlsx» (test/resources/facturacion/
 * enero-2026.json, solo datos de prueba): con las horas de cada fila de
 * INPUTS_2026 y las tarifas de «Equipos y costes», cada «Coste total» y cada
 * celda de la hoja «ENERO» tienen que salir iguales.
 */
class HojaMensualTest {

    private static final YearMonth ENERO = YearMonth.of(2026, 1);

    static JsonNode enero() throws IOException {
        try (InputStream in = HojaMensualTest.class.getResourceAsStream("/facturacion/enero-2026.json")) {
            return JsonJs.mapper().readTree(in);
        }
    }

    static Map<String, TipoEquipo> tipos(JsonNode doc) {
        Map<String, TipoEquipo> m = new HashMap<>();
        for (JsonNode t : doc.path("tipos")) {
            TipoEquipo tipo = TipoEquipo.desdeJson(t);
            m.put(tipo.clave(), tipo);
        }
        return m;
    }

    private static Entrada entrada(String banco, String proyecto, String requester, String productLine, String hv, Double setup,
                                   double parada, String inicio, String fin) {
        return new Entrada(banco, "t-" + banco, proyecto, "Test", requester, productLine, hv, setup, parada,
                LocalDate.parse(inicio), LocalDate.parse(fin));
    }

    private static Map<String, TipoEquipo> unTipo(String titulo, String grupo, Double sinHv, Double conHv, String unidad, Regla regla) {
        TipoEquipo t = new TipoEquipo(titulo, grupo, 2026, sinHv, conHv, unidad, regla);
        return Map.of(t.clave(), t);
    }

    /** Las 25 tareas de enero del Excel como líneas (horas, tarifa y coste calculados con los Equipment Types). */
    static List<Linea> lineasDeEnero(JsonNode doc) {
        Map<String, TipoEquipo> tipos = tipos(doc);
        List<Linea> lineas = new ArrayList<>();
        for (JsonNode l : doc.path("lineas")) {
            TipoEquipo t = tipos.get(TipoEquipo.clave(l.path("equipmentType").asText(), 2026));
            assertNotNull(t, l.path("equipmentType").asText());
            assertEquals(l.path("grupo").asText(), t.grupo());
            Double tarifa = t.tarifa(l.path("hv").asText());
            assertEquals(l.path("tarifaExcel").asDouble(), tarifa, 1e-9);
            // En el Excel las piezas de RACS se escriben en las horas; en la aplicación van en Setup Hours.
            double cantidad = l.path("horasTotales").asDouble();
            double coste = t.regla().coste(cantidad, tarifa);
            assertEquals(l.path("costeExcel").asDouble(), coste, 0.005, l.path("tarea").asText());
            Entrada en = new Entrada("b", "id", l.path("proyecto").asText(), "", l.path("requester").asText(), l.path("productLine").asText(),
                    l.path("hv").asText(), l.path("setup").asDouble(), 0, LocalDate.parse(l.path("inicio").asText()), LocalDate.parse(l.path("fin").asText()));
            lineas.add(new Linea(en, en.inicio(), en.fin(), l.path("orden").asText(), t.titulo(), t.grupo(), t.unidad(), t.regla().name(),
                    l.path("horasNetas").asDouble(), l.path("setup").asDouble(), cantidad, tarifa, coste, List.of(), List.of()));
        }
        return lineas;
    }

    @Test
    void eneroDelExcel_costesYTablaDinamica() throws IOException {
        JsonNode doc = enero();
        Tabla tabla = HojaMensual.tabla(lineasDeEnero(doc));
        List<String> columnas = new ArrayList<>();
        doc.path("columnas").forEach(c -> columnas.add(c.asText()));
        assertEquals(columnas, tabla.columnas());
        assertEquals(List.of("Billing Order", "Planta", "Tipo", "Equipo", "HV", "Inicio", "Fin", "Coste/hora"), tabla.niveles());
        // Primer nivel = columna de enero de la tabla dinámica del Excel (TablaDin_varios).
        Map<String, Double> porOrden = new HashMap<>();
        tabla.filas().stream().filter(f -> f.nivel() == 0).forEach(f -> porOrden.put(f.etiqueta(), f.total()));
        assertEquals(doc.path("ordenesTablaDinamica").size(), porOrden.size());
        doc.path("ordenesTablaDinamica").fields().forEachRemaining(x -> assertEquals(x.getValue().asDouble(), porOrden.get(x.getKey()), 0.01, x.getKey()));
        Fila total = tabla.filas().get(tabla.filas().size() - 1);
        assertTrue(tabla.esTotal(total));
        assertEquals(47210.59, total.total(), 0.01);
        // Camino completo: 463B11364MD4 › Heaters › HVCH (STC) › HVCH Lubu › No › 1 ene › 20 ene › 19,45 = 3 tareas.
        List<String> esperado = List.of("463B11364MD4", "Heaters", "HVCH (STC)", "HVCH Lubu", "No", "jueves, 1 de enero de 2026",
                "martes, 20 de enero de 2026", "19,45");
        List<String> camino = new ArrayList<>();
        Fila hoja = null;
        for (Fila f : tabla.filas()) {
            if (tabla.esTotal(f)) {
                continue;
            }
            while (camino.size() > f.nivel()) {
                camino.remove(camino.size() - 1);
            }
            camino.add(f.etiqueta());
            if (camino.equals(esperado)) {
                hoja = f;
            }
        }
        assertNotNull(hoja, "camino " + esperado);
        assertEquals(4482.94 + 4858.55 + 5300.19, hoja.total(), 0.02);
    }

    @Test
    void eneroDelExcel_soloPlanta_igualQueLaHojaEnero() throws IOException {
        JsonNode doc = enero();
        Tabla tabla = HojaMensual.tabla(lineasDeEnero(doc), HojaMensual.niveles(List.of("planta")));
        assertEquals(List.of("Planta"), tabla.niveles());
        int comprobadas = 0;
        for (JsonNode f : doc.path("filas")) {
            String e = f.path("etiqueta").asText();
            if (!HojaMensual.PRODUCT_LINES.contains(e) && !e.equals("Total general")) {
                continue;
            }
            Fila nuestra = tabla.filas().stream().filter(x -> x.etiqueta().equals(e)).findFirst().orElse(null);
            assertNotNull(nuestra, e);
            assertEquals(f.path("total").asDouble(), nuestra.total(), 0.01, e);
            f.path("valores").fields().forEachRemaining(v -> assertEquals(v.getValue().asDouble(), nuestra.valores().get(v.getKey()), 0.01, e));
            comprobadas++;
        }
        assertEquals(5, comprobadas, "Vigo, Viana, Heaters, eCoolers y Total general");
        assertEquals(List.of("Vigo", "Viana", "Heaters", "eCoolers", "Total general"), tabla.filas().stream().map(Fila::etiqueta).toList());
    }

    @Test
    void camposDeFilaElegibles() {
        assertEquals(List.of("orden", "hv"), HojaMensual.niveles(List.of("hv", "orden")).stream().map(HojaMensual.Nivel::id).toList(),
                "siempre en el orden fijo");
        assertEquals(8, HojaMensual.niveles(null).size());
        assertTrue(HojaMensual.niveles(List.of()).isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida.class,
                () -> HojaMensual.niveles(List.of("requester")));
        assertEquals("miércoles, 5 de agosto de 2026", HojaMensual.fechaLarga("2026-08-05"));
        assertEquals("5,78", HojaMensual.numeroEs("5.78"));
        assertEquals("24", HojaMensual.numeroEs("24.0"));
    }

    @Test
    void lineaCompletaDesdeLasFechasDeLaTarea() throws IOException {
        Map<String, TipoEquipo> tipos = tipos(enero());
        // 1-19 de enero = 19 días = 456 h, como «Cámara climática» de bperez (1.696,32 €).
        Linea l = HojaMensual.linea(entrada("CC1", "80000380 - Proyecto", "bperez", "Viana", "no", null, 0, "2026-01-01", "2026-01-19"),
                ENERO, "Cámara climática", tipos, Map.of("80000380 - Proyecto", "463B80380MA0"));
        assertTrue(l.completa(), String.valueOf(l.faltas()));
        assertEquals(456, l.horasNetas(), 1e-9);
        assertEquals(1696.32, l.coste(), 0.005);
        assertEquals("463B80380MA0", l.orden());
        assertEquals("Climático", l.grupo());
        // Reductora /200 con setup: 15 días (360 h) + 4 h de setup en «HVCH Lubu» → 364 h.
        Linea lubu = HojaMensual.linea(entrada("Lubu3", "11364 - R&D", "mrilo", "heaters", "no", 4.0, 0, "2026-01-01", "2026-01-15"),
                ENERO, "HVCH Lubu", tipos, Map.of());
        assertEquals(364, lubu.cantidad(), 1e-9);
        assertEquals(Regla.ASINH_200.coste(364, 19.45), lubu.coste(), 1e-9);
        assertEquals(List.of("El proyecto no tiene Billing Order"), lubu.avisos());
    }

    @Test
    void periodoDeFacturacion_del21al20() {
        assertEquals(LocalDate.of(2026, 1, 21), HojaMensual.inicioPeriodo(YearMonth.of(2026, 2)));
        assertEquals(LocalDate.of(2026, 2, 20), HojaMensual.finPeriodo(YearMonth.of(2026, 2)));
        assertEquals(LocalDate.of(2025, 12, 21), HojaMensual.inicioPeriodo(ENERO));
        assertEquals(YearMonth.of(2026, 2), HojaMensual.periodoDe(LocalDate.of(2026, 1, 21)));
        assertEquals(ENERO, HojaMensual.periodoDe(LocalDate.of(2026, 1, 20)));
        assertEquals(YearMonth.of(2027, 1), HojaMensual.periodoDe(LocalDate.of(2026, 12, 25)));
        // Tarea larga: en febrero solo cuenta del 21/01 al 20/02 (31 días) y eso es lo que se ve en Inicio y Fin.
        Map<String, TipoEquipo> t = unTipo("Shaker", "Shakers", 10.0, 14.0, TipoEquipo.HORAS, Regla.LINEAR);
        Linea l = HojaMensual.linea(entrada("S1", "1 - P", "ana", "Vigo", "no", null, 0, "2025-12-01", "2026-03-31"),
                YearMonth.of(2026, 2), "Shaker", t, Map.of());
        assertEquals(LocalDate.of(2026, 1, 21), l.desde());
        assertEquals(LocalDate.of(2026, 2, 20), l.hasta());
        assertEquals(31 * 24, l.horasNetas(), 1e-9);
    }

    @Test
    void tareaEntreDosPeriodos_paradaProporcionalYSetupSoloElPrimero() {
        Map<String, TipoEquipo> t = unTipo("Shaker", "Shakers", 10.0, 14.0, TipoEquipo.HORAS, Regla.LINEAR);
        // 16–25 ene: 5 días en el periodo de enero (16–20) y 5 en el de febrero (21–25); 2 días de parada (1 por periodo); setup 6 h.
        Entrada en = entrada("S1", "123 - P", "ana", "Vigo", "yes", 6.0, 2, "2026-01-16", "2026-01-25");
        Linea enero = HojaMensual.linea(en, ENERO, "Shaker", t, Map.of());
        Linea febrero = HojaMensual.linea(en, YearMonth.of(2026, 2), "Shaker", t, Map.of());
        assertEquals(5 * 24 - 24, enero.horasNetas(), 1e-9);
        assertEquals(6, enero.horasSetup(), 1e-9);
        assertEquals((96 + 6) * 14.0, enero.coste(), 1e-9);
        assertEquals(96, febrero.horasNetas(), 1e-9);
        assertEquals(0, febrero.horasSetup(), 1e-9);
        assertEquals(LocalDate.of(2026, 1, 16), enero.desde());
        assertEquals(LocalDate.of(2026, 1, 20), enero.hasta());
        assertEquals(LocalDate.of(2026, 1, 21), febrero.desde());
        assertEquals(LocalDate.of(2026, 1, 25), febrero.hasta());
        assertNull(HojaMensual.linea(en, YearMonth.of(2026, 3), "Shaker", t, Map.of()));
    }

    @Test
    void porPiezas_lasPiezasVanEnSetupHours() {
        Map<String, TipoEquipo> t = unTipo("RACS", "RACS", 126.92, 126.92, TipoEquipo.PIEZAS, Regla.PER_PIECE);
        Entrada en = entrada("R1", "80000511 - P", "yasantos", "Heaters", "no", 5.0, 0, "2026-01-10", "2026-02-20");
        assertEquals(634.6, HojaMensual.linea(en, ENERO, "RACS", t, Map.of()).coste(), 1e-9);
        assertNull(HojaMensual.linea(en, YearMonth.of(2026, 2), "RACS", t, Map.of()), "las piezas solo se facturan el primer periodo");
    }

    @Test
    void reglasDelExcel_stcYProyectoCero() {
        Map<String, TipoEquipo> t = unTipo("Shaker", "Shakers", 10.0, null, TipoEquipo.HORAS, Regla.LINEAR);
        Linea stc = HojaMensual.linea(entrada("S1", "123 - P", "ana", "stc", "yes", null, 0, "2026-01-01", "2026-01-01"), ENERO, "Shaker", t, Map.of());
        assertTrue(stc.completa(), "STC no necesita tarifa");
        assertEquals("STC", stc.entrada().productLine());
        assertEquals(0.0, stc.coste(), 0.0);
        Linea cero = HojaMensual.linea(entrada("S1", "00000 - STC General Works", "ana", "Vigo", "no", null, 0, "2026-01-01", "2026-01-01"),
                ENERO, "Shaker", t, Map.of());
        assertEquals(0.0, cero.coste(), 0.0);
        assertTrue(HojaMensual.proyectoCero("00000 - STC General Works"));
        assertFalse(HojaMensual.proyectoCero("80000885 - BMW"));
    }

    @Test
    void lineasIncompletas_salenEnLaVistaPreviaPeroNoEnLaExportacion() {
        TipoEquipo shaker = new TipoEquipo("Shaker", "Shakers", 2026, 10.0, null, TipoEquipo.HORAS, Regla.LINEAR);
        TipoEquipo nada = new TipoEquipo("Banco viejo", "Otros", 2026, 5.0, 5.0, TipoEquipo.HORAS, Regla.NOT_BILLABLE);
        List<Entrada> entradas = List.of(
                entrada("S1", "1 - P", "ana", "Vigo", "no", null, 0, "2026-01-01", "2026-01-01"),     // completa: 240 €
                entrada("S1", "1 - P", "ana", "Vigo", "yes", null, 0, "2026-01-02", "2026-01-02"),    // sin tarifa With HV
                entrada("S1", "1 - P", "ana", "Vigo", "", null, 0, "2026-01-03", "2026-01-03"),       // sin HV
                entrada("SIN", "1 - P", "ana", "Vigo", "no", null, 0, "2026-01-01", "2026-01-01"),    // banco sin Equipment Type
                entrada("OLD", "1 - P", "ana", "Vigo", "no", null, 0, "2026-01-01", "2026-01-01"));   // no facturable
        Map<String, String> tipoPorBanco = Map.of("S1", "Shaker", "OLD", "Banco viejo");
        HojaMensual.Hoja h = HojaMensual.calcular(entradas, ENERO, tipoPorBanco, List.of(shaker, nada), Map.of("1 - P", "463BX"));
        assertEquals(4, h.lineas().size());
        assertEquals(3, h.lineas().stream().filter(l -> !l.completa()).count());
        assertEquals(List.of("Shakers", HojaMensual.SIN_TIPO), h.vistaPrevia().columnas());
        assertTrue(h.vistaPrevia().filas().get(0).incompleta());
        assertEquals(List.of("Shakers"), h.exportable().columnas());
        Fila total = h.exportable().filas().get(h.exportable().filas().size() - 1);
        assertEquals("Total general", total.etiqueta());
        assertEquals(240, total.total(), 1e-9);
        assertFalse(h.exportable().filas().get(0).incompleta());
    }

    @Test
    void sinTarifaParaElAnio() {
        Map<String, TipoEquipo> t = unTipo("Shaker", "Shakers", 10.0, 10.0, TipoEquipo.HORAS, Regla.LINEAR);
        Linea l = HojaMensual.linea(entrada("S1", "1 - P", "ana", "Vigo", "no", null, 0, "2027-01-01", "2027-01-01"),
                YearMonth.of(2027, 1), "Shaker", t, Map.of());
        assertFalse(l.completa());
        assertNull(l.coste());
        assertEquals("El Equipment Type «Shaker» no está configurado para 2027", l.faltas().get(0));
    }
}
