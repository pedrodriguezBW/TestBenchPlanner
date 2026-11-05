package com.borgwarner.tbp.paridad;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Equivalente Java de herramientas/laboratorio/normalizar.cjs. */
final class Normalizacion {

    private Normalizacion() {
    }

    /** Diferencias campo a campo entre los DataJson de antes y de después. */
    static ObjectNode diferencias(Map<String, String> antes, Map<String, String> despues) {
        ObjectNode cambios = JsonJs.objetoVacio();
        Set<String> ids = new LinkedHashSet<>(antes.keySet());
        ids.addAll(despues.keySet());
        for (String id : ids) {
            String a = antes.get(id);
            String d = despues.get(id);
            if (Objects.equals(a, d)) {
                continue;
            }
            ObjectNode cambio = cambios.putObject(id);
            if (d == null) {
                cambio.putNull("texto");
            } else {
                cambio.put("texto", d);
            }
            if (a == null || d == null) {
                cambio.put("alta", a == null);
                cambio.put("baja", d == null);
                continue;
            }
            JsonNode oa = JsonJs.leer(a);
            JsonNode od = JsonJs.leer(d);
            ObjectNode campos = cambio.putObject("campos");
            Set<String> claves = new LinkedHashSet<>();
            oa.fieldNames().forEachRemaining(claves::add);
            od.fieldNames().forEachRemaining(claves::add);
            for (String k : claves) {
                JsonNode va = oa.get(k);
                JsonNode vd = od.get(k);
                if (!Objects.equals(va == null ? null : JsonJs.escribir(va), vd == null ? null : JsonJs.escribir(vd))) {
                    ArrayNode par = campos.putArray(k);
                    par.add(va == null ? TextNode.valueOf("(sin campo)") : va);
                    par.add(vd == null ? TextNode.valueOf("(sin campo)") : vd);
                }
            }
        }
        return cambios;
    }

    /** Renombra a nuevo-1, nuevo-2… los ids que no estaban en el escenario, por orden de aparición. */
    static ObjectNode renombrarIdsNuevos(ObjectNode foto, Set<String> iniciales, Set<String> actuales) {
        List<String> nuevos = actuales.stream().filter(id -> !iniciales.contains(id)).toList();
        if (nuevos.isEmpty()) {
            return foto;
        }
        Map<String, String> alias = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([^\"\\\\]+)\"").matcher(JsonJs.escribir(foto));
        while (m.find()) {
            String s = m.group(1);
            if (nuevos.contains(s) && !alias.containsKey(s)) {
                alias.put(s, "nuevo-" + (alias.size() + 1));
            }
        }
        return (ObjectNode) sustituir(foto, alias);
    }

    private static JsonNode sustituir(JsonNode valor, Map<String, String> alias) {
        if (valor.isTextual()) {
            String s = valor.textValue();
            if (alias.containsKey(s)) {
                return TextNode.valueOf(alias.get(s));
            }
            if (s.startsWith("{")) {
                Matcher m = Pattern.compile("\"([^\"\\\\]+)\"").matcher(s);
                StringBuilder salida = new StringBuilder();
                while (m.find()) {
                    String reemplazo = alias.containsKey(m.group(1)) ? "\"" + alias.get(m.group(1)) + "\"" : m.group(0);
                    m.appendReplacement(salida, Matcher.quoteReplacement(reemplazo));
                }
                m.appendTail(salida);
                return TextNode.valueOf(salida.toString());
            }
            return valor;
        }
        if (valor.isArray()) {
            ArrayNode salida = JsonJs.mapper().createArrayNode();
            valor.forEach(v -> salida.add(sustituir(v, alias)));
            return salida;
        }
        if (valor.isObject()) {
            ObjectNode salida = JsonJs.objetoVacio();
            Iterator<Map.Entry<String, JsonNode>> it = valor.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                salida.set(alias.getOrDefault(e.getKey(), e.getKey()), sustituir(e.getValue(), alias));
            }
            return salida;
        }
        return valor;
    }

    /** Diferencias entre dos árboles JSON (los números se comparan por valor). */
    static void comparar(JsonNode referencia, JsonNode nueva, String ruta, List<String[]> salida) {
        if (referencia == null || referencia.isNull() || referencia.isMissingNode()) {
            if (!(nueva == null || nueva.isNull() || nueva.isMissingNode())) {
                salida.add(new String[]{ruta, "null", JsonJs.escribir(nueva)});
            }
            return;
        }
        if (nueva == null || nueva.isNull() || nueva.isMissingNode()) {
            salida.add(new String[]{ruta, JsonJs.escribir(referencia), "null"});
            return;
        }
        if (referencia.isNumber() && nueva.isNumber()) {
            if (Double.compare(referencia.doubleValue(), nueva.doubleValue()) != 0) {
                salida.add(new String[]{ruta, referencia.asText(), nueva.asText()});
            }
            return;
        }
        if (referencia.isObject() && nueva.isObject()) {
            Set<String> claves = new LinkedHashSet<>();
            referencia.fieldNames().forEachRemaining(claves::add);
            nueva.fieldNames().forEachRemaining(claves::add);
            for (String k : claves) {
                comparar(referencia.get(k), nueva.get(k), ruta + "/" + k, salida);
            }
            return;
        }
        if (referencia.isArray() && nueva.isArray()) {
            int n = Math.max(referencia.size(), nueva.size());
            for (int i = 0; i < n; i++) {
                comparar(referencia.get(i), nueva.get(i), ruta + "/" + i, salida);
            }
            return;
        }
        if (!referencia.equals(nueva)) {
            salida.add(new String[]{ruta, JsonJs.escribir(referencia), JsonJs.escribir(nueva)});
        }
    }

    static List<String[]> comparar(JsonNode referencia, JsonNode nueva) {
        List<String[]> salida = new ArrayList<>();
        comparar(referencia, nueva, "", salida);
        return salida;
    }
}
