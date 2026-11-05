package com.borgwarner.tbp.dominio.planificacion;

import com.borgwarner.tbp.dominio.ensayo.Ensayo;

import java.util.HashMap;
import java.util.Map;

/**
 * Fechas calculadas de todos los ensayos activos (recomputeAll de la 4.9.4).
 * Se recalcula siempre que se necesita: nunca se guarda.
 */
public final class ResultadoPlan {

    private final Map<String, PlanEnsayo> porId = new HashMap<>();
    private final ContextoPlan contexto;

    private ResultadoPlan(ContextoPlan contexto) {
        this.contexto = contexto;
    }

    public static ResultadoPlan calcular(Planificacion planificacion, ContextoPlan contexto) {
        ResultadoPlan resultado = new ResultadoPlan(contexto);
        for (String banco : planificacion.bancos()) {
            resultado.porId.putAll(MotorFechas.planificarBanco(planificacion.activos(banco), contexto));
        }
        return resultado;
    }

    /** Plan del ensayo; SIN_FECHAS si está cerrado o no se ha planificado. */
    public PlanEnsayo de(Ensayo e) {
        return porId.getOrDefault(e.id(), PlanEnsayo.SIN_FECHAS);
    }

    public ContextoPlan contexto() {
        return contexto;
    }
}
