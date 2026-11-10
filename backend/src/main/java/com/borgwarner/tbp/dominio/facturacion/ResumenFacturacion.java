package com.borgwarner.tbp.dominio.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Resúmenes de un periodo: indicadores, resúmenes por proyecto y orden, por
 * equipo (categoría), por planta y por línea de producto, desgloses, hoja de
 * Controlling e incidencias. Es lo que muestra el overview y lo que exportan el
 * Excel y el HTML: una sola implementación para las tres salidas.
 */
public final class ResumenFacturacion {

    private static final List<String> ORDEN_GRUPOS = List.of("Shakers", "HVCH (STC)", "HVCH (CTAG)", "Climático", "RACS");

    /** Clave de agrupación de una línea. */
    record Clave(String id, String etiqueta) {
    }

    static final Map<String, Function<JsonNode, Clave>> DIMENSIONES = new LinkedHashMap<>();

    static {
        DIMENSIONES.put("proyecto", l -> {
            JsonNode a = l.get("aplicado");
            String p = Nodos.texto(a, "proyecto");
            String n = Nodos.texto(a, "proyectoNombre");
            return new Clave(p, (p.isEmpty() ? "—" : p) + (n.isEmpty() ? "" : " · " + n));
        });
        DIMENSIONES.put("orden", l -> simple(Nodos.texto(l.get("aplicado"), "orden")));
        DIMENSIONES.put("banco", l -> new Clave(Nodos.texto(l, "banco"), Nodos.texto(l, "banco")));
        DIMENSIONES.put("categoria", l -> simple(Nodos.texto(l.get("aplicado"), "categoria")));
        DIMENSIONES.put("tipoEnsayo", l -> simple(Nodos.texto(l, "tipoEnsayo")));
        DIMENSIONES.put("planta", l -> simple(Nodos.texto(l, "organizacion")));
        DIMENSIONES.put("lineaProducto", l -> simple(Nodos.texto(l.get("aplicado"), "lineaProducto")));
        DIMENSIONES.put("requester", l -> simple(Nodos.texto(l.get("aplicado"), "requester")));
        DIMENSIONES.put("hv", l -> {
            String hv = Nodos.texto(l.get("aplicado"), "hv");
            return new Clave(hv, hv.equals("yes") ? "Alta tensión: Sí" : hv.equals("no") ? "Alta tensión: No" : "—");
        });
    }

    private static Clave simple(String v) {
        return new Clave(v, v.isEmpty() ? "—" : v);
    }

    private ResumenFacturacion() {
    }

    private static double num(JsonNode n, String campo) {
        Double d = Nodos.numero(n, campo);
        return d == null ? 0 : d;
    }

    /** Grupo en construcción. */
    static final class Grupo {
        final ObjectNode datos;
        final Set<String> ensayos = new HashSet<>();
        final ArrayNode lineas = Nodos.lista();
        double horas;
        double setup;
        double horasTotales;
        double piezas;
        double coste;
        final TreeSet<Double> tarifas = new TreeSet<>();
        final TreeSet<String> bancos = new TreeSet<>();

        Grupo(ObjectNode datos) {
            this.datos = datos;
        }

        void sumar(JsonNode l) {
            JsonNode a = l.get("aplicado");
            ensayos.add(Nodos.texto(l, "ensayoId"));
            lineas.add(Nodos.texto(l, "id"));
            horas += num(a, "horasNetas");
            setup += num(a, "horasSetup");
            horasTotales += num(a, "horasTotales");
            piezas += "PIEZAS".equals(Nodos.texto(a, "unidad")) ? num(a, "piezas") : 0;
            coste += num(a, "coste");
        }

        ObjectNode json() {
            ObjectNode o = datos.deepCopy();
            o.put("ensayos", ensayos.size());
            o.set("lineas", lineas);
            o.set("horas", Nodos.num(horas));
            o.set("setup", Nodos.num(setup));
            o.set("horasTotales", Nodos.num(horasTotales));
            o.set("piezas", Nodos.num(piezas));
            o.set("coste", Nodos.num(coste));
            return o;
        }

