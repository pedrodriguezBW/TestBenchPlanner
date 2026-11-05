package com.borgwarner.tbp.dominio.calendario;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.regex.Pattern;

/**
 * Utilidades de fechas con la misma semántica que la 4.9.4.
 *
 * La 4.9.4 trabaja con días naturales en la hora local del PC (Madrid): convierte
 * cada texto con new Date(texto) y lo lleva a medianoche (función d0). Aquí se
 * usa LocalDate, que ya representa «un día» sin hora. Un texto que no es una
 * fecha válida se trata como null, que es lo que en JavaScript es «Invalid Date»
 * (cualquier comparación con él da falso).
 */
public final class Fechas {

    /** Zona horaria de referencia: la del laboratorio de Vigo. */
    public static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    private static final Pattern SOLO_FECHA = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Fechas() {
    }

    /** Equivale a d0(texto) para los textos que guarda la aplicación. */
    public static LocalDate dia(String texto) {
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        try {
            if (SOLO_FECHA.matcher(texto).matches()) {
                // new Date('aaaa-mm-dd') es medianoche UTC; en Madrid sigue siendo ese mismo día.
                return LocalDate.parse(texto);
            }
            if (texto.endsWith("Z") || texto.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return OffsetDateTime.parse(texto).atZoneSameInstant(ZONA).toLocalDate();
            }
            return LocalDateTime.parse(texto).toLocalDate(); // sin zona: hora local
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static LocalDate dia(JsonNode valor) {
        if (valor == null || !valor.isTextual()) {
            return null;
        }
        return dia(valor.textValue());
    }

    /** Fecha en formato aaaa-mm-dd (función iso de la 4.9.4). */
    public static String iso(LocalDate fecha) {
        return fecha == null ? null : fecha.toString();
    }

    /** Días entre dos fechas (daysBetween). */
    public static long diasEntre(LocalDate desde, LocalDate hasta) {
        return ChronoUnit.DAYS.between(desde, hasta);
    }

    /** Lunes de la semana de la fecha (weekStart). */
    public static LocalDate lunesDe(LocalDate fecha) {
        return fecha.minusDays(fecha.getDayOfWeek().getValue() - 1L);
    }

    /** Semana ISO (función cw de la 4.9.4). */
    public static int semanaIso(LocalDate fecha) {
        return fecha.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Año al que pertenece la semana ISO (para la corrección D8). */
    public static int anioDeSemanaIso(LocalDate fecha) {
        return fecha.get(IsoFields.WEEK_BASED_YEAR);
    }

    public static boolean esFinDeSemana(LocalDate fecha) {
        DayOfWeek dia = fecha.getDayOfWeek();
        return dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY;
    }

    /** a <= b, con la semántica de JavaScript para fechas inválidas (null = falso). */
    public static boolean noPosterior(LocalDate a, LocalDate b) {
        return a != null && b != null && !a.isAfter(b);
    }

    /** a > b, con la semántica de JavaScript para fechas inválidas (null = falso). */
    public static boolean posterior(LocalDate a, LocalDate b) {
        return a != null && b != null && a.isAfter(b);
    }

    /** new Date().toISOString(): instante en UTC con milisegundos. */
    public static String instanteIso(Instant instante) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneId.of("UTC"))
                .format(instante);
    }
}
