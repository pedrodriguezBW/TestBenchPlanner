package com.borgwarner.tbp.dominio.comun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;

import java.math.BigDecimal;

/**
 * Reglas de conversión de valores tal y como las aplica JavaScript.
 *
 * Los datos de cada ensayo (DataJson) los ha escrito la 4.9.4 y conviven con
 * ella durante la migración, así que hay campos con tipos mezclados: duraciones
 * guardadas como número o como texto, cadenas vacías en lugar de nulos, etc.
 * Para calcular exactamente lo mismo que la 4.9.4 hay que leerlos con su misma
 * semántica: qué es «verdadero», qué devuelve Number(), qué hace r.x || ''.
 *
 * Convención: un JsonNode null en Java equivale a «undefined» (campo ausente).
 */
public final class ValorJs {

    private ValorJs() {
    }

    /** Equivale a la conversión a booleano de JavaScript (if (valor) …). */
    public static boolean verdadero(JsonNode valor) {
        if (valor == null || valor.isNull() || valor.isMissingNode()) {
            return false;
        }
        if (valor.isBoolean()) {
            return valor.booleanValue();
        }
        if (valor.isNumber()) {
            double numero = valor.doubleValue();
            return numero != 0 && !Double.isNaN(numero);
        }
        if (valor.isTextual()) {
            return !valor.textValue().isEmpty();
        }
        return true; // objetos y listas siempre son verdaderos
    }

    /** Equivale a Number(valor). Un campo ausente da NaN, igual que Number(undefined). */
    public static double numero(JsonNode valor) {
        if (valor == null || valor.isMissingNode()) {
            return Double.NaN;
        }
        if (valor.isNull()) {
            return 0;
        }
        if (valor.isBoolean()) {
            return valor.booleanValue() ? 1 : 0;
        }
        if (valor.isNumber()) {
            return valor.doubleValue();
        }
        if (valor.isTextual()) {
            return numeroDeTexto(valor.textValue());
        }
        return Double.NaN;
    }

    /** Equivale a Number(valor || 0): todo lo «falso» cuenta como 0. */
    public static double numeroOCero(JsonNode valor) {
        return verdadero(valor) ? numero(valor) : 0;
    }

    /** Equivale a Number(texto) para lo que escribe la interfaz en un input. */
    public static double numeroDeTexto(String texto) {
        String limpio = texto.trim();
        if (limpio.isEmpty()) {
            return 0;
        }
        if (limpio.equals("Infinity") || limpio.equals("+Infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (limpio.equals("-Infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        // Java acepta sufijos como «1d» o «1f» que JavaScript rechaza.
        if (!limpio.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
            return Double.NaN;
        }
        return Double.parseDouble(limpio);
    }

    /** Equivale a (valor || '') convertido a texto. */
    public static String texto(JsonNode valor) {
        if (!verdadero(valor)) {
            return "";
        }
        if (valor.isTextual()) {
            return valor.textValue();
        }
        if (valor.isNumber()) {
            return numeroComoTexto(valor.doubleValue());
        }
        return valor.toString();
    }

    /**
     * Crea el nodo numérico que produciría JSON.stringify: los enteros sin
     * decimales (3, no 3.0) y el resto con su representación más corta.
     */
    public static JsonNode nodoNumero(double numero) {
        if (!Double.isNaN(numero) && !Double.isInfinite(numero)
                && numero == Math.rint(numero) && Math.abs(numero) < 1e15) {
            long entero = (long) numero;
            if (entero >= Integer.MIN_VALUE && entero <= Integer.MAX_VALUE) {
                return IntNode.valueOf((int) entero);
            }
            return LongNode.valueOf(entero);
        }
        return DoubleNode.valueOf(numero);
    }

    /** Texto de un número como lo escribe JavaScript (String(n) / JSON.stringify). */
    public static String numeroComoTexto(double numero) {
        if (Double.isNaN(numero) || Double.isInfinite(numero)) {
            return "null"; // JSON.stringify escribe null para NaN e Infinity
        }
        if (numero == 0) {
            return "0"; // también para -0
        }
        if (numero == Math.rint(numero) && Math.abs(numero) < 1e21) {
            return new BigDecimal(numero).toPlainString();
        }
        double absoluto = Math.abs(numero);
        if (absoluto >= 1e-6 && absoluto < 1e21) {
            return new BigDecimal(Double.toString(numero)).stripTrailingZeros().toPlainString();
        }
        // Notación exponencial al estilo JavaScript: 1e-7, 1.5e+21
        String java = Double.toString(numero);           // p. ej. 1.5E-7
        String[] partes = java.split("E");
        String mantisa = new BigDecimal(partes[0]).stripTrailingZeros().toPlainString();
        int exponente = Integer.parseInt(partes[1]);
        return mantisa + "e" + (exponente >= 0 ? "+" : "") + exponente;
    }

    /** Math.round de JavaScript (redondea .5 hacia +infinito). */
    public static double redondear(double numero) {
        if (Double.isNaN(numero) || Double.isInfinite(numero)) {
            return numero;
        }
        return Math.floor(numero + 0.5);
    }
}
