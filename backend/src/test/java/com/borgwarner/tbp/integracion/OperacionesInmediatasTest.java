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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Altas y copias que la interfaz enseña al momento: el backend guarda la fila
 * con el id que la interfaz ya está enseñando (idsPropuestos) si es válido y
 * está libre; si no, usa uno nuevo como siempre. Las reglas no cambian.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperacionesInmediatasTest {

    private static final String BANCO = "MD2";
    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-inmediatas");
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

    @BeforeEach
    void reiniciar() throws IOException {
        Path escenario = Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
        Files.copy(escenario.resolve("planner.json"), carpeta.resolve("datos").resolve("planner.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private ResponseEntity<JsonNode> operar(ObjectNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.add("X-TBP-Pestana", "pestana-de-prueba");
        return http.postForEntity("http://127.0.0.1:" + puerto + "/api/v1/bancos/" + BANCO + "/operaciones",
                new HttpEntity<>(JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    private List<String> ids(JsonNode vista) {
        List<String> salida = new ArrayList<>();
        vista.path("bancos").path(BANCO).forEach(f -> salida.add(f.path("id").asText()));
        return salida;
    }

    @Test
    void elAltaSeGuardaConElIdQueLaInterfazYaEnsena() {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("tipo", "anadir");
        cuerpo.putArray("idsPropuestos").add("rprueba1");
        ResponseEntity<JsonNode> r = operar(cuerpo);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("rprueba1", r.getBody().path("resultado").asText());
        assertTrue(ids(r.getBody().path("vista")).contains("rprueba1"));
        JsonNode leida = http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class);
        assertTrue(ids(leida).contains("rprueba1"), "guardada de verdad");
    }

    @Test
    void unIdPropuestoNoValidoOYaUsadoSeSustituye() {
        JsonNode antes = http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class);
        String existente = ids(antes).get(0);
        ObjectNode cuerpo = JsonJs.objetoVacio().put("tipo", "anadir");
        cuerpo.putArray("idsPropuestos").add(existente);
        String nuevo = operar(cuerpo).getBody().path("resultado").asText();
        assertNotEquals(existente, nuevo);
        assertTrue(nuevo.matches("r[0-9a-z]{7}"));

        ObjectNode mal = JsonJs.objetoVacio().put("tipo", "anadir");
        mal.putArray("idsPropuestos").add("../../x");
        assertTrue(operar(mal).getBody().path("resultado").asText().matches("r[0-9a-z]{7}"));
    }

    @Test
    void lasCopiasUsanLosIdsPropuestosEnOrdenDebajoDeSusOriginales() {
        JsonNode antes = http.getForObject("http://127.0.0.1:" + puerto + "/api/v1/planificacion", JsonNode.class);
        List<String> activos = new ArrayList<>();
        antes.path("bancos").path(BANCO).forEach(f -> {
            if (!f.path("cerrado").asBoolean()) {
                activos.add(f.path("id").asText());
            }
        });
        ObjectNode cuerpo = JsonJs.objetoVacio().put("tipo", "duplicar");
        cuerpo.putArray("ids").add(activos.get(0)).add(activos.get(1));
        cuerpo.putArray("idsPropuestos").add("rcopia01").add("rcopia02");
        ResponseEntity<JsonNode> r = operar(cuerpo);
        assertEquals("rcopia01,rcopia02", r.getBody().path("resultado").asText());
        List<String> despues = ids(r.getBody().path("vista"));
        assertEquals(despues.indexOf(activos.get(0)) + 1, despues.indexOf("rcopia01"));
        assertEquals(despues.indexOf(activos.get(1)) + 1, despues.indexOf("rcopia02"));
    }

    @Test
    void sinSeleccionLaReglaDeSiempre() {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("tipo", "duplicar");
        cuerpo.putArray("ids");
        cuerpo.putArray("idsPropuestos").add("rcopia03");
        ResponseEntity<JsonNode> r = operar(cuerpo);
        assertEquals(422, r.getStatusCode().value());
        assertEquals("Select at least one test to duplicate.", r.getBody().path("mensaje").asText());
    }
}
