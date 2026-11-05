package com.borgwarner.tbp.dominio.planificacion;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Duraciones;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de fechas de un banco (computeSchedule de la 4.9.4).
 *
 * Recorre los ensayos activos en orden de prioridad manteniendo el primer día
 * libre del banco. Para cada uno decide:
 *
 * INICIO: el arranque real o asumido si ya ha arrancado; si no, la fecha forzada;
 * si no, el máximo entre la cola, hoy y la llegada de muestras (saltando fines
 * de semana y festivos si el ensayo no corre en fin de semana).
 *
 * FIN: el fin real (solo si ha arrancado); si no, el forzado; si ha llegado al
 * 100 %, el día en que lo alcanzó; si está en marcha con avance, hoy + días
 * restantes (fin elástico, sin CF); si no, inicio + duración × CF. Un ensayo en
 * marcha nunca termina antes de hoy.
 *
 * Un ensayo sin duración no tiene fechas pero ocupa un día de cola (D3 del
 * análisis: comportamiento implícito que se conserva).
 */
public final class MotorFechas {

    private MotorFechas() {
    }

    /** Planifica los ensayos ACTIVOS de un banco, en su orden. */
    public static Map<String, PlanEnsayo> planificarBanco(List<Ensayo> activos, ContextoPlan contexto) {
        LocalDate hoy = contexto.hoy();
        Calendario calendario = contexto.calendario();
        LocalDate libre = hoy;
        List<Ensayo> ordenados = new ArrayList<>(activos);
        Map<Ensayo, LocalDate[]> fechas = new LinkedHashMap<>();
        Map<Ensayo, String> criterios = new HashMap<>();

        for (Ensayo e : ordenados) {
            double dias = Duraciones.diasAPlanificar(e);
            boolean finForzado = e.verdadero("endLock") && e.verdadero("endOverride");
            if (!(dias > 0) && !finForzado) {
                // Sin duración: sin fechas, pero el banco avanza un día.
                fechas.put(e, null);
                criterios.put(e, "");
                LocalDate siguiente = libre.plusDays(1);
                if (siguiente.isAfter(libre)) {
                    libre = siguiente;
                }
                continue;
            }
            long diasCompletos = (long) Math.max(1, Math.ceil(dias));
            double fraccion = Duraciones.fraccionAvanceMotor(e);
            long restantes = Duraciones.diasRestantes(e);
            boolean completo = fraccion >= 1;
            boolean arrancado = EstadosEnsayo.arrancado(e, hoy);
            boolean finDeSemana = e.finDeSemana();

            // ---- INICIO ----
            LocalDate inicio;
            String criterio;
            if (arrancado) {
                inicio = Fechas.dia(EstadosEnsayo.arranqueEfectivo(e, hoy));
                criterio = EstadosEnsayo.arranqueAsumido(e, hoy) ? "asumido" : "real";
            } else if (e.verdadero("startLock") && e.verdadero("startOverride")) {
                inicio = Fechas.dia(e.texto("startOverride"));
                criterio = "forzado";
            } else {
                inicio = libre.isAfter(hoy) ? libre : hoy;
                criterio = libre.isAfter(hoy) ? "cola" : "hoy";
                if (e.verdadero("avail")) {
                    LocalDate muestras = Fechas.dia(e.texto("avail"));
                    if (Fechas.posterior(muestras, inicio)) {
                        inicio = muestras;
                        criterio = "muestras";
                    }
                }
                if (!finDeSemana) {
                    inicio = calendario.siguienteLaborable(inicio);
                }
            }
            if (inicio == null) {
                // Fecha forzada con un texto que no es fecha: la 4.9.4 queda en «Invalid Date».
                fechas.put(e, null);
                criterios.put(e, criterio);
                continue;
            }

            // ---- FIN ----
            LocalDate fin;
            if (arrancado && e.verdadero("trueEnd")) {
                fin = Fechas.dia(e.texto("trueEnd"));
            } else if (finForzado) {
                fin = Fechas.dia(e.texto("endOverride"));
            } else if (completo) {
                String dia100 = e.verdadero("progress100Date") ? e.texto("progress100Date")
                        : (arrancado ? Fechas.iso(hoy) : Fechas.iso(inicio));
                fin = Fechas.dia(dia100);
            } else if (arrancado && fraccion > 0) {
                LocalDate base = hoy;
                if (inicio.isAfter(base)) {
                    base = inicio;
                }
                if (!finDeSemana) {
                    base = calendario.siguienteLaborable(base);
                }
                fin = finDeSemana ? base.plusDays(restantes - 1) : calendario.sumarLaborables(base, restantes - 1);
            } else {
                fin = finDeSemana ? inicio.plusDays(diasCompletos - 1) : calendario.sumarLaborables(inicio, diasCompletos - 1);
                if (arrancado && fin.isBefore(hoy)) {
                    fin = finDeSemana ? hoy : calendario.siguienteLaborable(hoy);
                }
            }
            if (fin == null) {
                fechas.put(e, null);
                criterios.put(e, criterio);
                continue;
            }
            if (fin.isBefore(inicio)) {
                fin = inicio;
            }
            fechas.put(e, new LocalDate[]{inicio, fin});
            criterios.put(e, criterio);
            LocalDate siguiente = fin.plusDays(1);
            if (siguiente.isAfter(libre)) {
                libre = siguiente;
            }
        }

        // ---- SOLAPES (solo posibles con fechas forzadas o reales; no bloquean) ----
        Map<Ensayo, List<PlanEnsayo.Solape>> solapes = new HashMap<>();
        for (int i = 0; i < ordenados.size(); i++) {
            LocalDate[] a = fechas.get(ordenados.get(i));
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < ordenados.size(); j++) {
                LocalDate[] b = fechas.get(ordenados.get(j));
                if (b == null) {
                    continue;
                }
                if (!a[0].isAfter(b[1]) && !a[1].isBefore(b[0])) {
                    solapes.computeIfAbsent(ordenados.get(i), k -> new ArrayList<>())
                            .add(new PlanEnsayo.Solape(etiqueta(ordenados.get(j)), b[0], b[1]));
                    solapes.computeIfAbsent(ordenados.get(j), k -> new ArrayList<>())
                            .add(new PlanEnsayo.Solape(etiqueta(ordenados.get(i)), a[0], a[1]));
                }
            }
        }

