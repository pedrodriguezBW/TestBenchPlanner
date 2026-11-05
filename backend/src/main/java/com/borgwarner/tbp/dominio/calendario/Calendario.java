package com.borgwarner.tbp.dominio.calendario;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calendario laboral: fines de semana y festivos.
 *
 * Igual que en la 4.9.4 (discrepancia D6, se conserva): el calendario es de
 * cada usuario, contiene los festivos de UN año (el elegido en Configuración →
 * Calendario, por defecto el del primer uso) y se puede editar a mano.
 */
public final class Calendario {

    /** Fiestas locales de Vigo por año; si un año no está, se usan las de 2026. */
    private static final Map<Integer, int[][]> FIESTAS_LOCALES_VIGO = Map.of(2026, new int[][]{{3, 28}, {8, 17}});
    private static final int[][] FIESTAS_LOCALES_POR_DEFECTO = {{3, 28}, {8, 17}};

    private final int anio;
    private final boolean saltarFestivos;
    private final List<Festivo> festivos;
    private final Set<LocalDate> diasFestivos = new HashSet<>();

    public Calendario(int anio, boolean saltarFestivos, List<Festivo> festivos) {
        this.anio = anio;
        this.saltarFestivos = saltarFestivos;
        this.festivos = List.copyOf(festivos);
        for (Festivo festivo : festivos) {
            LocalDate dia = Fechas.dia(festivo.date());
            if (dia != null) {
                diasFestivos.add(dia);
            }
        }
    }

    /** Calendario de un PC que abre la aplicación por primera vez (año de hoy). */
    public static Calendario porDefecto(LocalDate hoy) {
        return new Calendario(hoy.getYear(), true, festivosDeVigo(hoy.getYear()));
    }

    /** Festivos nacionales, de Galicia y locales de Vigo (computeHolidays). */
    public static List<Festivo> festivosDeVigo(int anio) {
        LocalDate pascua = domingoDePascua(anio);
        List<Festivo> lista = new ArrayList<>();
        Object[][] fijos = {
                {1, 1, "Ano Nuevo"}, {1, 6, "Reyes"}, {5, 1, "Dia del Trabajo"}, {8, 15, "Asuncion"},
                {10, 12, "Fiesta Nacional"}, {12, 6, "Constitucion"}, {12, 8, "Inmaculada"},
                {12, 25, "Navidad"}, {11, 1, "Todos los Santos"}, {3, 19, "San Jose"},
                {6, 24, "San Juan"}, {7, 25, "Dia de Galicia"}, {5, 17, "Letras Galegas"},
        };
        for (Object[] fijo : fijos) {
            lista.add(new Festivo(LocalDate.of(anio, (int) fijo[0], (int) fijo[1]).toString(), (String) fijo[2]));
        }
        lista.add(new Festivo(pascua.minusDays(3).toString(), "Jueves Santo"));
        lista.add(new Festivo(pascua.minusDays(2).toString(), "Viernes Santo"));
        for (int[] local : FIESTAS_LOCALES_VIGO.getOrDefault(anio, FIESTAS_LOCALES_POR_DEFECTO)) {
            lista.add(new Festivo(LocalDate.of(anio, local[0], local[1]).toString(), "Festivo local (Vigo)"));
        }
        // Sin duplicados (se queda el primero) y ordenados por fecha.
        Map<String, Festivo> unicos = new LinkedHashMap<>();
        for (Festivo festivo : lista) {
            unicos.putIfAbsent(festivo.date(), festivo);
        }
        List<Festivo> resultado = new ArrayList<>(unicos.values());
        resultado.sort(Comparator.comparing(Festivo::date));
        return resultado;
    }

    /** Algoritmo anónimo gregoriano, idéntico a la función easter() de la 4.9.4. */
    static LocalDate domingoDePascua(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31, dia = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, mes, dia);
    }

    public boolean esFestivo(LocalDate dia) {
        return saltarFestivos && diasFestivos.contains(dia);
    }

    /** Día laborable: ni fin de semana ni festivo (isFree). */
    public boolean esLaborable(LocalDate dia) {
        return !Fechas.esFinDeSemana(dia) && !esFestivo(dia);
    }

    /** Primer día laborable a partir de la fecha, incluida (nextFree). */
    public LocalDate siguienteLaborable(LocalDate desde) {
        LocalDate dia = desde;
        while (!esLaborable(dia)) {
            dia = dia.plusDays(1);
        }
        return dia;
    }

    /** Avanza n días laborables (addFree). */
    public LocalDate sumarLaborables(LocalDate desde, long n) {
        LocalDate dia = siguienteLaborable(desde);
        for (long contados = 0; contados < n; contados++) {
            dia = dia.plusDays(1);
            while (!esLaborable(dia)) {
                dia = dia.plusDays(1);
            }
        }
        return dia;
    }

    public int anio() {
        return anio;
    }

    public boolean saltarFestivos() {
        return saltarFestivos;
    }

    public List<Festivo> festivos() {
        return festivos;
    }
}
