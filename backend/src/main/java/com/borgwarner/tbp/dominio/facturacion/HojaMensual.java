package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hoja mensual de facturación, como la tabla dinámica del Excel: filas
 * Billing Order → Planta (Product Line) → Tipo (Group) → Equipo (Equipment
 * Type) → HV → Inicio → Fin → Coste/hora (NIVELES), columnas el Group del
 * Equipment Type y «Total general»; cada celda es la suma del coste total de
 * las tareas.
 *
 * Periodo de facturación de un mes: del día 21 del mes anterior al día 20 del
 * mes, ambos incluidos (febrero 2026 = 21/01/2026 – 20/02/2026).
 * Cada tarea da como mucho una línea por periodo:
 *   - Inicio / Fin: el tramo de la tarea dentro del periodo.
 *   - horas netas: de Arranque Real a Fin Real (días completos) dentro del periodo,
 *     menos la parada (días × 24) repartida en proporción a los días de cada
 *     periodo. Sin Arranque Real se usa Test Start; sin Fin Real, hasta hoy.
 *   - setup: entero en el periodo en que empieza la tarea. En los Equipment Types
 *     por piezas, ese campo son las piezas (como en el Excel).
 *   - tarifa: la del Equipment Type del banco en el año del mes de facturación, With HV o
 *     Without HV según la tarea. Product Line STC → 0 (regla del Excel).
 *   - coste: la regla del Equipment Type; número de proyecto 0 → 0.
 * Las líneas a las que les falta el Equipment Type, el HV o la tarifa salen en
 * la vista previa (marcadas) pero no en la exportación.
 */
public final class HojaMensual {

    public static final List<String> PRODUCT_LINES = List.of("Vigo", "Viana", "Heaters", "eCoolers", "Recuperador", "STC");
    /** Orden de las columnas de la hoja de enero del Excel; los demás grupos van después, por orden alfabético. */
    public static final List<String> GRUPOS_EXCEL = List.of("Shakers", "HVCH (STC)", "HVCH (CTAG)", "Climático", "Caudal", "RACS");
    public static final String PLANTA_SIN_COSTE = "STC";
    public static final String EN_BLANCO = "(en blanco)";
    public static final String SIN_TIPO = "(sin Equipment Type)";
    private static final Pattern NUMERO_PROYECTO = Pattern.compile("^\\s*(\\d+)");
    private static final double HORAS_DIA = 24;

    private HojaMensual() {
    }

    /** Lo que la facturación necesita de una tarea. Fechas inclusive (días completos). */
    public record Entrada(String banco, String ensayoId, String proyecto, String tipoEnsayo, String requester, String productLine,
                          String hv, Double setup, double paradaDias, LocalDate inicio, LocalDate fin) {
        public Entrada {
            proyecto = proyecto == null ? "" : proyecto.trim();
            requester = requester == null ? "" : requester.trim();
            productLine = HojaMensual.productLine(productLine);
            String h = hv == null ? "" : hv.trim().toLowerCase(Locale.ROOT);
            hv = h.equals("yes") || h.equals("no") ? h : "";
        }
    }

    /** Una tarea en un mes. coste null = no se puede calcular (falta algo). */
    /**
     * Una tarea en un periodo de facturación. desde/hasta: el tramo de la tarea dentro del
     * periodo (inclusive), que es lo que se factura y lo que se ve en Inicio y Fin.
     */
    public record Linea(Entrada entrada, LocalDate desde, LocalDate hasta, String orden, String equipmentType, String grupo, String unidad,
                        String regla, double horasNetas, double horasSetup, double cantidad, Double tarifa, Double coste,
                        List<String> faltas, List<String> avisos) {
        public boolean completa() {
            return faltas.isEmpty();
        }
    }

    /** Fila de la tabla: nivel 0.. según los niveles elegidos; «Total general» = número de niveles. */
    public record Fila(int nivel, String etiqueta, Map<String, Double> valores, double total, boolean incompleta) {
    }

