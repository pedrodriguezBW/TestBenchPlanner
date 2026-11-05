package com.borgwarner.tbp.dominio.operaciones;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ventana de cierre de un ensayo y operaciones sobre cerrados
 * (openCloseModal, closeRecalc, confirmClose, restoreDone, reuseDone).
 *
 * Lo que se confirma queda congelado en los campos final* y alimenta las
 * estadísticas anuales.
 */
public final class Cierre {

    /** Valores de la ventana tal como los escribe el usuario. */
    public record Valores(String arranqueReal, String finReal, String estadoFinal, String motivo,
                          String explicacion, String diasParada, String notaParada) {

        /** closeRead: los textos se recortan y una parada vacía cuenta como 0. */
        public double diasParadaNumero() {
            return diasParada == null || diasParada.isEmpty() ? 0 : ValorJs.numeroDeTexto(diasParada);
        }

        String explicacionLimpia() {
            return explicacion == null ? "" : explicacion.trim();
        }

        String notaLimpia() {
            return notaParada == null ? "" : notaParada.trim();
        }

        String motivoOVacio() {
            return motivo == null ? "" : motivo;
        }
    }

    /**
     * Resultado de validar la ventana en vivo.
     *
     * @param error          texto del error en rojo («» si se puede confirmar)
     * @param desviacion     fin real − Conf. end en días naturales (null sin compromiso o sin fin)
     * @param sugerirRetraso el fin real supera el comprometido + 1 laborable y el estado es En plazo
     * @param diasCalendario fin real − arranque real + 1 (0 si falta alguno)
     * @param diasFacturables días de calendario − días de parada (nunca negativo)
     */
    public record Validacion(String error, Long desviacion, boolean sugerirRetraso, long diasCalendario,
                             Double diasFacturables) {
    }

    private Cierre() {
    }

    /** Valores con los que se abre la ventana (openCloseModal). */
    public static Valores propuesta(Ensayo e, PlanEnsayo plan, boolean correccion, Usuario usuario, LocalDate hoy) {
        if (correccion && !usuario.esAdmin()) {
            throw new ReglaNoCumplida("Solo un Admin puede corregir un ensayo cerrado.");
        }
        String arranque;
        String fin;
        String estado;
        String motivo;
        String explicacion;
        String parada;
        if (correccion) {
            arranque = primero(e, "finalTrueStart", "finalActualStart", "histStart");
            fin = primero(e, "finalTrueEnd", "histEnd");
            estado = EstadosEnsayo.estadoFinal(e);
            motivo = primero(e, "finalReasonCode", "finalReason");
            explicacion = e.texto("finalComment");
            parada = textoParada(e.campo("finalDowntimeDays"));
        } else {
            String efectivo = EstadosEnsayo.arranqueEfectivo(e, hoy);
            arranque = !efectivo.isEmpty() ? efectivo : (plan != null && plan.inicio() != null ? Fechas.iso(plan.inicio()) : "");
            fin = e.verdadero("trueEnd") ? e.texto("trueEnd")
                    : (e.verdadero("progress100Date") ? e.texto("progress100Date") : Fechas.iso(hoy));
            estado = EstadosEnsayo.estadoDeclarado(e);
            motivo = e.texto("delayReason");
            explicacion = e.texto("closeComment");
            parada = textoParada(e.campo("downtimeDays"));
        }
        return new Valores(arranque, fin, estado, motivo, explicacion, parada, e.texto("downtimeNote"));
    }

