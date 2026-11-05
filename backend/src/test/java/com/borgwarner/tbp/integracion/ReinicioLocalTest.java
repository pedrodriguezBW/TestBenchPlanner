package com.borgwarner.tbp.integracion;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reinicio local que usan las pruebas de paridad de la interfaz React: vuelve
 * al escenario, fija la fecha y cambia de usuario. Va en su propio contexto
 * porque cambia la identidad y no debe afectar a ApiLocalTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReinicioLocalTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-reinicio");
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

    private JsonNode reiniciar(String cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("http://127.0.0.1:" + puerto + "/api/v1/local/reinicio",
                new HttpEntity<>(cuerpo, cabeceras), JsonNode.class).getBody();
    }

    private JsonNode leer(String ruta) {
        return http.getForObject("http://127.0.0.1:" + puerto + ruta, JsonNode.class);
    }

    @Test
    void cambiaFechaYUsuarioYVuelveAlEscenario() {
        JsonNode r = reiniciar("{\"escenario\":\"sharepoint-2026-11-05\",\"fecha\":\"2026-12-31\",\"email\":\"nadie@ejemplo.local\"}");
        assertTrue(r.path("ok").asBoolean());
        assertEquals("2026-12-31", leer("/api/v1/planificacion").path("hoy").asText());
        assertEquals("readonly", leer("/api/v1/sesion").path("rol").asText());

        reiniciar("{\"fecha\":\"2026-11-05\",\"email\":\"usuario07@ejemplo.local\"}");
        assertEquals("2026-11-05", leer("/api/v1/planificacion").path("hoy").asText());
        assertEquals("editor", leer("/api/v1/sesion").path("rol").asText());
    }

    @Test
    void rechazaNombresDeEscenarioConRutas() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        int estado = http.postForEntity("http://127.0.0.1:" + puerto + "/api/v1/local/reinicio",
                new HttpEntity<>("{\"escenario\":\"../privado\"}", cabeceras), JsonNode.class).getStatusCode().value();
        assertTrue(estado >= 400, "un nombre con ../ no debe aceptarse");
    }
}