    /** niveles: nombres de los niveles de las filas, en orden; la fila «Total general» tiene nivel = niveles.size(). */
    public record Tabla(List<String> columnas, List<String> niveles, List<Fila> filas) {
        public boolean esTotal(Fila f) {
            return f.nivel() >= niveles.size();
        }
    }

    public record Hoja(YearMonth mes, List<Linea> lineas, Tabla vistaPrevia, Tabla exportable) {
    }

    // ---- entrada desde la planificación -------------------------------------------------

    private static String primero(Ensayo e, String... campos) {
        for (String c : campos) {
            String v = e.texto(c);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static LocalDate dia(String texto) {
        if (texto == null || texto.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(texto.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Entrada de una tarea, o null si no se factura: tipos especiales
     * (Maintenance…) y tareas abiertas que aún no han arrancado.
     */
    public static Entrada entrada(String banco, Ensayo e, PlanEnsayo plan, LocalDate hoy) {
        if (e.esTipoEspecial()) {
            return null;
        }
        LocalDate inicio;
        LocalDate fin;
        if (e.cerrado()) {
            inicio = dia(primero(e, "finalTrueStart", "finalActualStart", "histStart", "actualStart"));
            fin = dia(primero(e, "finalTrueEnd", "histEnd", "trueEnd"));
            if (inicio == null && plan != null) {
                inicio = plan.inicio();
            }
            if (fin == null && plan != null) {
                fin = plan.fin();
            }
        } else {
            if (!EstadosEnsayo.arrancado(e, hoy)) {
                return null;
            }
            inicio = dia(EstadosEnsayo.arranqueReal(e));
            if (inicio == null) {
                inicio = plan != null && plan.inicio() != null ? plan.inicio() : dia(EstadosEnsayo.arranqueEfectivo(e, hoy));
            }
            fin = dia(e.texto("trueEnd"));
            if (fin == null) {
                fin = hoy;
            }
        }
        if (inicio == null || fin == null || fin.isBefore(inicio)) {
            return null;
        }
        String setupTexto = e.texto("setupHours");
        Double setup = setupTexto.isEmpty() ? null : ValorJs.numero(e.campo("setupHours"));
        if (setup != null && (setup.isNaN() || setup < 0)) {
            setup = null;
        }
        double parada = ValorJs.numeroOCero(e.campo("downtimeDays"));
        if (Double.isNaN(parada) || parada < 0) {
            parada = 0;
        }
        return new Entrada(banco, e.id(), e.texto("project"), e.tipo(), e.texto("resp"), e.texto("productLine"),
                e.texto("hv"), setup, parada, inicio, fin);
    }

    /** Product Line con la grafía de la lista (sin distinguir mayúsculas); otro texto se deja tal cual. */
    public static String productLine(String texto) {
        String t = texto == null ? "" : texto.trim();
        for (String p : PRODUCT_LINES) {
            if (p.equalsIgnoreCase(t)) {
                return p;
            }
        }
        return t;
    }

    /** Número de proyecto 0 (p. ej. «00000 - STC General Works»): coste 0, como en el Excel. */
    public static boolean proyectoCero(String proyecto) {
        Matcher m = NUMERO_PROYECTO.matcher(proyecto == null ? "" : proyecto);
        return m.find() && m.group(1).chars().allMatch(c -> c == '0');
    }

    static String ordenDe(String proyecto, Map<String, String> ordenes) {
        if (proyecto == null || proyecto.isEmpty() || ordenes == null) {
            return "";
        }
        String o = ordenes.get(proyecto);
        if (o == null) {
            for (Map.Entry<String, String> x : ordenes.entrySet()) {
                if (x.getKey().trim().equalsIgnoreCase(proyecto.trim())) {
                    o = x.getValue();
                    break;
                }
            }
        }
        return o == null ? "" : o.trim();
    }

    // ---- periodo de facturación -----------------------------------------------------------

    /** Día de corte: el periodo de un mes va del día 21 del mes anterior al día 20 del mes, ambos incluidos. */
    public static final int DIA_CORTE = 20;

    /** Primer día del periodo de facturación del mes (p. ej. febrero 2026 → 21/01/2026). */
    public static LocalDate inicioPeriodo(YearMonth mes) {
        return mes.minusMonths(1).atDay(DIA_CORTE + 1);
    }

    /** Último día del periodo de facturación del mes (p. ej. febrero 2026 → 20/02/2026). */
    public static LocalDate finPeriodo(YearMonth mes) {
        return mes.atDay(DIA_CORTE);
    }

    /** Mes de facturación al que pertenece un día (21/01 → febrero; 20/01 → enero). */
    public static YearMonth periodoDe(LocalDate dia) {
        YearMonth m = YearMonth.from(dia);
        return dia.getDayOfMonth() > DIA_CORTE ? m.plusMonths(1) : m;
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ---- una tarea en un mes ------------------------------------------------------------

    /**
     * Línea de la tarea en el mes, o null si no tiene nada que facturar en él
     * (fuera del mes, sin cantidad o regla «No facturable»).
     *
     * @param equipmentType Equipment Type del banco («» si no tiene)
     * @param tipos         Equipment Types por clave (TipoEquipo.clave)
     */
    public static Linea linea(Entrada en, YearMonth mes, String equipmentType, Map<String, TipoEquipo> tipos, Map<String, String> ordenes) {
        LocalDate desde = inicioPeriodo(mes);
        LocalDate hasta = finPeriodo(mes).plusDays(1);
        LocalDate a = en.inicio().isAfter(desde) ? en.inicio() : desde;
        LocalDate finExclusivo = en.fin().plusDays(1);
        LocalDate b = finExclusivo.isBefore(hasta) ? finExclusivo : hasta;
        long diasMes = Math.max(0, ChronoUnit.DAYS.between(a, b));
        if (diasMes == 0) {
            return null;
        }
        long diasTotales = ChronoUnit.DAYS.between(en.inicio(), finExclusivo);
        double paradaMes = en.paradaDias() * HORAS_DIA * diasMes / diasTotales;
        double netas = r2(Math.max(0, diasMes * HORAS_DIA - paradaMes));
        boolean empiezaEnElPeriodo = periodoDe(en.inicio()).equals(mes);
        double setup = empiezaEnElPeriodo && en.setup() != null ? en.setup() : 0;

        String et = equipmentType == null ? "" : equipmentType.trim();
        TipoEquipo tipo = et.isEmpty() ? null : tipos.get(TipoEquipo.clave(et, mes.getYear()));
        List<String> faltas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        if (et.isEmpty()) {
            faltas.add("El banco " + en.banco() + " no tiene Equipment Type");
        } else if (tipo == null) {
            faltas.add("El Equipment Type «" + et + "» no está configurado para " + mes.getYear());
        }
        if (tipo != null && tipo.regla() == Regla.NOT_BILLABLE) {
            return null;
        }
        boolean porPiezas = tipo != null && TipoEquipo.PIEZAS.equals(tipo.unidad());
        double cantidad = porPiezas ? setup : r2(netas + setup);
        if (cantidad == 0) {
            return null;
        }
        boolean stc = PLANTA_SIN_COSTE.equals(en.productLine());
        Double tarifa = null;
        if (stc) {
            tarifa = 0.0;
        } else if (en.hv().isEmpty()) {
            faltas.add("Falta indicar HV en la tarea");
        } else if (tipo != null) {
            tarifa = tipo.tarifa(en.hv());
            if (tarifa == null) {
                faltas.add("«" + tipo.titulo() + "» no tiene tarifa " + ("yes".equals(en.hv()) ? "With HV" : "Without HV") + " en " + mes.getYear());
            }
        }
        String orden = ordenDe(en.proyecto(), ordenes);
        if (en.productLine().isEmpty()) {
            avisos.add("Sin Product Line");
        }
        if (orden.isEmpty()) {
            avisos.add(en.proyecto().isEmpty() ? "Sin proyecto" : "El proyecto no tiene Billing Order");
        }
        if (en.requester().isEmpty()) {
            avisos.add("Sin Responsible");
        }
        Double coste = null;
        if (faltas.isEmpty() && tipo != null && tarifa != null) {
            coste = proyectoCero(en.proyecto()) ? 0.0 : tipo.regla().coste(cantidad, tarifa);
        }
        return new Linea(en, a, b.minusDays(1), orden, tipo != null ? tipo.titulo() : et, tipo != null && !tipo.grupo().isEmpty() ? tipo.grupo() : SIN_TIPO,
                tipo != null ? tipo.unidad() : TipoEquipo.HORAS, tipo != null ? tipo.regla().name() : "",
                netas, setup, cantidad, tarifa, coste, List.copyOf(faltas), List.copyOf(avisos));
    }

    // ---- la hoja --------------------------------------------------------------------------

    public static Hoja calcular(List<Entrada> entradas, YearMonth mes, Map<String, String> tipoPorBanco, List<TipoEquipo> tipos,
                                Map<String, String> ordenes) {
        return calcular(entradas, mes, tipoPorBanco, tipos, ordenes, NIVELES);
    }

    /** Hoja del mes con los niveles de fila elegidos. */
    public static Hoja calcular(List<Entrada> entradas, YearMonth mes, Map<String, String> tipoPorBanco, List<TipoEquipo> tipos,
                                Map<String, String> ordenes, List<Nivel> niveles) {
        Map<String, TipoEquipo> porClave = new HashMap<>();
        for (TipoEquipo t : tipos) {
            porClave.put(t.clave(), t);
        }
        List<Linea> lineas = new ArrayList<>();
        for (Entrada en : entradas) {
            Linea l = linea(en, mes, tipoPorBanco.getOrDefault(en.banco(), ""), porClave, ordenes);
            if (l != null) {
                lineas.add(l);
            }
        }
        lineas.sort(Comparator.comparing((Linea l) -> l.entrada().banco()).thenComparing(Linea::desde));
        return new Hoja(mes, lineas, tabla(lineas, niveles), tabla(lineas.stream().filter(Linea::completa).toList(), niveles));
    }

    private static int posicion(List<String> orden, String valor) {
        int i = orden.indexOf(valor);
        return i < 0 ? orden.size() : i;
    }

    private static boolean enBlanco(String v) {
        return v == null || v.isEmpty() || v.equals(SIN_TIPO);
    }

    /** Textos: los valores conocidos primero (en su orden), luego alfabético y «(en blanco)» al final. */
    private static Comparator<String> ordenTexto(List<String> conocidos) {
        return Comparator.<String>comparingInt(v -> enBlanco(v) ? 2 : (conocidos.contains(v) ? 0 : 1))
                .thenComparingInt(v -> posicion(conocidos, v))
                .thenComparing(v -> v.toLowerCase(Locale.ROOT))
                .thenComparing(v -> v);
    }

    /** Fechas aaaa-mm-dd y números: de menor a mayor, «(en blanco)» al final. */
    private static final Comparator<String> ORDEN_FECHA = Comparator.<String>comparingInt(v -> enBlanco(v) ? 1 : 0).thenComparing(v -> v);
    private static final Comparator<String> ORDEN_NUMERO = Comparator.<String>comparingInt(v -> enBlanco(v) ? 1 : 0)
            .thenComparingDouble(v -> enBlanco(v) ? 0 : Double.parseDouble(v));

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final java.time.format.DateTimeFormatter FECHA_LARGA =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ES);

    /** «miércoles, 5 de agosto de 2026», como la tabla dinámica del Excel. */
    public static String fechaLarga(String iso) {
        return iso == null || iso.isEmpty() ? EN_BLANCO : LocalDate.parse(iso).format(FECHA_LARGA);
    }

    /** 5.78 → «5,78»; 24.0 → «24» (formato General del Excel). */
    public static String numeroEs(String texto) {
        if (texto == null || texto.isEmpty()) {
            return EN_BLANCO;
        }
        java.math.BigDecimal b = new java.math.BigDecimal(texto).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return b.toPlainString().replace('.', ',');
    }

    /** Un nivel de las filas: valor de la línea (vacío = «(en blanco)»), orden y texto que se ve. */
    public record Nivel(String id, String nombre, java.util.function.Function<Linea, String> valor, Comparator<String> orden,
                       java.util.function.UnaryOperator<String> etiqueta) {
    }

    private static String etiquetaTexto(String v) {
        return v == null || v.isEmpty() ? EN_BLANCO : v;
    }

    /**
     * Filas de la tabla dinámica, en el orden de la del Excel: Billing Order →
     * Planta (Product Line) → Tipo (Group) → Equipo (Equipment Type) → HV →
     * Inicio → Fin → Coste/hora. Las columnas siguen siendo los Group.
     */
    public static final List<Nivel> NIVELES = List.of(
            new Nivel("orden", "Billing Order", Linea::orden, ordenTexto(List.of()), HojaMensual::etiquetaTexto),
            new Nivel("planta", "Planta", l -> l.entrada().productLine(), ordenTexto(PRODUCT_LINES), HojaMensual::etiquetaTexto),
            new Nivel("tipo", "Tipo", Linea::grupo, ordenTexto(GRUPOS_EXCEL), HojaMensual::etiquetaTexto),
            new Nivel("equipo", "Equipo", Linea::equipmentType, ordenTexto(List.of()), HojaMensual::etiquetaTexto),
            new Nivel("hv", "HV", l -> "yes".equals(l.entrada().hv()) ? "Sí" : ("no".equals(l.entrada().hv()) ? "No" : ""), ordenTexto(List.of()),
                    HojaMensual::etiquetaTexto),
            new Nivel("inicio", "Inicio", l -> Fechas.iso(l.desde()), ORDEN_FECHA, HojaMensual::fechaLarga),
            new Nivel("fin", "Fin", l -> Fechas.iso(l.hasta()), ORDEN_FECHA, HojaMensual::fechaLarga),
            new Nivel("tarifa", "Coste/hora", l -> l.tarifa() == null ? "" : java.math.BigDecimal.valueOf(l.tarifa()).toPlainString(), ORDEN_NUMERO,
                    HojaMensual::numeroEs));

    /**
     * Niveles elegidos por su id, siempre en el orden de NIVELES (null = todos;
     * lista vacía = solo «Total general»).
     */
    public static List<Nivel> niveles(java.util.Collection<String> ids) {
        if (ids == null) {
            return NIVELES;
        }
        Set<String> pedidos = new java.util.HashSet<>();
        for (String id : ids) {
            String limpio = id == null ? "" : id.trim();
            if (limpio.isEmpty()) {
                continue;
            }
            if (NIVELES.stream().noneMatch(n -> n.id().equals(limpio))) {
                throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("Campo de fila desconocido: " + limpio);
            }
            pedidos.add(limpio);
        }
        return NIVELES.stream().filter(n -> pedidos.contains(n.id())).toList();
    }

    private static final class Nodo {
        final Map<String, Double> valores = new LinkedHashMap<>();
        double total;
        boolean incompleta;
        final TreeMap<String, Nodo> hijos;

        Nodo(Comparator<String> orden) {
            hijos = orden == null ? null : new TreeMap<>(orden);
        }

        void sumar(Linea l) {
            double c = l.coste() == null ? 0 : l.coste();
            valores.merge(l.grupo(), c, Double::sum);
            total += c;
            incompleta |= !l.completa();
        }
    }

    /** Tabla dinámica de unas líneas con todos los niveles. */
    public static Tabla tabla(List<Linea> lineas) {
        return tabla(lineas, NIVELES);
    }

    /** Tabla dinámica de unas líneas con los niveles indicados (en ese orden). */
    public static Tabla tabla(List<Linea> lineas, List<Nivel> niveles) {
        Nodo raiz = new Nodo(niveles.isEmpty() ? null : niveles.get(0).orden());
        List<String> columnas = new ArrayList<>();
        for (Linea l : lineas) {
            if (!columnas.contains(l.grupo())) {
                columnas.add(l.grupo());
            }
            raiz.sumar(l);
            Nodo n = raiz;
            for (int i = 0; i < niveles.size(); i++) {
                String v = niveles.get(i).valor().apply(l);
                Comparator<String> siguiente = i + 1 < niveles.size() ? niveles.get(i + 1).orden() : null;
                n = n.hijos.computeIfAbsent(v == null ? "" : v, k -> new Nodo(siguiente));
                n.sumar(l);
            }
        }
        columnas.sort(ordenTexto(GRUPOS_EXCEL));
        List<Fila> filas = new ArrayList<>();
        recorrer(raiz, 0, niveles, filas);
        if (!lineas.isEmpty()) {
            filas.add(fila(niveles.size(), "Total general", raiz));
        }
        return new Tabla(List.copyOf(columnas), niveles.stream().map(Nivel::nombre).toList(), List.copyOf(filas));
    }

    private static void recorrer(Nodo n, int nivel, List<Nivel> niveles, List<Fila> filas) {
        if (n.hijos == null) {
            return;
        }
        n.hijos.forEach((valor, hijo) -> {
            filas.add(fila(nivel, niveles.get(nivel).etiqueta().apply(valor), hijo));
            recorrer(hijo, nivel + 1, niveles, filas);
        });
    }

    private static Fila fila(int nivel, String etiqueta, Nodo n) {
        return new Fila(nivel, etiqueta, Map.copyOf(n.valores), n.total, n.incompleta);
    }

    // ---- JSON de la API -------------------------------------------------------------------

    public static ObjectNode tablaJson(Tabla t) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        ArrayNode cols = n.putArray("columnas");
        t.columnas().forEach(cols::add);
        ArrayNode niveles = n.putArray("niveles");
        t.niveles().forEach(niveles::add);
        ArrayNode filas = n.putArray("filas");
        for (Fila f : t.filas()) {
            ObjectNode o = filas.addObject();
            o.put("nivel", f.nivel());
            o.put("etiqueta", f.etiqueta());
            ObjectNode v = o.putObject("valores");
            for (String c : t.columnas()) {
                Double x = f.valores().get(c);
                if (x != null) {
                    v.put(c, x);
                }
            }
            o.put("total", f.total());
            o.put("incompleta", f.incompleta());
        }
        return n;
    }

    public static ObjectNode json(Hoja h) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("mes", h.mes().toString());
        n.put("periodoInicio", Fechas.iso(inicioPeriodo(h.mes())));
        n.put("periodoFin", Fechas.iso(finPeriodo(h.mes())));
        n.set("vistaPrevia", tablaJson(h.vistaPrevia()));
        n.set("exportable", tablaJson(h.exportable()));
        ArrayNode lineas = n.putArray("lineas");
        int incompletas = 0;
        for (Linea l : h.lineas()) {
            Entrada e = l.entrada();
            ObjectNode o = lineas.addObject();
            o.put("banco", e.banco());
            o.put("ensayoId", e.ensayoId());
            o.put("proyecto", e.proyecto());
            o.put("tipoEnsayo", e.tipoEnsayo());
            o.put("requester", e.requester());
            o.put("productLine", e.productLine());
            o.put("orden", l.orden());
            o.put("equipmentType", l.equipmentType());
            o.put("grupo", l.grupo());
            o.put("hv", e.hv());
            o.put("inicio", Fechas.iso(l.desde()));
            o.put("fin", Fechas.iso(l.hasta()));
            o.put("inicioTarea", Fechas.iso(e.inicio()));
            o.put("finTarea", Fechas.iso(e.fin()));
            o.put("unidad", l.unidad());
            o.put("regla", l.regla());
            o.put("horasNetas", l.horasNetas());
            o.put("horasSetup", l.horasSetup());
            o.put("cantidad", l.cantidad());
            if (l.tarifa() == null) {
                o.putNull("tarifa");
            } else {
                o.put("tarifa", l.tarifa());
            }
            if (l.coste() == null) {
                o.putNull("coste");
            } else {
                o.put("coste", l.coste());
            }
            o.put("completa", l.completa());
            ArrayNode f = o.putArray("faltas");
            l.faltas().forEach(f::add);
            ArrayNode a = o.putArray("avisos");
            l.avisos().forEach(a::add);
            if (!l.completa()) {
                incompletas++;
            }
        }
        n.put("incompletas", incompletas);
        return n;
    }
}
