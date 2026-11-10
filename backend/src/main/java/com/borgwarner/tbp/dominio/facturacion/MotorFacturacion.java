package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motor de facturación: tramos por periodo, horas netas, resolución económica
 * (proyecto, orden, categoría, HV, tarifa, regla), coste, incidencias y estado
 * de cada línea.
 *
 * Réplica exacta de herramientas/laboratorio/facturacion/motor.cjs: cualquier
 * cambio de reglas se hace en los dos lados y lo comprueba ParidadFacturacionTest.
 */
public final class MotorFacturacion {

    public static final String BLOQ = "BLOQUEANTE";
    public static final String ADV = "ADVERTENCIA";

    /** Momento del cálculo: «aaaa-mm-ddThh:mm» de Madrid (puede ser vacío en pruebas). */
    public record Contexto(String ahora) {

        public Long ahoraMin() {
            return ahora == null || ahora.isEmpty() ? null : Tiempo.aMin(ahora);
        }
    }

    static final Map<String, String> TEXTOS = new LinkedHashMap<>();

    static {
        TEXTOS.put("FALTA_FECHA_REAL", "Falta la fecha real de arranque (el ensayo se da por arrancado según la planificación).");
        TEXTOS.put("FALTA_HORA_INICIO", "Falta la hora real de inicio.");
        TEXTOS.put("FALTA_HORA_FIN", "Falta la hora real de fin.");
        TEXTOS.put("FECHAS_INCOHERENTES", "Fechas incoherentes: el fin real es anterior al inicio real.");
        TEXTOS.put("HORAS_NEGATIVAS", "Las horas netas del tramo son negativas.");
        TEXTOS.put("PARADAS_INVALIDAS", "Paradas no válidas");
        TEXTOS.put("PROYECTO_SIN_ALTA", "El proyecto no está dado de alta en los maestros de facturación.");
        TEXTOS.put("SIN_ORDEN", "El proyecto no tiene ninguna orden contable activa.");
        TEXTOS.put("ORDEN_NO_SELECCIONADA", "El proyecto tiene varias órdenes: selecciona una.");
        TEXTOS.put("ORDEN_NO_VALIDA", "La orden no está asociada al proyecto (solo un Admin puede usar otra, con justificación).");
        TEXTOS.put("SIN_CATEGORIA", "El banco no tiene categoría de facturación para este tipo de ensayo.");
        TEXTOS.put("REGLA_NO_CONFIGURADA", "La categoría no tiene una regla de cálculo válida.");
        TEXTOS.put("HV_SIN_SELECCIONAR", "Falta indicar Alta tensión (HV) Sí/No.");
        TEXTOS.put("SETUP_NO_CONFIRMADO", "El setup no está confirmado.");
        TEXTOS.put("PIEZAS_SIN_INDICAR", "Faltan las piezas (categoría facturada por piezas).");
        TEXTOS.put("TARIFA_NO_ENCONTRADA", "No hay tarifa vigente para la categoría, la condición HV y el periodo.");
        TEXTOS.put("TARIFA_PENDIENTE", "Tarifa excepcional pendiente de aprobación (PENDING_RATE_APPROVAL).");
        TEXTOS.put("AJUSTE_SIN_VALIDAR", "Ajuste económico pendiente de validar por un Admin.");
        TEXTOS.put("YA_FACTURADO", "El tramo ya está incluido en un periodo aprobado.");
        TEXTOS.put("LINEAS_SOLAPADAS", "Se solapa con otra línea del mismo ensayo.");
        TEXTOS.put("ENSAYO_NO_ENCONTRADO", "El ensayo ya no existe o ya no corresponde a este periodo.");
        TEXTOS.put("ENSAYO_ABIERTO", "Ensayo todavía abierto: se factura hasta la fecha de corte.");
        TEXTOS.put("PERIODO_EN_CURSO", "El periodo aún no ha terminado: se cuenta hasta ahora.");
        TEXTOS.put("HORAS_ALTAS", "Horas excepcionalmente altas.");
        TEXTOS.put("AJUSTE_MANUAL", "Tiene ajustes manuales.");
        TEXTOS.put("ORDEN_EXCEPCIONAL", "Orden excepcional autorizada por un Admin.");
        TEXTOS.put("COSTE_CERO", "Coste cero.");
        TEXTOS.put("CAMBIO_POSTERIOR", "El ensayo ha cambiado desde el último cálculo y hay un ajuste manual.");
        TEXTOS.put("DE_PERIODO_ANTERIOR", "Pendiente del periodo anterior (aplazada).");
        TEXTOS.put("PROYECTO_CERO", "Proyecto 0: coste 0, como en el Excel.");
    }

    public static final List<String> CAMPOS_AJUSTABLES = List.of("horasNetas", "horasSetup", "piezas", "proyecto", "orden", "hv",
            "categoria", "coste", "textoControlling");
    public static final List<String> CAMPOS_ECONOMICOS = List.of("horasNetas", "horasSetup", "piezas", "proyecto", "hv", "categoria", "coste");

    private MotorFacturacion() {
    }

