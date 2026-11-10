package com.borgwarner.tbp.infraestructura.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.facturacion.MotorFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Nodos;
import com.borgwarner.tbp.dominio.facturacion.Periodos;
import com.borgwarner.tbp.dominio.facturacion.ResumenFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Tiempo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.borgwarner.tbp.infraestructura.facturacion.LibroExcel.c;

/**
 * Exportaciones de un periodo aprobado: Excel completo (información equivalente
 * al libro «2026 Ensayos interno», sin las pestañas internas de configuración),
 * Excel reducido de Controlling y HTML con formato de presentación.
 *
 * Réplica de herramientas/laboratorio/facturacion/exportaciones.cjs (allí se
 * comprueba con openpyxl que la hoja de Controlling coincide con la del Excel).
 */
public final class ExportacionesFacturacion {

    /** Datos de la exportación que se imprimen en los ficheros. */
    public record Meta(String id, int version, String aprobadoPor, String aprobadoEn, String exportadoPor, String exportadoEn) {
    }

    /** Fichero generado. */
    public record Archivo(String tipo, String nombre, byte[] contenido) {
    }

    private static final Map<String, String> ESTADOS = Map.of("BORRADOR", "Borrador", "EN_REVISION", "En revisión",
            "APROBADO", "Aprobado", "EXPORTADO", "Exportado");
    private static final Map<String, String> ESTADO_LINEA = Map.of("READY", "Lista", "WARNING", "Advertencia", "BLOCKED", "Bloqueada",
            "EXCLUDED", "Excluida", "PENDING", "Pendiente", "ADJUSTED", "Ajustada", "DEFERRED", "Aplazada");

    private ExportacionesFacturacion() {
    }

    // ---- utilidades --------------------------------------------------------------------

    private static double serie(String dia) {
        return Tiempo.aMin(dia.substring(0, 10)) / 1440.0 + 25569;
    }

    private static String fechaHora(String t) {
        if (t == null || t.isEmpty()) {
            return "";
        }
        if (t.length() < 10) {
            return t;
        }
        String s = t.substring(8, 10) + "/" + t.substring(5, 7) + "/" + t.substring(0, 4);
        return t.length() > 10 ? s + " " + t.substring(11, Math.min(16, t.length())) : s;
    }

    private static String hv(String hv) {
        return hv.equals("yes") ? "Sí" : hv.equals("no") ? "No" : "";
    }

    private static Double num(JsonNode n, String campo) {
        return Nodos.numero(n, campo);
    }

    private static LibroExcel.Celda h(String t) {
        return c(t, LibroExcel.CABECERA);
    }

    private static LibroExcel.Celda eur(Double v) {
        return c(v == null ? "" : v, LibroExcel.IMPORTE);
    }

    private static LibroExcel.Celda hrs(Double v) {
        return c(v == null ? "" : v, LibroExcel.HORAS);
    }

    private static List<LibroExcel.Celda> cabeceras(String... textos) {
        List<LibroExcel.Celda> l = new ArrayList<>();
        for (String t : textos) {
            l.add(t == null ? null : h(t));
        }
        return l;
    }

    private static String nombreFichero(JsonNode doc, String tipo, int version, String ext) {
        return "Facturacion_" + Nodos.texto(doc, "id") + "_v" + version + "_" + tipo + "." + ext;
    }

