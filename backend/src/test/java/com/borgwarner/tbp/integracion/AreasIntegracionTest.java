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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Áreas de bancos de punta a punta (almacén local): mover una categoría a un
 * área HVCH, organización fija, nombres de área, filtros de los KPI y foto
 * semanal con una fila por área.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AreasIntegracionTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-areas");
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

    private ObjectNode empezar() {
        enviar(HttpMethod.POST, "/api/v1/local/reinicio", JsonJs.objetoVacio().put("escenario", "sharepoint-2026-11-05")
                .put("fecha", "2026-11-05").put("email", "usuario01@ejemplo.local"));
        JsonNode catalogos = get("/api/v1/catalogos");
        ObjectNode cuerpo = JsonJs.objetoVacio();
        cuerpo.set("projects", catalogos.path("projects").deepCopy());
        cuerpo.set("benches", catalogos.path("benches").deepCopy());
        return cuerpo;
    }

    /** Pone el área a todos los bancos (Térmicos) y la categoría del banco indicado en otra área. */
    private static String moverCategoria(ObjectNode cuerpo, String banco, String area) {
        String clave = null;
        for (JsonNode b : cuerpo.get("benches")) {
            if (banco.equals(b.path("name").asText())) {
                clave = b.path("groupKey").asText();
            }
        }
        for (JsonNode b : cuerpo.get("benches")) {
            ((ObjectNode) b).put("area", b.path("groupKey").asText().equals(clave) ? area : "THERMAL");
        }
        return clave;
    }

    private List<String> bancosDelArea(String area) {
        List<String> bancos = new ArrayList<>();
        for (JsonNode g : get("/api/v1/planificacion").path("grupos")) {
            if (area.equals(g.path("area").asText())) {
                g.path("bancos").forEach(b -> bancos.add(b.path("nombre").asText()));
            }
        }
        return bancos;
    }

    @Test
    void sinAreaTodoEsTermicoYLaVistaTraeLasTresAreas() {
        empezar();
        JsonNode vista = get("/api/v1/planificacion");
        assertEquals(3, vista.path("areas").size());
        assertEquals("THERMAL", vista.path("areas").get(0).path("clave").asText());
        assertEquals("Thermal Benches", vista.path("areas").get(0).path("nombre").asText());
        assertEquals("HVCH - CTAG", vista.path("areas").get(1).path("nombre").asText());
        assertEquals("HVCH - STC", vista.path("areas").get(2).path("nombre").asText());
        for (JsonNode g : vista.path("grupos")) {
            assertEquals("THERMAL", g.path("area").asText());
        }
    }

    @Test
    void moverUnaCategoriaAHvchFijaSuOrganizacionYFiltra() {
        ObjectNode cuerpo = empezar();
        int totalAntes = get("/api/v1/indicadores/bloques?org=ALL").path("estaSemana").path("total").asInt();
        moverCategoria(cuerpo, "MD1", "HVCH-STC");
        cuerpo.putObject("areaNames").put("HVCH-STC", "STC Heaters");
        assertEquals(200, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());

        List<String> stc = bancosDelArea("HVCH-STC");
        assertTrue(stc.contains("MD1") && !stc.isEmpty());
        for (JsonNode b : get("/api/v1/catalogos").path("benches")) {
            if (stc.contains(b.path("name").asText())) {
                assertEquals("STC", b.path("org").asText(), "los bancos de HVCH - STC llevan organización STC");
                assertEquals("HVCH-STC", b.path("area").asText());
            }
        }
        assertEquals("STC Heaters", get("/api/v1/planificacion").path("areas").get(2).path("nombre").asText());

        int termicos = get("/api/v1/indicadores/bloques?org=THERMAL").path("estaSemana").path("total").asInt();
        int deStc = get("/api/v1/indicadores/bloques?org=HVCH-STC").path("estaSemana").path("total").asInt();
        int bw = get("/api/v1/indicadores/bloques?org=BW").path("estaSemana").path("total").asInt();
        int ctag = get("/api/v1/indicadores/bloques?org=CTAG").path("estaSemana").path("total").asInt();
        assertEquals(totalAntes, get("/api/v1/indicadores/bloques?org=ALL").path("estaSemana").path("total").asInt());
        assertEquals(stc.size(), deStc);
        assertEquals(totalAntes, termicos + deStc);
        assertEquals(termicos, bw + ctag);
        assertEquals(0, get("/api/v1/indicadores/bloques?org=HVCH-CTAG").path("estaSemana").path("total").asInt());
    }

    @Test
    void laInterfazClasicaConservaElArea() {
        ObjectNode cuerpo = empezar();
        moverCategoria(cuerpo, "MD1", "HVCH-CTAG");
        assertEquals(200, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        // La clásica guarda la configuración sin «area».
        ObjectNode clasica = JsonJs.objetoVacio();
        clasica.set("projects", get("/api/v1/catalogos").path("projects").deepCopy());
        ArrayNode bancos = clasica.putArray("benches");
        for (JsonNode b : get("/api/v1/catalogos").path("benches")) {
            ObjectNode copia = b.deepCopy();
            copia.remove("area");
            copia.remove("equipmentType");
            bancos.add(copia);
        }
        assertEquals(200, enviar(HttpMethod.POST, "/api/save_config", clasica).getStatusCode().value());
        assertTrue(bancosDelArea("HVCH-CTAG").contains("MD1"));
    }

    @Test
    void laFotoSemanalTieneUnaFilaPorFiltro() {
        ObjectNode cuerpo = empezar();
        moverCategoria(cuerpo, "MD1", "HVCH-STC");
        assertEquals(200, enviar(HttpMethod.PUT, "/api/v1/configuracion/bancos", cuerpo).getStatusCode().value());
        JsonNode fotos = enviar(HttpMethod.POST, "/api/v1/indicadores/foto-semanal", null).getBody();
        List<String> orgs = new ArrayList<>();
        fotos.forEach(f -> orgs.add(f.path("org").asText()));
        assertEquals(List.of("ALL", "BW", "CTAG", "THERMAL", "HVCH-CTAG", "HVCH-STC"), orgs);
        assertEquals(fotos.get(0).path("total").asInt(), fotos.get(3).path("total").asInt() + fotos.get(5).path("total").asInt());
        assertEquals(fotos.get(3).path("total").asInt(), fotos.get(1).path("total").asInt() + fotos.get(2).path("total").asInt());
        JsonNode historico = get("/api/v1/indicadores/historico?org=HVCH-STC");
        assertTrue(historico.path("semanas").size() >= 1);
    }
}