    public static ObjectNode incidencia(String codigo, String nivel, String detalle, boolean soloAprobacion) {
        ObjectNode i = Nodos.objeto();
        i.put("codigo", codigo).put("nivel", nivel)
                .put("mensaje", TEXTOS.get(codigo) + (detalle == null || detalle.isEmpty() ? "" : " " + detalle));
        if (soloAprobacion) {
            i.put("soloAprobacion", true);
        }
        return i;
    }

    public static ObjectNode incidencia(String codigo, String nivel) {
        return incidencia(codigo, nivel, "", false);
    }

    private static boolean presente(JsonNode n, String campo) {
        return n != null && !Nodos.nulo(n.get(campo));
    }

    // ---- tramo ---------------------------------------------------------------------

    /** Horas de un ensayo dentro de un periodo de ejecución. */
    public record Tramo(String inicioReal, String finReal, String tramoInicio, String tramoFin, boolean abierto,
                        boolean contieneInicio, long minutosBrutos, long minutosFinDeSemana, long minutosParadas,
                        long minutosNetos, double horasNetas, ArrayNode incidencias) {
    }

    /** null si el ensayo no ocupa el periodo. */
    public static Tramo tramo(JsonNode e, Periodos.Periodo per, Long ahoraMin) {
        long iniPer = Tiempo.aMin(per.inicio());
        long finPer = Tiempo.aMin(per.fin()) + Tiempo.DIA;
        Long[] real = EnsayosFacturables.inicioFinReal(e);
        Long inicio = real[0];
        Long fin = real[1];
        if (inicio == null || inicio >= finPer) {
            return null;
        }
        ArrayNode incidencias = Nodos.lista();
        long corte = finPer;
        boolean abierto = false;
        long finEfectivo;
        if (fin == null) {
            abierto = true;
            if (ahoraMin != null && ahoraMin < finPer) {
                corte = Math.max(iniPer, ahoraMin);
                incidencias.add(incidencia("PERIODO_EN_CURSO", ADV));
            }
            finEfectivo = corte;
            incidencias.add(incidencia("ENSAYO_ABIERTO", ADV));
        } else {
            finEfectivo = fin;
        }
        if (fin != null && fin <= iniPer) {
            return null;
        }
        JsonNode b = e.get("billing");
        long a = Math.max(inicio, iniPer);
        long z = Math.min(finEfectivo, finPer);
        boolean contieneInicio = inicio >= iniPer;
        if (contieneInicio && !Tiempo.esHora(Nodos.texto(b, "startTime"))) {
            incidencias.add(incidencia("FALTA_HORA_INICIO", BLOQ));
        }
        if (!abierto && fin <= finPer && !Tiempo.esHora(Nodos.texto(b, "endTime"))) {
            incidencias.add(incidencia("FALTA_HORA_FIN", BLOQ));
        }
        if (fin != null && fin <= inicio) {
            incidencias.add(incidencia("FECHAS_INCOHERENTES", BLOQ));
        }
        List<String> errores = EnsayosFacturables.validarParadas(b.get("stops"), inicio, fin);
        if (!errores.isEmpty()) {
            incidencias.add(incidencia("PARADAS_INVALIDAS", BLOQ, String.join(" ", errores), false));
        }
        boolean finDeSemana = Nodos.cierto(e, "finDeSemana");
        long brutos = Math.max(0, z - a);
        long finde = finDeSemana ? 0 : Tiempo.minutosFinDeSemana(a, z);
        long paradas = 0;
        for (JsonNode p : Nodos.arr(b, "stops")) {
            if (Nodos.cierto(p, "billable")) {
                continue;
            }
            Long pa = Tiempo.aMin(Nodos.texto(p, "from"));
            Long pb = Tiempo.aMin(Nodos.texto(p, "to"));
            if (pa == null || pb == null || pb <= pa) {
                continue;
            }
            long sa = Math.max(pa, a);
            long sb = Math.min(pb, z);
            if (sb <= sa) {
                continue;
            }
            paradas += (sb - sa) - (finDeSemana ? 0 : Tiempo.minutosFinDeSemana(sa, sb));
        }
        long netos = brutos - finde - paradas;
        if (netos < 0) {
            incidencias.add(incidencia("HORAS_NEGATIVAS", BLOQ));
        }
        return new Tramo(Tiempo.deMin(inicio), fin == null ? "" : Tiempo.deMin(fin), Tiempo.deMin(a), Tiempo.deMin(z), abierto,
                contieneInicio, brutos, finde, paradas, netos, Tiempo.horasDeMinutos(Math.max(0, netos)), incidencias);
    }

    // ---- resolución económica --------------------------------------------------------

    /** Valores de entrada de la resolución (ya con los ajustes que correspondan). */
    record Entrada(String proyecto, String orden, String categoria, String hv, boolean ordenExcepcional) {
    }

    record Economia(ObjectNode r, ArrayNode inc) {
    }

