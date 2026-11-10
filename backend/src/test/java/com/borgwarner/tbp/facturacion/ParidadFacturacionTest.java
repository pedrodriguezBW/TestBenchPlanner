package com.borgwarner.tbp.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.facturacion.EnsayosFacturables;
import com.borgwarner.tbp.dominio.facturacion.FichaFacturacion;
import com.borgwarner.tbp.dominio.facturacion.FlujoFacturacion;
import com.borgwarner.tbp.dominio.facturacion.FlujoFacturacion.Firmante;
import com.borgwarner.tbp.dominio.facturacion.MotorFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Nodos;
import com.borgwarner.tbp.dominio.facturacion.Periodos;
import com.borgwarner.tbp.dominio.facturacion.ResumenFacturacion;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Paridad del módulo de facturación con la especificación ejecutable del
 * laboratorio (herramientas/laboratorio/facturacion/motor.cjs).
 *
 * Repite cada paso de casos-facturacion.json con el dominio Java (crear y
 * recalcular periodos, ajustes, propuestas de tarifa, envío, aprobación,
 * reapertura, aplazamientos, alertas, ficha del ensayo) y compara lo observado
 * con referencias-facturacion.json: líneas, estados, incidencias, horas,
 * tarifas, costes, indicadores, Controlling, errores y auditoría.
 */
class ParidadFacturacionTest {

    private static final Map<String, Firmante> USUARIOS = Map.of(
            "admin", new Firmante("Admin Pruebas", "admin"),
            "editor", new Firmante("Editor Pruebas", "editor"),
            "readonly", new Firmante("Lector Pruebas", "readonly"));

    static Path carpeta() {
        return Path.of(System.getProperty("laboratorio.dir", "../herramientas/laboratorio"), "facturacion");
    }

    static ObjectNode maestrosIniciales() throws IOException {
        try (InputStream in = ParidadFacturacionTest.class.getResourceAsStream("/facturacion/maestros-iniciales.json")) {
            return (ObjectNode) JsonJs.mapper().readTree(in);
        }
    }

    static ObjectNode aplicarParches(ObjectNode maestros, JsonNode parches) {
        ObjectNode r = maestros.deepCopy();
        for (JsonNode p : parches == null ? Nodos.lista() : parches) {
            if (p.has("anadir")) {
                ArrayNode lista = Nodos.arrEn(r, p.get("anadir").asText());
                p.get("valores").forEach(v -> lista.add(v.deepCopy()));
            }
            if (p.has("poner")) {
                r.set(p.get("poner").asText(), p.get("valor").deepCopy());
            }
        }
        return r;
    }