        String clave() {
            return Nodos.texto(datos, "clave");
        }
    }

    private static List<Grupo> agrupar(List<JsonNode> lineas, Function<JsonNode, Clave> clave, Function<JsonNode, ObjectNode> datos) {
        Map<String, Grupo> grupos = new LinkedHashMap<>();
        for (JsonNode l : lineas) {
            Clave k = clave.apply(l);
            Grupo g = grupos.get(k.id());
            if (g == null) {
                ObjectNode d = Nodos.objeto();
                d.put("clave", k.id()).put("etiqueta", k.etiqueta());
                if (datos != null) {
                    d.setAll(datos.apply(l));
                }
                g = new Grupo(d);
                grupos.put(k.id(), g);
            }
            g.sumar(l);
            JsonNode a = l.get("aplicado");
            Double t = Nodos.numero(a, "tarifa");
            if (t != null) {
                g.tarifas.add(t);
            }
            g.bancos.add(Nodos.texto(l, "banco"));
        }
        return new ArrayList<>(grupos.values());
    }

    private static Map<String, Integer> ordenCategorias(JsonNode maestros) {
        Map<String, Integer> orden = new HashMap<>();
        for (JsonNode c : Nodos.arr(maestros, "categorias")) {
            Double n = Nodos.numero(c, "ordenControlling");
            orden.put(Nodos.texto(c, "codigo"), n == null || n == 0 || Double.isNaN(n) ? 999 : n.intValue());
        }
        return orden;
    }

    private static int ordenGrupo(String g) {
        int i = ORDEN_GRUPOS.indexOf(g);
        return i >= 0 ? i : 50;
    }

