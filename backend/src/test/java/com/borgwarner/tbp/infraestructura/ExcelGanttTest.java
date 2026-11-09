package com.borgwarner.tbp.infraestructura;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.excel.ExcelGantt;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gantt a Excel (bloque F): para las matrices que construye la 4.9.4, la hoja
 * y los estilos tienen que ser exactamente los de referencia, que a su vez son
 * iguales celda a celda al Excel de la 4.9.4 (ver referencias-excel/LEEME.md).
 */
class ExcelGanttTest {

    private static Path referencias() {
        return Path.of(System.getProperty("laboratorio.dir", "../herramientas/laboratorio"), "referencias-excel");
    }

    private static Map<String, String> partes(byte[] xlsx) throws IOException {
        Map<String, String> partes = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                partes.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return partes;
    }

    @TestFactory
    Stream<DynamicTest> igualQueLaReferencia() {
        return Stream.of("banco", "dash", "resp", "res", "foco").map(vista -> DynamicTest.dynamicTest(vista, () -> {
            JsonNode matriz = JsonJs.mapper().readTree(referencias().resolve(vista + ".json").toFile());
            ExcelGantt.Resultado r = ExcelGantt.generar(matriz, LocalDate.of(2026, 11, 5));
            Map<String, String> partes = partes(r.contenido());
            assertEquals(Files.readString(referencias().resolve(vista + ".sheet1.xml")), partes.get("xl/worksheets/sheet1.xml"), "hoja de " + vista);
            assertEquals(Files.readString(referencias().resolve(vista + ".styles.xml")), partes.get("xl/styles.xml"), "estilos de " + vista);
            assertTrue(r.nombre().endsWith("_20261105.xlsx"), r.nombre());
        }));
    }

    @Test
    void nombreDelFicheroComoLa494() {
        JsonNode m = JsonJs.objetoVacio().put("title", "Test bench: MD2  ·  Focus: PPQ5 Viana #5");
        assertEquals("Test_bench_MD2_Focus_PPQ5_Viana_5_20261105.xlsx", ExcelGantt.generar(m, LocalDate.of(2026, 11, 5)).nombre());
        assertEquals("gantt_20261105.xlsx", ExcelGantt.generar(JsonJs.objetoVacio().put("title", "···"), LocalDate.of(2026, 11, 5)).nombre());
    }

    @Test
    void libroCompletoConSusPartes() throws IOException {
        Map<String, String> partes = partes(ExcelGantt.generar(JsonJs.objetoVacio(), LocalDate.of(2026, 11, 5)).contenido());
        assertEquals(java.util.Set.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml", "xl/styles.xml"), partes.keySet());
        assertTrue(partes.get("xl/worksheets/sheet1.xml").contains("Gantt"));
    }
}