    static Economia resolverEconomia(JsonNode e, Entrada entrada, JsonNode maestros, Periodos.Periodo perFact) {
        ArrayNode inc = Nodos.lista();
        ObjectNode r = Nodos.objeto();
        String proyecto = entrada.proyecto() == null ? "" : entrada.proyecto();
        String orden = entrada.orden() == null ? "" : entrada.orden();
        String categoria = entrada.categoria() == null ? "" : entrada.categoria();
        String hv = entrada.hv() == null ? "" : entrada.hv();
        String regla = "";
        String grupo = "";
        String unidad = "H";
        String proyectoNombre = "";
        String lineaProducto = "";
        String requester = Nodos.texto(e, "requester");
        boolean ordenExcepcional = false;
        Double tarifa = null;
        String tarifaId = "";
        int nivelTarifa = 0;
        ObjectNode proy = proyecto.isEmpty() ? null : Maestros.buscarProyecto(maestros, proyecto);
        if (proy == null) {
            inc.add(incidencia("PROYECTO_SIN_ALTA", BLOQ, proyecto.isEmpty() ? "(el ensayo no tiene proyecto)" : "(" + proyecto + ")", false));
        } else {
            proyectoNombre = Nodos.texto(proy, "nombre");
            lineaProducto = Nodos.texto(proy, "lineaProducto");
            if (requester.isEmpty()) {
                requester = Nodos.texto(proy, "requester");
            }
            List<String> ordenes = Maestros.ordenesActivas(proy);
            if (orden.isEmpty()) {
                if (ordenes.size() == 1) {
                    orden = ordenes.get(0);
                } else if (ordenes.isEmpty()) {
                    inc.add(incidencia("SIN_ORDEN", BLOQ));
                } else {
                    inc.add(incidencia("ORDEN_NO_SELECCIONADA", BLOQ, "(" + String.join(", ", ordenes) + ")", false));
                }
            } else if (!ordenes.contains(orden.trim())) {
                if (entrada.ordenExcepcional()) {
                    ordenExcepcional = true;
                    inc.add(incidencia("ORDEN_EXCEPCIONAL", ADV));
                } else {
                    inc.add(incidencia("ORDEN_NO_VALIDA", BLOQ, "(" + orden + ")", false));
                }
            }
        }
        if (categoria.isEmpty()) {
            ObjectNode m = Maestros.buscarMapeo(maestros, Nodos.texto(e, "banco"), Nodos.texto(e, "tipoEnsayo"));
            if (m != null) {
                categoria = Nodos.texto(m, "categoria");
                regla = Nodos.texto(m, "regla");
            }
        }
        ObjectNode cat = categoria.isEmpty() ? null : Maestros.buscarCategoria(maestros, categoria);
        if (cat == null) {
            String tipo = Nodos.texto(e, "tipoEnsayo");
            inc.add(incidencia("SIN_CATEGORIA", BLOQ, categoria.isEmpty()
                    ? "(" + Nodos.texto(e, "banco") + (tipo.isEmpty() ? "" : " · " + tipo) + ")" : "(" + categoria + ")", false));
        } else {
            grupo = Nodos.texto(cat, "grupo");
            unidad = "PIEZAS".equals(Nodos.texto(cat, "unidad")) ? "PIEZAS" : "H";
            if (regla.isEmpty()) {
                regla = Nodos.texto(cat, "regla");
            }
            if (!Maestros.reglaValida(regla)) {
                inc.add(incidencia("REGLA_NO_CONFIGURADA", BLOQ));
            }
        }
        boolean hvValido = hv.equals("yes") || hv.equals("no");
        if (!hvValido) {
            inc.add(incidencia("HV_SIN_SELECCIONAR", BLOQ));
        }
        if (cat != null && !regla.equals("NOT_BILLABLE") && hvValido) {
            ObjectNode t = Maestros.resolverTarifa(maestros, new Maestros.ConsultaTarifa(Nodos.texto(cat, "codigo"), hv, proyecto,
                    Nodos.texto(e, "organizacion"), lineaProducto, perFact.fin()));
            if (t == null) {
                inc.add(incidencia("TARIFA_NO_ENCONTRADA", BLOQ, "(" + Nodos.texto(cat, "codigo") + ", HV "
                        + (hv.equals("yes") ? "Sí" : "No") + ", " + perFact.nombre() + ")", false));
            } else {
                tarifa = ValorJs.numero(t.get("importe"));
                tarifaId = Nodos.texto(t, "id");
                nivelTarifa = Nodos.entero(t, "nivel");
            }
        }
        r.put("proyecto", proyecto);
        r.put("proyectoNombre", proyectoNombre);
        r.put("orden", orden);
        r.put("categoria", categoria);
        r.put("grupo", grupo);
        r.put("unidad", unidad);
        r.put("regla", regla);
        r.put("hv", hv);
        r.set("tarifa", Nodos.num(tarifa));
        r.put("tarifaId", tarifaId);
        r.put("nivelTarifa", nivelTarifa);
        r.put("lineaProducto", lineaProducto);
        r.put("requester", requester);
        r.put("ordenExcepcional", ordenExcepcional);
        return new Economia(r, inc);
    }

    // ---- línea ---------------------------------------------------------------------

    private static JsonNode valorAjuste(ObjectNode linea, String campo, JsonNode defecto) {
        ObjectNode ajustes = Nodos.obj(linea, "ajustes");
        ObjectNode aj = ajustes == null ? null : Nodos.obj(ajustes, campo);
        return aj == null ? defecto : aj.get("valor");
    }