    /** Todo lo que muestra el overview y lo que exportan el Excel y el HTML. */
    public static ObjectNode resumen(JsonNode doc, JsonNode maestros) {
        List<JsonNode> ls = new ArrayList<>();
        Nodos.arr(doc, "lineas").forEach(ls::add);
        List<JsonNode> contadas = new ArrayList<>();
        for (JsonNode l : ls) {
            if (MotorFacturacion.cuenta(l)) {
                contadas.add(l);
            }
        }
        Map<String, Integer> ordenCat = ordenCategorias(maestros);
        ObjectNode salida = Nodos.objeto();

        ObjectNode ind = salida.putObject("indicadores");
        double coste = 0;
        double horasNetas = 0;
        double horasSetup = 0;
        double horasTotales = 0;
        for (JsonNode l : contadas) {
            JsonNode a = l.get("aplicado");
            coste += num(a, "coste");
            horasNetas += num(a, "horasNetas");
            horasSetup += num(a, "horasSetup");
            horasTotales += num(a, "horasTotales");
        }
        Set<String> ensayos = new HashSet<>();
        Map<String, Integer> porEstado = new LinkedHashMap<>();
        int ajustadas = 0;
        int ajustesPendientes = 0;
        for (JsonNode l : ls) {
            ensayos.add(Nodos.texto(l, "ensayoId"));
            porEstado.merge(Nodos.texto(l, "estado"), 1, Integer::sum);
            ObjectNode ajustes = Nodos.obj(l, "ajustes");
            if (ajustes != null && ajustes.size() > 0) {
                ajustadas++;
            }
            boolean pendiente = false;
            ObjectNode prop = Nodos.obj(l, "propuestaTarifa");
            if (prop != null && "PENDIENTE".equals(Nodos.texto(prop, "estado"))) {
                pendiente = true;
            }
            if (ajustes != null) {
                for (JsonNode aj : ajustes) {
                    if (Nodos.cierto(aj, "economico") && !Nodos.cierto(aj, "validado")) {
                        pendiente = true;
                    }
                }
            }
            if (pendiente) {
                ajustesPendientes++;
            }
        }
        ind.set("coste", Nodos.num(coste));
        ind.set("horasNetas", Nodos.num(horasNetas));
        ind.set("horasSetup", Nodos.num(horasSetup));
        ind.set("horasTotales", Nodos.num(horasTotales));
        ind.put("ensayos", ensayos.size());
        ind.put("lineas", ls.size());
        ind.put("lineasContadas", contadas.size());
        ind.put("preparadas", porEstado.getOrDefault("READY", 0) + porEstado.getOrDefault("ADJUSTED", 0));
        ind.put("bloqueadas", porEstado.getOrDefault("BLOCKED", 0));
        ind.put("advertencias", porEstado.getOrDefault("WARNING", 0));
        ind.put("excluidas", porEstado.getOrDefault("EXCLUDED", 0));
        ind.put("aplazadas", porEstado.getOrDefault("DEFERRED", 0));
        ind.put("pendientes", porEstado.getOrDefault("PENDING", 0));
        ind.put("ajustadas", ajustadas);
        ind.put("ajustesPendientes", ajustesPendientes);
        ObjectNode pe = ind.putObject("porEstado");
        porEstado.forEach(pe::put);

        // Proyecto y orden.
        List<Grupo> ppo = agrupar(contadas, l -> {
            JsonNode a = l.get("aplicado");
            return new Clave(Nodos.texto(a, "proyecto") + "|" + Nodos.texto(a, "orden"), Nodos.texto(a, "proyecto"));
        }, l -> {
            JsonNode a = l.get("aplicado");
            ObjectNode d = Nodos.objeto();
            d.put("proyecto", Nodos.texto(a, "proyecto")).put("proyectoNombre", Nodos.texto(a, "proyectoNombre")).put("orden", Nodos.texto(a, "orden"));
            return d;
        });
        ppo.sort(Comparator.comparing((Grupo g) -> Nodos.texto(g.datos, "proyecto")).thenComparing(g -> Nodos.texto(g.datos, "orden")));
        ArrayNode porProyectoOrden = salida.putArray("porProyectoOrden");
        ppo.forEach(g -> porProyectoOrden.add(g.json()));

        // Equipo (categoría).
        List<Grupo> eq = agrupar(contadas, l -> {
            String c = Nodos.texto(l.get("aplicado"), "categoria");
            return new Clave(c, c);
        }, l -> {
            JsonNode a = l.get("aplicado");
            ObjectNode d = Nodos.objeto();
            d.put("categoria", Nodos.texto(a, "categoria")).put("grupo", Nodos.texto(a, "grupo")).put("regla", Nodos.texto(a, "regla"))
                    .put("unidad", Nodos.texto(a, "unidad"));
            return d;
        });
        eq.sort(Comparator.comparing((Grupo g) -> ordenCat.getOrDefault(Nodos.texto(g.datos, "categoria"), 999))
                .thenComparing(g -> Nodos.texto(g.datos, "categoria")));
        ArrayNode porEquipo = salida.putArray("porEquipo");
        for (Grupo g : eq) {
            ObjectNode o = g.json();
            ArrayNode tarifas = o.putArray("tarifas");
            g.tarifas.forEach(t -> tarifas.add(Nodos.num(t)));
            ArrayNode bancos = o.putArray("bancos");
            g.bancos.forEach(bancos::add);
            String regla = Nodos.texto(o, "regla");
            o.put("noLineal", !regla.equals("LINEAR") && !regla.equals("PER_PIECE"));
            porEquipo.add(o);
        }

        // Planta (BW / CTAG).
        List<Grupo> pl = agrupar(contadas, DIMENSIONES.get("planta"), null);
        pl.sort(Comparator.comparing(Grupo::clave));
        ArrayNode porPlanta = salida.putArray("porPlanta");
        pl.forEach(g -> porPlanta.add(g.json()));

        // Desgloses por dimensión (importe de mayor a menor).
        ObjectNode desgloses = salida.putObject("desgloses");
        for (Map.Entry<String, Function<JsonNode, Clave>> d : DIMENSIONES.entrySet()) {
            List<Grupo> gs = agrupar(contadas, d.getValue(), null);
            gs.sort(Comparator.comparing((Grupo g) -> -g.coste).thenComparing(Grupo::clave));
            ArrayNode lista = desgloses.putArray(d.getKey());
            gs.forEach(g -> lista.add(g.json()));
        }

        // Línea de producto → orden → requester × grupo (tabla mensual del Excel).
        List<String> grupos = new ArrayList<>(new LinkedHashSet<>(contadas.stream().map(l -> Nodos.texto(l.get("aplicado"), "grupo")).toList()));
        grupos.sort(Comparator.comparing(ResumenFacturacion::ordenGrupo).thenComparing(g -> g));
        ObjectNode plp = salida.putObject("porLineaProducto");
        ArrayNode columnas = plp.putArray("columnas");
        grupos.forEach(columnas::add);
        ArrayNode filas = plp.putArray("filas");
        for (String lp : ordenar(contadas, l -> valorO(Nodos.texto(l.get("aplicado"), "lineaProducto"), "(sin línea)"))) {
            List<JsonNode> a = filtrar(contadas, l -> valorO(Nodos.texto(l.get("aplicado"), "lineaProducto"), "(sin línea)").equals(lp));
            filas.add(nodo(0, lp, a, grupos));
            for (String o : ordenar(a, l -> valorO(Nodos.texto(l.get("aplicado"), "orden"), "(sin orden)"))) {
                List<JsonNode> b = filtrar(a, l -> valorO(Nodos.texto(l.get("aplicado"), "orden"), "(sin orden)").equals(o));
                filas.add(nodo(1, o, b, grupos));
                for (String r : ordenar(b, l -> valorO(Nodos.texto(l.get("aplicado"), "requester"), "(sin requester)"))) {
                    filas.add(nodo(2, r, filtrar(b, l -> valorO(Nodos.texto(l.get("aplicado"), "requester"), "(sin requester)").equals(r)), grupos));
                }
            }
        }
        filas.add(nodo(-1, "Total general", contadas, grupos));

        salida.set("controlling", controlling(contadas, maestros));

        // Incidencias (primero las bloqueantes).
        List<ObjectNode> incidencias = new ArrayList<>();
        for (JsonNode l : ls) {
            for (JsonNode i : Nodos.arr(l, "incidencias")) {
                ObjectNode o = Nodos.objeto();
                o.put("lineaId", Nodos.texto(l, "id")).put("ensayoId", Nodos.texto(l, "ensayoId")).put("banco", Nodos.texto(l, "banco"))
                        .put("estado", Nodos.texto(l, "estado"));
                o.setAll((ObjectNode) i);
                incidencias.add(o);
            }
        }
        incidencias.sort(Comparator.comparing((ObjectNode o) -> MotorFacturacion.BLOQ.equals(Nodos.texto(o, "nivel")) ? 0 : 1)
                .thenComparing(o -> Nodos.texto(o, "banco")).thenComparing(o -> Nodos.texto(o, "ensayoId"))
                .thenComparing(o -> Nodos.texto(o, "codigo")));
        ArrayNode inc = salida.putArray("incidencias");
        incidencias.forEach(inc::add);
        return salida;
    }

