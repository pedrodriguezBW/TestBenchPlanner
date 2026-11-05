package com.borgwarner.tbp.dominio.ensayo;

import com.borgwarner.tbp.dominio.comun.ValorJs;

/**
 * Duración de un ensayo: en días u horas (modo manual) o por ciclos.
 * Funciones durDays, targetOf, cfOf, schedDays, plannedDays, progFrac y
 * remDaysOf de la 4.9.4.
 */
public final class Duraciones {

    /** Factor de contingencia por defecto. */
    public static final double CF_POR_DEFECTO = 1.2;

    private Duraciones() {
    }

    public static boolean porCiclos(Ensayo e) {
        return "cycles".equals(e.texto("durMode"));
    }

    /** Duración nominal en días (puede tener decimales; 0 si no está definida). */
    public static double diasNominales(Ensayo e) {
        if (porCiclos(e)) {
            double ciclos = ValorJs.numeroOCero(e.campo("cycles"));
            double valor = ValorJs.numeroOCero(e.campo("cycleVal"));
            if (ciclos <= 0 || valor <= 0) {
                return 0;
            }
            String unidad = e.texto("cycleUnit");
            double segundos;
            if ("sec".equals(unidad)) {
                segundos = valor;
            } else if ("hz".equals(unidad)) {
                segundos = 1 / valor;
            } else {
                segundos = valor * 60; // minutos
            }
            return ciclos * segundos / 86400;
        }
        double valor = ValorJs.numeroOCero(e.campo("durManual"));
        return "hours".equals(e.texto("manualUnit")) ? valor / 24 : valor;
    }

    /** Objetivo de avance: nº de ciclos o la duración manual tal cual (targetOf). */
    public static double objetivo(Ensayo e) {
        return porCiclos(e) ? ValorJs.numeroOCero(e.campo("cycles")) : ValorJs.numeroOCero(e.campo("durManual"));
    }

    /** Factor de contingencia (cfOf): un valor no válido o no positivo vale 1,2. */
    public static double factorContingencia(Ensayo e) {
        double cf = ValorJs.numero(e.campo("cf"));
        return (!Double.isNaN(cf) && !Double.isInfinite(cf) && cf > 0) ? cf : CF_POR_DEFECTO;
    }

    /** Días a planificar = duración × CF (schedDays). */
    public static double diasAPlanificar(Ensayo e) {
        return diasNominales(e) * factorContingencia(e);
    }

    /** Días planificados enteros (plannedDays): 0 si no hay duración. */
    public static int diasPlanificados(Ensayo e) {
        double dias = diasAPlanificar(e);
        return dias > 0 ? (int) Math.max(1, Math.ceil(dias)) : 0;
    }

    /** Fracción de avance entre 0 y 1 (progFrac). Puede ser NaN con datos no numéricos. */
    public static double fraccionAvance(Ensayo e) {
        double objetivo = objetivo(e);
        if (objetivo == 0 || Double.isNaN(objetivo)) {
            return 0;
        }
        return Math.max(0, Math.min(1, ValorJs.numeroOCero(e.campo("progressActual")) / objetivo));
    }

    /** Fracción de avance tal como la usa el motor de fechas (solo si el objetivo es > 0). */
    public static double fraccionAvanceMotor(Ensayo e) {
        double objetivo = objetivo(e);
        return objetivo > 0 ? Math.max(0, Math.min(1, ValorJs.numeroOCero(e.campo("progressActual")) / objetivo)) : 0;
    }

    /** Días que faltan, SIN volver a aplicar el CF (remDaysOf). */
    public static long diasRestantes(Ensayo e) {
        double fraccion = fraccionAvanceMotor(e);
        return (long) Math.max(1, Math.ceil(diasNominales(e) * (1 - fraccion)));
    }
}
