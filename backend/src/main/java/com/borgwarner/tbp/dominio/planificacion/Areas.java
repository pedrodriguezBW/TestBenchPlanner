package com.borgwarner.tbp.dominio.planificacion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Áreas: nivel por encima de las categorías de bancos.
 *
 * Son fijas (la aplicación no crea áreas nuevas) y van en este orden. Cada
 * categoría pertenece a un área, guardada en la columna «Area» de
 * TestBenchBenches (el mismo valor en todos los bancos de la categoría). Una
 * columna vacía o un valor desconocido cuenta como Bancos Térmicos, así que
 * los bancos de antes no necesitan migración.
 *
 * Solo los Bancos Térmicos se dividen por organización (BW / CTAG). Los
 * bancos de las áreas HVCH guardan una organización fija (CTAG o STC) para
 * que la interfaz clásica, que trata un banco sin organización como BW, no
 * los cuente como BW.
 */
public final class Areas {

    public static final String TERMICOS = "THERMAL";
    public static final String HVCH_CTAG = "HVCH-CTAG";
    public static final String HVCH_STC = "HVCH-STC";

    /** Códigos en el orden en que se muestran. */
    public static final List<String> CODIGOS = List.of(TERMICOS, HVCH_CTAG, HVCH_STC);

    /** Nombres de serie (un Admin puede renombrarlas). */
    public static final Map<String, String> NOMBRES_DE_SERIE;

    static {
        Map<String, String> nombres = new LinkedHashMap<>();
        nombres.put(TERMICOS, "Thermal Benches");
        nombres.put(HVCH_CTAG, "HVCH - CTAG");
        nombres.put(HVCH_STC, "HVCH - STC");
        NOMBRES_DE_SERIE = java.util.Collections.unmodifiableMap(nombres);
    }

    private Areas() {
    }

    /** Código de área a partir del valor guardado: vacío o desconocido = Bancos Térmicos. */
    public static String normalizar(String valor) {
        if (valor == null) {
            return TERMICOS;
        }
        String v = valor.trim().toUpperCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        while (v.contains("--")) {
            v = v.replace("--", "-");
        }
        return CODIGOS.contains(v) ? v : TERMICOS;
    }

    public static boolean esConOrganizacion(String area) {
        return TERMICOS.equals(normalizar(area));
    }

    /** Organización fija de los bancos del área, o null si se elige banco a banco (Térmicos). */
    public static String organizacionFija(String area) {
        return switch (normalizar(area)) {
            case HVCH_CTAG -> "CTAG";
            case HVCH_STC -> "STC";
            default -> null;
        };
    }

    /** Nombres de las áreas: los guardados sobre los de serie, en el orden fijo. */
    public static Map<String, String> nombres(Map<String, String> guardados) {
        Map<String, String> nombres = new LinkedHashMap<>();
        for (String codigo : CODIGOS) {
            String n = guardados == null ? null : guardados.get(codigo);
            nombres.put(codigo, n == null || n.trim().isEmpty() ? NOMBRES_DE_SERIE.get(codigo) : n.trim());
        }
        return nombres;
    }
}
