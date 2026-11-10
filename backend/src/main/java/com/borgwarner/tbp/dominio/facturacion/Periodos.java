package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Periodos de facturación: del día 21 del mes anterior al día 20 del mes que da
 * nombre al periodo, ambos incluidos (Enero de 2026 = 21/12/2025 – 20/01/2026).
 */
public final class Periodos {

    public static final List<String> MESES = List.of("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre");
    private static final Pattern ID = Pattern.compile("^(\\d{4})-(\\d{2})$");

    /** Periodo calculado a partir del mes nominal. */
    public record Periodo(String id, int anio, int mes, String nombre, String inicio, String fin) {

        public ObjectNode comoJson() {
            ObjectNode o = Nodos.objeto();
            o.put("id", id).put("anio", anio).put("mes", mes).put("nombre", nombre).put("inicio", inicio).put("fin", fin);
            return o;
        }
    }

    private Periodos() {
    }

    private static String dos(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    public static Periodo periodo(int anio, int mes) {
        int anioAnterior = mes == 1 ? anio - 1 : anio;
        int mesAnterior = mes == 1 ? 12 : mes - 1;
        return new Periodo(anio + "-" + dos(mes), anio, mes, MESES.get(mes - 1) + " " + anio,
                anioAnterior + "-" + dos(mesAnterior) + "-21", anio + "-" + dos(mes) + "-20");
    }

    public static Periodo deId(String id) {
        Matcher m = ID.matcher(id == null ? "" : id);
        if (!m.matches() || Integer.parseInt(m.group(2)) < 1 || Integer.parseInt(m.group(2)) > 12) {
            throw new ReglaNoCumplida("Periodo no válido: " + id);
        }
        return periodo(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    public static String siguiente(String id) {
        Periodo p = deId(id);
        return p.mes() == 12 ? (p.anio() + 1) + "-01" : p.anio() + "-" + dos(p.mes() + 1);
    }

    public static String anterior(String id) {
        Periodo p = deId(id);
        return p.mes() == 1 ? (p.anio() - 1) + "-12" : p.anio() + "-" + dos(p.mes() - 1);
    }

    /** Periodo al que pertenece un día: hasta el 20, el del mes; desde el 21, el del mes siguiente. */
    public static String deDia(String dia) {
        String[] partes = dia.split("-");
        int a = Integer.parseInt(partes[0]);
        int m = Integer.parseInt(partes[1]);
        int d = Integer.parseInt(partes[2].substring(0, 2));
        if (d <= 20) {
            return a + "-" + dos(m);
        }
        return m == 12 ? (a + 1) + "-01" : a + "-" + dos(m + 1);
    }
}
