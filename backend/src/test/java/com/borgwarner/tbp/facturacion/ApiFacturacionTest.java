package com.borgwarner.tbp.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API de facturación con el almacén LOCAL: Equipment Types, hoja mensual y exportación. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFacturacionTest {

    private static Path carpeta;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) throws IOException {
        carpeta = Files.createTempDirectory("tbp-facturacion");
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

    private String url(String ruta) {
        return "http://127.0.0.1:" + puerto + "/api/v1/facturacion" + ruta;
    }

    private ResponseEntity<JsonNode> put(String ruta, String json) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(ruta), HttpMethod.PUT, new HttpEntity<>(json, cabeceras), JsonNode.class);
    }

    @Test
    void tiposDeEquipoHojaYExportacion() {
        JsonNode inicial = http.getForObject(url("/tipos-equipo"), JsonNode.class);
        assertEquals(0, inicial.path("tipos").size(), "sin datos precargados");
        assertEquals(5, inicial.path("reglas").size());
        assertTrue(inicial.path("bancos").size() > 0);

        String tipo = "{\"titulo\":\"Cámara climática\",\"grupo\":\"Climático\",\"anio\":2026,\"sinHv\":3.72,\"conHv\":8.13,\"unidad\":\"HORAS\",\"regla\":\"LINEAR\"}";
        ResponseEntity<JsonNode> guardado = put("/tipos-equipo", "{\"tipos\":[" + tipo + "]}");
        assertEquals(200, guardado.getStatusCode().value(), String.valueOf(guardado.getBody()));
        assertEquals(1, guardado.getBody().path("tipos").size());
        assertEquals(8.13, guardado.getBody().path("tipos").get(0).path("conHv").asDouble(), 1e-9);
        assertEquals(1, http.getForObject(url("/tipos-equipo"), JsonNode.class).path("tipos").size());

        assertEquals(422, put("/tipos-equipo", "{\"tipos\":[" + tipo + "," + tipo + "]}").getStatusCode().value(), "repetido en el mismo año");
        assertEquals(422, put("/tipos-equipo", "{\"tipos\":[{\"titulo\":\"X\",\"anio\":2026,\"regla\":\"OTRA\"}]}").getStatusCode().value());

        JsonNode hoja = http.getForObject(url("/hoja?mes=2026-10"), JsonNode.class);
        assertEquals("2026-10", hoja.path("mes").asText());
        assertTrue(hoja.path("lineas").size() > 0, "hay tareas en octubre");
        // Ningún banco tiene Equipment Type en el escenario: todo es vista previa, nada se exporta.
        assertEquals(hoja.path("lineas").size(), hoja.path("incompletas").asInt());
        assertEquals(0, hoja.path("exportable").path("filas").size());

        JsonNode excel = http.getForObject(url("/hoja/exportar?mes=2026-10&formato=excel"), JsonNode.class);
        assertEquals("Facturacion_2026-10_OCTUBRE.xlsx", excel.path("filename").asText());
        byte[] bytes = Base64.getDecoder().decode(excel.path("b64").asText());
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        JsonNode html = http.getForObject(url("/hoja/exportar?mes=2026-10&formato=html"), JsonNode.class);
        String texto = new String(Base64.getDecoder().decode(html.path("b64").asText()), StandardCharsets.UTF_8);
        assertTrue(texto.contains("<table") && texto.contains("OCTUBRE 2026"));

        assertEquals(422, http.getForEntity(url("/hoja?mes=octubre"), JsonNode.class).getStatusCode().value());
        JsonNode dos = http.getForObject(url("/hoja?mes=2026-10&niveles=hv,orden"), JsonNode.class);
        assertEquals("[\"Billing Order\",\"HV\"]", dos.path("vistaPrevia").path("niveles").toString(), "campos elegidos, en el orden fijo");
        assertEquals(8, dos.path("nivelesDisponibles").size());
        assertEquals(422, http.getForEntity(url("/hoja?mes=2026-10&niveles=requester"), JsonNode.class).getStatusCode().value());
    }
}
