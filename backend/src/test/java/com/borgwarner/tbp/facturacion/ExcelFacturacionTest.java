package com.borgwarner.tbp.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.FlujoFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Maestros;
import com.borgwarner.tbp.dominio.facturacion.Nodos;
import com.borgwarner.tbp.dominio.facturacion.Periodos;
import com.borgwarner.tbp.dominio.facturacion.ResumenFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Tiempo;
import com.borgwarner.tbp.infraestructura.facturacion.ExportacionesFacturacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paridad con «2026 Ensayos interno.xlsx» (referencia extraída con
 * herramientas/laboratorio/facturacion/generar_referencia_excel.py):
 *   - las 522 filas de 2026 obtienen la misma tarifa (Coste/hora) y el mismo
 *     Coste total con las tarifas, reglas y redondeos trasladados a los maestros;
 *   - la hoja «Controlling - Internal Testing» de octubre sale igual: mismas
 *     filas, mismo orden de equipos, mismos importes y horas;
 *   - el .xlsx de Controlling exportado tiene esa hoja con las columnas del Excel.
 */
class ExcelFacturacionTest {

    private static JsonNode referencia() throws IOException {
        return JsonJs.mapper().readTree(Files.readString(ParidadFacturacionTest.carpeta().resolve("referencia-excel-2026.json")));
    }

    private static ObjectNode linea(JsonNode f, JsonNode maestros) {
        ObjectNode cat = Maestros.buscarCategoria(maestros, f.get("equipo").asText());
        String mes = f.get("mes").asText();
        ObjectNode t = Maestros.resolverTarifa(maestros, new Maestros.ConsultaTarifa(f.get("equipo").asText(), f.get("hv").asText(),
                Maestros.numeroCanonico(f.get("numeroProyecto").asText()), "", f.path("planta").asText(""), Periodos.deId(mes).fin()));
        assertNotNull(t, "Sin tarifa para la fila " + f.get("fila"));
        String regla = Nodos.texto(cat, "regla").equals("PER_PIECE") ? "LINEAR" : Nodos.texto(cat, "regla");
        double horas = f.get("horasTotales").asDouble();
        Double coste = Maestros.costePorRegla(regla, horas, t.get("importe").asDouble());
        if (Maestros.numeroCanonico(f.get("numeroProyecto").asText()).equals("0")) {
            coste = 0.0;
        }
        ObjectNode l = Nodos.objeto();
        l.put("id", "F" + f.get("fila").asInt()).put("ensayoId", "F" + f.get("fila").asInt()).put("banco", "X").put("tipoEnsayo", "");
        ObjectNode a = l.putObject("aplicado");
        a.put("categoria", f.get("equipo").asText()).put("orden", f.get("orden").asText()).put("textoControlling", f.get("equipo").asText())
                .put("unidad", "H").put("horasTotales", horas).put("cantidad", horas).put("tarifa", t.get("importe").asDouble());
        a.set("coste", Nodos.num(coste));
        a.putNull("piezas");
        return l;
    }

    @Test
    void tarifasYCostesDeTodasLasFilasDe2026() throws IOException {
        JsonNode ref = referencia();
        ObjectNode maestros = ParidadFacturacionTest.maestrosIniciales();
        int filas = 0;
        for (JsonNode f : ref.get("filas")) {
            ObjectNode l = linea(f, maestros);
            assertEquals(f.get("costeHora").asDouble(), l.get("aplicado").get("tarifa").asDouble(), 1e-9, "Coste/hora fila " + f.get("fila"));
            assertEquals(f.get("costeTotal").asDouble(), l.get("aplicado").get("coste").asDouble(),
                    1e-6 * Math.max(1, Math.abs(f.get("costeTotal").asDouble())), "Coste total fila " + f.get("fila"));
            filas++;
        }
        assertEquals(522, filas);
    }

    private static ArrayNode controllingDelMes(JsonNode ref, JsonNode maestros) {
        List<JsonNode> lineas = new ArrayList<>();
        for (JsonNode f : ref.get("filas")) {
            if (f.get("mes").asText().equals(ref.get("mesControlling").asText())) {
                lineas.add(linea(f, maestros));
            }
        }
        return ResumenFacturacion.controlling(lineas, maestros);
    }

