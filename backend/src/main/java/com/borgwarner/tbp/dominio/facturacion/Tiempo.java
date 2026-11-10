package com.borgwarner.tbp.dominio.facturacion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fechas y horas de la facturación: textos «aaaa-mm-dd» y «aaaa-mm-ddThh:mm» de
 * reloj de pared (Europe/Madrid) convertidos a minutos con aritmética UTC, de modo
 * que un cambio de hora no altera las horas (igual que (Fin−Inicio)×24 en el Excel).
 */
public final class Tiempo {

    public static final long DIA = 1440;
    private static final Pattern FECHA = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2}))?$");
    private static final Pattern HORA = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter CON_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SOLO_DIA = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Tiempo() {
    }

    /** Minutos desde 1970 de una fecha (con hora opcional); null si no es una fecha válida. */
    public static Long aMin(String texto) {
        if (texto == null) {
            return null;
        }
        Matcher m = FECHA.matcher(texto.trim());
        if (!m.matches()) {
            return null;
        }
        int anio = Integer.parseInt(m.group(1));
        int mes = Integer.parseInt(m.group(2));
        int dia = Integer.parseInt(m.group(3));
        int hora = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        int minuto = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
        if (mes < 1 || mes > 12 || dia < 1 || hora > 23 || minuto > 59) {
            return null;
        }
        try {
            return LocalDateTime.of(anio, mes, dia, hora, minuto).toEpochSecond(ZoneOffset.UTC) / 60;
        } catch (DateTimeException e) {
            return null;
        }
    }

    public static String deMin(long minutos) {
        return LocalDateTime.ofEpochSecond(minutos * 60, 0, ZoneOffset.UTC).format(CON_HORA);
    }

    public static String diaDeMin(long minutos) {
        return LocalDateTime.ofEpochSecond(minutos * 60, 0, ZoneOffset.UTC).format(SOLO_DIA);
    }

    public static boolean esFinDeSemana(long minutos) {
        DayOfWeek d = LocalDateTime.ofEpochSecond(minutos * 60, 0, ZoneOffset.UTC).getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public static String sumarDias(String dia, int n) {
        return diaDeMin(aMin(dia) + n * DIA);
    }

    public static boolean esHora(String texto) {
        return texto != null && HORA.matcher(texto).matches();
    }

    /** Minutos de [a,b) que caen en sábado o domingo. */
    public static long minutosFinDeSemana(long a, long b) {
        if (b <= a) {
            return 0;
        }
        long total = 0;
        long dia = Math.floorDiv(a, DIA) * DIA;
        while (dia < b) {
            if (esFinDeSemana(dia)) {
                total += Math.max(0, Math.min(b, dia + DIA) - Math.max(a, dia));
            }
            dia += DIA;
        }
        return total;
    }

    public static long solape(long a1, long b1, long a2, long b2) {
        return Math.max(0, Math.min(b1, b2) - Math.max(a1, a2));
    }

    /** Cuartos de hora más próximos (la mitad, hacia arriba): la precisión de facturación es de 15 minutos. */
    public static long cuartosDeMinutos(long minutos) {
        if (minutos >= 0) {
            return Math.floorDiv(2 * minutos + 15, 30);
        }
        return -Math.floorDiv(2 * -minutos + 15, 30);
    }

    public static double horasDeMinutos(long minutos) {
        return cuartosDeMinutos(minutos) / 4.0;
    }

    /** Math.round(x·100)/100 de JavaScript con el ajuste de Number.EPSILON. */
    public static double redondear2(double x) {
        return Math.floor((x + Math.ulp(1.0) * Math.signum(x)) * 100 + 0.5) / 100;
    }

    /** ROUND(x;2) de Excel: la mitad se aleja de cero, sobre la representación decimal (15 cifras). */
    public static double redondeoExcel2(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        double signo = x < 0 ? -1 : 1;
        double v = Math.abs(x);
        double centesimas = new BigDecimal(v * 100).round(new MathContext(15)).doubleValue();
        return signo * Math.floor(centesimas + 0.5) / 100;
    }
}