        Map<String, PlanEnsayo> resultado = new LinkedHashMap<>();
        for (Ensayo e : ordenados) {
            LocalDate[] f = fechas.get(e);
            String criterio = criterios.getOrDefault(e, "");
            List<PlanEnsayo.Solape> lista = List.copyOf(solapes.getOrDefault(e, List.of()));
            resultado.put(e.id(), f == null ? new PlanEnsayo(null, null, criterio, lista)
                    : new PlanEnsayo(f[0], f[1], criterio, lista));
        }
        return resultado;
    }

    private static String etiqueta(Ensayo e) {
        if (e.verdadero("project")) {
            return e.texto("project");
        }
        if (e.verdadero("type")) {
            return e.texto("type");
        }
        return "ensayo";
    }

    /** Fin con tolerancia: Conf. end + 1 día laborable (tolEndOf). */
    public static LocalDate finTolerado(Ensayo e, Calendario calendario) {
        if (!e.verdadero("confirmedEnd")) {
            return null;
        }
        LocalDate comprometido = Fechas.dia(e.texto("confirmedEnd"));
        if (comprometido == null) {
            return null;
        }
        return e.finDeSemana() ? comprometido.plusDays(1) : calendario.sumarLaborables(comprometido, 1);
    }

    /** Fin planificado: arranque efectivo + días planificados (plannedEndOf). */
    public static LocalDate finPlanificado(Ensayo e, ContextoPlan contexto) {
        if (!EstadosEnsayo.arrancado(e, contexto.hoy())) {
            return null;
        }
        LocalDate arranque = Fechas.dia(EstadosEnsayo.arranqueEfectivo(e, contexto.hoy()));
        int dias = Duraciones.diasPlanificados(e);
        if (dias == 0 || arranque == null) {
            return null;
        }
        return e.finDeSemana() ? arranque.plusDays(dias - 1L) : contexto.calendario().sumarLaborables(arranque, dias - 1L);
    }

    /** Días en marcha sin reportar avance (idleDays). */
    public static long diasSinAvance(Ensayo e, LocalDate hoy) {
        if (!EstadosEnsayo.arrancado(e, hoy) || e.cerrado()) {
            return 0;
        }
        double fraccion = Duraciones.fraccionAvance(e);
        if (fraccion > 0) {
            return 0;
        }
        LocalDate arranque = Fechas.dia(EstadosEnsayo.arranqueEfectivo(e, hoy));
        return Math.max(0, Fechas.diasEntre(arranque, hoy));
    }

    /** Utilidad para pruebas y depuración: número JS del avance. */
    static double avance(Ensayo e) {
        return ValorJs.numeroOCero(e.campo("progressActual"));
    }
}
