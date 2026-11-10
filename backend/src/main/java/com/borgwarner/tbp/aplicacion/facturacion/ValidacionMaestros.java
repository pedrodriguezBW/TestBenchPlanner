package com.borgwarner.tbp.aplicacion.facturacion;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.facturacion.Maestros;
import com.borgwarner.tbp.dominio.facturacion.Nodos;
import com.borgwarner.tbp.dominio.facturacion.Tiempo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Comprobaciones de los maestros antes de guardarlos (solo Admin): códigos
 * únicos, reglas implementadas, tarifas numéricas con vigencias coherentes y
 * mapeos que apuntan a categorías existentes. Las fórmulas no se editan: solo se
 * asigna una regla de las implementadas.
 */
final class ValidacionMaestros {

    private ValidacionMaestros() {
    }

    static void validar(ObjectNode m) {
        List<String> e = new ArrayList<>();
        Set<String> categorias = new HashSet<>();
        for (JsonNode c : Nodos.arr(m, "categorias")) {
            String codigo = Nodos.texto(c, "codigo").trim();
            if (codigo.isEmpty()) {
                e.add("Hay una categoría sin nombre.");
            } else if (!categorias.add(codigo)) {
                e.add("La categoría «" + codigo + "» está repetida.");
            }
            if (!Maestros.reglaValida(Nodos.texto(c, "regla"))) {
                e.add("La categoría «" + codigo + "» no tiene una regla de cálculo implementada.");
            }
            String unidad = Nodos.texto(c, "unidad");
            if (!unidad.equals("H") && !unidad.equals("PIEZAS")) {
                e.add("La categoría «" + codigo + "» debe facturarse por horas (H) o por piezas (PIEZAS).");
            }
        }
        Set<String> idsTarifa = new HashSet<>();
        for (JsonNode t : Nodos.arr(m, "tarifas")) {
            String id = Nodos.texto(t, "id");
            String cat = Nodos.texto(t, "categoria");
            if (id.isEmpty() || !idsTarifa.add(id)) {
                e.add("Cada tarifa necesita un identificador único (" + (id.isEmpty() ? "vacío" : id) + ").");
            }
            if (!cat.equals("*") && !categorias.contains(cat)) {
                e.add("La tarifa " + id + " usa una categoría que no existe: «" + cat + "».");
            }
            String hv = Nodos.texto(t, "hv");
            if (!hv.equals("yes") && !hv.equals("no") && !hv.equals("*")) {
                e.add("La tarifa " + id + " necesita HV Sí, No o *.");
            }
            Double importe = Nodos.numeroOVacio(t, "importe");
            if (importe == null || Double.isNaN(importe) || importe < 0) {
                e.add("La tarifa " + id + " necesita un importe mayor o igual que 0.");
            }
            String desde = Nodos.texto(t, "desde");
            String hasta = Nodos.texto(t, "hasta");
            if (desde.isEmpty() || Tiempo.aMin(desde) == null || desde.length() != 10) {
                e.add("La tarifa " + id + " necesita una fecha inicial válida.");
            }
            if (!hasta.isEmpty() && (Tiempo.aMin(hasta) == null || hasta.length() != 10 || hasta.compareTo(desde) < 0)) {
                e.add("La fecha final de la tarifa " + id + " no es válida.");
            }
        }
        Set<String> mapeos = new HashSet<>();
        for (JsonNode x : Nodos.arr(m, "mapeos")) {
            String banco = Nodos.texto(x, "banco");
            String tipo = Nodos.texto(x, "tipoEnsayo").trim().toLowerCase(java.util.Locale.ROOT);
            if (banco.isEmpty()) {
                e.add("Hay un mapeo sin banco.");
            }
            if (!categorias.contains(Nodos.texto(x, "categoria"))) {
                e.add("El mapeo de «" + banco + "» usa una categoría que no existe: «" + Nodos.texto(x, "categoria") + "».");
            }
            String regla = Nodos.texto(x, "regla");
            if (!regla.isEmpty() && !Maestros.reglaValida(regla)) {
                e.add("El mapeo de «" + banco + "» tiene una regla no implementada.");
            }
            JsonNode activo = x.get("activo");
            boolean estaActivo = !(activo != null && activo.isBoolean() && !activo.booleanValue());
            if (estaActivo && !mapeos.add(banco + "|" + tipo)) {
                e.add("El banco «" + banco + "» tiene dos mapeos activos para el mismo tipo de ensayo.");
            }
        }
        Set<String> proyectos = new HashSet<>();
        for (JsonNode p : Nodos.arr(m, "proyectos")) {
            String numero = Maestros.numeroCanonico(Nodos.texto(p, "numero"));
            if (numero.isEmpty()) {
                e.add("Hay un proyecto sin número.");
            } else if (!proyectos.add(numero)) {
                e.add("El proyecto " + numero + " está repetido.");
            }
            for (JsonNode o : Nodos.arr(p, "ordenes")) {
                if (Nodos.texto(o, "codigo").trim().isEmpty()) {
                    e.add("El proyecto " + numero + " tiene una orden sin código.");
                }
            }
        }
        for (JsonNode s : Nodos.arr(m, "setupRecomendado")) {
            Double h = Nodos.numeroOVacio(s, "horas");
            if (h == null || Double.isNaN(h) || h < 0 || Math.abs(h * 4 - Math.floor(h * 4 + 0.5)) > 1e-9) {
                e.add("El setup recomendado va en horas, en incrementos de 0,25 h.");
            }
        }
        JsonNode umbral = m.path("parametros").get("umbralHorasAltas");
        if (umbral != null && !umbral.isNull() && !(ValorJs.numero(umbral) > 0)) {
            e.add("El umbral de horas altas debe ser mayor que 0.");
        }
        if (!e.isEmpty()) {
            throw new ReglaNoCumplida(String.join(" ", e.subList(0, Math.min(8, e.size())))
                    + (e.size() > 8 ? " (y " + (e.size() - 8) + " más)" : ""));
        }
    }

    /** Texto para la auditoría: qué apartados han cambiado. */
    static String resumenCambios(JsonNode antes, JsonNode despues) {
        List<String> partes = new ArrayList<>();
        for (String seccion : List.of("proyectos", "categorias", "mapeos", "tarifas", "setupRecomendado", "controlling", "parametros")) {
            JsonNode a = antes.get(seccion);
            JsonNode d = despues.get(seccion);
            if (a == null ? d != null : !a.equals(d)) {
                partes.add(d != null && d.isArray() ? seccion + " (" + (a == null ? 0 : a.size()) + " → " + d.size() + ")" : seccion);
            }
        }
        return partes.isEmpty() ? "Sin cambios" : "Cambios en: " + String.join(", ", partes);
    }
}