    static ObjectNode ensayo(JsonNode e) {
        ObjectNode n = Nodos.objeto();
        n.put("id", "").put("banco", "").put("organizacion", "BW").put("cerrado", true).put("tipoEnsayo", "")
                .put("responsable", "Responsable 01").put("proyecto", "").put("approvalId", "").put("requester", "")
                .put("fechaInicio", "").put("fechaFin", "").put("finDeSemana", false).put("inicioAsumido", false)
                .put("inicioPlan", "").put("finPlan", "").put("tipoEspecial", false);
        Iterator<Map.Entry<String, JsonNode>> it = e.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> c = it.next();
            n.set(c.getKey(), c.getValue().deepCopy());
        }
        ObjectNode datos = Nodos.objeto();
        datos.set("billing", e.has("billing") ? e.get("billing").deepCopy() : Nodos.objeto());
        n.set("billing", EnsayosFacturables.datosFacturacion(datos));
        return n;
    }

    private static JsonNode n(JsonNode v) {
        if (Nodos.nulo(v)) {
            return Nodos.num((Double) null);
        }
        return Nodos.num(ValorJs.redondear(v.asDouble() * 1e6) / 1e6);
    }

    static ObjectNode normalizarLinea(JsonNode l) {
        ObjectNode o = Nodos.objeto();
        JsonNode c = Nodos.nulo(l.get("calculado")) ? Nodos.objeto() : l.get("calculado");
        JsonNode a = l.get("aplicado");
        o.put("id", Nodos.texto(l, "id")).put("estado", Nodos.texto(l, "estado")).put("origen", Nodos.texto(l, "origen"));
        List<String> codigos = new ArrayList<>();
        Nodos.arr(l, "incidencias").forEach(i -> codigos.add(Nodos.texto(i, "codigo")));
        codigos.sort(String::compareTo);
        ArrayNode inc = o.putArray("incidencias");
        codigos.forEach(inc::add);
        o.put("tramoInicio", Nodos.texto(l, "tramoInicio")).put("tramoFin", Nodos.texto(l, "tramoFin"));
        for (String k : List.of("minutosBrutos", "minutosFinDeSemana", "minutosParadas")) {
            o.set(k, c.has(k) ? c.get(k) : Nodos.num((Double) null));
        }
        o.set("horasNetas", n(c.get("horasNetas")));
        o.set("horasSetup", n(c.get("horasSetup")));
        for (String k : List.of("categoria", "orden", "hv")) {
            o.set(k, c.has(k) ? c.get(k) : Nodos.textoONulo(null));
        }
        o.set("tarifa", n(c.get("tarifa")));
        o.set("coste", n(c.get("coste")));
        if (Nodos.nulo(a)) {
            o.putNull("aplicado");
        } else {
            ObjectNode ap = o.putObject("aplicado");
            ap.set("horasNetas", n(a.get("horasNetas")));
            ap.set("horasSetup", n(a.get("horasSetup")));
            ap.set("tarifa", n(a.get("tarifa")));
            ap.set("coste", n(a.get("coste")));
            ap.set("orden", a.get("orden"));
            ap.set("categoria", a.get("categoria"));
        }
        List<String> ajustes = new ArrayList<>();
        ObjectNode aj = Nodos.obj(l, "ajustes");
        if (aj != null) {
            aj.fields().forEachRemaining(x -> ajustes.add(x.getKey() + ":" + (Nodos.cierto(x.getValue(), "validado") ? "validado" : "pendiente")
                    + (Nodos.nulo(x.getValue().get("conflicto")) ? "" : ":conflicto")));
        }
        ajustes.sort(String::compareTo);
        ArrayNode ajs = o.putArray("ajustes");
        ajustes.forEach(ajs::add);
        ObjectNode p = Nodos.obj(l, "propuestaTarifa");
        o.put("propuesta", p == null ? "" : Nodos.texto(p, "estado") + ":" + Nodos.texto(p, "alcance"));
        return o;
    }

    static ObjectNode observar(ObjectNode doc, JsonNode maestros) {
        ObjectNode r = ResumenFacturacion.resumen(doc, maestros);
        ObjectNode o = Nodos.objeto();
        o.put("periodo", Nodos.texto(doc, "id")).put("estado", Nodos.texto(doc, "estado")).put("versionAprobada", Nodos.entero(doc, "versionAprobada"));
        ArrayNode exp = o.putArray("exportaciones");
        Nodos.arr(doc, "exportaciones").forEach(x -> exp.add(Nodos.texto(x, "estado")));
        ArrayNode lineas = o.putArray("lineas");
        Nodos.arr(doc, "lineas").forEach(l -> lineas.add(normalizarLinea(l)));
        JsonNode ind = r.get("indicadores");
        ObjectNode i = o.putObject("indicadores");
        i.set("coste", n(ind.get("coste")));
        i.set("horasNetas", n(ind.get("horasNetas")));
        i.set("horasSetup", n(ind.get("horasSetup")));
        for (String k : List.of("lineas", "bloqueadas", "excluidas", "aplazadas", "pendientes")) {
            i.put(k, Nodos.entero(ind, k));
        }
        ArrayNode ctrl = o.putArray("controlling");
        for (JsonNode f : Nodos.arr(r, "controlling")) {
            ObjectNode x = ctrl.addObject();
            x.put("equipo", Nodos.texto(f, "equipo")).put("orden", Nodos.texto(f, "orden"));
            x.set("amount", f.get("amount"));
            x.set("totalQty", n(f.get("totalQty")));
            x.put("um", Nodos.texto(f, "um")).put("lineas", Nodos.arr(f, "lineas").size());
        }
        return o;
    }

    static ArrayNode ejecutar(JsonNode caso, ObjectNode maestrosBase) {
        ObjectNode maestros = aplicarParches(maestrosBase, caso.get("maestros"));
        List<ObjectNode> ensayos = new ArrayList<>();
        caso.get("ensayos").forEach(e -> ensayos.add(ensayo(e)));
        Map<String, ObjectNode> docs = new LinkedHashMap<>();
        ArrayNode salida = Nodos.lista();
        for (JsonNode paso : caso.get("pasos")) {
            String ahora = paso.has("ahora") ? paso.get("ahora").asText() : caso.path("ahora").asText("");
            MotorFacturacion.Contexto ctx = new MotorFacturacion.Contexto(ahora);
            Firmante usuario = USUARIOS.get(paso.path("usuario").asText("editor"));
            String periodo = paso.path("periodo").asText("");
            Map<String, ObjectNode> otros = new LinkedHashMap<>(docs);
            otros.remove(periodo);
            String tipo = paso.get("tipo").asText();
            ObjectNode obs = Nodos.objeto();
            obs.put("paso", tipo);
            String error = "";
            ObjectNode extra = null;
            try {
                switch (tipo) {
                    case "crear" -> {
                        Periodos.Periodo p = Periodos.deId(periodo);
                        ObjectNode doc = FlujoFacturacion.nuevoPeriodo(p.anio(), p.mes(), usuario, ctx);
                        FlujoFacturacion.recalcular(doc, ensayos, maestros, otros, usuario, ctx);
                        docs.put(periodo, doc);
                    }
                    case "recalcular" -> FlujoFacturacion.recalcular(docs.get(periodo), ensayos, maestros, otros, usuario, ctx);
                    case "accion" -> {
                        FlujoFacturacion.ResultadoAccion r = FlujoFacturacion.ejecutarAccionLinea(docs.get(periodo), paso.get("linea").asText(),
                                paso.get("accion").asText(), paso.get("datos"), usuario, ensayos, maestros, otros, ctx);
                        if (r.maestrosCambiados()) {
                            maestros.set("tarifas", r.maestros().get("tarifas"));
                        }
                        extra = Nodos.objeto();
                        ArrayNode aud = extra.putArray("auditoria");
                        r.auditoria().forEach(a -> aud.add(Nodos.texto(a, "accion")));
                        extra.put("maestrosCambiados", r.maestrosCambiados());
                    }
                    case "enviar" -> FlujoFacturacion.enviarRevision(docs.get(periodo), usuario, ctx);
                    case "aprobar" -> FlujoFacturacion.aprobar(docs.get(periodo), usuario, ctx, !paso.has("confirmacion")
                            || paso.get("confirmacion").asBoolean(), maestros);
                    case "reabrir" -> FlujoFacturacion.reabrir(docs.get(periodo), usuario, ctx, paso.path("justificacion").asText(""));
                    case "exportar" -> {
                        ObjectNode doc = docs.get(periodo);
                        int v = Nodos.entero(doc, "versionAprobada");
                        Nodos.arrEn(doc, "exportaciones").addObject().put("id", "EXP-" + periodo + "-" + v).put("version", v).put("tipo", "excel")
                                .put("por", usuario.nombre()).put("en", ahora).put("estado", "VALIDA").put("nombre", "x.xlsx");
                        doc.put("estado", "EXPORTADO");
                    }
                    case "cambiarEnsayo" -> {
                        ObjectNode e = ensayos.stream().filter(x -> Nodos.texto(x, "id").equals(paso.get("ensayo").asText())).findFirst().orElseThrow();
                        if (paso.has("campos")) {
                            paso.get("campos").fields().forEachRemaining(c -> e.set(c.getKey(), c.getValue().deepCopy()));
                        }
                        if (paso.has("billing")) {
                            ObjectNode b = e.get("billing").deepCopy();
                            paso.get("billing").fields().forEachRemaining(c -> b.set(c.getKey(), c.getValue().deepCopy()));
                            ObjectNode datos = Nodos.objeto();
                            datos.set("billing", b);
                            e.set("billing", EnsayosFacturables.datosFacturacion(datos));
                        }
                    }
                    case "alertas" -> {
                        extra = Nodos.objeto();
                        ArrayNode al = extra.putArray("alertas");
                        FlujoFacturacion.alertasTrasAprobar(docs.get(periodo), ensayos, maestros, ctx).forEach(a -> al.add(Nodos.texto(a, "lineaId")));
                    }
                    case "ficha" -> {
                        ObjectNode e = ensayos.stream().filter(x -> Nodos.texto(x, "id").equals(paso.get("ensayo").asText())).findFirst().orElseThrow();
                        ObjectNode f = FichaFacturacion.ficha(e, maestros, ctx);
                        extra = Nodos.objeto();
                        ObjectNode fi = extra.putObject("ficha");
                        fi.put("estado", Nodos.texto(f.get("estado"), "estado"));
                        fi.set("faltan", f.get("estado").get("faltan"));
                        fi.set("errores", f.get("errores"));
                        fi.set("setupRecomendado", f.get("setupRecomendado"));
                        ArrayNode tramos = fi.putArray("tramos");
                        Nodos.arr(f, "tramos").forEach(t -> tramos.add(Nodos.texto(t, "periodo") + ":" + Nodos.texto(t.get("horasNetas"))));
                    }
                    default -> throw new IllegalStateException("Paso desconocido " + tipo);
                }
            } catch (ReglaNoCumplida e) {
                error = e.getMessage();
            }
            obs.put("error", error);
            if (extra != null) {
                obs.setAll(extra);
            }
            if (!periodo.isEmpty() && docs.containsKey(periodo) && !tipo.equals("ficha")) {
                obs.set("resultado", observar(docs.get(periodo), maestros));
            }
            salida.add(obs);
        }
        return salida;
    }

    /** Igualdad profunda con tolerancia en los números. */
    static void comparar(String ruta, JsonNode esperado, JsonNode obtenido, List<String> diferencias) {
        if (diferencias.size() > 15) {
            return;
        }
        boolean nuloE = Nodos.nulo(esperado);
        boolean nuloO = Nodos.nulo(obtenido);
        if (nuloE || nuloO) {
            if (nuloE != nuloO) {
                diferencias.add(ruta + ": esperado " + esperado + ", obtenido " + obtenido);
            }
            return;
        }
        if (esperado.isNumber() && obtenido.isNumber()) {
            double a = esperado.asDouble();
            double b = obtenido.asDouble();
            if (Math.abs(a - b) > 1e-6 * Math.max(1, Math.abs(a))) {
                diferencias.add(ruta + ": esperado " + a + ", obtenido " + b);
            }
            return;
        }
        if (esperado.isObject() && obtenido.isObject()) {
            esperado.fieldNames().forEachRemaining(k -> comparar(ruta + "." + k, esperado.get(k), obtenido.get(k), diferencias));
            obtenido.fieldNames().forEachRemaining(k -> {
                if (!esperado.has(k)) {
                    diferencias.add(ruta + "." + k + ": sobra");
                }
            });
            return;
        }
        if (esperado.isArray() && obtenido.isArray()) {
            if (esperado.size() != obtenido.size()) {
                diferencias.add(ruta + ": " + esperado.size() + " elementos esperados, " + obtenido.size() + " obtenidos " + obtenido);
                return;
            }
            for (int i = 0; i < esperado.size(); i++) {
                comparar(ruta + "[" + i + "]", esperado.get(i), obtenido.get(i), diferencias);
            }
            return;
        }
        if (!esperado.equals(obtenido) && !Nodos.texto(esperado).equals(Nodos.texto(obtenido))) {
            diferencias.add(ruta + ": esperado " + esperado + ", obtenido " + obtenido);
        }
    }

    @TestFactory
    List<DynamicTest> casosDelLaboratorio() throws IOException {
        JsonNode casos = JsonJs.mapper().readTree(Files.readString(carpeta().resolve("casos-facturacion.json")));
        JsonNode referencias = JsonJs.mapper().readTree(Files.readString(carpeta().resolve("referencias-facturacion.json")));
        ObjectNode maestros = aplicarParches(maestrosIniciales(), casos.get("maestrosComunes"));
        List<DynamicTest> pruebas = new ArrayList<>();
        for (int i = 0; i < casos.get("casos").size(); i++) {
            JsonNode caso = casos.get("casos").get(i);
            JsonNode ref = referencias.get("casos").get(i);
            pruebas.add(DynamicTest.dynamicTest(caso.get("nombre").asText(), () -> {
                ArrayNode obtenido = ejecutar(caso, maestros);
                List<String> diferencias = new ArrayList<>();
                comparar(caso.get("nombre").asText(), ref.get("pasos"), obtenido, diferencias);
                if (!diferencias.isEmpty()) {
                    fail("Diferencias con la referencia JavaScript:\n" + String.join("\n", diferencias));
                }
            }));
        }
        return pruebas;
    }
}
