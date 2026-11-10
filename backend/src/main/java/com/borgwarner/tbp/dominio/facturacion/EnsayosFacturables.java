package com.borgwarner.tbp.dominio.facturacion;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que la facturación necesita de un ensayo, sin acoplarse a la planificación.
 *
 * Los datos propios de facturación viven en DataJson.billing (un único objeto
 * que la planificación conserva sin tocar): hora real de inicio y de fin,
 * paradas con fecha y hora, setup, alta tensión, condición facturable, orden y
 * observación. Las FECHAS reales son las de siempre (arranque real y fin real
 * del cierre): una sola fuente de verdad.
 */
public final class EnsayosFacturables {

    private static final Pattern NUMERO = Pattern.compile("\\d+");

    private EnsayosFacturables() {
    }

    /** DataJson.billing normalizado (valores por defecto incluidos). */
    public static ObjectNode datosFacturacion(JsonNode datos) {
        JsonNode b = datos == null ? null : datos.get("billing");
        if (!(b instanceof ObjectNode)) {
            b = Nodos.objeto();
        }
        ObjectNode n = Nodos.objeto();
        n.put("startTime", Nodos.texto(b, "startTime"));
        n.put("endTime", Nodos.texto(b, "endTime"));
        JsonNode paradas = b.get("stops");
        n.set("stops", paradas instanceof ArrayNode a ? a.deepCopy() : Nodos.lista());
        n.set("setupHours", valorOVacio(b.get("setupHours")));
        n.put("setupConfirmed", Nodos.cierto(b, "setupConfirmed"));
        String hv = Nodos.texto(b, "hv");
        n.put("hv", hv.equals("yes") || hv.equals("no") ? hv : "");
        JsonNode facturable = b.get("billable");
        n.put("billable", !(facturable != null && facturable.isBoolean() && !facturable.booleanValue()));
        n.put("billableBy", Nodos.texto(b, "billableBy"));
        n.put("billableAt", Nodos.texto(b, "billableAt"));
        n.put("order", Nodos.texto(b, "order"));
        ObjectNode excepcion = Nodos.obj(b, "orderException");
        n.set("orderException", excepcion == null ? Nodos.textoONulo(null) : excepcion.deepCopy());
        n.set("pieces", valorOVacio(b.get("pieces")));
        n.put("note", Nodos.texto(b, "note"));
        return n;
    }

    private static JsonNode valorOVacio(JsonNode v) {
        return Nodos.nulo(v) ? TextNode.valueOf("") : v.deepCopy();
    }

    /**
     * Ensayo facturable a partir del ensayo de la planificación.
     *
     * @param requesterPorAprobacion Approval ID → requester de la base de aprobaciones
     */
    public static ObjectNode desdeEnsayo(String banco, String organizacion, Ensayo e, PlanEnsayo plan, LocalDate hoy,
                                         Map<String, String> requesterPorAprobacion) {
        boolean cerrado = e.cerrado();
        String fechaInicio = cerrado ? primero(e, "finalTrueStart", "finalActualStart", "histStart", "actualStart") : e.texto("actualStart");
        String fechaFin = cerrado ? primero(e, "finalTrueEnd", "histEnd", "trueEnd") : e.texto("trueEnd");
        String aprobacion = "";
        Matcher m = NUMERO.matcher(e.texto("approvalId"));
        if (m.find()) {
            aprobacion = m.group();
        }
        JsonNode finDeSemana = e.campo("weekend");
        ObjectNode n = Nodos.objeto();
        n.put("id", e.id());
        n.put("banco", banco);
        n.put("organizacion", organizacion == null ? "" : organizacion);
        n.put("cerrado", cerrado);
        n.put("tipoEnsayo", e.texto("type"));
        n.put("responsable", e.texto("resp"));
        n.put("proyecto", e.texto("project"));
        n.put("approvalId", e.texto("approvalId"));
        String requester = aprobacion.isEmpty() || requesterPorAprobacion == null ? null : requesterPorAprobacion.get(aprobacion);
        n.put("requester", requester == null ? "" : requester);
        n.put("fechaInicio", fechaInicio);
        n.put("fechaFin", fechaFin);
        n.put("finDeSemana", finDeSemana != null && ((finDeSemana.isBoolean() && finDeSemana.booleanValue())
                || (finDeSemana.isTextual() && finDeSemana.textValue().equals("yes"))));
        n.put("inicioAsumido", !cerrado && e.texto("actualStart").isEmpty() && EstadosEnsayo.arranqueAsumido(e, hoy));
        n.put("inicioPlan", cerrado || plan == null || plan.inicio() == null ? "" : Fechas.iso(plan.inicio()));
        n.put("finPlan", cerrado || plan == null || plan.fin() == null ? "" : Fechas.iso(plan.fin()));
        n.put("tipoEspecial", e.esTipoEspecial());
        n.set("billing", datosFacturacion(e.datos()));
        return n;
    }

