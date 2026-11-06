package com.borgwarner.tbp.dominio.operaciones;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.planificacion.Sugerencias;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Operaciones sobre la cola de un banco: compromiso, prioridad, selección
 * múltiple, altas y bajas. Réplica de las funciones de la 4.9.4 indicadas en
 * cada método, incluidos sus efectos poco evidentes (documentados en
 * docs/02-discrepancias.md).
 *
 * Las confirmaciones («¿Seguro que…?») las pide la interfaz ANTES de llamar
 * aquí; si el usuario cancela, la operación no se invoca.
 */
public final class OperacionesBanco {

    private OperacionesBanco() {
    }

    private static Ensayo buscar(Planificacion p, String banco, String id) {
        return p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
    }

    // ---- compromiso (📌 / ↩ / Confirm all) -----------------------------------

    /** confirmDatesRow: congela las fechas previstas como comprometidas. */
    public static void comprometer(Planificacion p, ResultadoPlan plan, String banco, String id) {
        Ensayo e = buscar(p, banco, id);
        PlanEnsayo fechas = plan.de(e);
        if (!fechas.tieneFechas()) {
            throw new ReglaNoCumplida("This test has no computed dates yet (add a duration first).");
        }
        e.poner("confirmedStart", Fechas.iso(fechas.inicio()));
        e.poner("confirmedEnd", Fechas.iso(fechas.fin()));
    }

    /** unconfirmDatesRow: quita el compromiso (si no hay, no hace nada). */
    public static void deshacerCompromiso(Planificacion p, String banco, String id) {
        Ensayo e = buscar(p, banco, id);
        if (!e.verdadero("confirmedStart") && !e.verdadero("confirmedEnd")) {
            return;
        }
        e.poner("confirmedStart", "");
        e.poner("confirmedEnd", "");
    }

    /** confirmAll: compromete todos los ensayos activos que tienen fechas. */
    public static void comprometerTodos(Planificacion p, ResultadoPlan plan, String banco) {
        for (Ensayo e : p.activos(banco)) {
            PlanEnsayo fechas = plan.de(e);
            if (fechas.tieneFechas()) {
                e.poner("confirmedStart", Fechas.iso(fechas.inicio()));
                e.poner("confirmedEnd", Fechas.iso(fechas.fin()));
            }
        }
    }

    // ---- estado ------------------------------------------------------------

    public static void declararEstado(Planificacion p, String banco, String id, String estado) {
        EdicionEnsayo.declararEstado(buscar(p, banco, id), estado);
    }

    /** applySuggestion: aplica el estado sugerido, si lo hay. */
    public static void aplicarSugerencia(Planificacion p, ResultadoPlan plan, String banco, String id) {
        Ensayo e = buscar(p, banco, id);
        String sugerido = Sugerencias.estadoSugerido(e, plan.de(e), plan.contexto());
        if (!sugerido.isEmpty()) {
            EdicionEnsayo.declararEstado(e, sugerido);
        }
    }

    // ---- prioridad ---------------------------------------------------------

    /**
     * Soltar el ensayo `origen` sobre `destino` (arrastrar). Si `origen` forma
     * parte de la selección, se mueven todos los seleccionados en su orden.
     */
    public static void reordenar(Planificacion p, String banco, String origen, String destino, Collection<String> seleccion) {
        List<String> aMover = (seleccion != null && !seleccion.isEmpty() && seleccion.contains(origen))
                ? new ArrayList<>(seleccion) : List.of(origen);
        if (aMover.contains(destino) && aMover.size() == 1) {
            return;
        }
        List<Ensayo> activos = new ArrayList<>(p.activos(banco));
        List<Ensayo> cerrados = p.cerrados(banco);
        List<Ensayo> moviendo = activos.stream().filter(e -> aMover.contains(e.id())).toList();
        List<Ensayo> resto = new ArrayList<>(activos.stream().filter(e -> !aMover.contains(e.id())).toList());
        int posicion = -1;
        for (int i = 0; i < resto.size(); i++) {
            if (resto.get(i).id().equals(destino)) {
                posicion = i;
                break;
            }
        }
        if (posicion < 0) {
            posicion = resto.size(); // destino dentro de la selección: van al final (como en la 4.9.4)
        }
        resto.addAll(posicion, moviendo);
        resto.addAll(cerrados);
        p.reemplazar(banco, resto);
    }

    // ---- selección múltiple -------------------------------------------------

    /** Ids seleccionados que son ensayos activos del banco, en el orden del banco. */
    private static List<String> seleccionActiva(Planificacion p, String banco, Collection<String> seleccion) {
        Set<String> elegidos = new LinkedHashSet<>(seleccion);
        return p.activos(banco).stream().map(Ensayo::id).filter(elegidos::contains).toList();
    }

    /** duplicateSel: cada copia queda justo debajo de su original. Devuelve los ids nuevos. */
    public static List<String> duplicar(Planificacion p, String banco, Collection<String> seleccion, GeneradorIds ids) {
        List<String> elegidos = seleccionActiva(p, banco, seleccion);
        if (elegidos.isEmpty()) {
            throw new ReglaNoCumplida("Select at least one test to duplicate.");
        }
        List<Ensayo> salida = new ArrayList<>();
        List<String> nuevos = new ArrayList<>();
        for (Ensayo e : p.activos(banco)) {
            salida.add(e);
            if (elegidos.contains(e.id())) {
                Ensayo copia = e.copia();
                String id = ids.nuevo();
                copia.poner("id", id);
                salida.add(copia);
                nuevos.add(id);
            }
        }
        salida.addAll(p.cerrados(banco));
        p.reemplazar(banco, salida);
        return nuevos;
    }

