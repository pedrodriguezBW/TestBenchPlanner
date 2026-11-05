package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.kpi.Estadisticas;
import com.borgwarner.tbp.dominio.kpi.FiltroOrganizacion;
import com.borgwarner.tbp.dominio.kpi.FotoSemanal;
import com.borgwarner.tbp.dominio.kpi.HistoricoKpi;
import com.borgwarner.tbp.dominio.kpi.IndicadoresSemana;
import com.borgwarner.tbp.dominio.kpi.Ocupacion;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** KPI del dashboard, ocupación, foto semanal, histórico y estadísticas. */
@Service
public class ServicioIndicadores {

    private final ServicioPlanificacion planificacion;
    private final AlmacenHistoricoKpi historico;

    public ServicioIndicadores(ServicioPlanificacion planificacion, AlmacenHistoricoKpi historico) {
        this.planificacion = planificacion;
        this.historico = historico;
    }

    /** Bloques «Esta semana» y «Semana que viene» a partir del lunes indicado. */
    public Map<String, IndicadoresSemana.Bloque> bloques(Vista vista, LocalDate lunes, String organizacion) {
        var filtro = FiltroOrganizacion.de(organizacion, vista.planificacion().configuracion());
        Map<String, IndicadoresSemana.Bloque> bloques = new LinkedHashMap<>();
        bloques.put("estaSemana", IndicadoresSemana.bloque(vista.planificacion(), vista.plan(), Fechas.lunesDe(lunes), filtro));
        bloques.put("siguiente", IndicadoresSemana.bloque(vista.planificacion(), vista.plan(), Fechas.lunesDe(lunes).plusDays(7), filtro));
        return bloques;
    }

    /** Ocupación por tipo de banco y total en un periodo. */
    public Map<String, Object> ocupacion(Vista vista, LocalDate desde, LocalDate hasta, String organizacion) {
        var filtro = FiltroOrganizacion.de(organizacion, vista.planificacion().configuracion());
        Map<String, Object> porGrupo = new LinkedHashMap<>();
        List<String> todos = new ArrayList<>();
        for (Grupo grupo : vista.planificacion().configuracion().grupos()) {
            List<String> bancos = grupo.benches().stream().filter(filtro).toList();
            if (bancos.isEmpty()) {
                continue;
            }
            todos.addAll(bancos);
            porGrupo.put(grupo.key(), Ocupacion.de(vista.planificacion(), vista.plan(), bancos, desde, hasta));
        }
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("grupos", porGrupo);
        resultado.put("total", Ocupacion.de(vista.planificacion(), vista.plan(), todos, desde, hasta));
        return resultado;
    }

    /**
     * 📊 Update Yearly Status: toma la foto de la semana de hoy y la guarda en el
     * histórico (sustituye la de esa semana). Como en la 4.9.4, no depende del rol.
     */
    public List<ObjectNode> guardarFotoSemanal() {
        Vista vista = planificacion.vista();
        List<ObjectNode> fotos = FotoSemanal.tomar(vista.planificacion(), vista.plan());
        historico.guardarFotos(fotos);
        return fotos;
    }

    public List<HistoricoKpi.Semana> historico(String organizacion) {
        return HistoricoKpi.semanas(historico.leer(), organizacion);
    }

    public List<Estadisticas.Fila> estadisticas(Vista vista, LocalDate desde, LocalDate hasta, String organizacion, String responsable) {
        return Estadisticas.filas(vista.planificacion(), desde, hasta, organizacion, responsable);
    }
}
