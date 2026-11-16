package com.borgwarner.tbp.dominio.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un Equipment Type de facturación para un año (una fila de la lista
 * TestBenchEquipmentTypes): grupo (columna de la hoja mensual, como el «Tipo»
 * del Excel), tarifas sin y con HV, unidad (horas o piezas) y regla de cálculo.
 *
 * @param sinHv tarifa «Without HV»; null = no tiene
 * @param conHv tarifa «With HV»; null = no tiene
 * @param unidad HORAS o PIEZAS
 */
public record TipoEquipo(String titulo, String grupo, int anio, Double sinHv, Double conHv, String unidad, Regla regla) {

    public static final String HORAS = "HORAS";
    public static final String PIEZAS = "PIEZAS";

    public TipoEquipo {
        titulo = titulo == null ? "" : titulo.trim();
        grupo = grupo == null ? "" : grupo.trim();
        unidad = PIEZAS.equalsIgnoreCase(unidad == null ? "" : unidad.trim()) ? PIEZAS : HORAS;
        regla = regla == null ? Regla.LINEAR : regla;
    }

    /** Clave de una fila: título (sin distinguir mayúsculas) y año. */
    public String clave() {
        return clave(titulo, anio);
    }

    public static String clave(String titulo, int anio) {
        return (titulo == null ? "" : titulo.trim().toLowerCase(java.util.Locale.ROOT)) + "|" + anio;
    }

    /** Tarifa para HV «yes» / «no»; null si no tiene. */
    public Double tarifa(String hv) {
        return "yes".equals(hv) ? conHv : ("no".equals(hv) ? sinHv : null);
    }

    static Double numero(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        String t = v.asText("").trim().replace(',', '.');
        if (t.isEmpty() || t.equals("-")) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Desde el JSON de la API ({titulo, grupo, anio, sinHv, conHv, unidad, regla}). */
    public static TipoEquipo desdeJson(JsonNode n) {
        Double anio = numero(n.get("anio"));
        return new TipoEquipo(n.path("titulo").asText(""), n.path("grupo").asText(""), anio == null ? 0 : (int) Math.round(anio),
                numero(n.get("sinHv")), numero(n.get("conHv")), n.path("unidad").asText(HORAS), Regla.de(n.path("regla").asText("")));
    }

    public ObjectNode json() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("titulo", titulo);
        n.put("grupo", grupo);
        n.put("anio", anio);
        if (sinHv == null) {
            n.putNull("sinHv");
        } else {
            n.put("sinHv", sinHv);
        }
        if (conHv == null) {
            n.putNull("conHv");
        } else {
            n.put("conHv", conHv);
        }
        n.put("unidad", unidad);
        n.put("regla", regla.name());
        return n;
    }
}
