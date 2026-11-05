package com.borgwarner.tbp.dominio.operaciones;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Duraciones;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.LocalDate;
import java.util.Set;

/**
 * Edición de una celda de la tabla de un banco (manejadores de bindRows de la
 * 4.9.4). Cada campo tiene sus efectos: el avance actualiza «último reporte» y
 * el día del 100 %, el fin real no puede ser anterior al arranque real, etc.
 *
 * El valor llega como lo daría el control de la interfaz: texto para cajas y
 * desplegables, booleano para las casillas.
 */
public final class EdicionEnsayo {

    /** Campos numéricos: '' se guarda como '' y el resto como número. */
    private static final Set<String> NUMERICOS = Set.of("cycles", "cycleVal", "durManual", "cf");

    private EdicionEnsayo() {
    }

    /**
     * Aplica el cambio de un campo.
     *
     * @param plan fechas calculadas del ensayo ANTES del cambio (para los candados)
     * @throws ReglaNoCumplida si el valor no se admite (el campo no cambia)
     */
    public static void editar(Ensayo e, String campo, JsonNode valor, PlanEnsayo plan, LocalDate hoy) {
        String texto = valor == null || valor.isNull() ? "" : (valor.isTextual() ? valor.textValue() : valor.asText());
        switch (campo) {
            case "durMode", "cycleUnit", "manualUnit", "delayReason", "type", "startOverride", "endOverride",
                 "actualStart" -> e.poner(campo, texto);
            case "startLock" -> {
                boolean marcado = valor != null && valor.asBoolean(false);
                e.poner("startLock", marcado);
                if (marcado && !e.verdadero("startOverride")) {
                    e.poner("startOverride", plan != null && plan.inicio() != null ? Fechas.iso(plan.inicio()) : Fechas.iso(hoy));
                }
            }
            case "endLock" -> {
                boolean marcado = valor != null && valor.asBoolean(false);
                e.poner("endLock", marcado);
                if (marcado && !e.verdadero("endOverride")) {
                    e.poner("endOverride", plan != null && plan.fin() != null ? Fechas.iso(plan.fin()) : Fechas.iso(hoy));
                }
            }
            case "trueEnd" -> {
                // Comparación de textos aaaa-mm-dd, como en la 4.9.4.
                if (!texto.isEmpty() && e.verdadero("actualStart") && texto.compareTo(e.texto("actualStart")) < 0) {
                    throw new ReglaNoCumplida("El fin real no puede ser anterior al arranque real.");
                }
                e.poner("trueEnd", texto);
            }
            case "downtimeDays" -> {
                JsonNode dias = texto.isEmpty() ? TextNode.valueOf("")
                        : ValorJs.nodoNumero(Math.max(0, ValorJs.numeroDeTexto(texto)));
                ponerParada(e, dias, e.texto("downtimeNote"));
            }
            case "progressActual" -> {
                e.poner("progressActual", texto.isEmpty() ? TextNode.valueOf("") : ValorJs.nodoNumero(ValorJs.numeroDeTexto(texto)));
                e.poner("lastUpdate", Fechas.iso(hoy));
                // Al llegar al 100 % se memoriza el día para congelar ahí el fin.
                if (Duraciones.fraccionAvance(e) >= 1) {
                    if (!e.verdadero("progress100Date")) {
                        e.poner("progress100Date", Fechas.iso(hoy));
                    }
                } else {
                    e.poner("progress100Date", "");
                }
            }
            case "weekend" -> e.poner("weekend", "yes".equals(texto));
            case "inputs" -> e.poner("inputs", !"no".equals(texto));
            default -> {
                if (NUMERICOS.contains(campo)) {
                    e.poner(campo, texto.isEmpty() ? TextNode.valueOf("") : ValorJs.nodoNumero(ValorJs.numeroDeTexto(texto)));
                } else {
                    e.poner(campo, texto); // comments, project, resp, approvalId, avail…
                }
            }
        }
    }

    /**
     * Días y nota de parada (setDowntime). La lista downtime[] queda preparada
     * para periodos con fecha, aunque hoy solo se rellena un periodo sin fechas.
     */
    public static void ponerParada(Ensayo e, JsonNode dias, String nota) {
        JsonNode valorDias;
        if (dias == null || dias.isNull() || (dias.isTextual() && dias.textValue().isEmpty())) {
            valorDias = TextNode.valueOf("");
        } else {
            valorDias = ValorJs.nodoNumero(ValorJs.redondear(ValorJs.numero(dias) * 100) / 100);
        }
        e.poner("downtimeDays", valorDias);
        e.poner("downtimeNote", nota == null ? "" : nota);
        ArrayNode lista = JsonJs.mapper().createArrayNode();
        boolean sinDias = valorDias.isTextual() || !ValorJs.verdadero(valorDias);
        if (!(sinDias && e.texto("downtimeNote").isEmpty())) {
            ObjectNode periodo = lista.addObject();
            periodo.put("from", "");
            periodo.put("to", "");
            double numero = ValorJs.numero(valorDias);
            periodo.set("days", ValorJs.nodoNumero(Double.isNaN(numero) ? 0 : numero));
            periodo.put("note", e.texto("downtimeNote"));
        }
        e.poner("downtime", lista);
    }

    /** Estado declarado: En plazo borra el motivo de retraso (setManualState). */
    public static void declararEstado(Ensayo e, String estado) {
        String valor = (estado == null || estado.isEmpty()) ? EstadosEnsayo.EN_PLAZO : estado;
        e.poner("manualState", valor);
        if (EstadosEnsayo.EN_PLAZO.equals(valor)) {
            e.poner("delayReason", "");
        }
    }

    /** Asigna o quita un equipo auxiliar (toggleResource). */
    public static void alternarRecurso(Ensayo e, String recurso) {
        JsonNode actual = e.campo("resources");
        ArrayNode lista;
        if (actual instanceof ArrayNode existente) {
            lista = existente;
        } else {
            lista = JsonJs.mapper().createArrayNode();
            e.poner("resources", lista);
        }
        for (int i = 0; i < lista.size(); i++) {
            if (recurso.equals(lista.get(i).asText())) {
                lista.remove(i);
                return;
            }
        }
        lista.add(recurso);
    }
}