    @Test
    void controllingDeOctubreIgualQueElExcel() throws IOException {
        JsonNode ref = referencia();
        ObjectNode maestros = ParidadFacturacionTest.maestrosIniciales();
        ArrayNode ctrl = controllingDelMes(ref, maestros);
        assertEquals(ref.get("controlling").size(), ctrl.size());
        Map<String, JsonNode> esperado = new HashMap<>();
        List<String> ordenEquipos = new ArrayList<>();
        for (JsonNode r : ref.get("controlling")) {
            esperado.put(r.get("equipo").asText() + "|" + r.get("orden").asText().trim(), r);
            if (!ordenEquipos.contains(r.get("equipo").asText())) {
                ordenEquipos.add(r.get("equipo").asText());
            }
        }
        List<String> ordenObtenido = new ArrayList<>();
        for (JsonNode r : ctrl) {
            JsonNode e = esperado.get(r.get("equipo").asText() + "|" + r.get("orderNew").asText());
            assertNotNull(e, "Fila de Controlling que no está en el Excel: " + r.get("equipo") + " " + r.get("orden"));
            assertEquals(e.get("sumaCoste").asDouble(), r.get("sumaCoste").asDouble(), 1e-6);
            assertEquals(e.get("sumaHoras").asDouble(), r.get("sumaHoras").asDouble(), 1e-9);
            assertEquals(Tiempo.redondeoExcel2(e.get("sumaCoste").asDouble()), r.get("amount").asDouble(), 1e-9);
            assertEquals(e.get("cctrOld").asText(), r.get("cctrOld").asText());
            assertEquals(e.get("costElement").asText(), r.get("costElement").asText());
            assertEquals("EUR", r.get("crcy").asText());
            assertEquals("H", r.get("um").asText());
            if (!ordenObtenido.contains(r.get("equipo").asText())) {
                ordenObtenido.add(r.get("equipo").asText());
            }
        }
        assertEquals(ordenEquipos, ordenObtenido, "Orden de equipos de la hoja Controlling");
    }

    @Test
    void elExcelDeControllingTieneLaHojaYLasColumnasDelLibro() throws IOException {
        JsonNode ref = referencia();
        ObjectNode maestros = ParidadFacturacionTest.maestrosIniciales();
        ObjectNode doc = FlujoFacturacion.nuevoPeriodo(2026, 10, new FlujoFacturacion.Firmante("Prueba", "admin"),
                new com.borgwarner.tbp.dominio.facturacion.MotorFacturacion.Contexto("2026-10-21T09:00"));
        ArrayNode lineas = doc.putArray("lineas");
        for (JsonNode f : ref.get("filas")) {
            if (f.get("mes").asText().equals("2026-10")) {
                ObjectNode l = linea(f, maestros);
                l.put("estado", "READY").put("pendiente", false).putNull("exclusion");
                l.putNull("aplazamiento");
                l.putObject("ajustes");
                l.putArray("incidencias");
                ObjectNode a = (ObjectNode) l.get("aplicado");
                a.put("horasNetas", a.get("horasTotales").asDouble()).put("horasSetup", 0).put("grupo", "").put("regla", "LINEAR");
                lineas.add(l);
            }
        }
        ExportacionesFacturacion.Archivo a = ExportacionesFacturacion.excelControlling(doc, maestros,
                new ExportacionesFacturacion.Meta("EXP-2026-10-v1-1", 1, "Admin", "2026-10-21T10:00", "Admin", "2026-10-21T10:05"));
        assertEquals("Facturacion_2026-10_v1_controlling.xlsx", a.nombre());
        Map<String, String> partes = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(a.contenido()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                partes.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(partes.get("xl/workbook.xml").contains("name=\"Controlling - Internal Testing\""));
        String hoja = partes.get("xl/worksheets/sheet1.xml");
        for (String cabecera : List.of("Equipo", "Orden", "Suma de Coste total", "Sum of Horas totales", "CCtr (Old)", "Order (old)", "Oper. (old)",
                "Cost Element", "Amount", "Crcy", "CCtr (new)", "Order (new)", "Oper. (new)", "Total Qty", "UM", "Pers.No.", "Text")) {
            assertTrue(hoja.contains(">" + cabecera + "<"), "Falta la columna " + cabecera);
        }
        assertEquals(3 + ref.get("controlling").size(), hoja.split("<row ").length - 1, "Cabecera (3 filas) + filas de Controlling");
        assertTrue(hoja.contains(">6463-41<") && hoja.contains(">420000<") && hoja.contains(">EUR<"));
    }
}