    private static Double numeroDe(JsonNode v) {
        return Nodos.nulo(v) ? null : ValorJs.numero(v);
    }

    private static boolean finito(Double d) {
        return d != null && !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /**
     * Calcula (o recalcula) una línea. `previa` conserva exclusión, aplazamiento,
     * ajustes, propuesta de tarifa e historial. Devuelve null si el ensayo arrancó
     * pero no tiene horas dentro del periodo.
     */
    public static ObjectNode calcularLinea(JsonNode e, Periodos.Periodo perEjec, Periodos.Periodo perFact, JsonNode maestros,
                                           ObjectNode previa, Contexto ctx) {
        Tramo t = tramo(e, perEjec, ctx.ahoraMin());
        ObjectNode linea;
        if (previa != null) {
            linea = previa.deepCopy();
        } else {
            linea = Nodos.objeto();
            linea.put("id", Nodos.texto(e, "id") + "@" + perEjec.id());
            linea.put("ensayoId", Nodos.texto(e, "id"));
            linea.put("periodoEjecucion", perEjec.id());
            linea.put("periodoFacturacion", perFact.id());
            linea.put("origen", perEjec.id().equals(perFact.id()) ? "PERIODO" : "APLAZADA");
            linea.putObject("ajustes");
            linea.putNull("propuestaTarifa");
            linea.putNull("exclusion");
            linea.putNull("aplazamiento");
            linea.putArray("historial");
        }
        Nodos.objEn(linea, "ajustes");
        linea.put("periodoFacturacion", perFact.id());
        linea.put("banco", Nodos.texto(e, "banco"));
        linea.put("organizacion", Nodos.texto(e, "organizacion"));
        linea.put("tipoEnsayo", Nodos.texto(e, "tipoEnsayo"));
        linea.put("responsable", Nodos.texto(e, "responsable"));
        linea.put("proyectoTexto", Nodos.texto(e, "proyecto"));
        linea.put("approvalId", Nodos.texto(e, "approvalId"));
        linea.put("cerrado", Nodos.cierto(e, "cerrado"));
        JsonNode b = e.get("billing");
        ObjectNode exclusion = Nodos.obj(linea, "exclusion");
        if (exclusion != null && "ENSAYO".equals(Nodos.texto(exclusion, "origen"))) {
            linea.putNull("exclusion");
        }
        if (!Nodos.cierto(b, "billable")) {
            ObjectNode ex = linea.putObject("exclusion");
            ex.put("por", Nodos.texto(b, "billableBy")).put("en", Nodos.texto(b, "billableAt")).put("origen", "ENSAYO");
        }
        ArrayNode incidencias = Nodos.lista();
        String fechaInicio = Nodos.texto(e, "fechaInicio");
        if (t == null && !fechaInicio.isEmpty()) {
            return null;
        }
        if (t == null) {
            linea.put("pendiente", true);
            linea.put("inicioReal", "").put("finReal", "").put("tramoInicio", "").put("tramoFin", "");
            linea.put("abierto", !Nodos.cierto(e, "cerrado"));
            if (Nodos.cierto(e, "inicioAsumido")) {
                incidencias.add(incidencia("FALTA_FECHA_REAL", BLOQ));
            }
            linea.putNull("calculado");
            linea.putNull("aplicado");
            linea.set("incidencias", incidencias);
            linea.put("estado", estadoDeLinea(linea));
            linea.put("huella", "");
            return linea;
        }
        linea.put("pendiente", false);
        linea.put("inicioReal", t.inicioReal()).put("finReal", t.finReal()).put("tramoInicio", t.tramoInicio())
                .put("tramoFin", t.tramoFin()).put("abierto", t.abierto());
        incidencias.addAll(t.incidencias());
        String[] pr = Maestros.proyectoDeTexto(Nodos.texto(e, "proyecto"));
        Double setupNum = Nodos.numeroOVacio(b, "setupHours");
        double setup = t.contieneInicio() ? (setupNum == null ? 0 : setupNum) : 0;
        Double piezasNum = Nodos.numeroOVacio(b, "pieces");
        Double piezas = t.contieneInicio() ? piezasNum : Double.valueOf(0);
        ObjectNode excepcion = Nodos.obj(b, "orderException");
        boolean ordenEx = excepcion != null && ValorJs.verdadero(excepcion.get("justification"));
        Economia origen = resolverEconomia(e, new Entrada(pr[0], Nodos.texto(b, "order"), "", Nodos.texto(b, "hv"), ordenEx), maestros, perFact);
        ObjectNode calc = Nodos.objeto();
        calc.put("minutosBrutos", t.minutosBrutos());
        calc.put("minutosFinDeSemana", t.minutosFinDeSemana());
        calc.put("minutosParadas", t.minutosParadas());
        calc.put("minutosNetos", t.minutosNetos());
        calc.set("horasNetas", Nodos.num(t.horasNetas()));
        calc.set("horasSetup", Nodos.num(setup));
        calc.set("piezas", Nodos.num(piezas));
        calc.setAll(origen.r());
        double horasTotales = t.horasNetas() + setup;
        calc.set("horasTotales", Nodos.num(horasTotales));
        Double cantidad = "PIEZAS".equals(Nodos.texto(calc, "unidad")) ? piezas : Double.valueOf(horasTotales);
        calc.set("cantidad", Nodos.num(cantidad));
        String reglaOrigen = Nodos.texto(calc, "regla");
        Double coste = !reglaOrigen.isEmpty() && cantidad != null
                ? Maestros.costePorRegla(reglaOrigen, cantidad, Nodos.numero(calc, "tarifa")) : null;
        if (coste != null && pr[0].equals("0")) {
            coste = 0.0;
        }
        calc.set("coste", Nodos.num(coste));
        calc.put("textoControlling", Nodos.texto(calc, "categoria"));

        // Ajustes con un valor de origen distinto: se avisa, no se pisa.
        ObjectNode ajustes = Nodos.objEn(linea, "ajustes");
        Iterator<Map.Entry<String, JsonNode>> it = ajustes.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entrada = it.next();
            if (!(entrada.getValue() instanceof ObjectNode aj)) {
                continue;
            }
            JsonNode nuevo = calc.get(entrada.getKey());
            JsonNode nuevoONulo = Nodos.nulo(nuevo) ? NullNode.getInstance() : nuevo;
            if (!igual(aj.get("original"), nuevoONulo)) {
                ObjectNode conflicto = aj.putObject("conflicto");
                conflicto.set("anterior", Nodos.nulo(aj.get("original")) ? NullNode.getInstance() : aj.get("original").deepCopy());
                conflicto.set("nuevo", nuevoONulo.deepCopy());
            } else {
                aj.remove("conflicto");
            }
        }

        // Valores aplicados.
        ObjectNode ajusteOrden = Nodos.obj(ajustes, "orden");
        boolean ordenExAjuste = ajusteOrden != null && Nodos.cierto(ajusteOrden, "excepcional");
        Economia ajustado = resolverEconomia(e, new Entrada(
                Nodos.texto(valorAjuste(linea, "proyecto", Nodos.textoONulo(pr[0]))),
                Nodos.texto(valorAjuste(linea, "orden", b.get("order"))),
                Nodos.texto(valorAjuste(linea, "categoria", Nodos.textoONulo(""))),
                Nodos.texto(valorAjuste(linea, "hv", b.get("hv"))),
                ordenEx || ordenExAjuste), maestros, perFact);
        ObjectNode ap = Nodos.objeto();
        double horasNetasAp = ValorJs.numero(valorAjuste(linea, "horasNetas", calc.get("horasNetas")));
        double horasSetupAp = ValorJs.numero(valorAjuste(linea, "horasSetup", calc.get("horasSetup")));
        Double piezasAp = numeroDe(valorAjuste(linea, "piezas", calc.get("piezas")));
        ap.set("horasNetas", Nodos.num(horasNetasAp));
        ap.set("horasSetup", Nodos.num(horasSetupAp));
        ap.set("piezas", Nodos.num(piezasAp));
        ap.setAll(ajustado.r());
        ObjectNode prop = Nodos.obj(linea, "propuestaTarifa");
        if (prop != null && "APROBADA".equals(Nodos.texto(prop, "estado")) && "LINEA".equals(Nodos.texto(prop, "alcance"))) {
            ap.set("tarifa", Nodos.num(ValorJs.numero(prop.get("tarifaFinal"))));
            ap.put("tarifaId", "EXCEPCIONAL");
        }
        double horasTotalesAp = horasNetasAp + horasSetupAp;
        ap.set("horasTotales", Nodos.num(horasTotalesAp));
        Double cantidadAp = "PIEZAS".equals(Nodos.texto(ap, "unidad")) ? piezasAp : Double.valueOf(horasTotalesAp);
        ap.set("cantidad", Nodos.num(cantidadAp));
        String reglaAp = Nodos.texto(ap, "regla");
        Double costeCalculado = !reglaAp.isEmpty() && cantidadAp != null
                ? Maestros.costePorRegla(reglaAp, cantidadAp, Nodos.numero(ap, "tarifa")) : null;
        if (costeCalculado != null && Nodos.texto(ap, "proyecto").equals("0")) {
            costeCalculado = 0.0;
        }
        ap.set("costeCalculado", Nodos.num(costeCalculado));
        ObjectNode ajusteCoste = Nodos.obj(ajustes, "coste");
        Double costeAp = ajusteCoste != null ? Double.valueOf(ValorJs.numero(ajusteCoste.get("valor"))) : costeCalculado;
        ap.set("coste", Nodos.num(costeAp));
        ap.put("textoControlling", Nodos.texto(valorAjuste(linea, "textoControlling", ap.get("categoria"))));
        incidencias.addAll(ajustado.inc());
        if (Nodos.texto(ap, "proyecto").equals("0")) {
            incidencias.add(incidencia("PROYECTO_CERO", ADV));
        }
        exclusion = Nodos.obj(linea, "exclusion");
        if (reglaAp.equals("NOT_BILLABLE") && exclusion == null) {
            linea.putObject("exclusion").put("por", "").put("en", "").put("origen", "REGLA");
        }
        exclusion = Nodos.obj(linea, "exclusion");
        if (exclusion != null && "REGLA".equals(Nodos.texto(exclusion, "origen")) && !reglaAp.equals("NOT_BILLABLE")) {
            linea.putNull("exclusion");
        }
        if (t.contieneInicio() && !Nodos.cierto(b, "setupConfirmed") && Nodos.obj(ajustes, "horasSetup") == null) {
            incidencias.add(incidencia("SETUP_NO_CONFIRMADO", BLOQ));
        }
        if ("PIEZAS".equals(Nodos.texto(ap, "unidad")) && !finito(piezasAp)) {
            incidencias.add(incidencia("PIEZAS_SIN_INDICAR", BLOQ));
        }
        if (prop != null && "PENDIENTE".equals(Nodos.texto(prop, "estado"))) {
            incidencias.add(incidencia("TARIFA_PENDIENTE", BLOQ, "", true));
        }
        boolean sinValidar = false;
        boolean conflicto = false;
        for (JsonNode aj : ajustes) {
            if (Nodos.cierto(aj, "economico") && !Nodos.cierto(aj, "validado")) {
                sinValidar = true;
            }
            if (presente(aj, "conflicto")) {
                conflicto = true;
            }
        }
        if (sinValidar) {
            incidencias.add(incidencia("AJUSTE_SIN_VALIDAR", BLOQ, "", true));
        }
        JsonNode parametros = maestros.get("parametros");
        JsonNode umbralNodo = parametros == null ? null : parametros.get("umbralHorasAltas");
        double umbral = ValorJs.verdadero(umbralNodo) ? ValorJs.numero(umbralNodo) : 720;
        if (horasNetasAp > umbral) {
            incidencias.add(incidencia("HORAS_ALTAS", ADV, "(" + ValorJs.numeroComoTexto(horasNetasAp) + " h)", false));
        }
        if (ajustes.size() > 0) {
            incidencias.add(incidencia("AJUSTE_MANUAL", ADV));
        }
        if (conflicto) {
            incidencias.add(incidencia("CAMBIO_POSTERIOR", ADV));
        }
        if (costeAp != null && costeAp == 0 && !reglaAp.equals("NOT_BILLABLE")) {
            incidencias.add(incidencia("COSTE_CERO", ADV));
        }
        if ("APLAZADA".equals(Nodos.texto(linea, "origen"))) {
            incidencias.add(incidencia("DE_PERIODO_ANTERIOR", ADV,
                    "(ejecución: " + Periodos.deId(Nodos.texto(linea, "periodoEjecucion")).nombre() + ")", false));
        }
        linea.set("calculado", calc);
        linea.set("aplicado", ap);
        linea.set("incidencias", incidencias);
        linea.put("huella", huella(calc));
        linea.put("estado", estadoDeLinea(linea));
        return linea;
    }

