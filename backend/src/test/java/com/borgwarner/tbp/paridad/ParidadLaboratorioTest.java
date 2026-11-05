package com.borgwarner.tbp.paridad;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * PARIDAD con la 4.9.4: ejecuta cada caso del laboratorio sobre el dominio Java
 * y compara su foto con la referencia generada por la 4.9.4 original.
 *
 * Se comparan los apartados que dependen del backend (planificación, DataJson
 * guardado, orden, KPI, ocupación, estadísticas, histórico y valores de la
 * ventana de cierre). Los textos de avisos y el número de envíos son de la
 * interfaz y se comprobarán en las pruebas de React (Hito 3).
 *
 * Una diferencia solo se admite si está en cambios-aprobados.json, ligada a
 * una discrepancia aprobada (D1, D8…). La referencia NUNCA se toca para que
 * una prueba pase.
 *
 * Cada foto se guarda también en target/paridad/ para poder usar el
 * comparador del laboratorio (node comparar.cjs ../backend/target/paridad).
 */
class ParidadLaboratorioTest {

    private static final List<String> APARTADOS = List.of("planificacion", "persistido", "orden", "cambiosPersistidos",
            "kpi", "estadisticas", "historicoKpi");

    private static Path laboratorio() {
        return Path.of(System.getProperty("laboratorio.dir", "../herramientas/laboratorio"));
    }

    private static Path escenarios() {
        return Path.of(System.getProperty("escenarios.dir", "../datos/escenarios"));
    }

    @TestFactory
    Stream<DynamicTest> casosDelLaboratorio() throws IOException {
        List<JsonNode> casos = new ArrayList<>();
        try (Stream<Path> ficheros = Files.list(laboratorio().resolve("casos"))) {
            for (Path f : ficheros.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonJs.mapper().readTree(f.toFile()).forEach(casos::add);
            }
        }
        JsonNode aprobados = JsonJs.mapper().readTree(laboratorio().resolve("cambios-aprobados.json").toFile());
        Path salida = Path.of("target", "paridad");
        Files.createDirectories(salida);
        return casos.stream().map(caso -> DynamicTest.dynamicTest(caso.path("id").asText() + " · " + caso.path("titulo").asText(),
                () -> comprobar(caso, aprobados, salida)));
    }

    private void comprobar(JsonNode caso, JsonNode aprobados, Path salida) throws IOException {
        String id = caso.path("id").asText();
        Path rutaReferencia = laboratorio().resolve("referencias").resolve(id + ".json");
        if (!Files.exists(rutaReferencia)) {
            fail("No hay referencia de la 4.9.4 para " + id + " (genera las referencias con el laboratorio)");
        }
        JsonNode referencia = JsonJs.mapper().readTree(rutaReferencia.toFile()).path("resultado");
        ObjectNode foto = new LaboratorioJava(caso, escenarios()).ejecutar();

        ObjectNode guardada = JsonJs.objetoVacio();
        guardada.put("id", id);
        guardada.set("resultado", foto);
        JsonJs.mapper().writerWithDefaultPrettyPrinter().writeValue(salida.resolve(id + ".json").toFile(), guardada);

        List<String> sinClasificar = new ArrayList<>();
        for (String apartado : APARTADOS) {
            if (!referencia.has(apartado)) {
                continue;
            }
            for (String[] d : Normalizacion.comparar(referencia.get(apartado), foto.get(apartado))) {
                String ruta = "/" + apartado + d[0];
                if (!aprobada(aprobados, id, ruta)) {
                    sinClasificar.add(ruta + "\n      4.9.4: " + d[1] + "\n      Java : " + d[2]);
                }
            }
        }
        if (referencia.has("cierre")) {
            JsonNode refCierre = referencia.get("cierre");
            JsonNode javaCierre = foto.path("cierre");
            for (String campo : List.of("abierta", "arranqueReal", "finReal", "estadoFinal", "motivo", "diasParada", "error")) {
                if (!refCierre.has(campo)) {
                    continue;
                }
                String a = refCierre.get(campo).asText();
                String b = javaCierre.path(campo).asText();
                if (!a.equals(b) && !aprobada(aprobados, id, "/cierre/" + campo)) {
                    sinClasificar.add("/cierre/" + campo + "\n      4.9.4: " + a + "\n      Java : " + b);
                }
            }
        }
        if (!sinClasificar.isEmpty()) {
            fail(id + ": " + sinClasificar.size() + " diferencia(s) sin clasificar con la 4.9.4:\n  "
                    + String.join("\n  ", sinClasificar.subList(0, Math.min(25, sinClasificar.size()))));
        }
    }

    private static boolean aprobada(JsonNode aprobados, String caso, String ruta) {
        for (JsonNode a : aprobados) {
            if (Pattern.compile(a.path("caso").asText()).matcher(caso).find()
                    && Pattern.compile(a.path("ruta").asText()).matcher(ruta).find()) {
                return true;
            }
        }
        return false;
    }
}
