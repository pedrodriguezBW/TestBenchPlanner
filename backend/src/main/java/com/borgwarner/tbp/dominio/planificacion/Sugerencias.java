package com.borgwarner.tbp.dominio.planificacion;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sugerencias «⚠ ¿En retraso?» (suggestionsFor de la 4.9.4). La aplicación
 * nunca cambia el estado por su cuenta: solo propone, y la persona decide.
 */
public final class Sugerencias {

    /** Días sin reportar avance antes de sugerir retraso (NO_REPORT_DAYS). */
    public static final int DIAS_SIN_REPORTE = 7;

    /** Clave de la sugerencia y las fechas que la explican (el texto lo compone la interfaz). */
    public record Sugerencia(String clave, Long dias, LocalDate fecha, LocalDate referencia) {
    }

    private Sugerencias() {
    }

    public static List<Sugerencia> de(Ensayo e, PlanEnsayo plan, ContextoPlan contexto) {
        List<Sugerencia> lista = new ArrayList<>();
        if (e.cerrado() || e.esTipoEspecial()) {
            return lista;
        }
        LocalDate hoy = contexto.hoy();
        long sinAvance = MotorFechas.diasSinAvance(e, hoy);
        if (sinAvance > DIAS_SIN_REPORTE) {
            lista.add(new Sugerencia("noprog", sinAvance, null, null));
        }
        LocalDate fin = plan == null ? null : plan.fin();
        LocalDate tolerado = MotorFechas.finTolerado(e, contexto.calendario());
        if (tolerado != null && fin != null && fin.isAfter(tolerado)) {
            lista.add(new Sugerencia("endlate", null, fin, Fechas.dia(e.texto("confirmedEnd"))));
        }
        if (e.verdadero("confirmedStart") && EstadosEnsayo.arrancado(e, hoy)) {
            LocalDate real = Fechas.dia(EstadosEnsayo.arranqueReal(e));
            LocalDate comprometido = Fechas.dia(e.texto("confirmedStart"));
            if (Fechas.posterior(real, comprometido)) {
                lista.add(new Sugerencia("startlate", null, real, comprometido));
            }
        }
        LocalDate planificado = MotorFechas.finPlanificado(e, contexto);
        if (planificado != null && fin != null && fin.isAfter(planificado)) {
            lista.add(new Sugerencia("slow", null, fin, planificado));
        }
        return lista;
    }

    /** Estado sugerido: «delayed» si hay alguna sugerencia; «» si no. */
    public static String estadoSugerido(Ensayo e, PlanEnsayo plan, ContextoPlan contexto) {
        return de(e, plan, contexto).isEmpty() ? "" : EstadosEnsayo.EN_RETRASO;
    }

    /** La sugerencia solo se muestra si es peor que lo declarado (suggestionOpen). */
    public static boolean abierta(Ensayo e, PlanEnsayo plan, ContextoPlan contexto) {
        return !de(e, plan, contexto).isEmpty() && EstadosEnsayo.EN_PLAZO.equals(EstadosEnsayo.estadoDeclarado(e));
    }
}