    /** closeRecalc: validaciones en el mismo orden y con los mismos textos que la 4.9.4. */
    public static Validacion validar(Ensayo e, Valores v, Calendario calendario, LocalDate hoy) {
        LocalDate inicio = Fechas.dia(v.arranqueReal());
        LocalDate fin = Fechas.dia(v.finReal());
        Long desviacion = null;
        boolean tarde = false;
        if (notEmpty(v.finReal()) && e.verdadero("confirmedEnd")) {
            LocalDate comprometido = Fechas.dia(e.texto("confirmedEnd"));
            if (comprometido != null && fin != null) {
                desviacion = Fechas.diasEntre(comprometido, fin);
                LocalDate tolerado = e.finDeSemana() ? comprometido.plusDays(1) : calendario.sumarLaborables(comprometido, 1);
                tarde = fin.isAfter(tolerado);
            }
        }
        long calendarioDias = (notEmpty(v.arranqueReal()) && notEmpty(v.finReal()) && inicio != null && fin != null)
                ? Fechas.diasEntre(inicio, fin) + 1 : 0;
        double parada = v.diasParadaNumero();
        Double facturables = calendarioDias != 0 ? Math.max(0, calendarioDias - parada) : null;

        String error = "";
        if (!notEmpty(v.arranqueReal())) {
            error = "Indica el arranque real.";
        } else if (!notEmpty(v.finReal())) {
            error = "Indica el fin real.";
        } else if (Fechas.posterior(inicio, fin)) {
            error = "El fin real no puede ser anterior al arranque real.";
        } else if (Fechas.posterior(fin, hoy)) {
            error = "El fin real no puede ser una fecha futura.";
        } else if (parada < 0 || Double.isNaN(parada)) {
            error = "Los días de parada no pueden ser negativos.";
        } else if (parada > calendarioDias) {
            error = "Los días de parada (" + ValorJs.numeroComoTexto(parada) + ") superan la duración real del ensayo ("
                    + calendarioDias + " d).";
        } else if (EstadosEnsayo.EN_RETRASO.equals(v.estadoFinal()) && v.motivoOVacio().isEmpty()) {
            error = "Un ensayo en retraso necesita un motivo.";
        } else if ("Otro".equals(v.motivo()) && v.explicacionLimpia().isEmpty()) {
            error = "Con el motivo «Otro» la explicación es obligatoria.";
        }
        boolean sugerir = tarde && EstadosEnsayo.EN_PLAZO.equals(v.estadoFinal());
        return new Validacion(error, desviacion, sugerir, calendarioDias, facturables);
    }

    /**
     * confirmClose: guarda los datos vivos del ensayo y la foto final.
     *
     * Corrección aprobada D1: al corregir (Admin) un cierre que nunca se había
     * confirmado (versiones anteriores a la 4.9.4), queda confirmado con quién y
     * cuándo; si ya estaba confirmado, se conserva la confirmación original.
     */
    public static void confirmar(Planificacion p, String banco, String id, Valores v, boolean correccion,
                                 Usuario usuario, Calendario calendario, LocalDate hoy, Instant ahora) {
        Ensayo e = p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
        if (correccion && !usuario.esAdmin()) {
            throw new ReglaNoCumplida("Solo un Admin puede corregir un ensayo cerrado.");
        }
        String error = validar(e, v, calendario, hoy).error();
        if (!error.isEmpty()) {
            throw new ReglaNoCumplida(error);
        }
        String firma = usuario.firma();
        String instante = Fechas.instanteIso(ahora);
        String motivo = v.motivoOVacio();
        double parada = v.diasParadaNumero();

        // Datos vivos de la fila, coherentes con lo confirmado.
        e.poner("actualStart", v.arranqueReal());
        e.poner("trueEnd", v.finReal());
        e.poner("manualState", v.estadoFinal());
        e.poner("delayReason", motivo);
        e.poner("closeComment", v.explicacionLimpia());
        EdicionEnsayo.ponerParada(e, ValorJs.nodoNumero(parada), v.notaLimpia());
        if (!correccion) {
            e.poner("done", true);
            e.poner("doneDate", Fechas.iso(hoy));
            e.poner("finalBy", firma);
        }
        // Foto congelada del cierre.
        e.poner("histStart", v.arranqueReal());
        e.poner("histEnd", v.finReal());
        e.poner("finalStatus", v.estadoFinal());
        e.poner("finalReason", motivo);
        e.poner("finalReasonCode", motivo);
        e.poner("finalComment", v.explicacionLimpia());
        e.poner("finalActualStart", v.arranqueReal());
        e.poner("finalTrueStart", v.arranqueReal());
        e.poner("finalTrueEnd", v.finReal());
        JsonNode comprometido = e.verdadero("confirmedEnd") ? e.campo("confirmedEnd") : NullNode.getInstance();
        e.poner("finalCommittedEnd", comprometido);
        if (e.verdadero("confirmedEnd")) {
            LocalDate a = Fechas.dia(e.texto("confirmedEnd"));
            LocalDate b = Fechas.dia(v.finReal());
            e.poner("finalDelayDays", a != null && b != null ? ValorJs.nodoNumero(Fechas.diasEntre(a, b)) : NullNode.getInstance());
        } else {
            e.poner("finalDelayDays", NullNode.getInstance());
        }
        e.poner("finalDowntimeDays", ValorJs.nodoNumero(Double.isNaN(parada) ? 0 : parada));
        JsonNode parcial = e.campo("downtime");
        e.poner("finalDowntime", parcial instanceof ArrayNode lista ? lista.deepCopy() : JsonJs.mapper().createArrayNode());
        e.poner("finalDowntimeNote", v.notaLimpia());
        if (correccion) {
            JsonNode existentes = e.campo("finalEdits");
            ArrayNode ediciones;
            if (existentes instanceof ArrayNode lista) {
                ediciones = lista;
            } else {
                ediciones = JsonJs.mapper().createArrayNode();
                e.poner("finalEdits", ediciones);
            }
            ObjectNode edicion = ediciones.addObject();
            edicion.put("by", firma);
            edicion.put("at", instante);
            if (!EstadosEnsayo.cierreConfirmado(e)) {          // D1
                e.poner("finalConfirmedBy", firma);
                e.poner("finalConfirmedAt", instante);
            }
        } else {
            e.poner("finalConfirmedBy", firma);
            e.poner("finalConfirmedAt", instante);
        }
        p.activosPrimero(banco);
    }

