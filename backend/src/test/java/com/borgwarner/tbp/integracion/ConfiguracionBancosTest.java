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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration → Test Benches (bloque E): renombrar un banco conserva sus
 * ensayos, borrarlo los borra, mover o cambiar la organización no toca ningún
 * ensayo, y solo un Admin puede hacerlo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfiguracionBancosTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-bancos");
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

    /** Escenario limpio con el usuario indicado y la lectura de catálogos hecha (D11). */
    private ObjectNode empezar(String email) {
        enviar(HttpMethod.POST, "/api/v1/local/reinicio",
                JsonJs.objetoVacio().put("escenario", "sharepoint-2026-11-05").put("fecha", "2026-11-05").put("email", email));
        JsonNode catalogos = get("/api/v1/catalogos");
        ObjectNode cuerpo = JsonJs.objetoVacio();
        cuerpo.set("projects", catalogos.path("projects").deepCopy());
        cuerpo.set("benches", catalogos.path("benches").deepCopy());
        return cuerpo;
    }

    private int filas() {
        return enviar(HttpMethod.POST, "/api/load", null).getBody().size();
    }

    private int ensayosDe(String banco) {
        return get("/api/v1/planificacion").path("bancos").path(banco).size();
    }

    private ObjectNode banco(ObjectNode cuerpo, String nombre) {
        for (JsonNode b : cuerpo.path("benches")) {
            if (nombre.equals(b.path("name").asText())) {
                return (ObjectNode) b;
            }
        }
        throw new AssertionError("No está el banco " + nombre);
    }

    @Test
    void renombrarConservaLosEnsayos() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int enMd2 = ensayosDe("MD2");
        int total = filas();
        assertTrue(enMd2 > 0);
        banco(cuerpo, "MD2").put("name", "MD2-B");
        cuerpo.putObject("renombrados").put("MD2", "MD2-B");
        ResponseEntity<JsonNode> r = enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(enMd2, r.getBody().path("filasGuardadas").asInt());
        assertEquals(0, r.getBody().path("ensayosBorrados").asInt());
        assertEquals(enMd2, ensayosDe("MD2-B"));
        assertFalse(get("/api/v1/planificacion").path("bancos").has("MD2"));
        assertEquals(total, filas());
        boolean reescritos = false;
        for (JsonNode f : enviar(HttpMethod.POST, "/api/load", null).getBody()) {
            reescritos |= f.path("data").asText().startsWith("{\"_bench\":\"MD2-B\"");
        }
        assertTrue(reescritos, "los ensayos llevan el nombre nuevo del banco");
    }

    @Test
    void borrarUnBancoBorraSusEnsayos() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int enMd3 = ensayosDe("MD3");
        int total = filas();
        ArrayNode sinMd3 = JsonJs.mapper().createArrayNode();
        cuerpo.path("benches").forEach(b -> { if (!"MD3".equals(b.path("name").asText())) sinMd3.add(b); });
        cuerpo.set("benches", sinMd3);
        ResponseEntity<JsonNode> r = enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(enMd3, r.getBody().path("ensayosBorrados").asInt());
        assertEquals(total - enMd3, filas());
    }

    @Test
    void moverYCambiarOrganizacionNoTocaEnsayos() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int total = filas();
        String textoAntes = enviar(HttpMethod.POST, "/api/load", null).getBody().toString();
        ObjectNode md1 = banco(cuerpo, "MD1");
        ObjectNode fatiga = banco(cuerpo, "Fatiga");
        md1.put("groupKey", fatiga.path("groupKey").asText()).put("groupName", fatiga.path("groupName").asText())
                .put("dot", fatiga.path("dot").asText()).put("org", "CTAG").put("seq", 999);
        ResponseEntity<JsonNode> r = enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(0, r.getBody().path("filasGuardadas").asInt());
        assertEquals(total, filas());
        assertEquals(textoAntes, enviar(HttpMethod.POST, "/api/load", null).getBody().toString());
        boolean enFatiga = false;
        for (JsonNode g : get("/api/v1/planificacion").path("grupos")) {
            for (JsonNode b : g.path("bancos")) {
                if ("MD1".equals(b.path("nombre").asText())) {
                    enFatiga = g.path("clave").asText().equals(fatiga.path("groupKey").asText())
                            && "CTAG".equals(b.path("organizacion").asText());
                }
            }
        }
        assertTrue(enFatiga);
    }

    @Test
    void soloUnAdminPuedeCambiarBancos() {
        ObjectNode cuerpo = empezar("usuario07@ejemplo.local"); // Editor
        int total = filas();
        banco(cuerpo, "MD2").put("name", "Otro");
        cuerpo.putObject("renombrados").put("MD2", "Otro");
        assertEquals(403, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        assertEquals(total, filas());
        assertTrue(get("/api/v1/planificacion").path("bancos").has("MD2"));
    }

    @Test
    void renombrarYCrearOtroConElNombreAntiguo() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int enMd2 = ensayosDe("MD2");
        int total = filas();
        banco(cuerpo, "MD2").put("name", "MD2-B");
        ObjectNode nuevo = ((ArrayNode) cuerpo.get("benches")).addObject();
        nuevo.setAll(banco(cuerpo, "MD1").deepCopy());
        nuevo.put("name", "MD2").put("seq", 0);
        cuerpo.putObject("renombrados").put("MD2", "MD2-B");
        assertEquals(200, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        assertEquals(enMd2, ensayosDe("MD2-B"));
        assertEquals(0, ensayosDe("MD2"), "el banco nuevo con el nombre antiguo empieza vacío");
        assertEquals(total, filas());
    }

    @Test
    void noSeRenombraAUnBancoQueYaExiste() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int total = filas();
        String antes = enviar(HttpMethod.POST, "/api/load", null).getBody().toString();
        banco(cuerpo, "MD2").put("name", "MD3");
        cuerpo.putObject("renombrados").put("MD2", "MD3");
        assertEquals(400, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        assertEquals(total, filas());
        assertEquals(antes, enviar(HttpMethod.POST, "/api/load", null).getBody().toString());
    }

    @Test
    void noSeVaciaLaListaDeBancos() {
        ObjectNode cuerpo = empezar("usuario01@ejemplo.local");
        int total = filas();
        cuerpo.set("benches", JsonJs.mapper().createArrayNode());
        assertEquals(412, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        assertEquals(total, filas());
    }
}
