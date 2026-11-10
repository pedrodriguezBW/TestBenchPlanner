package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/**
 * Maestros de facturación (proyectos y órdenes, categorías, mapeo de bancos,
 * tarifas versionadas, reglas, setup recomendado y parámetros de Controlling) y
 * las búsquedas que hace el motor sobre ellos.
 */
public final class Maestros {

    /** Reglas de cálculo implementadas: el Admin las asigna, no se escriben fórmulas. */
    public record Regla(String codigo, String nombre, String descripcion, int divisor, String unidad) {
    }

    public static final List<Regla> REGLAS = List.of(
            new Regla("LINEAR", "Lineal", "Coste = horas totales × tarifa", 0, "H"),
            new Regla("ASINH_100", "Reductora /100", "Coste = tarifa × ASINH(horas totales / 100) × 100", 100, "H"),
            new Regla("ASINH_200", "Reductora /200", "Coste = tarifa × ASINH(horas totales / 200) × 200", 200, "H"),
            new Regla("PER_PIECE", "Por pieza", "Coste = piezas × tarifa (RACS)", 0, "PIEZAS"),
            new Regla("NOT_BILLABLE", "No facturable", "La línea se excluye: no suma horas ni importe", 0, "H"));

    /** Motivos de parada: los motivos de retraso de la aplicación más «Festivo». */
    public static final List<String> MOTIVOS_PARADA = List.of("Averia", "Falta de recursos", "Piezas con retraso",
            "Caida de ensayo", "Cambio de prioridad", "Festivo", "Otro");

    private Maestros() {
    }

    public static boolean reglaValida(String codigo) {
        return REGLAS.stream().anyMatch(r -> r.codigo().equals(codigo));
    }

    public static ArrayNode reglasJson() {
        ArrayNode salida = Nodos.lista();
        for (Regla r : REGLAS) {
            salida.addObject().put("codigo", r.codigo()).put("nombre", r.nombre()).put("descripcion", r.descripcion())
                    .put("divisor", r.divisor()).put("unidad", r.unidad());
        }
        return salida;
    }

    /** Coste de una línea según su regla (lógica del Excel «Coste total»); null si falta tarifa o regla. */
    public static Double costePorRegla(String regla, Double cantidad, Double tarifa) {
        if (tarifa == null || cantidad == null || Double.isNaN(cantidad) || Double.isInfinite(cantidad)) {
            return null;
        }
        double c = cantidad;
        double t = tarifa;
        return switch (regla == null ? "" : regla) {
            case "LINEAR", "PER_PIECE" -> c * t;
            case "ASINH_100" -> c == 0 ? 0.0 : t * asinh(c / 100) * 100;
            case "ASINH_200" -> c == 0 ? 0.0 : t * asinh(c / 200) * 200;
            case "NOT_BILLABLE" -> 0.0;
            default -> null;
        };
    }

