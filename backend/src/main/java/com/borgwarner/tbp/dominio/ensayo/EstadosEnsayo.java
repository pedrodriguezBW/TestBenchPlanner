package com.borgwarner.tbp.dominio.ensayo;

import com.borgwarner.tbp.dominio.calendario.Fechas;

import java.time.LocalDate;

/**
 * Arranque efectivo y estado de un ensayo (effStartOf, isStarted, statusOf…).
 *
 * Estados: queued (en cola), confirmed (comprometido, sin arrancar), running
 * (en marcha y en plazo), delayed (retraso declarado), completed y special
 * (tipo que bloquea el banco). La aplicación nunca declara un retraso por su
 * cuenta: solo lo sugiere (ver Sugerencias).
 */
public final class EstadosEnsayo {

    public static final String EN_COLA = "queued";
    public static final String COMPROMETIDO = "confirmed";
    public static final String EN_MARCHA = "running";
    public static final String EN_RETRASO = "delayed";
    public static final String COMPLETADO = "completed";
    public static final String ESPECIAL = "special";

    /** Estados que se pueden declarar a mano. */
    public static final String EN_PLAZO = "ok";

    private EstadosEnsayo() {
    }

    /** Arranque real escrito por el usuario, o «». */
    public static String arranqueReal(Ensayo e) {
        return e.verdadero("actualStart") ? e.texto("actualStart") : "";
    }

    /**
     * Arranque efectivo: el real; si no, el forzado o el comprometido si ya han
     * llegado (arranque asumido); si no, «».
     */
    public static String arranqueEfectivo(Ensayo e, LocalDate hoy) {
        String real = arranqueReal(e);
        if (!real.isEmpty()) {
            return real;
        }
        String forzado = (e.verdadero("startLock") && e.verdadero("startOverride")) ? e.texto("startOverride") : "";
        if (!forzado.isEmpty() && Fechas.noPosterior(Fechas.dia(forzado), hoy)) {
            return forzado;
        }
        if (e.verdadero("confirmedStart") && Fechas.noPosterior(Fechas.dia(e.texto("confirmedStart")), hoy)) {
            return e.texto("confirmedStart");
        }
        return "";
    }

    public static boolean arrancado(Ensayo e, LocalDate hoy) {
        String arranque = arranqueEfectivo(e, hoy);
        return !arranque.isEmpty() && Fechas.noPosterior(Fechas.dia(arranque), hoy);
    }

    /** Arrancado sin arranque real escrito (insignia A). */
    public static boolean arranqueAsumido(Ensayo e, LocalDate hoy) {
        return arranqueReal(e).isEmpty() && arrancado(e, hoy);
    }

    /** Estado declarado: «ok» o «delayed». El valor legado «risk» cuenta como retraso. */
    public static String estadoDeclarado(Ensayo e) {
        String valor = e.texto("manualState");
        if ("risk".equals(valor)) {
            valor = EN_RETRASO;
        }
        return (EN_PLAZO.equals(valor) || EN_RETRASO.equals(valor)) ? valor : EN_PLAZO;
    }

    /** Estado final de un cierre; «risk» y «behind» (legados) cuentan como retraso. */
    public static String estadoFinal(Ensayo e) {
        String valor = e.texto("finalStatus");
        if ("risk".equals(valor) || "behind".equals(valor)) {
            valor = EN_RETRASO;
        }
        return (EN_PLAZO.equals(valor) || EN_RETRASO.equals(valor)) ? valor : EN_PLAZO;
    }

    public static String estado(Ensayo e, LocalDate hoy) {
        if (e.cerrado()) {
            return COMPLETADO;
        }
        if (e.esTipoEspecial()) {
            return ESPECIAL;
        }
        boolean arrancado = arrancado(e, hoy);
        if (!arrancado && !e.verdadero("confirmedStart")) {
            return EN_COLA;
        }
        if (EN_PLAZO.equals(estadoDeclarado(e))) {
            return arrancado ? EN_MARCHA : COMPROMETIDO;
        }
        return EN_RETRASO;
    }

    /** Cierre confirmado en la ventana de cierre (finalConfirmedAt presente). */
    public static boolean cierreConfirmado(Ensayo e) {
        return e.verdadero("finalConfirmedAt");
    }
}
