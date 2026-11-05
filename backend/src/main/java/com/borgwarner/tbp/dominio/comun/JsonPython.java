package com.borgwarner.tbp.dominio.comun;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Escritura de JSON con el formato de json.dumps(…, ensure_ascii=False) de
 * Python, que es como el backend 4.9.4 guardaba la columna KPIJson del
 * histórico de KPI. Se usa solo para que el texto guardado sea idéntico.
 */
public final class JsonPython {

    private JsonPython() {
    }

    public static String escribir(JsonNode valor) {
        StringBuilder salida = new StringBuilder();
        escribir(valor, salida);
        return salida.toString();
    }

    private static void escribir(JsonNode valor, StringBuilder salida) {
        if (valor == null || valor.isNull() || valor.isMissingNode()) {
            salida.append("null");
        } else if (valor.isObject()) {
            salida.append('{');
            boolean primero = true;
            Iterator<Map.Entry<String, JsonNode>> campos = valor.fields();
            while (campos.hasNext()) {
                Map.Entry<String, JsonNode> campo = campos.next();
                if (!primero) {
                    salida.append(", ");
                }
                primero = false;
                escribirTexto(campo.getKey(), salida);
                salida.append(": ");
                escribir(campo.getValue(), salida);
            }
            salida.append('}');
        } else if (valor.isArray()) {
            salida.append('[');
            for (int i = 0; i < valor.size(); i++) {
                if (i > 0) {
                    salida.append(", ");
                }
                escribir(valor.get(i), salida);
            }
            salida.append(']');
        } else if (valor.isTextual()) {
            escribirTexto(valor.textValue(), salida);
        } else if (valor.isBoolean()) {
            salida.append(valor.booleanValue() ? "true" : "false");
        } else if (valor.isIntegralNumber()) {
            salida.append(valor.bigIntegerValue().toString());
        } else if (valor.isNumber()) {
            salida.append(reprPython(valor.doubleValue()));
        } else {
            salida.append("null");
        }
    }

    /** repr() de un float de Python: 1.5, 1e+16, NaN, inf… */
    private static String reprPython(double numero) {
        if (Double.isNaN(numero)) {
            return "NaN";
        }
        if (Double.isInfinite(numero)) {
            return numero > 0 ? "Infinity" : "-Infinity";
        }
        String js = ValorJs.numeroComoTexto(numero);
        if (js.contains("e")) {
            return js; // JavaScript y Python coinciden en la forma exponencial
        }
        return js.contains(".") ? js : js + ".0"; // Python escribe 7.0 para un float entero
    }

    private static void escribirTexto(String texto, StringBuilder salida) {
        salida.append('"');
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> salida.append("\\\"");
                case '\\' -> salida.append("\\\\");
                case '\b' -> salida.append("\\b");
                case '\f' -> salida.append("\\f");
                case '\n' -> salida.append("\\n");
                case '\r' -> salida.append("\\r");
                case '\t' -> salida.append("\\t");
                default -> {
                    if (c < 0x20) {
                        salida.append(String.format("\\u%04x", (int) c));
                    } else {
                        salida.append(c);
                    }
                }
            }
        }
        salida.append('"');
    }
}