    /** Huella del valor de origen: detecta cambios del ensayo después de aprobar. */
    static String huella(JsonNode c) {
        List<String> partes = new ArrayList<>();
        for (String campo : List.of("horasNetas", "horasSetup", "piezas", "proyecto", "orden", "categoria", "hv", "tarifa")) {
            partes.add(Nodos.texto(c.get(campo)));
        }
        Double coste = Nodos.numero(c, "coste");
        partes.add(coste == null ? "" : ValorJs.numeroComoTexto(Tiempo.redondear2(coste)));
        return String.join("|", partes);
    }

    public static String estadoDeLinea(JsonNode l) {
        if (presente(l, "exclusion")) {
            return "EXCLUDED";
        }
        if (presente(l, "aplazamiento")) {
            return "DEFERRED";
        }
        boolean adv = false;
        boolean soloAjuste = true;
        for (JsonNode i : Nodos.arr(l, "incidencias")) {
            String nivel = Nodos.texto(i, "nivel");
            if (BLOQ.equals(nivel)) {
                return "BLOCKED";
            }
        }
        if (Nodos.cierto(l, "pendiente")) {
            return "PENDING";
        }
        for (JsonNode i : Nodos.arr(l, "incidencias")) {
            if (ADV.equals(Nodos.texto(i, "nivel"))) {
                adv = true;
                String c = Nodos.texto(i, "codigo");
                if (!c.equals("AJUSTE_MANUAL") && !c.equals("ORDEN_EXCEPCIONAL")) {
                    soloAjuste = false;
                }
            }
        }
        ObjectNode ajustes = Nodos.obj(l, "ajustes");
        if (ajustes != null && ajustes.size() > 0 && soloAjuste) {
            return "ADJUSTED";
        }
        return adv ? "WARNING" : "READY";
    }

