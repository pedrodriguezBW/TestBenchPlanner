package com.borgwarner.tbp.infraestructura.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Libro de Excel sencillo escrito en Office Open XML directamente (sin
 * librerías), como ExcelGantt. Réplica de la parte de libro de
 * herramientas/laboratorio/facturacion/exportaciones.cjs.
 *
 * Estilos: 0 normal · 1 cabecera · 2 importe · 3 importe en negrita · 4 fecha ·
 * 5 horas · 6 título · 7 negrita · 8 mes (mmm-yy) · 9 horas en negrita.
 */
public final class LibroExcel {

    public static final int NORMAL = 0;
    public static final int CABECERA = 1;
    public static final int IMPORTE = 2;
    public static final int IMPORTE_NEGRITA = 3;
    public static final int FECHA = 4;
    public static final int HORAS = 5;
    public static final int TITULO = 6;
    public static final int NEGRITA = 7;
    public static final int MES = 8;
    public static final int HORAS_NEGRITA = 9;

    private static final String ESTILOS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"3\">"
            + "<numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy\"/>"
            + "<numFmt numFmtId=\"166\" formatCode=\"mmm\\-yy\"/></numFmts><fonts count=\"4\"><font><sz val=\"10\"/><name val=\"Arial\"/></font>"
            + "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font>"
            + "<font><b/><sz val=\"14\"/><color rgb=\"FF0C1D2C\"/><name val=\"Arial\"/></font>"
            + "<font><b/><sz val=\"10\"/><name val=\"Arial\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF0C1D2C\"/>"
            + "<bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFD9D9D9\"/></left><right style=\"thin\"><color rgb=\"FFD9D9D9\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFD9D9D9\"/></top><bottom style=\"thin\"><color rgb=\"FFD9D9D9\"/></bottom><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"10\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment wrapText=\"1\" vertical=\"center\"/></xf>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"2\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

    /** Celda: valor (texto o número; null = vacía) y estilo. */
    public record Celda(Object valor, int estilo) {
    }

    public static Celda c(Object valor, int estilo) {
        return new Celda(valor, estilo);
    }

    public static Celda c(Object valor) {
        return new Celda(valor, NORMAL);
    }

    /** Una hoja: filas de celdas, anchos de columna y filas inmovilizadas. */
    public static final class Hoja {
        final String nombre;
        final List<List<Celda>> filas = new ArrayList<>();
        double[] anchos = new double[0];
        int fijarFila;

        public Hoja(String nombre) {
            this.nombre = nombre;
        }

        public Hoja anchos(double... anchos) {
            this.anchos = anchos;
            return this;
        }

        public Hoja fijar(int filas) {
            this.fijarFila = filas;
            return this;
        }

        public Hoja fila(Celda... celdas) {
            filas.add(new ArrayList<>(Arrays.asList(celdas)));
            return this;
        }

        public Hoja fila(List<Celda> celdas) {
            filas.add(new ArrayList<>(celdas));
            return this;
        }

        public Hoja vacia() {
            filas.add(new ArrayList<>());
            return this;
        }

        String xml() {
            StringBuilder s = new StringBuilder();
            s.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            if (fijarFila > 0) {
                s.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(fijarFila).append("\" topLeftCell=\"A")
                        .append(fijarFila + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
            } else {
                s.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>");
            }
            s.append("<sheetFormatPr defaultRowHeight=\"13.2\"/>");
            if (anchos.length > 0) {
                s.append("<cols>");
                for (int i = 0; i < anchos.length; i++) {
                    s.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                            .append(ValorJs.numeroComoTexto(anchos[i])).append("\" customWidth=\"1\"/>");
                }
                s.append("</cols>");
            }
            s.append("<sheetData>");
            for (int i = 0; i < filas.size(); i++) {
                s.append("<row r=\"").append(i + 1).append("\">");
                List<Celda> celdas = filas.get(i);
                for (int j = 0; j < celdas.size(); j++) {
                    Celda c = celdas.get(j);
                    if (c == null) {
                        continue;
                    }
                    String ref = columna(j) + (i + 1);
                    Object v = c.valor();
                    if (v == null || (v instanceof String t && t.isEmpty())) {
                        if (c.estilo() != 0) {
                            s.append("<c r=\"").append(ref).append("\" s=\"").append(c.estilo()).append("\"/>");
                        }
                        continue;
                    }
                    if (v instanceof Number n && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
                        s.append("<c r=\"").append(ref).append("\" s=\"").append(c.estilo()).append("\"><v>")
                                .append(ValorJs.numeroComoTexto(n.doubleValue())).append("</v></c>");
                    } else {
                        s.append("<c r=\"").append(ref).append("\" s=\"").append(c.estilo()).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                .append(escapar(String.valueOf(v))).append("</t></is></c>");
                    }
                }
                s.append("</row>");
            }
            s.append("</sheetData></worksheet>");
            return s.toString();
        }
    }

    private LibroExcel() {
    }

    static String columna(int c) {
        StringBuilder s = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            s.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    public static String escapar(String t) {
        StringBuilder s = new StringBuilder();
        for (char ch : t.toCharArray()) {
            switch (ch) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        s.append(ch);
                    }
                }
            }
        }
        return s.toString();
    }

    /** El .xlsx con las hojas indicadas. */
    public static byte[] libro(List<Hoja> hojas) {
        StringBuilder tipos = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder libro = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < hojas.size(); i++) {
            int n = i + 1;
            tipos.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            libro.append("<sheet name=\"").append(escapar(hojas.get(i).nombre)).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(n)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(n).append(".xml\"/>");
        }
        tipos.append("</Types>");
        libro.append("</sheets></workbook>");
        rels.append("<Relationship Id=\"rId").append(hojas.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida, StandardCharsets.UTF_8)) {
            escribir(zip, "[Content_Types].xml", tipos.toString());
            escribir(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            escribir(zip, "xl/workbook.xml", libro.toString());
            escribir(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            escribir(zip, "xl/styles.xml", ESTILOS);
            for (int i = 0; i < hojas.size(); i++) {
                escribir(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", hojas.get(i).xml());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return salida.toByteArray();
    }

    private static void escribir(ZipOutputStream zip, String nombre, String texto) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(texto.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
