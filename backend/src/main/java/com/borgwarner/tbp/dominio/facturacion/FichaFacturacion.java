package com.borgwarner.tbp.dominio.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Ficha de facturación de un ensayo («Billing Data»): datos guardados, proyecto y
 * órdenes disponibles, categoría resuelta, setup recomendado, validación y vista
 * previa de las horas netas de cada periodo. También el estado de los datos
 * (COMPLETO o BILLING_DATA_INCOMPLETE) que se muestra en la tabla del banco.
 */
public final class FichaFacturacion {

    private FichaFacturacion() {
    }

    /** Estado de los datos de facturación de un ensayo y lo que falta. */
    public static ObjectNode estadoDatos(JsonNode e, JsonNode maestros) {
        ObjectNode r = Nodos.objeto();
        ArrayNode faltan = r.putArray("faltan");
        if (Nodos.cierto(e, "tipoEspecial")) {
            r.put("estado", "NO_APLICA");
            return r;
        }
        JsonNode b = e.get("billing");
        if (!Nodos.cierto(b, "billable")) {
            r.put("estado", "NO_FACTURABLE");
            return r;
        }
        if (!Nodos.texto(e, "fechaInicio").isEmpty() && !Tiempo.esHora(Nodos.texto(b, "startTime"))) {
            faltan.add("hora de inicio");
        }
        if (!Nodos.texto(e, "fechaFin").isEmpty() && !Tiempo.esHora(Nodos.texto(b, "endTime"))) {
            faltan.add("hora de fin");
        }
        String hv = Nodos.texto(b, "hv");
        if (!hv.equals("yes") && !hv.equals("no")) {
            faltan.add("alta tensión");
        }
        if (!Nodos.cierto(b, "setupConfirmed")) {
            faltan.add("setup");
        }
        String numero = Maestros.proyectoDeTexto(Nodos.texto(e, "proyecto"))[0];
        ObjectNode proy = numero.isEmpty() ? null : Maestros.buscarProyecto(maestros, numero);
        if (proy == null) {
            faltan.add("proyecto en maestros");
        } else {
            List<String> ordenes = Maestros.ordenesActivas(proy);
            String orden = Nodos.texto(b, "order");
            ObjectNode excepcion = Nodos.obj(b, "orderException");
            boolean conExcepcion = excepcion != null && !Nodos.texto(excepcion, "justification").isEmpty();
            if (orden.isEmpty() && ordenes.size() != 1) {
                faltan.add("orden");
            }
            if (!orden.isEmpty() && !ordenes.contains(orden) && !conExcepcion) {
                faltan.add("orden válida");
            }
        }
        if (Maestros.buscarMapeo(maestros, Nodos.texto(e, "banco"), Nodos.texto(e, "tipoEnsayo")) == null) {
            faltan.add("categoría del banco");
        }
        if (!EnsayosFacturables.validarDatos(e).isEmpty()) {
            faltan.add("paradas o fechas válidas");
        }
        r.put("estado", faltan.size() > 0 ? "BILLING_DATA_INCOMPLETE" : "COMPLETO");
        return r;
    }

