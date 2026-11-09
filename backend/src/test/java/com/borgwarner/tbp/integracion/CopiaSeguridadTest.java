package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloque F: copia de seguridad JSON (formato de la 4.9.4) y Gantt a Excel por
 * las dos API. Importar sustituye la planificación (D10), solo Admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CopiaSeguridadTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-copia");
        registro.add("planner.almacen", () -> "local");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", () -> carpeta.toString());
        registro.add("planner.local.carpeta-datos", () -> carpeta.resolve("datos").toString());
        registro.add("planner.local.escenario-inicial",
                () -> Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05").toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<JsonNode> enviar(HttpMethod metodo, String ruta, JsonNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("http://127.0.0.1:" + puerto + ruta, metodo,
                new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    private JsonNode get(String ruta) {
        return http.getForObject("http://127.0.0.1:" + puerto + ruta, JsonNode.class);
    }

    private void empezar(String email) {
        enviar(HttpMethod.POST, "/api/v1/local/reinicio",
                JsonJs.objetoVacio().put("escenario", "sharepoint-2026-11-05").put("fecha", "2026-11-05").put("email", email));
        get("/api/v1/catalogos"); // lectura previa exigida por la protección de listas (D11)
    }

    private int filas() {
        return enviar(HttpMethod.POST, "/api/load", null).getBody().size();
    }

    @Test
    void exportarTieneElFormatoDeLa494() {
        empezar("usuario01@ejemplo.local");
        JsonNode copia = get("/api/v1/exportaciones/copia");
        JsonNode vista = get("/api/v1/planificacion");
        assertEquals(vista.path("bancos").path("MD2").size(), copia.path("benches").path("MD2").size());
        assertTrue(copia.path("groups").isArray() && copia.path("groups").size() > 0);
        assertTrue(copia.path("groups").get(0).path("benches").isArray());
        assertEquals(2026, copia.path("settings").path("year").asInt());
        assertTrue(copia.path("holidays").size() > 0);
        assertTrue(copia.path("projects").size() > 0);
        assertTrue(copia.path("benchOrg").has("MD2"));
        assertFalse(copia.path("benches").path("MD2").get(0).has("_bench"));
    }

    @Test
    void exportarEImportarSinCambiosNoTocaNada() {
        empezar("usuario01@ejemplo.local");
        String antes = enviar(HttpMethod.POST, "/api/load", null).getBody().toString();
        JsonNode copia = get("/api/v1/exportaciones/copia");
        ResponseEntity<JsonNode> r = enviar(HttpMethod.POST, "/api/v1/importaciones/copia", copia);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(0, r.getBody().path("ensayosGuardados").asInt());
        assertEquals(0, r.getBody().path("ensayosBorrados").asInt());
        assertEquals(antes, enviar(HttpMethod.POST, "/api/load", null).getBody().toString());
    }

    @Test
    void importarSustituyeLaPlanificacion() {
        empezar("usuario01@ejemplo.local");
        int total = filas();
        ObjectNode copia = (ObjectNode) get("/api/v1/exportaciones/copia");
        ArrayNode md2 = (ArrayNode) copia.path("benches").path("MD2");
        String quitado = md2.get(0).path("id").asText();
        md2.remove(0);
        ((ObjectNode) md2.get(0)).put("comments", "Cambiado en la copia");
        ResponseEntity<JsonNode> r = enviar(HttpMethod.POST, "/api/v1/importaciones/copia", copia);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(1, r.getBody().path("ensayosBorrados").asInt());
        assertTrue(r.getBody().path("ensayosGuardados").asInt() >= 1);
        assertEquals(total - 1, filas());
        JsonNode filasMd2 = get("/api/v1/planificacion").path("bancos").path("MD2");
        boolean cambiado = false, sigue = false;
        for (JsonNode f : filasMd2) {
            cambiado |= "Cambiado en la copia".equals(f.path("datos").path("comments").asText());
            sigue |= quitado.equals(f.path("id").asText());
        }
        assertTrue(cambiado);
        assertFalse(sigue);
    }

    @Test
    void importarSoloAdminYFicheroValido() {
        empezar("usuario07@ejemplo.local"); // Editor
        int total = filas();
        JsonNode copia = get("/api/v1/exportaciones/copia");
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/importaciones/copia", copia).getStatusCode().value());
        empezar("usuario01@ejemplo.local");
        assertEquals(400, enviar(HttpMethod.POST, "/api/v1/importaciones/copia", JsonJs.objetoVacio().put("x", 1)).getStatusCode().value());
        assertEquals(total, filas());
    }

    @Test
    void importarNoPuedeVaciarLaListaDeProyectos() {
        empezar("usuario01@ejemplo.local");
        String antes = enviar(HttpMethod.POST, "/api/load", null).getBody().toString();
        ObjectNode copia = (ObjectNode) get("/api/v1/exportaciones/copia");
        copia.putArray("projects");
        ((ArrayNode) copia.path("benches").path("MD2")).removeAll();
        assertEquals(412, enviar(HttpMethod.POST, "/api/v1/importaciones/copia", copia).getStatusCode().value());
        assertEquals(antes, enviar(HttpMethod.POST, "/api/load", null).getBody().toString());
    }

    @Test
    void ganttAExcelPorLasDosApi() {
        empezar("usuario01@ejemplo.local");
        ObjectNode matriz = JsonJs.objetoVacio().put("title", "Test bench: MD2");
        matriz.putArray("days").addObject().put("day", 5).put("month", "Nov 26").put("cw", 45).put("today", true);
        matriz.putArray("rows").addObject().put("label", "P1").putArray("cells").addObject().put("c0", 0).put("c1", 0).put("text", "x").put("color", "#4C8DFF");
        for (String ruta : new String[]{"/api/v1/exportaciones/gantt", "/api/export_gantt"}) {
            JsonNode r = enviar(HttpMethod.POST, ruta, matriz).getBody();
            assertTrue(r.path("ok").asBoolean(), ruta);
            assertEquals("Test_bench_MD2_20261105.xlsx", r.path("filename").asText());
            byte[] xlsx = Base64.getDecoder().decode(r.path("b64").asText());
            assertEquals('P', (char) xlsx[0]);
            assertEquals('K', (char) xlsx[1]);
        }
    }
}
