package com.borgwarner.tbp.infraestructura.excel;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gantt a Excel (.xlsx) con el mismo aspecto que do_export_gantt de la 4.9.4
 * (que usaba openpyxl): título, banda de meses, banda de CW, fila de días con
 * fin de semana / festivo / hoy sombreados, una celda combinada y coloreada por
 * ensayo, leyenda de proyectos, primera columna y cabecera inmovilizadas.
 *
 * Se escribe el formato Office Open XML directamente (sin librerías externas):
 * un libro con una hoja, estilos propios y textos en línea.
 *
 * Entrada (buildGanttMatrix de la interfaz):
 * {"title", "days": [{day, month, cw, we, hol, today}], "rows": [{label, cells: [{c0, c1, text, color, dim}]}],
 *  "legend": [{name, color}]}. Sin "days" se usa "weeks" (vista semanal antigua).
 */
public final class ExcelGantt {

    /** Fichero generado. */
    public record Resultado(byte[] contenido, String nombre) {
    }

    private static final String BORDE = "D9D9D9";
    private static final String CABECERA = "0C1D2C";

    private ExcelGantt() {
    }

    public static Resultado generar(JsonNode p, LocalDate hoy) {
        String titulo = p.path("title").asText("");
        if (titulo.isEmpty()) {
            titulo = "Gantt";
        }
        List<JsonNode> dias = lista(p.path("days"));
        boolean diario = !dias.isEmpty();
        List<JsonNode> columnas = diario ? dias : lista(p.path("weeks"));
        List<JsonNode> filas = lista(p.path("rows"));
        List<JsonNode> leyenda = lista(p.path("legend"));
        int n = columnas.size();

        Hoja hoja = new Hoja();
        Estilos e = hoja.estilos;

        // Fila 1: título combinado.
        hoja.combinar(1, 1, 1, Math.max(2, 1 + n));
        hoja.poner(1, 1, titulo, e.xf(e.fuente(true, CABECERA, 13), 0, 0, Alineacion.IZQUIERDA));

        // Fila 2: meses (se combinan los días seguidos del mismo mes).
        hoja.poner(2, 1, "", e.xf(e.fuente(false, "000000", 11), e.relleno(CABECERA), 0, Alineacion.NINGUNA));
        int estiloMes = e.xf(e.fuente(true, CABECERA, 10), e.relleno("E9EEF2"), 0, Alineacion.CENTRO);
        for (int i = 0; i < n; ) {
            String mes = columnas.get(i).path("month").asText("");
            int tramo = 0;
            while (i + tramo < n && columnas.get(i + tramo).path("month").asText("").equals(mes)) {
                tramo++;
            }
            if (tramo > 1) {
                hoja.combinar(2, 2 + i, 2, 2 + i + tramo - 1);
            }
            hoja.poner(2, 2 + i, mes, estiloMes);
            i += tramo;
        }

        // Fila 3: CW (se combinan las columnas seguidas de la misma semana).
        int estiloCab = e.xf(e.fuente(true, "FFFFFF", 10), e.relleno(CABECERA), 1, Alineacion.CENTRO);
        hoja.poner(3, 1, "Bench / Test", estiloCab);
        for (int i = 0; i < n; ) {
            String cw = columnas.get(i).path("cw").asText("");
            int tramo = 0;
            while (i + tramo < n && columnas.get(i + tramo).path("cw").asText("").equals(cw)) {
                tramo++;
            }
            if (tramo > 1) {
                hoja.combinar(3, 2 + i, 3, 2 + i + tramo - 1);
            }
            hoja.poner(3, 2 + i, "CW" + cw, estiloCab);
            i += tramo;
        }

        // Fila 4 (solo diario): número de día con fin de semana, festivo y hoy.
        int filasCabecera = 3;
        if (diario) {
            filasCabecera = 4;
            hoja.poner(4, 1, "", e.xf(e.fuente(false, "000000", 11), e.relleno(CABECERA), 0, Alineacion.NINGUNA));
            int fuenteDia = e.fuente(false, "FFFFFF", 8);
            for (int d = 0; d < n; d++) {
                JsonNode dia = columnas.get(d);
                int estilo;
                if (dia.path("today").asBoolean(false)) {
                    estilo = e.xf(e.fuente(true, "06231F", 8), e.relleno("23BCA3"), 1, Alineacion.CENTRO);
                } else if (dia.path("hol").asBoolean(false)) {
                    estilo = e.xf(fuenteDia, e.relleno("5A2530"), 1, Alineacion.CENTRO);
                } else if (dia.path("we").asBoolean(false)) {
                    estilo = e.xf(fuenteDia, e.relleno("24384A"), 1, Alineacion.CENTRO);
                } else {
                    estilo = e.xf(fuenteDia, e.relleno(CABECERA), 1, Alineacion.CENTRO);
                }
                hoja.ponerValor(4, 2 + d, dia.get("day"), estilo);
            }
        }

        // Cuerpo: una fila por banco / ensayo / equipo.
        int primera = filasCabecera + 1;
        int estiloEtiqueta = e.xf(e.fuente(true, "1D2D3B", 10), e.relleno("F4F7F9"), 1, Alineacion.IZQUIERDA);
        int estiloRejilla = e.xf(0, 0, 1, Alineacion.NINGUNA);
        int estiloRejillaFinde = e.xf(0, e.relleno("EFF3F6"), 1, Alineacion.NINGUNA);
        for (int r = 0; r < filas.size(); r++) {
            JsonNode fila = filas.get(r);
            int rr = primera + r;
            hoja.ponerValor(rr, 1, fila.get("label"), estiloEtiqueta);
            for (int d = 0; d < n; d++) {
                boolean sombra = diario && (columnas.get(d).path("we").asBoolean(false) || columnas.get(d).path("hol").asBoolean(false));
                hoja.estilo(rr, 2 + d, sombra ? estiloRejillaFinde : estiloRejilla);
            }
            // Cada tramo ocupa columnas libres seguidas (no se pisan dos combinaciones).
            Set<Integer> ocupadas = new HashSet<>();
            for (JsonNode tramo : lista(fila.path("cells"))) {
                int c0 = Math.max(0, tramo.path("c0").asInt(0));
                int c1 = Math.min(n - 1, Math.max(c0, tramo.path("c1").asInt(c0)));
                while (c0 <= c1 && ocupadas.contains(c0)) {
                    c0++;
                }
                if (c0 > c1) {
                    continue;
                }
                int fin = c0;
                while (fin + 1 <= c1 && !ocupadas.contains(fin + 1)) {
                    fin++;
                }
                if (fin > c0) {
                    hoja.combinar(rr, 2 + c0, rr, 2 + fin);
                }
                boolean atenuado = tramo.path("dim").asBoolean(false);
                String color = tramo.path("color").asText("");
                int estilo = e.xf(e.fuente(!atenuado, atenuado ? "111111" : "0B1620", 9),
                        e.relleno(color.isEmpty() ? "#8CA0AD" : color), 1, Alineacion.IZQUIERDA_SIN_AJUSTE);
                hoja.poner(rr, 2 + c0, tramo.path("text").asText(""), estilo);
                for (int c = c0; c <= fin; c++) {
                    ocupadas.add(c);
                }
            }
        }

        // Leyenda de proyectos debajo de la tabla.
        if (!leyenda.isEmpty()) {
            int lr = primera + filas.size() + 1;
            hoja.poner(lr, 1, "Leyenda de proyectos", e.xf(e.fuente(true, CABECERA, 11), 0, 0, Alineacion.NINGUNA));
            int estiloNombre = e.xf(e.fuente(false, "1D2D3B", 10), 0, 0, Alineacion.IZQUIERDA);
            for (int i = 0; i < leyenda.size(); i++) {
                JsonNode l = leyenda.get(i);
                String color = l.path("color").asText("");
                hoja.estilo(lr + 1 + i, 1, e.xf(0, e.relleno(color.isEmpty() ? "#8CA0AD" : color), 1, Alineacion.NINGUNA));
                hoja.ponerValor(lr + 1 + i, 2, l.get("name"), estiloNombre);
            }
        }

        hoja.anchos.put(1, 34.0);
        for (int d = 0; d < n; d++) {
            hoja.anchos.put(2 + d, diario ? 2.9 : 5.4);
        }
        hoja.altoFila1 = 22;
        hoja.filaInmovil = filasCabecera;

        String seguro = titulo.replaceAll("[^A-Za-z0-9]+", "_");
        seguro = seguro.substring(0, Math.min(40, seguro.length())).replaceAll("^_+|_+$", "");
        if (seguro.isEmpty()) {
            seguro = "gantt";
        }
        String nombre = seguro + "_" + hoy.format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        return new Resultado(hoja.libro(), nombre);
    }