    /** Línea que suma horas e importe. */
    public static boolean cuenta(JsonNode l) {
        return !presente(l, "exclusion") && !presente(l, "aplazamiento") && !Nodos.cierto(l, "pendiente") && presente(l, "aplicado");
    }

    /** Igualdad de valores al estilo del motor JavaScript (números con tolerancia). */
    public static boolean igual(JsonNode a, JsonNode b) {
        boolean na = Nodos.nulo(a);
        boolean nb = Nodos.nulo(b);
        if (na && nb) {
            return true;
        }
        if (na || nb) {
            return false;
        }
        if (a.isNumber() || b.isNumber()) {
            return Math.abs(ValorJs.numero(a) - ValorJs.numero(b)) < 1e-9;
        }
        return Nodos.texto(a).equals(Nodos.texto(b));
    }

    // ---- periodo --------------------------------------------------------------------

    /**
     * Selección de ensayos y cálculo de las líneas del periodo. Las líneas previas
     * conservan su estado manual (ajustes, exclusión, aplazamiento, propuestas).
     *
     * @param otros documentos de los demás periodos por id
     */
    public static ObjectNode calcularPeriodo(ObjectNode doc, List<ObjectNode> ensayos, JsonNode maestros,
                                             Map<String, ObjectNode> otros, Contexto ctx) {
        Periodos.Periodo per = Periodos.deId(Nodos.texto(doc, "id"));
        Map<String, ObjectNode> previas = new LinkedHashMap<>();
        for (ObjectNode l : Nodos.objetos(doc.get("lineas"))) {
            previas.put(Nodos.texto(l, "id"), l);
        }
        Map<String, ObjectNode> porId = new LinkedHashMap<>();
        ensayos.forEach(e -> porId.put(Nodos.texto(e, "id"), e));
        List<ObjectNode> lineas = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (ObjectNode e : ensayos) {
            if (Nodos.cierto(e, "tipoEspecial")) {
                continue;
            }
            String id = Nodos.texto(e, "id") + "@" + per.id();
            String fi = Nodos.texto(e, "fechaInicio");
            String ff = Nodos.texto(e, "fechaFin");
            String ip = Nodos.texto(e, "inicioPlan");
            String fp = Nodos.texto(e, "finPlan");
            boolean ocupa = false;
            if (!fi.isEmpty()) {
                ocupa = fi.compareTo(per.fin()) <= 0 && (ff.isEmpty() || ff.compareTo(per.inicio()) >= 0);
            } else if (!Nodos.cierto(e, "cerrado") && !ip.isEmpty() && !fp.isEmpty()) {
                ocupa = ip.compareTo(per.fin()) <= 0 && fp.compareTo(per.inicio()) >= 0;
            }
            if (!ocupa) {
                continue;
            }
            ObjectNode l = calcularLinea(e, per, per, maestros, previas.get(id), ctx);
            if (l == null) {
                continue;
            }
            lineas.add(l);
            vistos.add(id);
        }
        // Líneas aplazadas desde el periodo anterior.
        ObjectNode ant = otros.get(Periodos.anterior(per.id()));
        if (ant != null) {
            ArrayNode foto = fotoVigente(ant);
            ArrayNode origenAnt = foto != null ? foto : Nodos.arr(ant, "lineas");
            for (ObjectNode la : Nodos.objetos(origenAnt)) {
                ObjectNode aplazamiento = Nodos.obj(la, "aplazamiento");
                if (aplazamiento == null || !per.id().equals(Nodos.texto(aplazamiento, "a"))) {
                    continue;
                }
                ObjectNode e = porId.get(Nodos.texto(la, "ensayoId"));
                String id = Nodos.texto(la, "id");
                ObjectNode previa = previas.get(id);
                if (previa == null) {
                    previa = la.deepCopy();
                    previa.putNull("aplazamiento");
                    previa.put("origen", "APLAZADA");
                    ArrayNode historial = Nodos.arrEn(previa, "historial");
                    String facturacion = Nodos.texto(la, "periodoFacturacion");
                    historial.addObject().put("en", ctx.ahora() == null ? "" : ctx.ahora()).put("por", "").put("accion", "Recibida aplazada")
                            .put("campo", "").put("de", per.id().equals(facturacion) ? "" : facturacion).put("a", per.id()).put("justificacion", "");
                }
                if (e == null) {
                    lineas.add(huerfana(previa));
                } else {
                    ObjectNode l = calcularLinea(e, Periodos.deId(Nodos.texto(la, "periodoEjecucion")), per, maestros, previa, ctx);
                    if (l == null) {
                        l = huerfana(previa);
                    }
                    l.put("origen", "APLAZADA");
                    boolean tiene = false;
                    for (JsonNode i : Nodos.arr(l, "incidencias")) {
                        if ("DE_PERIODO_ANTERIOR".equals(Nodos.texto(i, "codigo"))) {
                            tiene = true;
                        }
                    }
                    if (!tiene) {
                        Nodos.arrEn(l, "incidencias").add(incidencia("DE_PERIODO_ANTERIOR", ADV));
                    }
                    l.put("estado", estadoDeLinea(l));
                    lineas.add(l);
                }
                vistos.add(id);
            }
        }
        // Líneas previas con trabajo manual que ya no aparecen: se conservan bloqueadas.
        for (Map.Entry<String, ObjectNode> p : previas.entrySet()) {
            if (vistos.contains(p.getKey())) {
                continue;
            }
            ObjectNode l = p.getValue();
            ObjectNode ajustes = Nodos.obj(l, "ajustes");
            ObjectNode exclusion = Nodos.obj(l, "exclusion");
            boolean manual = (ajustes != null && ajustes.size() > 0) || presente(l, "propuestaTarifa")
                    || (exclusion != null && "LINEA".equals(Nodos.texto(exclusion, "origen"))) || presente(l, "aplazamiento");
            if (manual) {
                lineas.add(huerfana(l));
            }
        }
        // Duplicados: tramo ya incluido en un periodo aprobado / líneas solapadas del mismo ensayo.
        Set<String> aprobadas = new HashSet<>();
        for (ObjectNode o : otros.values()) {
            if (per.id().equals(Nodos.texto(o, "id"))) {
                continue;
            }
            ArrayNode foto = fotoVigente(o);
            if (foto != null) {
                for (JsonNode l : foto) {
                    if (cuenta(l)) {
                        aprobadas.add(Nodos.texto(l, "id"));
                    }
                }
            }
        }
        Map<String, List<ObjectNode>> porEnsayo = new LinkedHashMap<>();
        for (ObjectNode l : lineas) {
            if (aprobadas.contains(Nodos.texto(l, "id")) && !presente(l, "exclusion")) {
                Nodos.arrEn(l, "incidencias").add(incidencia("YA_FACTURADO", BLOQ));
            }
            porEnsayo.computeIfAbsent(Nodos.texto(l, "ensayoId"), k -> new ArrayList<>()).add(l);
        }
        for (List<ObjectNode> ls : porEnsayo.values()) {
            List<ObjectNode> con = new ArrayList<>();
            for (ObjectNode l : ls) {
                if (!Nodos.texto(l, "tramoInicio").isEmpty() && !presente(l, "exclusion")) {
                    con.add(l);
                }
            }
            for (int i = 0; i < con.size(); i++) {
                for (int j = i + 1; j < con.size(); j++) {
                    long s = Tiempo.solape(Tiempo.aMin(Nodos.texto(con.get(i), "tramoInicio")), Tiempo.aMin(Nodos.texto(con.get(i), "tramoFin")),
                            Tiempo.aMin(Nodos.texto(con.get(j), "tramoInicio")), Tiempo.aMin(Nodos.texto(con.get(j), "tramoFin")));
                    if (s > 0) {
                        Nodos.arrEn(con.get(i), "incidencias").add(incidencia("LINEAS_SOLAPADAS", BLOQ));
                        Nodos.arrEn(con.get(j), "incidencias").add(incidencia("LINEAS_SOLAPADAS", BLOQ));
                    }
                }
            }
        }
        lineas.forEach(l -> l.put("estado", estadoDeLinea(l)));
        lineas.sort((x, y) -> compararLineas(x, y));
        ArrayNode salida = doc.putArray("lineas");
        lineas.forEach(salida::add);
        doc.set("resumenRapido", resumenRapido(doc));
        return doc;
    }