    private static String valorO(String v, String defecto) {
        return v.isEmpty() ? defecto : v;
    }

    private static List<String> ordenar(List<JsonNode> ls, Function<JsonNode, String> f) {
        List<String> valores = new ArrayList<>(new LinkedHashSet<>(ls.stream().map(f).toList()));
        valores.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        return valores;
    }

    private static List<JsonNode> filtrar(List<JsonNode> ls, java.util.function.Predicate<JsonNode> p) {
        return ls.stream().filter(p).toList();
    }

    private static ObjectNode nodo(int nivel, String etiqueta, List<JsonNode> ls, List<String> grupos) {
        ObjectNode n = Nodos.objeto();
        n.put("nivel", nivel).put("etiqueta", etiqueta);
        ObjectNode valores = n.putObject("valores");
        double total = 0;
        for (String g : grupos) {
            double s = 0;
            boolean hay = false;
            for (JsonNode l : ls) {
                if (Nodos.texto(l.get("aplicado"), "grupo").equals(g)) {
                    hay = true;
                    s += num(l.get("aplicado"), "coste");
                }
            }
            if (hay) {
                valores.set(g, Nodos.num(s));
            }
        }
        for (JsonNode l : ls) {
            total += num(l.get("aplicado"), "coste");
        }
        n.set("total", Nodos.num(total));
        return n;
    }