    /** Math.asinh de JavaScript. */
    static double asinh(double x) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        double a = Math.abs(x);
        double r = Math.log(a + Math.sqrt(a * a + 1));
        return x < 0 ? -r : r;
    }

    // ---- proyectos -----------------------------------------------------------------

    /** Número canónico: sin ceros a la izquierda y 8000xxxx → 8xxxx (80000885 → 80885, como en las órdenes). */
    public static String numeroCanonico(String n) {
        String s = (n == null ? "" : n).replaceAll("\\D", "");
        if (s.isEmpty()) {
            return "";
        }
        s = s.replaceFirst("^0+", "");
        if (s.isEmpty()) {
            s = "0";
        }
        if (s.length() == 8 && s.startsWith("8000")) {
            s = "8" + s.substring(4);
        }
        return s;
    }

    /** «11364 - BMW - …» → número canónico y nombre. */
    public static String[] proyectoDeTexto(String texto) {
        String t = texto == null ? "" : texto.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\s*(?:-\\s*(.*))?$").matcher(t);
        if (!m.matches()) {
            return new String[] {"", t};
        }
        return new String[] {numeroCanonico(m.group(1)), m.group(2) == null ? "" : m.group(2).trim()};
    }

    private static boolean activo(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return !(v != null && v.isBoolean() && !v.booleanValue());
    }

    public static ObjectNode buscarProyecto(JsonNode maestros, String numero) {
        for (ObjectNode p : Nodos.objetos(maestros.get("proyectos"))) {
            if (numeroCanonico(Nodos.texto(p, "numero")).equals(numero) && activo(p, "activo")) {
                return p;
            }
        }
        return null;
    }

    public static ObjectNode buscarCategoria(JsonNode maestros, String codigo) {
        for (ObjectNode c : Nodos.objetos(maestros.get("categorias"))) {
            if (Nodos.texto(c, "codigo").equals(codigo) && activo(c, "activa")) {
                return c;
            }
        }
        return null;
    }

    /** Mapeo banco + tipo de ensayo → categoría (el tipo concreto gana al genérico). */
    public static ObjectNode buscarMapeo(JsonNode maestros, String banco, String tipoEnsayo) {
        String tipo = (tipoEnsayo == null ? "" : tipoEnsayo).trim().toLowerCase(Locale.ROOT);
        ObjectNode generico = null;
        for (ObjectNode m : Nodos.objetos(maestros.get("mapeos"))) {
            if (!activo(m, "activo") || !Nodos.texto(m, "banco").equals(banco)) {
                continue;
            }
            String tipoMapeo = Nodos.texto(m, "tipoEnsayo");
            if (!tipoMapeo.isEmpty() && tipoMapeo.trim().toLowerCase(Locale.ROOT).equals(tipo)) {
                return m;
            }
            if (tipoMapeo.isEmpty() && generico == null) {
                generico = m;
            }
        }
        return generico;
    }

    public static boolean vigente(JsonNode t, String dia) {
        String desde = Nodos.texto(t, "desde");
        String hasta = Nodos.texto(t, "hasta");
        return (desde.isEmpty() || desde.compareTo(dia) <= 0) && (hasta.isEmpty() || hasta.compareTo(dia) >= 0);
    }

    /** Lo que define la tarifa de una línea. */
    public record ConsultaTarifa(String categoria, String hv, String proyecto, String planta, String lineaProducto, String dia) {
    }

    /**
     * Tarifa vigente el último día del periodo de facturación. Jerarquía:
     * 1) proyecto o cliente, 2) planta (BW/CTAG) o línea de producto, 3) general de
     * la categoría y la condición HV. En cada nivel gana la categoría exacta sobre
     * «*», el HV exacto sobre «*» y la vigencia más reciente. null si no hay ninguna.
     */
    public static ObjectNode resolverTarifa(JsonNode maestros, ConsultaTarifa q) {
        ObjectNode mejor = null;
        long[] claveMejor = null;
        int i = -1;
        for (ObjectNode t : Nodos.objetos(maestros.get("tarifas"))) {
            i++;
            String categoria = Nodos.texto(t, "categoria");
            String hv = Nodos.texto(t, "hv");
            JsonNode importe = t.get("importe");
            if (!(categoria.equals(q.categoria()) || categoria.equals("*"))) {
                continue;
            }
            if (!(hv.equals(q.hv()) || hv.equals("*") || hv.isEmpty())) {
                continue;
            }
            if (!vigente(t, q.dia()) || Nodos.nulo(importe) || (importe.isTextual() && importe.textValue().isEmpty())) {
                continue;
            }
            double valor = ValorJs.numero(importe);
            if (Double.isNaN(valor) || Double.isInfinite(valor)) {
                continue;
            }
            int nivel = nivel(t, q);
            if (nivel == 0) {
                continue;
            }
            Long desde = Tiempo.aMin(Nodos.texto(t, "desde"));
            long[] clave = {nivel, categoria.equals("*") ? 1 : 0, (hv.equals("*") || hv.isEmpty()) ? 1 : 0,
                    desde == null ? 0 : -desde, i};
            if (claveMejor == null || comparar(clave, claveMejor) < 0) {
                mejor = t.deepCopy();
                mejor.put("nivel", nivel);
                claveMejor = clave;
            }
        }
        return mejor;
    }

    private static int nivel(JsonNode t, ConsultaTarifa q) {
        String proyecto = Nodos.texto(t, "proyecto");
        if (!proyecto.isEmpty()) {
            return numeroCanonico(proyecto).equals(q.proyecto()) ? 1 : 0;
        }
        String planta = Nodos.texto(t, "planta");
        String linea = Nodos.texto(t, "lineaProducto");
        if (!planta.isEmpty() || !linea.isEmpty()) {
            boolean okPlanta = planta.isEmpty() || planta.equals(q.planta());
            boolean okLinea = linea.isEmpty() || linea.toLowerCase(Locale.ROOT)
                    .equals((q.lineaProducto() == null ? "" : q.lineaProducto()).toLowerCase(Locale.ROOT));
            return okPlanta && okLinea ? 2 : 0;
        }
        return 3;
    }

    private static int comparar(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /** Setup recomendado por categoría, banco y tipo de ensayo (el más concreto); null si no hay. */
    public static Double setupRecomendado(JsonNode maestros, String categoria, String banco, String tipoEnsayo) {
        String tipo = (tipoEnsayo == null ? "" : tipoEnsayo).trim().toLowerCase(Locale.ROOT);
        ObjectNode mejor = null;
        int puntos = -1;
        for (ObjectNode s : Nodos.objetos(maestros.get("setupRecomendado"))) {
            String sb = Nodos.texto(s, "banco");
            String sc = Nodos.texto(s, "categoria");
            String st = Nodos.texto(s, "tipoEnsayo");
            if (!sb.isEmpty() && !sb.equals(banco)) {
                continue;
            }
            if (!sc.isEmpty() && !sc.equals(categoria)) {
                continue;
            }
            if (!st.isEmpty() && !st.trim().toLowerCase(Locale.ROOT).equals(tipo)) {
                continue;
            }
            int p = (sb.isEmpty() ? 0 : 4) + (st.isEmpty() ? 0 : 2) + (sc.isEmpty() ? 0 : 1);
            if (p > puntos) {
                mejor = s;
                puntos = p;
            }
        }
        return mejor == null ? null : ValorJs.numero(mejor.get("horas"));
    }

    /** Órdenes activas de un proyecto (códigos recortados). */
    public static List<String> ordenesActivas(JsonNode proyecto) {
        List<String> salida = new java.util.ArrayList<>();
        for (ObjectNode o : Nodos.objetos(proyecto.get("ordenes"))) {
            if (activo(o, "activa")) {
                salida.add(Nodos.texto(o, "codigo").trim());
            }
        }
        return salida;
    }
}
