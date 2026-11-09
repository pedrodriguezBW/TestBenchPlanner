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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión transversal de solo lectura: todas las consultas de las pantallas
 * migradas siguen disponibles y las operaciones que persisten datos devuelven
 * 403 antes de modificar el almacén.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoloLecturaIntegracionTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-solo-lectura");
        registro.add("planner.almacen", () -> "local");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", () -> carpeta.toString());
        registro.add("planner.local.carpeta-datos", () -> carpeta.resolve("datos").toString());
        registro.add("planner.local.escenario-inicial",
                () -> Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05").toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        registro.add("planner.local.usuario-email", () -> "nadie@ejemplo.local");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void reiniciarComoSoloLectura() {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("escenario", "sharepoint-2026-11-05")
                .put("fecha", "2026-11-05").put("email", "nadie@ejemplo.local");
        assertEquals(200, enviar(HttpMethod.POST, "/api/v1/local/reinicio", cuerpo).getStatusCode().value());
        assertEquals("readonly", get("/api/v1/sesion").getBody().path("rol").asText());
    }

    private String url(String ruta) {
        return "http://127.0.0.1:" + puerto + ruta;
    }

    private ResponseEntity<JsonNode> get(String ruta) {
        return http.getForEntity(url(ruta), JsonNode.class);
    }

    private ResponseEntity<JsonNode> enviar(HttpMethod metodo, String ruta, JsonNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(ruta), metodo,
                new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    @Test
    void filtrosYRecargasDeTodasLasPantallasSiguenDisponibles() {
        ResponseEntity<JsonNode> planificacion = get("/api/v1/planificacion");
        assertEquals(200, planificacion.getStatusCode().value());
        assertTrue(planificacion.getBody().path("bancos").has("MD2"));

        JsonNode panelAll = get("/api/v1/indicadores/bloques?lunes=2026-11-02&org=ALL").getBody();
        JsonNode panelCtag = get("/api/v1/indicadores/bloques?lunes=2026-11-02&org=CTAG").getBody();
        assertNotEquals(panelAll, panelCtag, "el filtro de organización del Dashboard debe cambiar el resultado");

        ResponseEntity<JsonNode> responsable = get("/api/v1/indicadores/bloques?lunes=2026-11-02&org=BW&responsable=Responsable%2005");
        assertEquals(200, responsable.getStatusCode().value());

        JsonNode estadisticasAnio = get("/api/v1/indicadores/estadisticas?desde=2026-01-01&hasta=2026-12-31&org=ALL&responsable=").getBody();
        JsonNode estadisticasMes = get("/api/v1/indicadores/estadisticas?desde=2026-11-01&hasta=2026-11-30&org=ALL&responsable=").getBody();
        assertNotEquals(estadisticasAnio, estadisticasMes, "el rango de KPI History debe cambiar el resultado");
        assertEquals(200, get("/api/v1/indicadores/historico?org=BW").getStatusCode().value());

        assertEquals(200, get("/api/v1/recursos").getStatusCode().value());
        assertEquals(200, get("/api/v1/aprobaciones").getStatusCode().value());
    }

    @Test
    void todasLasEscriturasMigradasSiguenBloqueadas() {
        JsonNode vista = get("/api/v1/planificacion").getBody();
        JsonNode fila = vista.path("bancos").path("MD2").get(0);
        String id = fila.path("id").asText();

        ObjectNode edicion = JsonJs.objetoVacio().put("version", fila.path("version").asText());
        edicion.putArray("cambios").addObject().put("campo", "comments").put("valor", "No debe guardarse");
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/bancos/MD2/ensayos/" + id + "/campos", edicion).getStatusCode().value());

        ObjectNode operacion = JsonJs.objetoVacio().put("tipo", "anadir");
        operacion.putArray("ids");
        operacion.putObject("versiones");
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/bancos/MD2/operaciones", operacion).getStatusCode().value());
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/indicadores/foto-semanal", JsonJs.objetoVacio()).getStatusCode().value());

        JsonNode recursos = get("/api/v1/recursos").getBody();
        assertEquals(403, enviar(HttpMethod.PUT, "/api/v1/recursos", recursos).getStatusCode().value());
        ObjectNode bloqueo = JsonJs.objetoVacio().put("resource", "Hydraulic Cylinder 1")
                .put("start", "2026-11-05").put("end", "2026-11-06").put("reason", "Prueba").put("note", "");
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/recursos/bloqueos", bloqueo).getStatusCode().value());
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/recursos/bloqueos/borrar", JsonJs.objetoVacio().put("id", 1)).getStatusCode().value());
        assertEquals(403, enviar(HttpMethod.POST, "/api/v1/recursos/renombrar",
                JsonJs.objetoVacio().put("anterior", "Hydraulic Cylinder 1").put("nuevo", "No debe guardarse")).getStatusCode().value());

        JsonNode despues = get("/api/v1/planificacion").getBody().path("bancos").path("MD2").get(0).path("datos");
        assertNotEquals("No debe guardarse", despues.path("comments").asText());
    }
}