    private static String primero(Ensayo e, String... campos) {
        for (String c : campos) {
            String v = e.texto(c);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** Inicio y fin real con hora en minutos; la hora que falte cuenta como 00:00 (inicio) o 24:00 (fin). */
    public static Long[] inicioFinReal(JsonNode e) {
        JsonNode b = e.get("billing");
        String fechaInicio = Nodos.texto(e, "fechaInicio");
        String fechaFin = Nodos.texto(e, "fechaFin");
        String hi = Nodos.texto(b, "startTime");
        String hf = Nodos.texto(b, "endTime");
        Long inicio = fechaInicio.isEmpty() ? null : Tiempo.aMin(fechaInicio + "T" + (Tiempo.esHora(hi) ? hi : "00:00"));
        Long fin = null;
        if (!fechaFin.isEmpty()) {
            if (Tiempo.esHora(hf)) {
                fin = Tiempo.aMin(fechaFin + "T" + hf);
            } else {
                Long dia = Tiempo.aMin(fechaFin);
                fin = dia == null ? null : dia + Tiempo.DIA;
            }
        }
        return new Long[] {inicio, fin};
    }

    private record Tramo(long a, long b, int n) {
    }

    /** Errores de las paradas (vacío si son correctas). */
    public static List<String> validarParadas(JsonNode paradas, Long inicio, Long fin) {
        List<String> errores = new ArrayList<>();
        List<Tramo> validas = new ArrayList<>();
        int i = 0;
        for (JsonNode p : paradas == null ? Nodos.lista() : paradas) {
            i++;
            Long a = Tiempo.aMin(Nodos.texto(p, "from"));
            Long b = Tiempo.aMin(Nodos.texto(p, "to"));
            if (a == null || b == null) {
                errores.add("Parada " + i + ": indica inicio y fin con fecha y hora.");
                continue;
            }
            if (b < a) {
                errores.add("Parada " + i + ": el final no puede ser anterior al inicio.");
                continue;
            }
            if (b.equals(a)) {
                errores.add("Parada " + i + ": la duración es cero.");
                continue;
            }
            if (Nodos.texto(p, "reason").isEmpty()) {
                errores.add("Parada " + i + ": falta el motivo.");
            }
            if (inicio != null && a < inicio) {
                errores.add("Parada " + i + ": empieza antes del inicio real del ensayo.");
            }
            if (fin != null && b > fin) {
                errores.add("Parada " + i + ": termina después del fin real del ensayo.");
            }
            validas.add(new Tramo(a, b, i));
        }
        validas.sort(Comparator.comparingLong(Tramo::a).thenComparingLong(Tramo::b));
        for (int k = 1; k < validas.size(); k++) {
            if (validas.get(k).a() < validas.get(k - 1).b()) {
                errores.add("Las paradas " + validas.get(k - 1).n() + " y " + validas.get(k).n() + " se solapan.");
            }
        }
        return errores;
    }

    /** Validación completa de los datos de facturación (ficha del ensayo y ventana de cierre). */
    public static List<String> validarDatos(JsonNode e) {
        JsonNode b = e.get("billing");
        List<String> errores = new ArrayList<>();
        String hi = Nodos.texto(b, "startTime");
        String hf = Nodos.texto(b, "endTime");
        if (!hi.isEmpty() && !Tiempo.esHora(hi)) {
            errores.add("La hora real de inicio no es válida (hh:mm).");
        }
        if (!hf.isEmpty() && !Tiempo.esHora(hf)) {
            errores.add("La hora real de fin no es válida (hh:mm).");
        }
        Long[] real = inicioFinReal(e);
        if (real[0] != null && real[1] != null && real[1] <= real[0]) {
            errores.add("El fin real con hora debe ser posterior al inicio real.");
        }
        Double setup = Nodos.numeroOVacio(b, "setupHours");
        if (setup != null) {
            if (Double.isNaN(setup) || Double.isInfinite(setup) || setup < 0) {
                errores.add("El setup debe ser un número de horas mayor o igual que 0.");
            } else if (Math.abs(setup * 4 - Math.floor(setup * 4 + 0.5)) > 1e-9) {
                errores.add("El setup va en incrementos de 0,25 h.");
            }
        }
        Double piezas = Nodos.numeroOVacio(b, "pieces");
        if (piezas != null && !(piezas >= 0)) {
            errores.add("Las piezas deben ser un número mayor o igual que 0.");
        }
        errores.addAll(validarParadas(b.get("stops"), real[0], real[1]));
        return errores;
    }
}