    /**
     * restoreDone: devuelve el ensayo a la cola (al final de los activos) y
     * borra la foto del cierre. Conserva el arranque y el fin reales, el estado,
     * la parada y las correcciones (discrepancia D2, se mantiene).
     */
    public static void restaurar(Planificacion p, String banco, String id) {
        Ensayo e = p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
        e.poner("done", false);
        for (String campo : List.of("histStart", "histEnd", "doneDate", "finalStatus", "finalReason", "finalBy",
                "finalActualStart", "finalCommittedEnd", "finalTrueStart", "finalTrueEnd", "finalDelayDays",
                "finalReasonCode", "finalComment", "finalDowntimeDays", "finalDowntime", "finalDowntimeNote",
                "finalConfirmedBy", "finalConfirmedAt")) {
            e.quitar(campo);
        }
        p.activosPrimero(banco);
    }

    /** reuseDone: crea un ensayo nuevo, el PRIMERO de la cola, con los datos básicos del cerrado. */
    public static String reutilizar(Planificacion p, String banco, String id, GeneradorIds ids) {
        Ensayo r = p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
        boolean ciclos = "cycles".equals(r.texto("durMode"));
        ObjectNode c = JsonJs.objetoVacio();
        String nuevoId = ids.nuevo();
        c.put("id", nuevoId);
        c.put("done", false);
        c.put("type", r.texto("type"));
        c.put("resp", r.texto("resp"));
        c.put("project", r.texto("project"));
        c.put("comments", "");
        c.put("avail", "");
        c.put("launchConfirmed", "");
        c.put("startOverride", "");
        c.put("actualStart", "");
        c.put("manualState", "");
        c.put("durMode", r.verdadero("durMode") ? r.texto("durMode") : "manual");
        c.put("manualUnit", r.verdadero("manualUnit") ? r.texto("manualUnit") : "days");
        c.set("durManual", ciclos ? TextNode.valueOf("") : valorOVacio(r, "durManual"));
        c.set("cycles", ciclos ? valorOVacio(r, "cycles") : TextNode.valueOf(""));
        c.set("cycleVal", ciclos ? valorOVacio(r, "cycleVal") : TextNode.valueOf(""));
        c.put("cycleUnit", r.verdadero("cycleUnit") ? r.texto("cycleUnit") : "min");
        c.put("weekend", r.verdadero("weekend"));
        c.put("inputs", true);
        c.put("approvalId", "");
        c.put("progressActual", "");
        c.put("lastUpdate", "");
        List<Ensayo> lista = new java.util.ArrayList<>();
        lista.add(new Ensayo(c));
        lista.addAll(p.activos(banco));
        lista.addAll(p.cerrados(banco));
        p.reemplazar(banco, lista);
        return nuevoId;
    }

    // ---- utilidades ---------------------------------------------------------

    /** r.campo || '' conservando el tipo (número o texto). */
    private static JsonNode valorOVacio(Ensayo e, String campo) {
        return e.verdadero(campo) ? e.campo(campo).deepCopy() : TextNode.valueOf("");
    }

    private static String primero(Ensayo e, String... campos) {
        for (String campo : campos) {
            if (e.verdadero(campo)) {
                return e.texto(campo);
            }
        }
        return "";
    }

    /** Valor de la caja de parada: el número guardado o vacío. */
    private static String textoParada(JsonNode valor) {
        if (valor == null || valor.isNull() || (valor.isTextual() && valor.textValue().isEmpty())) {
            return "";
        }
        return valor.isNumber() ? ValorJs.numeroComoTexto(valor.doubleValue()) : valor.asText();
    }

    private static boolean notEmpty(String texto) {
        return texto != null && !texto.isEmpty();
    }
}
