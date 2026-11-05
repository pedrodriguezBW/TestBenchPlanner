package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.Versiones;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Duraciones;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.planificacion.ContextoPlan;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.borgwarner.tbp.dominio.planificacion.MotorFechas;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.Sugerencias;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

/**
 * Convierte el estado de la planificación en el JSON que consume la interfaz
 * React. Incluye los datos del ensayo tal cual (DataJson) y, aparte, todo lo
 * calculado por el dominio: la interfaz no recalcula ninguna regla.
 */
public final class RepresentacionVista {

    private RepresentacionVista() {
    }

    public static ObjectNode de(Vista vista) {
        Planificacion p = vista.planificacion();
        ContextoPlan contexto = vista.contexto();
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("hoy", Fechas.iso(contexto.hoy()));
        salida.put("semanaHoy", Fechas.semanaIso(contexto.hoy()));

        ObjectNode calendario = salida.putObject("calendario");
        calendario.put("anio", contexto.calendario().anio());
        calendario.put("saltarFestivos", contexto.calendario().saltarFestivos());
        ArrayNode festivos = calendario.putArray("festivos");
        for (Festivo f : contexto.calendario().festivos()) {
            festivos.addObject().put("fecha", f.date()).put("nombre", f.name());
        }

        ArrayNode grupos = salida.putArray("grupos");
        for (Grupo g : p.configuracion().grupos()) {
            ObjectNode grupo = grupos.addObject();
            grupo.put("clave", g.key()).put("nombre", g.name()).put("color", g.dot());
            ArrayNode bancos = grupo.putArray("bancos");
            g.benches().forEach(b -> bancos.addObject().put("nombre", b).put("organizacion", p.configuracion().organizacion(b)));
        }

        ObjectNode bancos = salida.putObject("bancos");
        for (String banco : p.bancos()) {
            ArrayNode lista = bancos.putArray(banco);
            int prioridad = 0;
            for (Ensayo e : p.ensayos(banco)) {
                ObjectNode fila = lista.addObject();
                fila.put("id", e.id());
                fila.put("version", Versiones.de(p.textoLeido(e.id())));
                fila.put("cerrado", e.cerrado());
                if (!e.cerrado()) {
                    prioridad++;
                    fila.put("prioridad", prioridad);
                }
                fila.set("datos", e.datos());
                fila.set("calculo", calculo(e, vista));
            }
        }
        return salida;
    }

    /** Todo lo que la 4.9.4 calculaba en el navegador para pintar una fila. */
    static ObjectNode calculo(Ensayo e, Vista vista) {
        ContextoPlan contexto = vista.contexto();
        LocalDate hoy = contexto.hoy();
        PlanEnsayo plan = vista.plan().de(e);
        ObjectNode c = JsonJs.objetoVacio();
        c.put("estado", EstadosEnsayo.estado(e, hoy));
        c.put("estadoDeclarado", EstadosEnsayo.estadoDeclarado(e));
        c.put("diasNominales", Duraciones.diasNominales(e));
        c.put("diasPlanificados", Duraciones.diasPlanificados(e));
        c.put("fraccionAvance", Duraciones.fraccionAvance(e));
        c.put("tipoEspecial", e.esTipoEspecial());
        if (e.cerrado()) {
            c.put("estadoFinal", EstadosEnsayo.estadoFinal(e));
            c.put("cierreConfirmado", EstadosEnsayo.cierreConfirmado(e));
            return c;
        }
        c.put("inicio", Fechas.iso(plan.inicio()));
        c.put("fin", Fechas.iso(plan.fin()));
        c.put("criterioInicio", plan.criterio());
        c.put("arranqueEfectivo", EstadosEnsayo.arranqueEfectivo(e, hoy));
        c.put("arranqueAsumido", EstadosEnsayo.arranqueAsumido(e, hoy));
        c.put("finTolerado", Fechas.iso(MotorFechas.finTolerado(e, contexto.calendario())));
        c.put("finPlanificado", Fechas.iso(MotorFechas.finPlanificado(e, contexto)));
        c.put("diasSinAvance", MotorFechas.diasSinAvance(e, hoy));
        ArrayNode solapes = c.putArray("solapes");
        plan.solapes().forEach(s -> solapes.addObject().put("etiqueta", s.etiqueta())
                .put("inicio", Fechas.iso(s.inicio())).put("fin", Fechas.iso(s.fin())));
        ArrayNode sugerencias = c.putArray("sugerencias");
        for (Sugerencias.Sugerencia s : Sugerencias.de(e, plan, contexto)) {
            ObjectNode sug = sugerencias.addObject().put("clave", s.clave());
            if (s.dias() != null) {
                sug.put("dias", s.dias());
            }
            sug.put("fecha", Fechas.iso(s.fecha()));
            sug.put("referencia", Fechas.iso(s.referencia()));
        }
        c.put("sugerenciaAbierta", Sugerencias.abierta(e, plan, contexto));
        return c;
    }
}