    /** 1234.5 → «1.234,50» (siempre con separador de miles). */
    static String numeroEs(double v) {
        boolean negativo = v < 0;
        String fijo = String.format(Locale.ROOT, "%.2f", Math.abs(Tiempo.redondear2(v)));
        String[] partes = fijo.split("\\.");
        String entero = partes[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
        return (negativo ? "-" : "") + entero + "," + partes[1];
    }

    static String euros(Double v) {
        return v == null ? "—" : numeroEs(v) + " €";
    }

    static String horas(Double v) {
        return numeroEs(v == null ? 0 : v) + " h";
    }

    private static String tarifas(JsonNode g) {
        List<String> t = new ArrayList<>();
        for (JsonNode x : Nodos.arr(g, "tarifas")) {
            t.add(String.format(Locale.ROOT, "%.2f", x.asDouble()).replace('.', ','));
        }
        return String.join(" / ", t) + " €/h";
    }

    // ---- Excel ---------------------------------------------------------------------------

    static LibroExcel.Hoja hojaControlling(JsonNode doc, JsonNode r) {
        LibroExcel.Hoja h = new LibroExcel.Hoja("Controlling - Internal Testing");
        h.anchos(26, 16, 18, 16, 3, 3, 12, 12, 10, 13, 12, 7, 11, 16, 10, 11, 6, 10, 26);
        h.fila(c("Mes facturación", LibroExcel.NEGRITA), c(serie(Nodos.texto(doc, "id") + "-01"), LibroExcel.MES));
        h.fila(c("Ensayos por horas", LibroExcel.NEGRITA));
        h.fila(cabeceras("Equipo", "Orden", "Suma de Coste total", "Sum of Horas totales", null, null, "CCtr (Old)", "Order (old)", "Oper. (old)",
                "Cost Element", "Amount", "Crcy", "CCtr (new)", "Order (new)", "Oper. (new)", "Total Qty", "UM", "Pers.No.", "Text"));
        for (JsonNode f : Nodos.arr(r, "controlling")) {
            h.fila(c(Nodos.texto(f, "equipo")), c(Nodos.texto(f, "orden")), c(num(f, "sumaCoste")), c(num(f, "sumaHoras")), null, null,
                    c(Nodos.texto(f, "cctrOld")), c(Nodos.texto(f, "orderOld")), c(Nodos.texto(f, "operOld")), c(Nodos.texto(f, "costElement")),
                    c(num(f, "amount"), LibroExcel.IMPORTE), c(Nodos.texto(f, "crcy")), c(Nodos.texto(f, "cctrNew")), c(Nodos.texto(f, "orderNew")),
                    c(Nodos.texto(f, "operNew")), c(num(f, "totalQty")), c(Nodos.texto(f, "um")), c(Nodos.texto(f, "persNo")), c(Nodos.texto(f, "text")));
        }
        h.fijar(3);
        return h;
    }

    static List<LibroExcel.Hoja> hojasCompletas(JsonNode doc, JsonNode r, Meta meta) {
        JsonNode ind = r.get("indicadores");
        List<LibroExcel.Hoja> hojas = new ArrayList<>();

        LibroExcel.Hoja res = new LibroExcel.Hoja("Resumen").anchos(34, 28);
        res.fila(c("Facturación de " + Nodos.texto(doc, "nombre"), LibroExcel.TITULO));
        res.fila(c("Periodo"), c(fechaHora(Nodos.texto(doc, "inicio")) + " - " + fechaHora(Nodos.texto(doc, "fin"))));
        String estado = Nodos.texto(doc, "estado");
        res.fila(c("Estado"), c(ESTADOS.getOrDefault(estado, estado)));
        res.fila(c("Versión aprobada"), c(meta.version()));
        res.fila(c("Aprobado por"), c(meta.aprobadoPor()));
        res.fila(c("Aprobado el"), c(fechaHora(meta.aprobadoEn())));
        res.fila(c("Exportado por"), c(meta.exportadoPor()));
        res.fila(c("Exportado el"), c(fechaHora(meta.exportadoEn())));
        res.fila(c("Identificador de la exportación"), c(meta.id()));
        res.vacia();
        res.fila(h("Indicador"), h("Valor"));
        res.fila(c("Coste total (€)"), eur(num(ind, "coste")));
        res.fila(c("Horas netas"), hrs(num(ind, "horasNetas")));
        res.fila(c("Horas de setup"), hrs(num(ind, "horasSetup")));
        res.fila(c("Horas totales"), hrs(num(ind, "horasTotales")));
        for (String[] f : new String[][] {{"Ensayos", "ensayos"}, {"Líneas", "lineas"}, {"Líneas que suman", "lineasContadas"},
                {"Líneas preparadas", "preparadas"}, {"Líneas bloqueadas", "bloqueadas"}, {"Advertencias", "advertencias"},
                {"Líneas excluidas", "excluidas"}, {"Líneas aplazadas", "aplazadas"}, {"Líneas pendientes (sin arrancar)", "pendientes"},
                {"Líneas con ajustes", "ajustadas"}}) {
            res.fila(c(f[0]), c(Nodos.entero(ind, f[1])));
        }
        hojas.add(res);

        LibroExcel.Hoja det = new LibroExcel.Hoja("Detalle").anchos(30, 12, 14, 12, 16, 14, 14, 22, 6, 17, 17, 12, 10, 12, 10, 12, 11, 16, 8, 12, 11, 11, 30);
        det.fila(cabeceras("Proyecto", "Test / Task", "Requester", "Número proyecto", "Orden", "Planta / línea", "Tipo", "Equipo", "HV", "Inicio", "Fin",
                "Horas netas ensayo", "Horas set-up", "Horas totales", "Coste/hora", "Coste total", "Mes facturación", "Banco", "Planta",
                "Tipo de ensayo", "Regla", "Periodo ejecución", "Ajustes"));
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            if (!MotorFacturacion.cuenta(l)) {
                continue;
            }
            JsonNode a = l.get("aplicado");
            String nombre = Nodos.texto(a, "proyectoNombre");
            List<String> ajustes = new ArrayList<>();
            ObjectNode aj = Nodos.obj(l, "ajustes");
            if (aj != null) {
                aj.fieldNames().forEachRemaining(ajustes::add);
            }
            det.fila(c(nombre.isEmpty() ? Nodos.texto(l, "proyectoTexto") : nombre), c(Nodos.texto(l, "ensayoId")), c(Nodos.texto(a, "requester")),
                    c(Nodos.texto(a, "proyecto")), c(Nodos.texto(a, "orden")), c(Nodos.texto(a, "lineaProducto")), c(Nodos.texto(a, "grupo")),
                    c(Nodos.texto(a, "categoria")), c(hv(Nodos.texto(a, "hv"))), c(fechaHora(Nodos.texto(l, "tramoInicio"))),
                    c(fechaHora(Nodos.texto(l, "tramoFin"))), hrs(num(a, "horasNetas")), hrs(num(a, "horasSetup")), hrs(num(a, "horasTotales")),
                    eur(num(a, "tarifa")), c(num(a, "coste"), LibroExcel.IMPORTE), c(serie(Nodos.texto(doc, "id") + "-01"), LibroExcel.MES),
                    c(Nodos.texto(l, "banco")), c(Nodos.texto(l, "organizacion")), c(Nodos.texto(l, "tipoEnsayo")), c(Nodos.texto(a, "regla")),
                    c(Nodos.texto(l, "periodoEjecucion")), c(String.join(", ", ajustes)));
        }
        det.fila(c("Total", LibroExcel.NEGRITA), null, null, null, null, null, null, null, null, null, null, c(num(ind, "horasNetas"), LibroExcel.HORAS_NEGRITA),
                c(num(ind, "horasSetup"), LibroExcel.HORAS_NEGRITA), c(num(ind, "horasTotales"), LibroExcel.HORAS_NEGRITA), null,
                c(num(ind, "coste"), LibroExcel.IMPORTE_NEGRITA));
        det.fijar(1);
        hojas.add(det);

        LibroExcel.Hoja ppo = new LibroExcel.Hoja("Proyecto y orden").anchos(12, 32, 16, 9, 11, 9, 13);
        ppo.fila(cabeceras("Proyecto", "Nombre", "Orden", "Ensayos", "Horas", "Setup", "Coste"));
        for (JsonNode g : Nodos.arr(r, "porProyectoOrden")) {
            ppo.fila(c(Nodos.texto(g, "proyecto")), c(Nodos.texto(g, "proyectoNombre")), c(Nodos.texto(g, "orden")), c(Nodos.entero(g, "ensayos")),
                    hrs(num(g, "horas")), hrs(num(g, "setup")), eur(num(g, "coste")));
        }
        ppo.fijar(1);
        hojas.add(ppo);

        LibroExcel.Hoja eq = new LibroExcel.Hoja("Equipos").anchos(24, 14, 28, 11, 9, 12, 22, 13);
        eq.fila(cabeceras("Equipo", "Tipo", "Bancos", "Horas", "Setup", "Horas totales", "Tarifa o regla", "Coste"));
        for (JsonNode g : Nodos.arr(r, "porEquipo")) {
            List<String> bancos = new ArrayList<>();
            Nodos.arr(g, "bancos").forEach(b -> bancos.add(b.asText()));
            eq.fila(c(Nodos.texto(g, "categoria")), c(Nodos.texto(g, "grupo")), c(String.join(", ", bancos)), hrs(num(g, "horas")),
                    hrs(num(g, "setup")), hrs(num(g, "horasTotales")),
                    c(tarifas(g) + (Nodos.cierto(g, "noLineal") ? " · " + Nodos.texto(g, "regla") + " (no lineal)" : "")), eur(num(g, "coste")));
        }
        eq.fijar(1);
        hojas.add(eq);

        LibroExcel.Hoja te = new LibroExcel.Hoja("Tipo de ensayo").anchos(28, 9, 11, 9, 13);
        te.fila(cabeceras("Tipo de ensayo", "Ensayos", "Horas", "Setup", "Coste"));
        for (JsonNode g : Nodos.arr(r.get("desgloses"), "tipoEnsayo")) {
            te.fila(c(Nodos.texto(g, "etiqueta")), c(Nodos.entero(g, "ensayos")), hrs(num(g, "horas")), hrs(num(g, "setup")), eur(num(g, "coste")));
        }
        te.fijar(1);
        hojas.add(te);

        LibroExcel.Hoja pl = new LibroExcel.Hoja("Planta y línea").anchos(30, 12, 12, 12, 12, 12, 12, 12);
        pl.fila(cabeceras("Planta", "Ensayos", "Horas", "Setup", "Coste"));
        for (JsonNode g : Nodos.arr(r, "porPlanta")) {
            pl.fila(c(Nodos.texto(g, "etiqueta")), c(Nodos.entero(g, "ensayos")), hrs(num(g, "horas")), hrs(num(g, "setup")), eur(num(g, "coste")));
        }
        pl.vacia();
        JsonNode plp = r.get("porLineaProducto");
        List<String> columnas = new ArrayList<>();
        Nodos.arr(plp, "columnas").forEach(x -> columnas.add(x.asText()));
        List<LibroExcel.Celda> cab = new ArrayList<>();
        cab.add(h("Planta (si EGM) / product line"));
        columnas.forEach(x -> cab.add(h(x.isEmpty() ? "(sin tipo)" : x)));
        cab.add(h("Total general"));
        pl.fila(cab);
        for (JsonNode f : Nodos.arr(plp, "filas")) {
            int nivel = Nodos.entero(f, "nivel");
            List<LibroExcel.Celda> fila = new ArrayList<>();
            fila.add(c("   ".repeat(Math.max(0, nivel)) + Nodos.texto(f, "etiqueta"), nivel <= 0 ? LibroExcel.NEGRITA : LibroExcel.NORMAL));
            JsonNode valores = f.get("valores");
            for (String col : columnas) {
                fila.add(valores != null && valores.has(col) ? c(valores.get(col).asDouble(), nivel <= 0 ? LibroExcel.IMPORTE_NEGRITA : LibroExcel.IMPORTE) : null);
            }
            fila.add(c(num(f, "total"), nivel <= 0 ? LibroExcel.IMPORTE_NEGRITA : LibroExcel.IMPORTE));
            pl.fila(fila);
        }
        hojas.add(pl);

        hojas.add(hojaControlling(doc, r));

        LibroExcel.Hoja inc = new LibroExcel.Hoja("Incidencias y ajustes").anchos(24, 12, 14, 13, 14, 22, 60, 16, 16, 18, 30, 10);
        inc.fila(c("Incidencias", LibroExcel.NEGRITA));
        inc.fila(cabeceras("Línea", "Ensayo", "Banco", "Estado", "Nivel", "Código", "Mensaje"));
        for (JsonNode i : Nodos.arr(r, "incidencias")) {
            String e = Nodos.texto(i, "estado");
            inc.fila(c(Nodos.texto(i, "lineaId")), c(Nodos.texto(i, "ensayoId")), c(Nodos.texto(i, "banco")), c(ESTADO_LINEA.getOrDefault(e, e)),
                    c(Nodos.texto(i, "nivel")), c(Nodos.texto(i, "codigo")), c(Nodos.texto(i, "mensaje")));
        }
        inc.vacia();
        inc.fila(c("Exclusiones y aplazamientos", LibroExcel.NEGRITA));
        inc.fila(cabeceras("Línea", "Ensayo", "Banco", "Estado", "Usuario", "Fecha", "Detalle"));
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            ObjectNode ex = Nodos.obj(l, "exclusion");
            ObjectNode ap = Nodos.obj(l, "aplazamiento");
            if (ex == null && ap == null) {
                continue;
            }
            ObjectNode x = ex != null ? ex : ap;
            String detalle;
            if (ex != null) {
                String origen = Nodos.texto(ex, "origen");
                detalle = "Excluida (" + (origen.equals("ENSAYO") ? "ensayo no facturable" : origen.equals("REGLA") ? "categoría no facturable" : "en la revisión") + ")";
            } else {
                detalle = "Aplazada a " + Periodos.deId(Nodos.texto(ap, "a")).nombre();
            }
            String e = Nodos.texto(l, "estado");
            inc.fila(c(Nodos.texto(l, "id")), c(Nodos.texto(l, "ensayoId")), c(Nodos.texto(l, "banco")), c(ESTADO_LINEA.getOrDefault(e, e)),
                    c(Nodos.texto(x, "por")), c(fechaHora(Nodos.texto(x, "en"))), c(detalle));
        }
        inc.vacia();
        inc.fila(c("Ajustes", LibroExcel.NEGRITA));
        inc.fila(cabeceras("Línea", "Ensayo", "Campo", "Valor calculado", "Valor aplicado", "Usuario", "Fecha", "Rol", "Validado", "Justificación"));
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            ObjectNode ajustes = Nodos.obj(l, "ajustes");
            if (ajustes == null) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = ajustes.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode a = e.getValue();
                inc.fila(c(Nodos.texto(l, "id")), c(Nodos.texto(l, "ensayoId")), c(e.getKey()), valorCelda(a.get("original")), valorCelda(a.get("valor")),
                        c(Nodos.texto(a, "por")), c(fechaHora(Nodos.texto(a, "en"))), c(Nodos.texto(a, "rol")),
                        c(Nodos.cierto(a, "economico") ? (Nodos.cierto(a, "validado") ? "Sí" : "No") : ""), c(Nodos.texto(a, "justificacion")));
            }
        }
        inc.vacia();
        inc.fila(c("Tarifas excepcionales", LibroExcel.NEGRITA));
        inc.fila(cabeceras("Línea", "Ensayo", "Tarifa calculada", "Tarifa propuesta", "Diferencia (€)", "Propuesta por", "Estado", "Resuelta por",
                "Tarifa final", "Alcance", "Justificación"));
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            ObjectNode p = Nodos.obj(l, "propuestaTarifa");
            if (p == null) {
                continue;
            }
            inc.fila(c(Nodos.texto(l, "id")), c(Nodos.texto(l, "ensayoId")), eur(num(p, "calculada")), eur(num(p, "propuesta")), eur(num(p, "diferencia")),
                    c(Nodos.texto(p, "por")), c(Nodos.texto(p, "estado")), c(Nodos.texto(p, "resueltaPor")), eur(num(p, "tarifaFinal")),
                    c(Nodos.texto(p, "alcance")), c(Nodos.texto(p, "justificacion")));
        }
        if (Nodos.arr(doc, "reaperturas").size() > 0) {
            inc.vacia();
            inc.fila(c("Reaperturas", LibroExcel.NEGRITA));
            inc.fila(cabeceras("Versión reabierta", "Usuario", "Fecha", "Justificación"));
            for (JsonNode x : Nodos.arr(doc, "reaperturas")) {
                inc.fila(c(Nodos.entero(x, "version")), c(Nodos.texto(x, "por")), c(fechaHora(Nodos.texto(x, "en"))), c(Nodos.texto(x, "justificacion")));
            }
        }
        hojas.add(inc);
        return hojas;
    }

    private static LibroExcel.Celda valorCelda(JsonNode v) {
        if (Nodos.nulo(v)) {
            return c("");
        }
        return v.isNumber() ? c(v.doubleValue()) : c(Nodos.texto(v));
    }

    public static Archivo excelCompleto(JsonNode doc, JsonNode maestros, Meta meta) {
        ObjectNode r = ResumenFacturacion.resumen(doc, maestros);
        return new Archivo("excel", nombreFichero(doc, "completo", meta.version(), "xlsx"), LibroExcel.libro(hojasCompletas(doc, r, meta)));
    }

    public static Archivo excelControlling(JsonNode doc, JsonNode maestros, Meta meta) {
        ObjectNode r = ResumenFacturacion.resumen(doc, maestros);
        return new Archivo("controlling", nombreFichero(doc, "controlling", meta.version(), "xlsx"), LibroExcel.libro(List.of(hojaControlling(doc, r))));
    }

    // ---- HTML de presentación --------------------------------------------------------------

    private static String esc(String t) {
        return LibroExcel.escapar(t == null ? "" : t);
    }

    /** Celda numérica (alineada a la derecha) o de texto. */
    private record Td(String texto, boolean numerica) {
    }

    private static Td n(String t) {
        return new Td(t, true);
    }

    private static Td t(String t) {
        return new Td(t, false);
    }

    private static String tabla(List<String> cab, List<List<Td>> filas) {
        StringBuilder s = new StringBuilder("<table><thead><tr>");
        cab.forEach(x -> s.append("<th>").append(esc(x)).append("</th>"));
        s.append("</tr></thead><tbody>");
        for (List<Td> f : filas) {
            s.append("<tr>");
            for (Td d : f) {
                s.append(d.numerica() ? "<td class=\"n\">" : "<td>").append(esc(d.texto())).append("</td>");
            }
            s.append("</tr>");
        }
        return s.append("</tbody></table>").toString();
    }

    private static String barras(JsonNode grupos, String campoEtiqueta) {
        double max = 1;
        for (JsonNode g : grupos) {
            max = Math.max(max, g.path("coste").asDouble());
        }
        StringBuilder s = new StringBuilder("<div class=\"barras\">");
        for (JsonNode g : grupos) {
            s.append("<div class=\"barra\"><span class=\"et\">").append(esc(Nodos.texto(g, campoEtiqueta)))
                    .append("</span><span class=\"pista\"><span class=\"relleno\" style=\"width:")
                    .append(String.format(Locale.ROOT, "%.1f", 100 * g.path("coste").asDouble() / max))
                    .append("%\"></span></span><span class=\"va\">").append(euros(g.path("coste").asDouble())).append("</span></div>");
        }
        return s.append("</div>").toString();
    }

    private static String kpi(String titulo, String valor) {
        return "<div class=\"kpi\"><div class=\"v\">" + esc(valor) + "</div><div class=\"t\">" + esc(titulo) + "</div></div>";
    }

    public static Archivo htmlPresentacion(JsonNode doc, JsonNode maestros, Meta meta) {
        ObjectNode r = ResumenFacturacion.resumen(doc, maestros);
        JsonNode ind = r.get("indicadores");
        String nombre = Nodos.texto(doc, "nombre");
        List<String[]> diapos = new ArrayList<>();
        diapos.add(new String[] {"Portada", "<div class=\"portada\"><div class=\"marca\">TestBenchPlanner · Billing</div><h1>Facturación de " + esc(nombre)
                + "</h1><p class=\"sub\">Periodo " + esc(fechaHora(Nodos.texto(doc, "inicio"))) + " – " + esc(fechaHora(Nodos.texto(doc, "fin")))
                + "</p><p class=\"total\">" + euros(num(ind, "coste")) + "</p><p class=\"meta\">Versión aprobada " + meta.version() + " · aprobada por "
                + esc(meta.aprobadoPor()) + " el " + esc(fechaHora(meta.aprobadoEn())) + "</p></div>"});
        diapos.add(new String[] {"Resumen general", "<div class=\"kpis\">" + kpi("Coste total", euros(num(ind, "coste")))
                + kpi("Horas netas", horas(num(ind, "horasNetas"))) + kpi("Horas de setup", horas(num(ind, "horasSetup")))
                + kpi("Ensayos", String.valueOf(Nodos.entero(ind, "ensayos"))) + kpi("Líneas que suman", String.valueOf(Nodos.entero(ind, "lineasContadas")))
                + kpi("Excluidas", String.valueOf(Nodos.entero(ind, "excluidas"))) + kpi("Aplazadas", String.valueOf(Nodos.entero(ind, "aplazadas")))
                + kpi("Con ajustes", String.valueOf(Nodos.entero(ind, "ajustadas"))) + "</div>"});
        List<List<Td>> plantas = new ArrayList<>();
        for (JsonNode g : Nodos.arr(r, "porPlanta")) {
            plantas.add(List.of(t(Nodos.texto(g, "etiqueta")), n(String.valueOf(Nodos.entero(g, "ensayos"))), n(horas(num(g, "horas"))),
                    n(horas(num(g, "setup"))), n(euros(num(g, "coste")))));
        }
        diapos.add(new String[] {"Por planta", tabla(List.of("Planta", "Ensayos", "Horas", "Setup", "Coste"), plantas) + barras(r.get("porPlanta"), "etiqueta")});
        List<List<Td>> equipos = new ArrayList<>();
        for (JsonNode g : Nodos.arr(r, "porEquipo")) {
            equipos.add(List.of(t(Nodos.texto(g, "categoria")), t(Nodos.texto(g, "grupo")), n(horas(num(g, "horas"))), n(horas(num(g, "setup"))),
                    t(tarifas(g) + (Nodos.cierto(g, "noLineal") ? " · no lineal (" + Nodos.texto(g, "regla") + ")" : "")), n(euros(num(g, "coste")))));
        }
        diapos.add(new String[] {"Por equipo", tabla(List.of("Equipo", "Tipo", "Horas", "Setup", "Tarifa o regla", "Coste"), equipos)
                + barras(r.get("porEquipo"), "categoria")});
        List<List<Td>> proyectos = new ArrayList<>();
        for (JsonNode g : Nodos.arr(r, "porProyectoOrden")) {
            proyectos.add(List.of(t(Nodos.texto(g, "proyecto")), t(Nodos.texto(g, "proyectoNombre")), t(Nodos.texto(g, "orden")),
                    n(String.valueOf(Nodos.entero(g, "ensayos"))), n(horas(num(g, "horas"))), n(horas(num(g, "setup"))), n(euros(num(g, "coste")))));
        }
        diapos.add(new String[] {"Por proyecto y orden", tabla(List.of("Proyecto", "Nombre", "Orden", "Ensayos", "Horas", "Setup", "Coste"), proyectos)});
        JsonNode plp = r.get("porLineaProducto");
        List<String> columnas = new ArrayList<>();
        Nodos.arr(plp, "columnas").forEach(x -> columnas.add(x.asText()));
        List<String> cabLinea = new ArrayList<>();
        cabLinea.add("Línea / orden / requester");
        columnas.forEach(x -> cabLinea.add(x.isEmpty() ? "(sin tipo)" : x));
        cabLinea.add("Total");
        List<List<Td>> lineasProducto = new ArrayList<>();
        for (JsonNode f : Nodos.arr(plp, "filas")) {
            List<Td> fila = new ArrayList<>();
            fila.add(t("\u2003".repeat(Math.max(0, Nodos.entero(f, "nivel"))) + Nodos.texto(f, "etiqueta")));
            JsonNode valores = f.get("valores");
            for (String col : columnas) {
                fila.add(n(valores != null && valores.has(col) ? euros(valores.get(col).asDouble()) : ""));
            }
            fila.add(n(euros(num(f, "total"))));
            lineasProducto.add(fila);
        }
        diapos.add(new String[] {"Por línea de producto", tabla(cabLinea, lineasProducto)});
        List<List<Td>> incidencias = new ArrayList<>();
        for (JsonNode i : Nodos.arr(r, "incidencias")) {
            incidencias.add(List.of(t(Nodos.texto(i, "lineaId")), t(Nodos.texto(i, "nivel")), t(Nodos.texto(i, "mensaje"))));
        }
        List<List<Td>> ajustes = new ArrayList<>();
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            ObjectNode aj = Nodos.obj(l, "ajustes");
            if (aj == null) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = aj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                ajustes.add(List.of(t(Nodos.texto(l, "id")), t(e.getKey()), t(Nodos.texto(e.getValue().get("original"))),
                        t(Nodos.texto(e.getValue().get("valor"))), t(Nodos.texto(e.getValue(), "por")), t(Nodos.texto(e.getValue(), "justificacion"))));
            }
        }
        diapos.add(new String[] {"Incidencias y ajustes", (incidencias.isEmpty() ? "<p class=\"vacio\">Sin incidencias.</p>"
                : tabla(List.of("Línea", "Nivel", "Incidencia"), incidencias))
                + (ajustes.isEmpty() ? "" : "<h3>Ajustes</h3>" + tabla(List.of("Línea", "Campo", "Calculado", "Aplicado", "Usuario", "Justificación"), ajustes))});
        List<List<Td>> ctrl = new ArrayList<>();
        double totalCtrl = 0;
        for (JsonNode f : Nodos.arr(r, "controlling")) {
            totalCtrl += f.path("amount").asDouble();
            ctrl.add(List.of(t(Nodos.texto(f, "equipo")), t(Nodos.texto(f, "orden")), t(Nodos.texto(f, "cctrOld")), t(Nodos.texto(f, "costElement")),
                    n(euros(num(f, "amount"))), t(Nodos.texto(f, "crcy")), t(Nodos.texto(f, "orderNew")),
                    n(ValorJs.numeroComoTexto(f.path("totalQty").asDouble())), t(Nodos.texto(f, "um")), t(Nodos.texto(f, "text"))));
        }
        diapos.add(new String[] {"Controlling - Internal Testing", tabla(List.of("Equipo", "Orden", "CCtr (Old)", "Cost Element", "Amount", "Crcy",
                "Order (new)", "Total Qty", "UM", "Text"), ctrl) + "<p class=\"total-ctrl\">Total Controlling: " + euros(totalCtrl) + "</p>"});

        StringBuilder secciones = new StringBuilder();
        for (int i = 0; i < diapos.size(); i++) {
            secciones.append("<section class=\"diapo").append(i == 0 ? " activa" : "").append("\" data-i=\"").append(i).append("\"><header><h2>")
                    .append(esc(diapos.get(i)[0])).append("</h2><span>").append(i + 1).append(" / ").append(diapos.size())
                    .append("</span></header><div class=\"contenido\">").append(diapos.get(i)[1]).append("</div></section>\n");
        }
        String html = "<!doctype html>\n<html lang=\"es\"><head><meta charset=\"utf-8\"><title>Facturación " + esc(nombre) + " · v" + meta.version()
                + "</title>\n<style>\n" + CSS + "</style></head><body>\n" + secciones
                + "<nav><button id=\"ant\" type=\"button\">&#9664; Anterior</button><button id=\"sig\" type=\"button\">Siguiente &#9654;</button>"
                + "<span class=\"pie\">TestBenchPlanner · " + esc(nombre) + " · versión aprobada " + meta.version() + " · exportación " + esc(meta.id())
                + " · " + esc(fechaHora(meta.exportadoEn())) + " · solo lectura</span></nav>\n<script>\n" + JS + "</script></body></html>";
        return new Archivo("html", nombreFichero(doc, "presentacion", meta.version(), "html"), html.getBytes(StandardCharsets.UTF_8));
    }

    private static final String CSS = """
            *{box-sizing:border-box}body{margin:0;font-family:Segoe UI,Arial,sans-serif;background:#0c1d2c;color:#e8eef5}
            .diapo{display:none;min-height:100vh;padding:28px 48px 80px}.diapo.activa{display:block}
            header{display:flex;justify-content:space-between;align-items:baseline;border-bottom:2px solid #23bca3;margin-bottom:18px}h2{margin:0 0 8px;font-size:26px}header span{color:#9fb3c8}
            .portada{text-align:center;padding-top:12vh}.portada h1{font-size:44px;margin:12px 0}.marca{color:#23bca3;font-weight:700;letter-spacing:.08em;text-transform:uppercase}.sub{font-size:20px;color:#9fb3c8}.total{font-size:56px;font-weight:700;color:#ffc800;margin:24px 0}.meta{color:#9fb3c8}
            .kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.kpi{background:#13293d;border-radius:10px;padding:18px}.kpi .v{font-size:28px;font-weight:700;color:#ffc800}.kpi .t{color:#9fb3c8;margin-top:6px}
            table{width:100%;border-collapse:collapse;font-size:13px;background:#13293d;border-radius:8px;overflow:hidden;margin-bottom:16px}th{background:#1d3a55;text-align:left;padding:7px 9px}td{padding:6px 9px;border-top:1px solid #1d3a55}td.n{text-align:right;white-space:nowrap}
            .barras{margin-top:10px}.barra{display:grid;grid-template-columns:220px 1fr 130px;gap:10px;align-items:center;margin:6px 0}.pista{background:#13293d;border-radius:6px;height:16px;overflow:hidden}.relleno{display:block;height:100%;background:linear-gradient(90deg,#23bca3,#4c8dff)}.va{text-align:right}
            .vacio{color:#9fb3c8}.total-ctrl{text-align:right;font-weight:700;font-size:18px;color:#ffc800}
            nav{position:fixed;bottom:0;left:0;right:0;display:flex;gap:10px;justify-content:center;align-items:center;padding:12px;background:rgba(12,29,44,.95);border-top:1px solid #1d3a55}
            nav button{background:#23bca3;color:#0c1d2c;border:0;border-radius:6px;padding:8px 16px;font-weight:700;cursor:pointer}nav button:disabled{opacity:.4;cursor:default}nav .pie{color:#9fb3c8;font-size:12px}
            @media print{nav{display:none}.diapo{display:block;page-break-after:always;min-height:auto}}
            """;

    private static final String JS = """
            (function(){var d=document.querySelectorAll('.diapo'),i=0,a=document.getElementById('ant'),s=document.getElementById('sig');
            function ir(n){i=Math.max(0,Math.min(d.length-1,n));for(var k=0;k<d.length;k++)d[k].className='diapo'+(k===i?' activa':'');a.disabled=i===0;s.disabled=i===d.length-1;}
            a.onclick=function(){ir(i-1)};s.onclick=function(){ir(i+1)};document.addEventListener('keydown',function(e){if(e.key==='ArrowRight'||e.key==='PageDown')ir(i+1);if(e.key==='ArrowLeft'||e.key==='PageUp')ir(i-1);});ir(0);})();
            """;
}
