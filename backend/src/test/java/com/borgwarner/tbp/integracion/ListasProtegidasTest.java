package com.borgwarner.tbp.integracion;

import com.borgwarner.tbp.aplicacion.RegistroLecturas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D11: las listas compartidas no se reescriben si no ha habido antes una
 * lectura correcta, si la lectura falló o llegó vacía, o si se iban a dejar
 * vacías. En todos esos casos el fichero queda EXACTAMENTE igual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListasProtegidasTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-listas");
        registro.add("planner.almacen", () -> "local");
        registro.add("planner.fecha-fija", () -> "2026-11-05");
        registro.add("planner.carpeta-usuario", () -> carpeta.toString());
        registro.add("planner.local.carpeta-datos", () -> carpeta.resolve("datos").toString());
        registro.add("planner.local.escenario-inicial", () -> escenario().toString());
        registro.add("planner.local.reiniciar-al-arrancar", () -> "true");
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local");
    }

    private static Path escenario() {
        return Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"), "sharepoint-2026-11-05");
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    @Autowired
    RegistroLecturas lecturas;

    @BeforeEach
    void reiniciar() throws IOException {
        for (String f : new String[]{"configuracion.json", "usuarios.json", "recursos.json"}) {
            Files.copy(escenario().resolve(f), datos(f), StandardCopyOption.REPLACE_EXISTING);
        }
        lecturas.olvidarTodo();
    }

    private static Path datos(String fichero) {
        return carpeta.resolve("datos").resolve(fichero);
    }

    private ResponseEntity<JsonNode> post(String ruta, JsonNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("http://127.0.0.1:" + puerto + ruta,
                new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    /** Configuración actual con un proyecto menos (un cambio normal). */
    private ObjectNode configuracionSinUnProyecto() throws IOException {
        ObjectNode cfg = (ObjectNode) JsonJs.mapper().readTree(datos("configuracion.json").toFile());
        ((ArrayNode) cfg.get("projects")).remove(0);
        return cfg;
    }

    @Test
    void sinLecturaPreviaNoSeEscribe() throws IOException {
        String antes = Files.readString(datos("configuracion.json"));
        ResponseEntity<JsonNode> r = post("/api/save_config", configuracionSinUnProyecto());
        assertEquals(412, r.getStatusCode().value());
        assertEquals(antes, Files.readString(datos("configuracion.json")));
    }

    @Test
    void conLecturaPreviaSeGuarda() throws IOException {
        assertEquals(200, post("/api/load_config", null).getStatusCode().value());
        ObjectNode cfg = configuracionSinUnProyecto();
        assertEquals(200, post("/api/save_config", cfg).getStatusCode().value());
        JsonNode guardado = JsonJs.mapper().readTree(datos("configuracion.json").toFile());
        assertEquals(cfg.get("projects").size(), guardado.get("projects").size());
    }

    @Test
    void vaciarProyectosNoSeEscribe() throws IOException {
        post("/api/load_config", null);
        String antes = Files.readString(datos("configuracion.json"));
        ObjectNode cfg = (ObjectNode) JsonJs.mapper().readTree(antes);
        cfg.putArray("projects");
        ResponseEntity<JsonNode> r = post("/api/save_config", cfg);
        assertEquals(412, r.getStatusCode().value());
        assertTrue(r.getBody().path("mensaje").asText().contains("vacía"));
        assertEquals(antes, Files.readString(datos("configuracion.json")));
    }

    @Test
    void lecturaVaciaNoHabilitaLaEscritura() throws IOException {
        // Los bancos guardados llegan vacíos (lectura inesperada): no se puede guardar.
        ObjectNode cfg = (ObjectNode) JsonJs.mapper().readTree(datos("configuracion.json").toFile());
        ArrayNode bancos = cfg.get("benches").deepCopy();
        cfg.putArray("benches");
        Files.writeString(datos("configuracion.json"), JsonJs.escribir(cfg));
        post("/api/load_config", null);
        cfg.set("benches", bancos);
        String antes = Files.readString(datos("configuracion.json"));
        assertEquals(412, post("/api/save_config", cfg).getStatusCode().value());
        assertEquals(antes, Files.readString(datos("configuracion.json")));
    }

    @Test
    void lecturaFallidaAnulaLaAnterior() throws IOException {
        post("/api/load_config", null);
        ObjectNode cfg = configuracionSinUnProyecto();
        Files.writeString(datos("configuracion.json"), "{ esto no es JSON");
        assertNotEquals(200, post("/api/load_config", null).getStatusCode().value());
        assertEquals(412, post("/api/save_config", cfg).getStatusCode().value());
        assertEquals("{ esto no es JSON", Files.readString(datos("configuracion.json")));
    }

    @Test
    void usuariosSoloTrasWhoamiYNuncaVacios() throws IOException {
        String antes = Files.readString(datos("usuarios.json"));
        ObjectNode vacio = JsonJs.objetoVacio();
        vacio.putArray("users");
        assertEquals(412, post("/api/save_users", vacio).getStatusCode().value());   // sin whoami
        post("/api/whoami", null);
        assertEquals(412, post("/api/save_users", vacio).getStatusCode().value());   // dejarla vacía
        assertEquals(antes, Files.readString(datos("usuarios.json")));
    }

    @Test
    void recursosSoloTrasLeerlos() throws IOException {
        String antes = Files.readString(datos("recursos.json"));
        JsonNode recursos = JsonJs.mapper().readTree(antes);
        assertEquals(412, post("/api/save_resources", recursos).getStatusCode().value());
        post("/api/load_resources", null);
        assertEquals(200, post("/api/save_resources", recursos).getStatusCode().value());
    }
}
