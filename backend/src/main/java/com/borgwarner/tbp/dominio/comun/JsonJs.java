package com.borgwarner.tbp.dominio.comun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Lectura y escritura de JSON con el mismo formato que JSON.stringify.
 *
 * Es imprescindible para la convivencia con la 4.9.4: cada PC compara el texto
 * DataJson que leyó con el que construiría él mismo, y si difieren lo vuelve a
 * subir. Si Java escribiese el mismo contenido con otro formato (espacios,
 * escapes, 3.0 en vez de 3…), las versiones antiguas reescribirían filas sin
 * cambios. Por eso no se usa el serializador de Jackson para escribir DataJson.
 */
public final class JsonJs {

    private static final ObjectMapper LECTOR = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, false);

    private JsonJs() {
    }

    /** Lee un texto JSON. Devuelve null si no es JSON válido (como safeJson de la 4.9.4). */
    public static JsonNode leer(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return LECTOR.readTree(texto);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Lee un objeto JSON; null si el texto no es un objeto. */
    public static ObjectNode leerObjeto(String texto) {
        JsonNode nodo = leer(texto);
        return nodo instanceof ObjectNode objeto ? objeto : null;
    }

    public static ObjectNode objetoVacio() {
        return LECTOR.createObjectNode();
    }

    public static ObjectMapper mapper() {
        return LECTOR;
    }

    /** Equivalente exacto de JSON.stringify(valor) (sin sangrado). */
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
                    salida.append(',');
                }
                primero = false;
                escribirTexto(campo.getKey(), salida);
                salida.append(':');
                escribir(campo.getValue(), salida);
            }
            salida.append('}');
        } else if (valor.isArray()) {
            salida.append('[');
            for (int i = 0; i < valor.size(); i++) {
                if (i > 0) {
                    salida.append(',');
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
            salida.append(ValorJs.numeroComoTexto(valor.doubleValue()));
        } else {
            salida.append("null");
        }
    }

    /** Escapes de JSON.stringify: comillas, barra invertida y caracteres de control. */
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
                    boolean control = c < 0x20;
                    boolean sustitutoSuelto = Character.isSurrogate(c) && !formaPar(texto, i);
                    if (control || sustitutoSuelto) {
                        salida.append(String.format("\\u%04x", (int) c));
                    } else {
                        salida.append(c);
                    }
                }
            }
        }
        salida.append('"');
    }

    /** Un sustituto UTF-16 solo es válido si forma pareja con el de al lado. */
    private static boolean formaPar(String texto, int i) {
        char c = texto.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < texto.length() && Character.isLowSurrogate(texto.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(texto.charAt(i - 1));
    }
}