    private static List<JsonNode> lista(JsonNode nodo) {
        List<JsonNode> salida = new ArrayList<>();
        if (nodo != null && nodo.isArray()) {
            nodo.forEach(salida::add);
        }
        return salida;
    }

    // ---- escritura del libro -----------------------------------------------

    enum Alineacion {
        NINGUNA(""),
        CENTRO("<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>"),
        IZQUIERDA("<alignment horizontal=\"left\" vertical=\"center\"/>"),
        IZQUIERDA_SIN_AJUSTE("<alignment horizontal=\"left\" vertical=\"center\" wrapText=\"0\"/>");

        final String xml;

        Alineacion(String xml) {
            this.xml = xml;
        }
    }

    /** Fuentes, rellenos y combinaciones de estilo usadas (se reutilizan si se repiten). */
    static final class Estilos {
        private final List<String> fuentes = new ArrayList<>(List.of(
                "<font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"));
        private final List<String> rellenos = new ArrayList<>(List.of(
                "<fill><patternFill patternType=\"none\"/></fill>", "<fill><patternFill patternType=\"gray125\"/></fill>"));
        private final List<String> xfs = new ArrayList<>(List.of(
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"));

        int fuente(boolean negrita, String color, int tamano) {
            return indice(fuentes, "<font>" + (negrita ? "<b/>" : "") + "<sz val=\"" + tamano + "\"/><color rgb=\"FF" + hex(color)
                    + "\"/><name val=\"Calibri\"/><family val=\"2\"/></font>");
        }

        int relleno(String color) {
            return indice(rellenos, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + hex(color)
                    + "\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }

        /** @param borde 0 = sin borde, 1 = fino gris en los cuatro lados */
        int xf(int fuente, int relleno, int borde, Alineacion alineacion) {
            StringBuilder x = new StringBuilder("<xf numFmtId=\"0\" fontId=\"").append(fuente).append("\" fillId=\"").append(relleno)
                    .append("\" borderId=\"").append(borde).append("\" xfId=\"0\"");
            if (fuente != 0) {
                x.append(" applyFont=\"1\"");
            }
            if (relleno != 0) {
                x.append(" applyFill=\"1\"");
            }
            if (borde != 0) {
                x.append(" applyBorder=\"1\"");
            }
            if (alineacion != Alineacion.NINGUNA) {
                x.append(" applyAlignment=\"1\">").append(alineacion.xml).append("</xf>");
            } else {
                x.append("/>");
            }
            return indice(xfs, x.toString());
        }

        String xml() {
            String borde = "<left style=\"thin\"><color rgb=\"FF" + BORDE + "\"/></left><right style=\"thin\"><color rgb=\"FF" + BORDE
                    + "\"/></right><top style=\"thin\"><color rgb=\"FF" + BORDE + "\"/></top><bottom style=\"thin\"><color rgb=\"FF"
                    + BORDE + "\"/></bottom><diagonal/>";
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"" + fuentes.size() + "\">" + String.join("", fuentes) + "</fonts>"
                    + "<fills count=\"" + rellenos.size() + "\">" + String.join("", rellenos) + "</fills>"
                    + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border>" + borde + "</border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"" + xfs.size() + "\">" + String.join("", xfs) + "</cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>";
        }

        private static int indice(List<String> lista, String valor) {
            int i = lista.indexOf(valor);
            if (i >= 0) {
                return i;
            }
            lista.add(valor);
            return lista.size() - 1;
        }

        private static String hex(String color) {
            String h = color == null ? "" : color.trim();
            if (h.startsWith("#")) {
                h = h.substring(1);
            }
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return h.matches("[0-9A-Fa-f]{6}") ? h.toUpperCase() : "8CA0AD";
        }
    }

    /** Una hoja: celdas por fila y columna, combinaciones, anchos e inmovilización. */
    static final class Hoja {
        final Estilos estilos = new Estilos();
        private final TreeMap<Integer, TreeMap<Integer, String[]>> celdas = new TreeMap<>();
        private final List<String> combinaciones = new ArrayList<>();
        final Map<Integer, Double> anchos = new LinkedHashMap<>();
        int altoFila1;
        int filaInmovil;

        private String[] celda(int fila, int col) {
            return celdas.computeIfAbsent(fila, f -> new TreeMap<>()).computeIfAbsent(col, c -> new String[]{null, null, "0"});
        }

        /** Texto en la celda. Un texto vacío deja la celda sin valor (como openpyxl). */
        void poner(int fila, int col, String texto, int estilo) {
            String[] c = celda(fila, col);
            c[2] = String.valueOf(estilo);
            if (!texto.isEmpty()) {
                c[0] = texto;
                c[1] = "s";
            }
        }

        /** Número si el valor es numérico; texto en otro caso; vacío si no hay valor. */
        void ponerValor(int fila, int col, JsonNode valor, int estilo) {
            if (valor == null || valor.isNull()) {
                estilo(fila, col, estilo);
            } else if (valor.isNumber()) {
                String[] c = celda(fila, col);
                c[0] = valor.asText();
                c[1] = "n";
                c[2] = String.valueOf(estilo);
            } else {
                poner(fila, col, valor.asText(), estilo);
            }
        }

        void estilo(int fila, int col, int estilo) {
            celda(fila, col)[2] = String.valueOf(estilo);
        }

        void combinar(int f0, int c0, int f1, int c1) {
            combinaciones.add(ref(f0, c0) + ":" + ref(f1, c1));
        }

        static String columna(int col) {
            StringBuilder s = new StringBuilder();
            for (int c = col; c > 0; c = (c - 1) / 26) {
                s.insert(0, (char) ('A' + (c - 1) % 26));
            }
            return s.toString();
        }

        static String ref(int fila, int col) {
            return columna(col) + fila;
        }

        String hojaXml() {
            StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                    .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
            x.append("<sheetViews><sheetView workbookViewId=\"0\"><pane xSplit=\"1\" ySplit=\"").append(filaInmovil)
                    .append("\" topLeftCell=\"").append(ref(filaInmovil + 1, 2))
                    .append("\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews>");
            x.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
            if (!anchos.isEmpty()) {
                x.append("<cols>");
                anchos.forEach((col, ancho) -> x.append("<col min=\"").append(col).append("\" max=\"").append(col)
                        .append("\" width=\"").append(ancho).append("\" customWidth=\"1\"/>"));
                x.append("</cols>");
            }
            x.append("<sheetData>");
            celdas.forEach((fila, columnas) -> {
                x.append("<row r=\"").append(fila).append('"');
                if (fila == 1 && altoFila1 > 0) {
                    x.append(" ht=\"").append(altoFila1).append("\" customHeight=\"1\"");
                }
                x.append('>');
                columnas.forEach((col, c) -> {
                    x.append("<c r=\"").append(ref(fila, col)).append("\" s=\"").append(c[2]).append('"');
                    if (c[0] == null) {
                        x.append("/>");
                    } else if ("n".equals(c[1])) {
                        x.append("><v>").append(c[0]).append("</v></c>");
                    } else {
                        x.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escapar(c[0])).append("</t></is></c>");
                    }
                });
                x.append("</row>");
            });
            x.append("</sheetData>");
            if (!combinaciones.isEmpty()) {
                x.append("<mergeCells count=\"").append(combinaciones.size()).append("\">");
                combinaciones.forEach(m -> x.append("<mergeCell ref=\"").append(m).append("\"/>"));
                x.append("</mergeCells>");
            }
            x.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
            return x.append("</worksheet>").toString();
        }

        byte[] libro() {
            Map<String, String> partes = new LinkedHashMap<>();
            partes.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            partes.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            partes.put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Gantt\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            partes.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            // La hoja se escribe antes que los estilos: así ya están todos registrados.
            String hoja = hojaXml();
            partes.put("xl/worksheets/sheet1.xml", hoja);
            partes.put("xl/styles.xml", estilos.xml());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, String> parte : partes.entrySet()) {
                    zip.putNextEntry(new ZipEntry(parte.getKey()));
                    zip.write(parte.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return bytes.toByteArray();
        }

        /** Escapa XML y quita los caracteres de control que Excel no admite. */
        static String escapar(String texto) {
            StringBuilder s = new StringBuilder(texto.length());
            texto.codePoints().forEach(cp -> {
                switch (cp) {
                    case '&' -> s.append("&amp;");
                    case '<' -> s.append("&lt;");
                    case '>' -> s.append("&gt;");
                    case '"' -> s.append("&quot;");
                    default -> {
                        if (cp >= 0x20 || cp == '\t' || cp == '\n' || cp == '\r') {
                            s.appendCodePoint(cp);
                        }
                    }
                }
            });
            return s.toString();
        }
    }
}
