package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integración del backend con el almacén LOCAL: API compatible con la 4.9.4,
 * API nueva, control de concurrencia (409) y roles. No toca SharePoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiLocalTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-prueba");
        registro.add("planner.almacen", () -> "local");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", () -> carpeta.toString());
        registro.add("planner.local.carpeta-datos", () -> carpeta.resolve("datos").toString());
        registro.add("planner.local.escenario-inicial",
                () -> Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05").toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        // usuario01 es Admin en el escenario anonimizado
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void reiniciar() throws IOException {
        // Cada prueba parte del escenario original.
        Path escenario = Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
        Files.copy(escenario.resolve("planner.json"), carpeta.resolve("datos").resolve("planner.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private ResponseEntity<JsonNode> post(String ruta, Object cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("http://127.0.0.1:" + puerto + ruta,
                new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir((JsonNode) cuerpo), cabeceras), JsonNode.class);
    }

    @Test
    void apiCompatibleLeeLoMismoQueElEscenario() {
        ResponseEntity<JsonNode> r = post("/api/load", null);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(281, r.getBody().size());
        assertEquals("2026-11-05", http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class).path("hoy").asText());
    }

    @Test
    void whoamiDaElRolDeLaLista() {
        JsonNode yo = post("/api/whoami", null).getBody();
        assertEquals("usuario01@ejemplo.local", yo.path("email").asText());
        assertEquals("admin", yo.path("role").asText());
    }

    @Test
    void edicionConVersionYConflicto() {
        JsonNode vista = http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class);
        JsonNode md2 = vista.path("bancos").path("MD2");
        String id = md2.get(1).path("id").asText();
        String version = md2.get(1).path("version").asText();

        ObjectNode cambio = JsonJs.objetoVacio().put("version", version);
        cambio.putArray("cambios").addObject().put("campo", "comments").put("valor", "Primero");
        ResponseEntity<JsonNode> r1 = post("/api/v1/bancos/MD2/ensayos/" + id + "/campos", cambio);
        assertEquals(200, r1.getStatusCode().value());

        // Segundo usuario con la versión antigua: conflicto, no se pisa.
        ObjectNode otro = JsonJs.objetoVacio().put("version", version);
        otro.putArray("cambios").addObject().put("campo", "comments").put("valor", "Segundo");
        ResponseEntity<JsonNode> r2 = post("/api/v1/bancos/MD2/ensayos/" + id + "/campos", otro);
        assertEquals(409, r2.getStatusCode().value());
        assertEquals("Primero", r2.getBody().path("filas").get(0).path("datos").path("comments").asText());

        // «Guardar la mía encima»
        otro.put("forzar", true);
        ResponseEntity<JsonNode> r3 = post("/api/v1/bancos/MD2/ensayos/" + id + "/campos", otro);
        assertEquals(200, r3.getStatusCode().value());
        JsonNode md2Final = r3.getBody().path("vista").path("bancos").path("MD2");
        assertEquals("Segundo", md2Final.get(1).path("datos").path("comments").asText());
    }

    @Test
    void reglaDeNegocioDevuelve422() {
        JsonNode vista = http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class);
        JsonNode primero = vista.path("bancos").path("MD2").get(0);
        ObjectNode cambio = JsonJs.objetoVacio().put("version", primero.path("version").asText());
        cambio.putArray("cambios").addObject().put("campo", "trueEnd").put("valor", "2026-01-01");
        ResponseEntity<JsonNode> r = post("/api/v1/bancos/MD2/ensayos/" + primero.path("id").asText() + "/campos", cambio);
        assertEquals(422, r.getStatusCode().value());
        assertTrue(r.getBody().path("mensaje").asText().contains("anterior al arranque real"));
    }

    @Test
    void interfazClasicaSeSirve() {
        String html = http.getForObject("http://127.0.0.1:" + puerto + "/clasico", String.class);
        assertTrue(html.contains("BW_APP_MARKER"));
    }
}