    /** Ficha completa con la vista previa de horas por periodo. */
    public static ObjectNode ficha(JsonNode e, JsonNode maestros, MotorFacturacion.Contexto ctx) {
        String[] pr = Maestros.proyectoDeTexto(Nodos.texto(e, "proyecto"));
        ObjectNode proy = pr[0].isEmpty() ? null : Maestros.buscarProyecto(maestros, pr[0]);
        ObjectNode mapeo = Maestros.buscarMapeo(maestros, Nodos.texto(e, "banco"), Nodos.texto(e, "tipoEnsayo"));
        ObjectNode categoria = mapeo == null ? null : Maestros.buscarCategoria(maestros, Nodos.texto(mapeo, "categoria"));
        Long[] real = EnsayosFacturables.inicioFinReal(e);
        ArrayNode tramos = Nodos.lista();
        String fechaInicio = Nodos.texto(e, "fechaInicio");
        String fechaFin = Nodos.texto(e, "fechaFin");
        String ahora = ctx.ahora() == null ? "" : ctx.ahora();
        if (real[0] != null) {
            String id = Periodos.deDia(fechaInicio);
            String ultimo = real[1] != null ? Periodos.deDia(fechaFin) : (!ahora.isEmpty() ? Periodos.deDia(ahora.substring(0, 10)) : id);
            for (int n = 0; n < 36; n++) {
                Periodos.Periodo per = Periodos.deId(id);
                MotorFacturacion.Tramo t = MotorFacturacion.tramo(e, per, ctx.ahoraMin());
                if (t != null) {
                    ObjectNode o = tramos.addObject();
                    o.put("periodo", per.id()).put("nombre", per.nombre()).put("tramoInicio", t.tramoInicio()).put("tramoFin", t.tramoFin())
                            .put("minutosBrutos", t.minutosBrutos()).put("minutosFinDeSemana", t.minutosFinDeSemana())
                            .put("minutosParadas", t.minutosParadas());
                    o.set("horasNetas", Nodos.num(t.horasNetas()));
                    o.put("abierto", t.abierto());
                }
                if (id.equals(ultimo)) {
                    break;
                }
                id = Periodos.siguiente(id);
            }
        }
        ObjectNode f = Nodos.objeto();
        f.put("ensayoId", Nodos.texto(e, "id"));
        f.put("banco", Nodos.texto(e, "banco"));
        f.put("organizacion", Nodos.texto(e, "organizacion"));
        f.put("tipoEnsayo", Nodos.texto(e, "tipoEnsayo"));
        f.put("proyectoTexto", Nodos.texto(e, "proyecto"));
        String requester = Nodos.texto(e, "requester");
        f.put("requester", !requester.isEmpty() ? requester : (proy == null ? "" : Nodos.texto(proy, "requester")));
        f.put("fechaInicio", fechaInicio);
        f.put("fechaFin", fechaFin);
        f.put("cerrado", Nodos.cierto(e, "cerrado"));
        f.put("finDeSemana", Nodos.cierto(e, "finDeSemana"));
        f.set("billing", e.get("billing").deepCopy());
        if (proy == null) {
            f.putNull("proyecto");
        } else {
            ObjectNode p = f.putObject("proyecto");
            p.put("numero", Nodos.texto(proy, "numero")).put("nombre", Nodos.texto(proy, "nombre"))
                    .put("lineaProducto", Nodos.texto(proy, "lineaProducto"));
            ArrayNode ordenes = p.putArray("ordenes");
            for (ObjectNode o : Nodos.objetos(proy.get("ordenes"))) {
                JsonNode activa = o.get("activa");
                if (!(activa != null && activa.isBoolean() && !activa.booleanValue())) {
                    ordenes.add(o.deepCopy());
                }
            }
        }
        f.put("numeroProyecto", pr[0]);
        if (categoria == null) {
            f.putNull("categoria");
        } else {
            ObjectNode c = f.putObject("categoria");
            String regla = Nodos.texto(mapeo, "regla");
            c.put("codigo", Nodos.texto(categoria, "codigo")).put("grupo", Nodos.texto(categoria, "grupo"))
                    .put("unidad", Nodos.texto(categoria, "unidad")).put("regla", regla.isEmpty() ? Nodos.texto(categoria, "regla") : regla);
        }
        f.set("setupRecomendado", Nodos.num(Maestros.setupRecomendado(maestros, categoria == null ? "" : Nodos.texto(categoria, "codigo"),
                Nodos.texto(e, "banco"), Nodos.texto(e, "tipoEnsayo"))));
        ArrayNode motivos = f.putArray("motivosParada");
        Maestros.MOTIVOS_PARADA.forEach(motivos::add);
        ArrayNode errores = f.putArray("errores");
        List<String> lista = new ArrayList<>(EnsayosFacturables.validarDatos(e));
        lista.forEach(errores::add);
        f.set("estado", estadoDatos(e, maestros));
        f.set("tramos", tramos);
        f.put("inicioReal", real[0] == null ? "" : Tiempo.deMin(real[0]));
        f.put("finReal", real[1] == null ? "" : Tiempo.deMin(real[1]));
        return f;
    }
}
