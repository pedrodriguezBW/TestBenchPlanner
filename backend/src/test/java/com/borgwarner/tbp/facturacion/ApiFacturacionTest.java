package com.borgwarner.tbp.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.facturacion.Maestros;
import com.borgwarner.tbp.dominio.facturacion.Periodos;
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
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facturación de punta a punta con el almacén LOCAL (sin SharePoint): ficha del
 * ensayo, maestros, periodo, revisión, aprobación, exportación, reapertura,
 * auditoría y concurrencia. La planificación no se toca salvo DataJson.billing.
 */
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
        registro.add("planner.local.usuario-email", () -> "usuario01@ejemplo.local"); // Admin
    }

    @LocalServerPort
    int puerto;

    @Autowired
    TestRestTemplate http;

    private String url(String ruta) {
        return "http://127.0.0.1:" + puerto + ruta;
    }

    private ResponseEntity<JsonNode> enviar(HttpMethod metodo, String ruta, JsonNode cuerpo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(ruta), metodo, new HttpEntity<>(cuerpo == null ? "{}" : JsonJs.escribir(cuerpo), cabeceras), JsonNode.class);
    }

    private JsonNode get(String ruta) {
        return http.getForObject(url(ruta), JsonNode.class);
    }

    private static ObjectNode objeto() {
        return JsonJs.objetoVacio();
    }

    /** Primer ensayo cerrado con fechas reales y proyecto con número. */
    private String[] ensayoCerrado(JsonNode vista) {
        Iterator<Map.Entry<String, JsonNode>> bancos = vista.path("bancos").fields();
        while (bancos.hasNext()) {
            Map.Entry<String, JsonNode> b = bancos.next();
            for (JsonNode f : b.getValue()) {
                JsonNode d = f.path("datos");
                String inicio = d.path("finalTrueStart").asText("");
                String fin = d.path("finalTrueEnd").asText("");
                String proyecto = Maestros.proyectoDeTexto(d.path("project").asText(""))[0];
                if (f.path("cerrado").asBoolean() && !inicio.isEmpty() && !fin.isEmpty() && fin.compareTo(inicio) > 0 && !proyecto.isEmpty()) {
                    return new String[] {b.getKey(), f.path("id").asText(), f.path("version").asText(), proyecto, fin};
                }
            }
        }
        throw new IllegalStateException("El escenario no tiene ensayos cerrados con fechas");
    }

    @Test
    void flujoCompletoDeUnPeriodo() {
        JsonNode vista = get("/api/v1/planificacion");
        String[] e = ensayoCerrado(vista);
        String banco = e[0];
        String id = e[1];
        String periodo = Periodos.deDia(e[4]);

        // Maestros: el proyecto del ensayo con una sola orden y el banco mapeado a cámara climática.
        JsonNode vm = get("/api/v1/facturacion/maestros");
        assertEquals(5, vm.path("reglas").size());
        ObjectNode maestros = vm.path("maestros").deepCopy();
        // Se sustituyen el proyecto y el mapeo del banco si ya existían en los maestros iniciales.
        ArrayNode proyectos = (ArrayNode) maestros.get("proyectos");
        for (int i = proyectos.size() - 1; i >= 0; i--) {
            if (Maestros.numeroCanonico(proyectos.get(i).path("numero").asText()).equals(e[3])) {
                proyectos.remove(i);
            }
        }
        ArrayNode mapeos = (ArrayNode) maestros.get("mapeos");
        for (int i = mapeos.size() - 1; i >= 0; i--) {
            if (mapeos.get(i).path("banco").asText().equals(banco)) {
                mapeos.remove(i);
            }
        }
        ((ArrayNode) maestros.get("proyectos")).addObject().put("numero", e[3]).put("nombre", "Proyecto de prueba").put("activo", true)
                .putArray("ordenes").addObject().put("codigo", "463BPRUEBA01").put("activa", true);
        ((ArrayNode) maestros.get("mapeos")).addObject().put("id", "M-PRUEBA").put("banco", banco).put("tipoEnsayo", "")
                .put("categoria", "Cámara climática").put("regla", "").put("activo", true);
        ObjectNode cuerpo = objeto();
        cuerpo.set("maestros", maestros);
        cuerpo.put("version", vm.path("version").asText(""));
        ResponseEntity<JsonNode> r = enviar(HttpMethod.PUT, "/api/v1/facturacion/maestros", cuerpo);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));

        // Paradas solapadas: 422 con el motivo.
        ObjectNode billing = objeto();
        billing.put("startTime", "08:00").put("endTime", "16:00").put("hv", "no").put("setupHours", 1).put("setupConfirmed", true);
        ArrayNode paradas = billing.putArray("stops");
        paradas.addObject().put("from", e[4] + "T08:30").put("to", e[4] + "T10:00").put("reason", "Averia").put("billable", false);
        paradas.addObject().put("from", e[4] + "T09:00").put("to", e[4] + "T11:00").put("reason", "Averia").put("billable", false);
        ObjectNode guardar = objeto();
        guardar.set("billing", billing);
        guardar.put("version", e[2]);
        r = enviar(HttpMethod.PUT, "/api/v1/facturacion/bancos/" + banco + "/ensayos/" + id, guardar);
        assertEquals(422, r.getStatusCode().value());
        assertTrue(r.getBody().path("mensaje").asText().contains("se solapan"));

        // Datos correctos: se guardan en DataJson.billing sin tocar el resto del ensayo.
        paradas.remove(1);
        r = enviar(HttpMethod.PUT, "/api/v1/facturacion/bancos/" + banco + "/ensayos/" + id, guardar);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        JsonNode ficha = get("/api/v1/facturacion/bancos/" + banco + "/ensayos/" + id);
        assertEquals("08:00", ficha.path("billing").path("startTime").asText());
        assertEquals("COMPLETO", ficha.path("estado").path("estado").asText(), ficha.path("estado").toString());
        assertTrue(ficha.path("tramos").size() >= 1);

        // Periodo: la línea del ensayo calculada; el resto se excluye para poder enviar a revisión.
        ObjectNode crear = objeto();
        crear.put("anio", Periodos.deId(periodo).anio()).put("mes", Periodos.deId(periodo).mes());
        r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos", crear);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        JsonNode p = r.getBody();
        String lineaId = id + "@" + periodo;
        JsonNode nuestra = null;
        for (JsonNode l : p.path("periodo").path("lineas")) {
            if (l.path("id").asText().equals(lineaId)) {
                nuestra = l;
            }
        }
        assertNotNull(nuestra, "Falta la línea del ensayo");
        assertTrue(nuestra.path("aplicado").path("coste").asDouble() > 0, nuestra.toString());
        assertEquals(3.72, nuestra.path("aplicado").path("tarifa").asDouble(), 1e-9);

        // Concurrencia: una versión antigua da 409.
        ObjectNode viejo = objeto();
        viejo.put("version", "version-antigua");
        assertEquals(409, enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/recalcular", viejo).getStatusCode().value());

        String version = p.path("version").asText();
        for (JsonNode l : p.path("periodo").path("lineas")) {
            String estado = l.path("estado").asText();
            if (!l.path("id").asText().equals(lineaId) && !estado.equals("EXCLUDED") && !estado.equals("PENDING")) {
                ObjectNode accion = objeto();
                accion.put("accion", "excluir").put("version", version);
                r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/lineas/" + l.path("id").asText(), accion);
                assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
                version = r.getBody().path("version").asText();
            }
        }
        JsonNode actual = get("/api/v1/facturacion/periodos/" + periodo);
        version = actual.path("version").asText();
        for (JsonNode l : actual.path("periodo").path("lineas")) {
            if (l.path("estado").asText().equals("PENDING") && l.path("incidencias").size() > 0) {
                ObjectNode accion = objeto();
                accion.put("accion", "excluir").put("version", version);
                version = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/lineas/" + l.path("id").asText(), accion)
                        .getBody().path("version").asText();
            }
        }
        ObjectNode v = objeto();
        v.put("version", version);
        r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/enviar", v);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        assertEquals("EN_REVISION", r.getBody().path("periodo").path("estado").asText());

        // Aprobar sin confirmar: 422; confirmando: APROBADO con foto v1.
        ObjectNode aprobar = objeto();
        aprobar.put("version", r.getBody().path("version").asText()).put("confirmacion", false);
        assertEquals(422, enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/aprobar", aprobar).getStatusCode().value());
        aprobar.put("confirmacion", true);
        r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/aprobar", aprobar);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        assertEquals("APROBADO", r.getBody().path("periodo").path("estado").asText());
        assertEquals(1, r.getBody().path("periodo").path("versionAprobada").asInt());

        // Exportar todo: Excel completo, Controlling y HTML.
        ObjectNode exportar = objeto();
        exportar.put("version", r.getBody().path("version").asText());
        exportar.putArray("tipos").add("todos");
        r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/exportar", exportar);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        assertEquals(3, r.getBody().path("exportaciones").size());
        byte[] excel = Base64.getDecoder().decode(r.getBody().path("exportaciones").get(0).path("b64").asText());
        assertEquals('P', (char) excel[0]);
        assertEquals("EXPORTADO", r.getBody().path("periodo").path("periodo").path("estado").asText());
        assertTrue(Files.exists(carpeta.resolve("datos").resolve("facturacion").resolve("exportaciones")
                .resolve(r.getBody().path("exportaciones").get(0).path("nombre").asText())));

        // Reabrir: justificación obligatoria; las exportaciones quedan obsoletas.
        ObjectNode reabrir = objeto();
        reabrir.put("version", r.getBody().path("periodo").path("version").asText()).put("justificacion", "");
        assertEquals(422, enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/reabrir", reabrir).getStatusCode().value());
        reabrir.put("justificacion", "Prueba de reapertura");
        r = enviar(HttpMethod.POST, "/api/v1/facturacion/periodos/" + periodo + "/reabrir", reabrir);
        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        assertEquals("EN_REVISION", r.getBody().path("periodo").path("estado").asText());
        for (JsonNode x : r.getBody().path("periodo").path("exportaciones")) {
            assertEquals("OBSOLETA", x.path("estado").asText());
        }
        // Una exportación obsoleta se puede volver a descargar (no se borra).
        String exportId = r.getBody().path("periodo").path("exportaciones").get(0).path("id").asText();
        JsonNode descarga = get("/api/v1/facturacion/periodos/" + periodo + "/exportaciones/" + exportId);
        assertEquals("OBSOLETA", descarga.path("estado").asText());

        // Auditoría: aprobación, exportación y reapertura registradas.
        JsonNode auditoria = get("/api/v1/facturacion/auditoria?periodo=" + periodo);
        String texto = auditoria.toString();
        assertTrue(texto.contains("PERIODO_APROBADO") && texto.contains("EXPORTACION") && texto.contains("PERIODO_REABIERTO"), texto);
        assertTrue(get("/api/v1/facturacion/auditoria").toString().contains("MAESTROS_MODIFICADOS"));

        // El listado de periodos lo muestra con su importe.
        JsonNode lista = get("/api/v1/facturacion/periodos");
        assertEquals(periodo, lista.get(0).path("id").asText());
    }
}
