package com.borgwarner.tbp.infraestructura.facturacion;

import com.borgwarner.tbp.dominio.facturacion.HojaMensual;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Fila;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual.Tabla;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exportación de la hoja mensual (solo las líneas completas) en Excel y en
 * HTML, con la misma forma que las hojas mensuales del Excel de facturación:
 * mes y periodo de facturación (21 del mes anterior – 20 del mes) en la fila 1, cabecera en la fila 2 y la tabla dinámica Billing
 * Order → Planta → Tipo → Equipo → HV → Inicio → Fin → Coste/hora con
 * «Total general». En Excel las filas quedan agrupadas por niveles (se
 * pliegan con los botones del margen, como la tabla dinámica). Réplica:
 * herramientas/laboratorio/facturacion/hoja.cjs.
 */
public final class HojaExcel {

    /** Cabecera de la primera columna: los niveles elegidos, p. ej. «Billing Order › Planta › Tipo». */
    public static String cabecera(Tabla t) {
        return t.niveles().isEmpty() ? "Etiquetas de fila" : String.join(" \u203a ", t.niveles());
    }
    private static final String[] MESES = {"ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO",
        "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"};

    private HojaExcel() {
    }

    public static String nombreMes(YearMonth mes) {
        return MESES[mes.getMonthValue() - 1];
    }

    /** Nombre del archivo sin extensión, p. ej. «Facturacion_2026-01_ENERO». */
    public static String nombreArchivo(YearMonth mes) {
        return "Facturacion_" + mes + "_" + nombreMes(mes);
    }

    private static final java.time.format.DateTimeFormatter DD_MM_AAAA = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** «Periodo 21/01/2026 – 20/02/2026». */
    public static String periodo(YearMonth mes) {
        return "Periodo " + HojaMensual.inicioPeriodo(mes).format(DD_MM_AAAA) + " \u2013 " + HojaMensual.finPeriodo(mes).format(DD_MM_AAAA);
    }

    public static byte[] excel(YearMonth mes, Tabla t) {
        LibroExcel.Hoja h = new LibroExcel.Hoja(nombreMes(mes) + " " + mes.getYear());
        double[] anchos = new double[t.columnas().size() + 2];
        anchos[0] = 46;
        for (int i = 1; i < anchos.length; i++) {
            anchos[i] = 15;
        }
        h.anchos(anchos).fijar(2);
        h.fila(LibroExcel.c(nombreMes(mes) + " " + mes.getYear(), LibroExcel.NEGRITA), LibroExcel.c(periodo(mes)));
        List<LibroExcel.Celda> cab = new ArrayList<>();
        cab.add(LibroExcel.c(cabecera(t), LibroExcel.CABECERA));
        t.columnas().forEach(c -> cab.add(LibroExcel.c(c, LibroExcel.CABECERA)));
        cab.add(LibroExcel.c("Total general", LibroExcel.CABECERA));
        h.fila(cab);
        for (Fila f : t.filas()) {
            boolean total = t.esTotal(f);
            boolean negrita = f.nivel() == 0 || total;
            int estiloTexto = negrita ? LibroExcel.NEGRITA : LibroExcel.sangria(f.nivel());
            int estiloImporte = negrita ? LibroExcel.IMPORTE_NEGRITA : LibroExcel.IMPORTE;
            List<LibroExcel.Celda> fila = new ArrayList<>();
            fila.add(LibroExcel.c(f.etiqueta(), estiloTexto));
            for (String c : t.columnas()) {
                fila.add(LibroExcel.c(f.valores().get(c), estiloImporte));
            }
            fila.add(LibroExcel.c(f.total(), estiloImporte));
            h.fila(fila, total ? 0 : f.nivel());
        }
        return LibroExcel.libro(List.of(h));
    }

    static String importe(Double v) {
        if (v == null) {
            return "";
        }
        String t = String.format(Locale.ROOT, "%,.2f", v);
        // 1,234.56 → 1.234,56
        return t.replace(',', '\u0001').replace('.', ',').replace('\u0001', '.') + " €";
    }

    public static String html(YearMonth mes, Tabla t) {
        StringBuilder s = new StringBuilder();
        String titulo = "Facturación " + nombreMes(mes) + " " + mes.getYear() + " · " + periodo(mes);
        s.append("<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\"><title>").append(LibroExcel.escapar(titulo))
                .append("</title><style>body{font-family:Arial,sans-serif;font-size:13px;margin:24px;color:#111}")
                .append("h1{font-size:18px}table{border-collapse:collapse}th,td{border:1px solid #d9d9d9;padding:3px 8px}")
                .append("th{background:#0c1d2c;color:#fff;text-align:left}td.n{text-align:right;white-space:nowrap}")
                .append("tr.n0 td{font-weight:bold;background:#dde6f0}tr.total td{font-weight:bold;border-top:2px solid #0c1d2c}</style></head><body>\n");
        s.append("<h1>").append(LibroExcel.escapar(titulo)).append("</h1>\n<table>\n<thead><tr><th>").append(LibroExcel.escapar(cabecera(t))).append("</th>");
        t.columnas().forEach(c -> s.append("<th>").append(LibroExcel.escapar(c)).append("</th>"));
        s.append("<th>Total general</th></tr></thead>\n<tbody>\n");
        for (Fila f : t.filas()) {
            boolean total = t.esTotal(f);
            s.append("<tr class=\"").append(total ? "total" : "n" + f.nivel()).append("\"><td style=\"padding-left:")
                    .append(8 + (total ? 0 : f.nivel()) * 16).append("px\">").append(LibroExcel.escapar(f.etiqueta())).append("</td>");
            for (String c : t.columnas()) {
                s.append("<td class=\"n\">").append(importe(f.valores().get(c))).append("</td>");
            }
            s.append("<td class=\"n\">").append(importe(f.total())).append("</td></tr>\n");
        }
        if (t.filas().isEmpty()) {
            s.append("<tr><td colspan=\"").append(t.columnas().size() + 2).append("\">Sin líneas completas este mes.</td></tr>\n");
        }
        s.append("</tbody></table>\n</body></html>\n");
        return s.toString();
    }
}
