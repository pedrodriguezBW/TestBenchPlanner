package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilidades para leer y escribir los documentos de facturación (JSON).
 *
 * El módulo trabaja con documentos JSON (periodo, líneas, maestros) con la misma
 * forma que la réplica JavaScript del laboratorio
 * (herramientas/laboratorio/facturacion/motor.cjs), para que las referencias
 * se puedan comparar campo a campo.
 */
public final class Nodos {

    private Nodos() {
    }

    public static ObjectNode objeto() {
        return JsonJs.mapper().createObjectNode();
    }

    public static ArrayNode lista() {
        return JsonJs.mapper().createArrayNode();
    }

    /** Valor ausente o null de JavaScript. */
    public static boolean nulo(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode();
    }

    /** String(valor ?? ''): '' si falta o es null. */
    public static String texto(JsonNode v) {
        if (nulo(v)) {
            return "";
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        if (v.isNumber()) {
            return ValorJs.numeroComoTexto(v.doubleValue());
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "true" : "false";
        }
        return v.toString();
    }

    public static String texto(JsonNode n, String campo) {
        return n == null ? "" : texto(n.get(campo));
    }

    /** Campo === true */
    public static boolean cierto(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        return v != null && v.isBoolean() && v.booleanValue();
    }

    /** Número del campo; null si falta, es null o es el texto vacío (casillas sin rellenar). */
    public static Double numeroOVacio(JsonNode v) {
        if (nulo(v) || (v.isTextual() && v.textValue().isEmpty())) {
            return null;
        }
        return ValorJs.numero(v);
    }

    public static Double numeroOVacio(JsonNode n, String campo) {
        return n == null ? null : numeroOVacio(n.get(campo));
    }

    /** Número de un campo que, si existe, siempre es numérico (null si falta). */
    public static Double numero(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        if (nulo(v)) {
            return null;
        }
        return v.isNumber() ? v.doubleValue() : ValorJs.numero(v);
    }

    public static int entero(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        return nulo(v) ? 0 : v.asInt();
    }

    /** Nodo numérico como lo escribiría JSON.stringify (null para null). */
    public static JsonNode num(Double d) {
        return d == null ? NullNode.getInstance() : ValorJs.nodoNumero(d);
    }

    public static JsonNode num(double d) {
        return ValorJs.nodoNumero(d);
    }

    public static ObjectNode obj(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        return v instanceof ObjectNode o ? o : null;
    }

    /** Lista del campo, o una lista vacía nueva si no lo es. */
    public static ArrayNode arr(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        return v instanceof ArrayNode a ? a : lista();
    }

    /** Lista del campo, creándola dentro del objeto si no existe. */
    public static ArrayNode arrEn(ObjectNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v instanceof ArrayNode a) {
            return a;
        }
        return n.putArray(campo);
    }

    /** Objeto del campo, creándolo dentro del objeto si no existe. */
    public static ObjectNode objEn(ObjectNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v instanceof ObjectNode o) {
            return o;
        }
        return n.putObject(campo);
    }

    public static List<ObjectNode> objetos(JsonNode lista) {
        List<ObjectNode> salida = new ArrayList<>();
        if (lista != null && lista.isArray()) {
            lista.forEach(x -> {
                if (x instanceof ObjectNode o) {
                    salida.add(o);
                }
            });
        }
        return salida;
    }

    @SuppressWarnings("unchecked")
    public static <T extends JsonNode> T copia(T n) {
        return n == null ? null : (T) n.deepCopy();
    }

    /** Texto o null de JavaScript: null si el valor es null. */
    public static JsonNode textoONulo(String s) {
        return s == null ? NullNode.getInstance() : JsonJs.mapper().getNodeFactory().textNode(s);
    }
}