    /**
     * Hoja «Controlling - Internal Testing»: una fila por categoría (Equipo), orden
     * y texto, en el orden de categorías del Excel; Amount = ROUND(suma de costes; 2).
     */
    public static ArrayNode controlling(List<JsonNode> lineas, JsonNode maestros) {
        JsonNode p = maestros.get("controlling");
        String cctrOld = valorO(Nodos.texto(p, "cctrOld"), "6463-41");
        String costElement = valorO(Nodos.texto(p, "costElement"), "420000");
        String moneda = valorO(Nodos.texto(p, "moneda"), "EUR");
        String unidadHoras = valorO(Nodos.texto(p, "unidadHoras"), "H");
        String unidadPiezas = valorO(Nodos.texto(p, "unidadPiezas"), "PC");
        Map<String, Integer> ordenCat = ordenCategorias(maestros);
        Map<String, ObjectNode> filas = new LinkedHashMap<>();
        Map<String, double[]> sumas = new HashMap<>();
        for (JsonNode l : lineas) {
            JsonNode a = l.get("aplicado");
            String categoria = Nodos.texto(a, "categoria");
            String texto = valorO(Nodos.texto(a, "textoControlling"), categoria);
            String orden = Nodos.texto(a, "orden");
            String k = categoria + "|" + orden.trim() + "|" + texto;
            ObjectNode f = filas.get(k);
            if (f == null) {
                f = Nodos.objeto();
                f.put("equipo", categoria).put("orden", orden).put("text", texto).put("unidad", Nodos.texto(a, "unidad"));
                f.putArray("lineas");
                filas.put(k, f);
                sumas.put(k, new double[2]);
            }
            double c = num(a, "coste");
            double cantidad = "PIEZAS".equals(Nodos.texto(a, "unidad")) ? num(a, "piezas") : num(a, "horasTotales");
            sumas.get(k)[0] += c;
            sumas.get(k)[1] += cantidad;
            ObjectNode det = ((ArrayNode) f.get("lineas")).addObject();
            det.put("lineaId", Nodos.texto(l, "id")).put("ensayoId", Nodos.texto(l, "ensayoId")).put("banco", Nodos.texto(l, "banco"))
                    .put("tipoEnsayo", Nodos.texto(l, "tipoEnsayo"));
            det.set("coste", Nodos.num(c));
            det.set("cantidad", a.get("cantidad") == null ? Nodos.num((Double) null) : a.get("cantidad").deepCopy());
        }
        List<String> claves = new ArrayList<>(filas.keySet());
        claves.sort(Comparator.comparing((String k) -> ordenCat.getOrDefault(Nodos.texto(filas.get(k), "equipo"), 999))
                .thenComparing(k -> Nodos.texto(filas.get(k), "equipo"))
                .thenComparing(k -> Nodos.texto(filas.get(k), "orden"))
                .thenComparing(k -> Nodos.texto(filas.get(k), "text")));
        ArrayNode salida = Nodos.lista();
        for (String k : claves) {
            ObjectNode f = filas.get(k);
            double[] s = sumas.get(k);
            ObjectNode o = salida.addObject();
            o.put("equipo", Nodos.texto(f, "equipo"));
            o.put("orden", Nodos.texto(f, "orden"));
            o.set("sumaCoste", Nodos.num(s[0]));
            o.set("sumaHoras", Nodos.num(s[1]));
            o.put("cctrOld", cctrOld).put("orderOld", "").put("operOld", "").put("costElement", costElement);
            o.set("amount", Nodos.num(Tiempo.redondeoExcel2(s[0])));
            o.put("crcy", moneda).put("cctrNew", "").put("orderNew", Nodos.texto(f, "orden").trim()).put("operNew", "");
            o.set("totalQty", Nodos.num(s[1]));
            o.put("um", "PIEZAS".equals(Nodos.texto(f, "unidad")) ? unidadPiezas : unidadHoras);
            o.put("persNo", "");
            o.put("text", Nodos.texto(f, "text"));
            o.set("lineas", f.get("lineas"));
        }
        return salida;
    }
}
