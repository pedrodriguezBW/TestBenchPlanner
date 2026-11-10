package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flujo de un periodo: crear, recalcular, revisar líneas (ajustes, exclusión,
 * aplazamiento, tarifas excepcionales), enviar a revisión, aprobar (foto
 * inmutable), reabrir (solo Admin, con justificación) y alertas de cambios
 * posteriores a la aprobación.
 *
 * Estados: BORRADOR → EN_REVISION → APROBADO → EXPORTADO. APROBADO implica
 * periodo cerrado y bloqueado; no hay estado CERRADO.
 */
public final class FlujoFacturacion {

    /** Quién actúa: nombre que firma y rol (admin, editor o readonly). */
    public record Firmante(String nombre, String rol) {

        public boolean esAdmin() {
            return "admin".equals(rol);
        }

        public boolean puedeEditar() {
            return "admin".equals(rol) || "editor".equals(rol);
        }
    }

    /** Resultado de una acción de línea: eventos de auditoría y si han cambiado los maestros. */
    public record ResultadoAccion(List<ObjectNode> auditoria, boolean maestrosCambiados, ObjectNode linea, ObjectNode maestros) {
    }

    private FlujoFacturacion() {
    }

    static void exigir(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new ReglaNoCumplida(mensaje);
        }
    }

    private static String ahora(MotorFacturacion.Contexto ctx) {
        return ctx.ahora() == null ? "" : ctx.ahora();
    }

    public static boolean editable(JsonNode doc) {
        String e = Nodos.texto(doc, "estado");
        return e.equals("BORRADOR") || e.equals("EN_REVISION");
    }

    public static ObjectNode nuevoPeriodo(int anio, int mes, Firmante usuario, MotorFacturacion.Contexto ctx) {
        ObjectNode d = Periodos.periodo(anio, mes).comoJson();
        d.put("estado", "BORRADOR").put("revision", 0).put("creadoPor", usuario.nombre()).put("creadoEn", ahora(ctx))
                .put("ultimoCalculo", "").put("calculadoPor", "");
        d.putArray("lineas");
        d.put("versionAprobada", 0);
        d.putArray("fotos");
        d.putArray("reaperturas");
        d.putArray("exportaciones");
        d.putArray("envios");
        d.putNull("resumenRapido");
        return d;
    }

    public static ObjectNode recalcular(ObjectNode doc, List<ObjectNode> ensayos, JsonNode maestros, Map<String, ObjectNode> otros,
                                        Firmante usuario, MotorFacturacion.Contexto ctx) {
        exigir(editable(doc), "El periodo está aprobado: no se recalcula (un Admin puede reabrirlo).");
        MotorFacturacion.calcularPeriodo(doc, ensayos, maestros, otros, ctx);
        doc.put("ultimoCalculo", ahora(ctx));
        doc.put("calculadoPor", usuario.nombre());
        return doc;
    }

    private static boolean tieneIncidencia(JsonNode l, java.util.function.Predicate<JsonNode> p) {
        for (JsonNode i : Nodos.arr(l, "incidencias")) {
            if (p.test(i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean activa(JsonNode l) {
        return Nodos.nulo(l.get("exclusion")) && Nodos.nulo(l.get("aplazamiento"));
    }

    /** Comprobaciones antes de enviar a revisión (también se muestran en la pantalla). */
    public static List<String> comprobacionesEnvio(JsonNode doc) {
        List<String> errores = new ArrayList<>();
        int bloqueos = 0;
        boolean facturable = false;
        int setup = 0;
        int orden = 0;
        int tarifa = 0;
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            if (!activa(l)) {
                continue; // las líneas excluidas o aplazadas no bloquean el periodo
            }
            if (tieneIncidencia(l, i -> MotorFacturacion.BLOQ.equals(Nodos.texto(i, "nivel")) && !Nodos.cierto(i, "soloAprobacion"))) {
                bloqueos++;
            }
            if (MotorFacturacion.cuenta(l)) {
                Double c = Nodos.numero(l.get("aplicado"), "coste");
                if ((c == null ? 0 : c) >= 0) {
                    facturable = true;
                }
                if (Nodos.numero(l.get("aplicado"), "tarifa") == null || Nodos.texto(l.get("aplicado"), "regla").isEmpty()) {
                    tarifa++;
                }
            }
            if (tieneIncidencia(l, i -> "SETUP_NO_CONFIRMADO".equals(Nodos.texto(i, "codigo")))) {
                setup++;
            }
            if (tieneIncidencia(l, i -> List.of("ORDEN_NO_SELECCIONADA", "ORDEN_NO_VALIDA", "SIN_ORDEN").contains(Nodos.texto(i, "codigo")))) {
                orden++;
            }
        }
        if (bloqueos > 0) {
            errores.add("Hay " + bloqueos + " línea(s) con errores bloqueantes.");
        }
        if (!facturable) {
            errores.add("No hay ninguna línea facturable.");
        }
        if (setup > 0) {
            errores.add("Faltan " + setup + " setup(s) por confirmar.");
        }
        if (orden > 0) {
            errores.add("Faltan " + orden + " orden(es) por completar.");
        }
        if (tarifa > 0) {
            errores.add(tarifa + " línea(s) sin tarifa o sin regla.");
        }
        return errores;
    }

    public static List<String> comprobacionesAprobacion(JsonNode doc) {
        List<String> errores = comprobacionesEnvio(doc);
        int pendientes = 0;
        for (JsonNode l : Nodos.arr(doc, "lineas")) {
            if (activa(l) && tieneIncidencia(l, i -> Nodos.cierto(i, "soloAprobacion"))) {
                pendientes++;
            }
        }
        if (pendientes > 0) {
            errores.add("Quedan " + pendientes + " propuesta(s) o ajuste(s) económicos sin resolver.");
        }
        return errores;
    }

    private static JsonNode valor(Object o) {
        if (o == null) {
            return NullNode.getInstance();
        }
        if (o instanceof JsonNode n) {
            return n.deepCopy();
        }
        if (o instanceof Number n) {
            return Nodos.num(n.doubleValue());
        }
        return Nodos.textoONulo(o.toString());
    }

    static void registrar(ObjectNode linea, Firmante usuario, MotorFacturacion.Contexto ctx, String accion, String campo,
                          Object de, Object a, String justificacion) {
        ObjectNode h = Nodos.arrEn(linea, "historial").addObject();
        h.put("en", ahora(ctx)).put("por", usuario.nombre()).put("accion", accion).put("campo", campo == null ? "" : campo);
        h.set("de", de == null ? Nodos.textoONulo("") : valor(de));
        h.set("a", a == null ? Nodos.textoONulo("") : valor(a));
        h.put("justificacion", justificacion == null ? "" : justificacion);
    }

    private static ObjectNode evento(String accion, String detalle, String justificacion) {
        ObjectNode e = Nodos.objeto();
        e.put("accion", accion).put("detalle", detalle);
        if (justificacion != null) {
            e.put("justificacion", justificacion);
        }
        return e;
    }

    /** Aplica la acción sobre la línea (sin recalcular). Devuelve los eventos de auditoría. */
    static List<ObjectNode> accionLinea(ObjectNode doc, ObjectNode linea, String accion, JsonNode datos, Firmante usuario,
                                        MotorFacturacion.Contexto ctx) {
        exigir(editable(doc), "El periodo está aprobado: no se puede modificar (un Admin puede reabrirlo).");
        boolean esAdmin = usuario.esAdmin();
        List<ObjectNode> auditoria = new ArrayList<>();
        String just = Nodos.texto(datos, "justificacion").trim();
        ObjectNode ajustes = Nodos.objEn(linea, "ajustes");
        JsonNode calculado = linea.get("calculado");
        JsonNode aplicado = linea.get("aplicado");
        switch (accion) {
            case "ajustar" -> {
                ObjectNode campos = Nodos.obj(datos, "campos");
                if (campos == null) {
                    campos = Nodos.objeto();
                }
                Iterator<Map.Entry<String, JsonNode>> it = campos.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> c = it.next();
                    String campo = c.getKey();
                    exigir(MotorFacturacion.CAMPOS_AJUSTABLES.contains(campo), "Campo no ajustable: " + campo);
                    JsonNode calc = calculado == null || Nodos.nulo(calculado.get(campo)) ? NullNode.getInstance() : calculado.get(campo);
                    JsonNode v = c.getValue();
                    if (List.of("horasNetas", "horasSetup", "piezas", "coste").contains(campo)) {
                        double n = ValorJs.numero(v);
                        exigir(!Double.isNaN(n) && !Double.isInfinite(n) && n >= 0, "Valor no válido para " + campo + ".");
                        if (campo.equals("horasNetas") || campo.equals("horasSetup")) {
                            exigir(Math.abs(n * 4 - Math.floor(n * 4 + 0.5)) < 1e-9, "Las horas van en incrementos de 0,25 h.");
                        }
                        v = Nodos.num(n);
                    }
                    if (campo.equals("hv")) {
                        String hv = Nodos.texto(v);
                        exigir(hv.equals("yes") || hv.equals("no"), "HV debe ser Sí o No.");
                    }
                    if (campo.equals("proyecto")) {
                        v = Nodos.textoONulo(Maestros.numeroCanonico(Nodos.texto(v)));
                    }
                    ObjectNode previo = Nodos.obj(ajustes, campo);
                    JsonNode anterior = previo != null ? previo.get("valor") : (aplicado == null ? NullNode.getInstance() : aplicado.get(campo));
                    if (MotorFacturacion.igual(v, calc)) {
                        ajustes.remove(campo);
                    } else {
                        ObjectNode aj = ajustes.putObject(campo);
                        aj.set("valor", v.deepCopy());
                        aj.set("original", calc.deepCopy());
                        aj.put("por", usuario.nombre()).put("rol", usuario.rol()).put("en", ahora(ctx)).put("justificacion", just)
                                .put("economico", MotorFacturacion.CAMPOS_ECONOMICOS.contains(campo)).put("validado", esAdmin);
                        if (campo.equals("orden") && esAdmin && Nodos.cierto(datos, "excepcional")) {
                            aj.put("excepcional", true);
                        }
                    }
                    registrar(linea, usuario, ctx, "Ajuste", campo, anterior, v, just);
                }
            }
            case "restaurar" -> {
                List<String> campos = new ArrayList<>();
                String campo = Nodos.texto(datos, "campo");
                if (!campo.isEmpty()) {
                    campos.add(campo);
                } else {
                    ajustes.fieldNames().forEachRemaining(campos::add);
                }
                for (String c : campos) {
                    ObjectNode aj = Nodos.obj(ajustes, c);
                    if (aj != null) {
                        registrar(linea, usuario, ctx, "Restaurar valor calculado", c, aj.get("valor"), aj.get("original"), just);
                        ajustes.remove(c);
                    }
                }
            }
            case "resolverCambio" -> {
                String campo = Nodos.texto(datos, "campo");
                ObjectNode aj = Nodos.obj(ajustes, campo);
                exigir(aj != null && !Nodos.nulo(aj.get("conflicto")), "Ese ajuste no tiene cambios pendientes.");
                JsonNode conflicto = aj.get("conflicto");
                if ("recalculado".equals(Nodos.texto(datos, "opcion"))) {
                    registrar(linea, usuario, ctx, "Usar valor recalculado", campo, aj.get("valor"), conflicto.get("nuevo"), just);
                    ajustes.remove(campo);
                } else {
                    registrar(linea, usuario, ctx, "Conservar ajuste", campo, conflicto.get("nuevo"), aj.get("valor"), just);
                    aj.set("original", conflicto.get("nuevo").deepCopy());
                    aj.remove("conflicto");
                }
            }
            case "validarAjuste" -> {
                exigir(esAdmin, "Solo un Admin puede aprobar ajustes económicos.");
                Iterator<Map.Entry<String, JsonNode>> it = ajustes.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> c = it.next();
                    ObjectNode aj = (ObjectNode) c.getValue();
                    if (Nodos.cierto(aj, "economico") && !Nodos.cierto(aj, "validado")) {
                        aj.put("validado", true).put("validadoPor", usuario.nombre()).put("validadoEn", ahora(ctx));
                        registrar(linea, usuario, ctx, "Ajuste validado", c.getKey(), "", aj.get("valor"), "");
                    }
                }
                auditoria.add(evento("AJUSTE_VALIDADO", "Ajustes de " + Nodos.texto(linea, "id") + " validados", null));
            }
            case "rechazarAjuste" -> {
                exigir(esAdmin, "Solo un Admin puede rechazar ajustes económicos.");
                List<String> quitar = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> it = ajustes.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> c = it.next();
                    ObjectNode aj = (ObjectNode) c.getValue();
                    if (Nodos.cierto(aj, "economico") && !Nodos.cierto(aj, "validado")) {
                        registrar(linea, usuario, ctx, "Ajuste rechazado", c.getKey(), aj.get("valor"), aj.get("original"), just);
                        quitar.add(c.getKey());
                    }
                }
                quitar.forEach(ajustes::remove);
                auditoria.add(evento("AJUSTE_RECHAZADO", "Ajustes de " + Nodos.texto(linea, "id") + " rechazados", just));
            }
            case "excluir" -> {
                exigir(Nodos.nulo(linea.get("exclusion")), "La línea ya está excluida.");
                linea.putObject("exclusion").put("por", usuario.nombre()).put("en", ahora(ctx)).put("origen", "LINEA");
                registrar(linea, usuario, ctx, "Excluir", "", "", "", just);
                auditoria.add(evento("LINEA_EXCLUIDA", Nodos.texto(linea, "id"), just));
            }
            case "incluir" -> {
                ObjectNode ex = Nodos.obj(linea, "exclusion");
                exigir(ex != null && "LINEA".equals(Nodos.texto(ex, "origen")), ex != null
                        ? "La exclusión viene de la ficha del ensayo o de la regla de la categoría." : "La línea no está excluida.");
                linea.putNull("exclusion");
                registrar(linea, usuario, ctx, "Volver a incluir", "", "", "", just);
                auditoria.add(evento("LINEA_INCLUIDA", Nodos.texto(linea, "id"), just));
            }
            case "aplazar" -> {
                exigir(Nodos.nulo(linea.get("aplazamiento")), "La línea ya está aplazada.");
                exigir(!Nodos.cierto(linea, "pendiente"), "Una línea pendiente no tiene importe que aplazar.");
                String a = Periodos.siguiente(Nodos.texto(doc, "id"));
                linea.putObject("aplazamiento").put("a", a).put("por", usuario.nombre()).put("en", ahora(ctx));
                registrar(linea, usuario, ctx, "Aplazar al periodo siguiente", "periodoFacturacion", Nodos.texto(doc, "id"), a, just);
                auditoria.add(evento("LINEA_APLAZADA", Nodos.texto(linea, "id") + " → " + a, just));
            }
            case "deshacerAplazamiento" -> {
                ObjectNode ap = Nodos.obj(linea, "aplazamiento");
                exigir(ap != null, "La línea no está aplazada.");
                registrar(linea, usuario, ctx, "Deshacer aplazamiento", "periodoFacturacion", Nodos.texto(ap, "a"), Nodos.texto(doc, "id"), just);
                auditoria.add(evento("APLAZAMIENTO_DESHECHO", Nodos.texto(linea, "id"), just));
                linea.putNull("aplazamiento");
            }
            case "proponerTarifa" -> {
                exigir(aplicado != null && !aplicado.isNull() && Nodos.numero(aplicado, "tarifa") != null, "La línea no tiene tarifa calculada.");
                double t = ValorJs.numero(datos.get("tarifa"));
                exigir(!Nodos.nulo(datos.get("tarifa")) && !Double.isNaN(t) && !Double.isInfinite(t) && t >= 0, "Indica la tarifa propuesta.");
                exigir(!just.isEmpty(), "La justificación es obligatoria.");
                Double calculadaTarifa = Nodos.numero(calculado, "tarifa");
                Double conPropuesta = Maestros.costePorRegla(Nodos.texto(aplicado, "regla"), Nodos.numero(aplicado, "cantidad"), t);
                Double costeCalculado = Nodos.numero(aplicado, "costeCalculado");
                ObjectNode p = linea.putObject("propuestaTarifa");
                p.set("calculada", Nodos.num(calculadaTarifa));
                p.set("propuesta", Nodos.num(t));
                p.set("diferencia", Nodos.num((conPropuesta == null ? 0 : conPropuesta) - (costeCalculado == null ? 0 : costeCalculado)));
                p.put("por", usuario.nombre()).put("en", ahora(ctx)).put("justificacion", just).put("estado", "PENDIENTE")
                        .put("resueltaPor", "").put("resueltaEn", "").put("alcance", "");
                p.putNull("tarifaFinal");
                registrar(linea, usuario, ctx, "Propuesta de tarifa excepcional", "tarifa", calculadaTarifa, t, just);
                auditoria.add(evento("TARIFA_PROPUESTA", Nodos.texto(linea, "id") + ": " + (calculadaTarifa == null ? "null" : ValorJs.numeroComoTexto(calculadaTarifa))
                        + " → " + ValorJs.numeroComoTexto(t), just));
            }
            case "resolverTarifa" -> {
                exigir(esAdmin, "Solo un Admin puede resolver tarifas excepcionales.");
                ObjectNode p = Nodos.obj(linea, "propuestaTarifa");
                exigir(p != null && "PENDIENTE".equals(Nodos.texto(p, "estado")), "No hay ninguna propuesta pendiente.");
                String decision = Nodos.texto(datos, "decision");
                exigir(List.of("aprobar", "rechazar", "sustituir").contains(decision), "Decisión no válida.");
                p.put("resueltaPor", usuario.nombre()).put("resueltaEn", ahora(ctx));
                if (decision.equals("rechazar")) {
                    p.put("estado", "RECHAZADA");
                } else {
                    double fin = decision.equals("sustituir") ? ValorJs.numero(datos.get("tarifa")) : ValorJs.numero(p.get("propuesta"));
                    exigir(!(decision.equals("sustituir") && Nodos.nulo(datos.get("tarifa"))) && !Double.isNaN(fin) && !Double.isInfinite(fin) && fin >= 0,
                            "Indica la tarifa que sustituye a la propuesta.");
                    p.put("estado", "APROBADA");
                    p.set("tarifaFinal", Nodos.num(fin));
                    p.put("alcance", "MAESTRA".equals(Nodos.texto(datos, "alcance")) ? "MAESTRA" : "LINEA");
                }
                String estado = Nodos.texto(p, "estado");
                registrar(linea, usuario, ctx, "Tarifa excepcional " + estado.toLowerCase(java.util.Locale.ROOT), "tarifa", p.get("calculada"),
                        Nodos.nulo(p.get("tarifaFinal")) ? "" : p.get("tarifaFinal"), just);
                String alcance = Nodos.texto(p, "alcance");
                auditoria.add(evento("TARIFA_RESUELTA", Nodos.texto(linea, "id") + ": " + estado + (alcance.isEmpty() ? "" : " (" + alcance + ")")
                        + " " + Nodos.texto(p.get("tarifaFinal")), just));
            }
            default -> throw new ReglaNoCumplida("Acción desconocida: " + accion);
        }
        return auditoria;
    }

    /** Justificación obligatoria si el importe cambia. */
    static void exigirJustificacion(Double antes, Double despues, String justificacion, String accion) {
        if (List.of("excluir", "incluir", "aplazar", "deshacerAplazamiento", "validarAjuste").contains(accion)) {
            return;
        }
        if (!mismoImporte(antes, despues) && (justificacion == null || justificacion.trim().isEmpty())) {
            throw new ReglaNoCumplida("Justificación obligatoria: el cambio modifica el importe.");
        }
    }

    private static boolean mismoImporte(Double a, Double b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(Tiempo.redondear2(a) - Tiempo.redondear2(b)) < 1e-9;
    }

    /** Nueva tarifa maestra a partir de una propuesta aprobada con alcance MAESTRA. */
    static ObjectNode tarifaMaestraDePropuesta(ObjectNode maestros, ObjectNode linea, JsonNode doc, Firmante usuario) {
        JsonNode a = linea.get("aplicado");
        String desde = Nodos.texto(doc, "inicio");
        String fin = Nodos.texto(doc, "fin");
        String categoria = Nodos.texto(a, "categoria");
        String hv = Nodos.texto(a, "hv");
        ArrayNode tarifas = Nodos.arrEn(maestros, "tarifas");
        for (JsonNode t : tarifas) {
            if (Nodos.texto(t, "categoria").equals(categoria) && Nodos.texto(t, "hv").equals(hv) && Nodos.texto(t, "proyecto").isEmpty()
                    && Nodos.texto(t, "planta").isEmpty() && Nodos.texto(t, "lineaProducto").isEmpty() && Maestros.vigente(t, fin)) {
                ((ObjectNode) t).put("hasta", Tiempo.sumarDias(desde, -1));
            }
        }
        ObjectNode nueva = tarifas.addObject();
        nueva.put("id", "T-" + Nodos.texto(doc, "id") + "-" + Nodos.texto(linea, "ensayoId"));
        nueva.put("categoria", categoria).put("proyecto", "").put("planta", "").put("lineaProducto", "").put("hv", hv);
        nueva.set("importe", linea.get("propuestaTarifa").get("tarifaFinal").deepCopy());
        nueva.put("unidad", Nodos.texto(a, "unidad")).put("desde", desde).put("hasta", "")
                .put("nota", "Tarifa excepcional aprobada por " + usuario.nombre() + " (" + Nodos.texto(linea, "id") + ")");
        return nueva;
    }

    /**
     * Acción sobre una línea con todo lo que conlleva: aplicar, recalcular el
     * periodo, exigir justificación si cambia el importe y, si un Admin aprueba
     * una tarifa con alcance MAESTRA, crear la tarifa. Se trabaja sobre copias:
     * si algo falla no se modifica nada. Devuelve el documento nuevo en `doc`.
     */
    public static ResultadoAccion ejecutarAccionLinea(ObjectNode docOriginal, String lineaId, String accion, JsonNode datosEntrada,
                                                      Firmante usuario, List<ObjectNode> ensayos, ObjectNode maestrosOriginales,
                                                      Map<String, ObjectNode> otros, MotorFacturacion.Contexto ctx) {
        JsonNode datos = datosEntrada == null ? Nodos.objeto() : datosEntrada;
        ObjectNode doc = docOriginal.deepCopy();
        ObjectNode maestros = maestrosOriginales.deepCopy();
        ObjectNode linea = null;
        for (ObjectNode l : Nodos.objetos(doc.get("lineas"))) {
            if (Nodos.texto(l, "id").equals(lineaId)) {
                linea = l;
            }
        }
        exigir(linea != null, "La línea ya no existe en el periodo.");
        exigir(usuario.puedeEditar(), "Tu usuario es de solo lectura.");
        Double costeAntes = Nodos.nulo(linea.get("aplicado")) ? null : Nodos.numero(linea.get("aplicado"), "coste");
        List<ObjectNode> auditoria = accionLinea(doc, linea, accion, datos, usuario, ctx);
        boolean maestrosCambiados = false;
        ObjectNode prop = Nodos.obj(linea, "propuestaTarifa");
        if (accion.equals("resolverTarifa") && prop != null && "APROBADA".equals(Nodos.texto(prop, "estado"))
                && "MAESTRA".equals(Nodos.texto(prop, "alcance"))) {
            ObjectNode t = tarifaMaestraDePropuesta(maestros, linea, doc, usuario);
            auditoria.add(evento("TARIFA_MAESTRA_CREADA", Nodos.texto(t, "id") + ": " + Nodos.texto(t, "categoria") + " HV " + Nodos.texto(t, "hv")
                    + " = " + Nodos.texto(t.get("importe")) + " desde " + Nodos.texto(t, "desde"), null));
            maestrosCambiados = true;
        }
        MotorFacturacion.calcularPeriodo(doc, ensayos, maestros, otros, ctx);
        ObjectNode nueva = null;
        for (ObjectNode l : Nodos.objetos(doc.get("lineas"))) {
            if (Nodos.texto(l, "id").equals(lineaId)) {
                nueva = l;
            }
        }
        Double costeDespues = nueva == null || Nodos.nulo(nueva.get("aplicado")) ? null : Nodos.numero(nueva.get("aplicado"), "coste");
        String just = Nodos.texto(datos, "justificacion");
        exigirJustificacion(costeAntes, costeDespues, just, accion);
        if (!mismoImporte(costeAntes, costeDespues)) {
            ObjectNode ev = evento("IMPORTE_MODIFICADO", lineaId + " · " + accion, just);
            ev.set("costeAntes", Nodos.num(costeAntes));
            ev.set("costeDespues", Nodos.num(costeDespues));
            auditoria.add(ev);
        }
        doc.set("resumenRapido", MotorFacturacion.resumenRapido(doc));
        docOriginal.removeAll();
        docOriginal.setAll(doc);
        return new ResultadoAccion(auditoria, maestrosCambiados, nueva, maestros);
    }

    public static void enviarRevision(ObjectNode doc, Firmante usuario, MotorFacturacion.Contexto ctx) {
        exigir("BORRADOR".equals(Nodos.texto(doc, "estado")), "Solo se envía a revisión un periodo en borrador.");
        List<String> errores = comprobacionesEnvio(doc);
        exigir(errores.isEmpty(), "No se puede enviar a revisión: " + String.join(" ", errores));
        doc.put("estado", "EN_REVISION");
        Nodos.arrEn(doc, "envios").addObject().put("por", usuario.nombre()).put("en", ahora(ctx));
    }

    /** Aprueba: guarda la foto inmutable (líneas, indicadores y maestros usados) y bloquea el periodo. */
    public static int aprobar(ObjectNode doc, Firmante usuario, MotorFacturacion.Contexto ctx, boolean confirmacion, JsonNode maestros) {
        exigir(usuario.esAdmin(), "Solo un Admin puede aprobar el periodo.");
        exigir("EN_REVISION".equals(Nodos.texto(doc, "estado")), "Solo se aprueba un periodo en revisión.");
        exigir(confirmacion, "Confirma que has revisado las líneas, incidencias y ajustes.");
        List<String> errores = comprobacionesAprobacion(doc);
        exigir(errores.isEmpty(), "No se puede aprobar: " + String.join(" ", errores));
        int version = 0;
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            version = Math.max(version, Nodos.entero(f, "version"));
        }
        version++;
        ObjectNode r = ResumenFacturacion.resumen(doc, maestros);
        ObjectNode foto = Nodos.arrEn(doc, "fotos").addObject();
        foto.put("version", version).put("aprobadoPor", usuario.nombre()).put("aprobadoEn", ahora(ctx))
                .put("revisionMaestros", Nodos.entero(maestros, "revision"));
        foto.set("lineas", Nodos.arr(doc, "lineas").deepCopy());
        foto.set("indicadores", r.get("indicadores"));
        foto.set("tarifas", Nodos.arr(maestros, "tarifas").deepCopy());
        foto.set("categorias", Nodos.arr(maestros, "categorias").deepCopy());
        ObjectNode controlling = Nodos.obj(maestros, "controlling");
        foto.set("controlling", controlling == null ? Nodos.objeto() : controlling.deepCopy());
        doc.put("versionAprobada", version);
        doc.put("estado", "APROBADO");
        return version;
    }

    public static void reabrir(ObjectNode doc, Firmante usuario, MotorFacturacion.Contexto ctx, String justificacion) {
        exigir(usuario.esAdmin(), "Solo un Admin puede reabrir un periodo.");
        String estado = Nodos.texto(doc, "estado");
        exigir(estado.equals("APROBADO") || estado.equals("EXPORTADO"), "Solo se reabre un periodo aprobado.");
        String just = justificacion == null ? "" : justificacion.trim();
        exigir(!just.isEmpty(), "La justificación es obligatoria para reabrir.");
        Nodos.arrEn(doc, "reaperturas").addObject().put("por", usuario.nombre()).put("en", ahora(ctx)).put("justificacion", just)
                .put("version", Nodos.entero(doc, "versionAprobada"));
        for (JsonNode x : Nodos.arr(doc, "exportaciones")) {
            ((ObjectNode) x).put("estado", "OBSOLETA");
        }
        doc.put("estado", "EN_REVISION");
    }

    /** Maestros tal como estaban al aprobar (tarifas, categorías y Controlling de la foto). */
    public static ObjectNode maestrosDeFoto(JsonNode doc, JsonNode maestros) {
        ObjectNode m = maestros.deepCopy();
        int version = Nodos.entero(doc, "versionAprobada");
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            if (Nodos.entero(f, "version") == version) {
                m.set("tarifas", f.get("tarifas").deepCopy());
                m.set("categorias", f.get("categorias").deepCopy());
                if (f.has("controlling")) {
                    m.set("controlling", f.get("controlling").deepCopy());
                }
            }
        }
        return m;
    }

    /** Alertas de un periodo aprobado: ensayos cambiados después de aprobar (no se recalcula nada). */
    public static ArrayNode alertasTrasAprobar(JsonNode doc, List<ObjectNode> ensayos, JsonNode maestros, MotorFacturacion.Contexto ctx) {
        ArrayNode alertas = Nodos.lista();
        ArrayNode foto = MotorFacturacion.fotoVigente(doc);
        if (foto == null) {
            return alertas;
        }
        JsonNode fMaestros = maestrosDeFoto(doc, maestros);
        Map<String, ObjectNode> porId = new java.util.HashMap<>();
        ensayos.forEach(e -> porId.put(Nodos.texto(e, "id"), e));
        for (ObjectNode l : Nodos.objetos(foto)) {
            if (Nodos.cierto(l, "pendiente")) {
                continue;
            }
            ObjectNode e = porId.get(Nodos.texto(l, "ensayoId"));
            if (e == null) {
                alertas.addObject().put("lineaId", Nodos.texto(l, "id")).put("ensayoId", Nodos.texto(l, "ensayoId"))
                        .put("mensaje", "El ensayo ya no existe.");
                continue;
            }
            ObjectNode actual = MotorFacturacion.calcularLinea(e, Periodos.deId(Nodos.texto(l, "periodoEjecucion")),
                    Periodos.deId(Nodos.texto(l, "periodoFacturacion")), fMaestros, l.deepCopy(), ctx);
            if (actual == null || !Nodos.texto(actual, "huella").equals(Nodos.texto(l, "huella"))) {
                ObjectNode a = alertas.addObject();
                a.put("lineaId", Nodos.texto(l, "id")).put("ensayoId", Nodos.texto(l, "ensayoId"))
                        .put("mensaje", "El ensayo ha cambiado después de aprobar el periodo.")
                        .put("antes", Nodos.texto(l, "huella")).put("ahora", actual == null ? "" : Nodos.texto(actual, "huella"));
                a.set("costeAprobado", Nodos.nulo(l.get("aplicado")) ? NullNode.getInstance() : l.get("aplicado").get("coste"));
                a.set("costeActual", actual == null || Nodos.nulo(actual.get("calculado")) ? NullNode.getInstance() : actual.get("calculado").get("coste"));
            }
        }
        return alertas;
    }

    /** Ids de líneas de las fotos aprobadas de otros periodos (para avisos de duplicados). */
    public static Set<String> lineasAprobadas(Map<String, ObjectNode> otros) {
        Set<String> ids = new HashSet<>();
        for (ObjectNode o : otros.values()) {
            ArrayNode f = MotorFacturacion.fotoVigente(o);
            if (f != null) {
                f.forEach(l -> ids.add(Nodos.texto(l, "id")));
            }
        }
        return ids;
    }
}