    private static int compararLineas(JsonNode a, JsonNode b) {
        String[] ka = clave(a);
        String[] kb = clave(b);
        for (int i = 0; i < ka.length; i++) {
            int c = ka[i].compareTo(kb[i]);
            if (c != 0) {
                return c < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private static String[] clave(JsonNode l) {
        String inicio = Nodos.texto(l, "tramoInicio");
        return new String[] {Nodos.texto(l, "banco"), inicio.isEmpty() ? Nodos.texto(l, "periodoEjecucion") : inicio,
                Nodos.texto(l, "ensayoId"), Nodos.texto(l, "periodoEjecucion")};
    }

    static ObjectNode huerfana(ObjectNode l) {
        ObjectNode h = l.deepCopy();
        ArrayNode inc = h.putArray("incidencias");
        inc.add(incidencia("ENSAYO_NO_ENCONTRADO", BLOQ));
        h.put("estado", estadoDeLinea(h));
        return h;
    }

    /** Líneas de la última foto aprobada (null si el periodo no está aprobado). */
    public static ArrayNode fotoVigente(JsonNode doc) {
        if (doc == null) {
            return null;
        }
        String estado = Nodos.texto(doc, "estado");
        if (!estado.equals("APROBADO") && !estado.equals("EXPORTADO")) {
            return null;
        }
        int version = Nodos.entero(doc, "versionAprobada");
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            if (Nodos.entero(f, "version") == version) {
                JsonNode lineas = f.get("lineas");
                return lineas instanceof ArrayNode a ? a : null;
            }
        }
        return null;
    }

    public static ObjectNode resumenRapido(JsonNode doc) {
        Set<String> ensayos = new HashSet<>();
        int lineas = 0;
        int incidencias = 0;
        int bloqueadas = 0;
        double importe = 0;
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            lineas++;
            ensayos.add(Nodos.texto(l, "ensayoId"));
            for (JsonNode i : Nodos.arr(l, "incidencias")) {
                String nivel = Nodos.texto(i, "nivel");
                if (nivel.equals(BLOQ) || nivel.equals(ADV)) {
                    incidencias++;
                }
            }
            if ("BLOCKED".equals(Nodos.texto(l, "estado"))) {
                bloqueadas++;
            }
            if (cuenta(l)) {
                Double c = Nodos.numero(l.get("aplicado"), "coste");
                importe += c == null ? 0 : c;
            }
        }
        ObjectNode r = Nodos.objeto();
        r.put("ensayos", ensayos.size()).put("lineas", lineas).put("incidencias", incidencias).put("bloqueadas", bloqueadas);
        r.set("importe", Nodos.num(Tiempo.redondear2(importe)));
        return r;
    }
}