    /** deleteSel: borra los seleccionados (la interfaz ya ha pedido confirmación). */
    public static void borrarSeleccion(Planificacion p, String banco, Collection<String> seleccion) {
        List<String> elegidos = seleccionActiva(p, banco, seleccion);
        if (elegidos.isEmpty()) {
            throw new ReglaNoCumplida("Select at least one test to delete.");
        }
        p.reemplazar(banco, p.ensayos(banco).stream().filter(e -> !elegidos.contains(e.id())).toList());
    }

    /** delRow: borra un ensayo. */
    public static void borrar(Planificacion p, String banco, String id) {
        p.reemplazar(banco, p.ensayos(banco).stream().filter(e -> !e.id().equals(id)).toList());
    }

    /**
     * moveSel: mueve los seleccionados a otro banco en la prioridad indicada.
     * Limpia arranque real, fecha forzada, estado y avance, pero CONSERVA el
     * compromiso, los candados, el fin real y la parada (discrepancia D3).
     *
     * @return el aviso que muestra la 4.9.4 tras mover, o null si no había selección
     */
    public static String mover(Planificacion p, String banco, Collection<String> seleccion, String destino, Integer posicion) {
        if (destino == null || destino.isEmpty() || destino.equals(banco) || !p.existeBanco(destino)) {
            throw new ReglaNoCumplida("Choose a destination bench.");
        }
        List<String> elegidos = seleccionActiva(p, banco, seleccion);
        if (elegidos.isEmpty()) {
            return null;
        }
        List<Ensayo> moviendo = p.ensayos(banco).stream().filter(e -> elegidos.contains(e.id())).toList();
        p.reemplazar(banco, p.ensayos(banco).stream().filter(e -> !elegidos.contains(e.id())).toList());
        List<Ensayo> activosDestino = new ArrayList<>(p.activos(destino));
        List<Ensayo> cerradosDestino = p.cerrados(destino);
        int prioridad = posicion == null ? activosDestino.size() + 1 : posicion;
        if (prioridad < 1) {
            prioridad = 1;
        }
        if (prioridad > activosDestino.size() + 1) {
            prioridad = activosDestino.size() + 1;
        }
        for (Ensayo e : moviendo) {
            for (String campo : List.of("launchConfirmed", "startOverride", "actualStart", "manualState",
                    "progress100Date", "progressActual", "lastUpdate")) {
                e.poner(campo, "");
            }
        }
        activosDestino.addAll(prioridad - 1, moviendo);
        activosDestino.addAll(cerradosDestino);
        p.reemplazar(destino, activosDestino);
        return elegidos.size() + " test(s) moved to " + destino + " at priority " + prioridad + " (reset to Queued).";
    }

    // ---- altas -------------------------------------------------------------

    /** addRow: ensayo vacío al final de los activos, con el responsable por defecto. */
    public static String anadir(Planificacion p, String banco, Usuario usuario, GeneradorIds ids) {
        ObjectNode d = JsonJs.objetoVacio();
        String id = ids.nuevo();
        d.put("id", id);
        d.put("done", false);
        d.put("type", "");
        d.put("resp", "");
        d.put("project", "");
        d.put("comments", "");
        d.put("avail", "");
        d.put("startLock", false);
        d.put("startOverride", "");
        d.put("endLock", false);
        d.put("endOverride", "");
        d.put("actualStart", "");
        d.put("manualState", "");
        d.put("confirmedStart", "");
        d.put("confirmedEnd", "");
        d.put("durMode", "manual");
        d.put("manualUnit", "days");
        d.put("durManual", "");
        d.put("cycles", "");
        d.put("cycleVal", "");
        d.put("cycleUnit", "min");
        d.put("cf", 1.2);
        d.put("weekend", false);
        d.put("inputs", false);
        d.put("approvalId", "");
        d.put("progressActual", "");
        d.put("lastUpdate", "");
        Ensayo nuevo = new Ensayo(d);
        String responsable = usuario.responsablePorDefecto();
        if (!responsable.isEmpty()) {
            nuevo.poner("resp", responsable);
        }
        List<Ensayo> lista = new ArrayList<>(p.activos(banco));
        lista.add(nuevo);
        lista.addAll(p.cerrados(banco));
        p.reemplazar(banco, lista);
        return id;
    }

    /** Asigna o quita un equipo auxiliar. */
    public static void alternarRecurso(Planificacion p, String banco, String id, String recurso) {
        EdicionEnsayo.alternarRecurso(buscar(p, banco, id), recurso);
    }

    /**
     * Renombrar un equipo del catálogo (renameResource): el nombre cambia en
     * todos los ensayos que lo usan, activos y cerrados, de todos los bancos.
     *
     * @return número de ensayos afectados
     */
    public static int renombrarRecurso(Planificacion p, String anterior, String nuevo) {
        int afectados = 0;
        for (String banco : p.bancos()) {
            for (Ensayo e : p.ensayos(banco)) {
                if (e.campo("resources") instanceof ArrayNode lista) {
                    for (int i = 0; i < lista.size(); i++) {
                        if (anterior.equals(lista.get(i).asText())) {
                            lista.set(i, TextNode.valueOf(nuevo));
                            afectados++;
                            break; // como indexOf de la 4.9.4: solo la primera aparición
                        }
                    }
                }
            }
        }
        return afectados;
    }
}
