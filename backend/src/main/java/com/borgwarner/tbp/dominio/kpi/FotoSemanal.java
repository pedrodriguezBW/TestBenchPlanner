package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Foto semanal de KPI para el histórico (snapshotKPIs de la 4.9.4), una por
 * organización: ALL, BW y CTAG. Siempre es la semana de HOY.
 *
 * Corrección aprobada D8: el año de la foto es el año de la semana ISO. La
 * 4.9.4 usaba el año natural, de modo que el 1-ene-2027 generaba «2027-CW53»
 * y el 31-dic-2029 «2029-CW01», que sobrescribía la semana 1 real de 2029.
 *
 * Se devuelve como JSON ordenado porque así se guarda en KPIJson y el orden
 * de las claves forma parte del texto.
 */
public final class FotoSemanal {

    private FotoSemanal() {
    }

    public static List<ObjectNode> tomar(Planificacion planificacion, ResultadoPlan plan) {
        LocalDate hoy = plan.contexto().hoy();
        LocalDate lunes = Fechas.lunesDe(hoy);
        LocalDate domingo = lunes.plusDays(6);
        int anio = Fechas.anioDeSemanaIso(hoy);   // D8 (antes: hoy.getYear())
        int semana = Fechas.semanaIso(hoy);
        List<ObjectNode> fotos = new ArrayList<>();
        for (String organizacion : FiltroOrganizacion.ORGANIZACIONES) {
            Predicate<String> filtro = FiltroOrganizacion.de(organizacion, planificacion.configuracion());
            IndicadoresSemana.Semana k = IndicadoresSemana.semana(planificacion, plan, lunes, filtro);
            int total = (int) planificacion.bancos().stream().filter(filtro).count();

            List<String> todos = new ArrayList<>();
            ArrayNode porGrupo = JsonJs.mapper().createArrayNode();
            for (Grupo grupo : planificacion.configuracion().grupos()) {
                List<String> bancosGrupo = grupo.benches().stream().filter(filtro).toList();
                if (bancosGrupo.isEmpty()) {
                    continue;
                }
                todos.addAll(bancosGrupo);
                Ocupacion.Horas o = Ocupacion.de(planificacion, plan, bancosGrupo, lunes, domingo);
                ObjectNode g = porGrupo.addObject();
                g.put("key", grupo.key());
                g.put("name", grupo.name());
                g.put("benches", bancosGrupo.size());
                g.put("busy", o.busy());
                g.put("cap", o.cap());
                g.put("pct", o.pct());
            }
            Ocupacion.Horas ocupacion = Ocupacion.de(planificacion, plan, todos, lunes, domingo);

            ObjectNode foto = JsonJs.objetoVacio();
            foto.put("year", anio);
            foto.put("cw", semana);
            foto.put("date", Fechas.iso(hoy));
            foto.put("org", organizacion);
            foto.put("total", total);
            foto.put("benches", k.benches());
            foto.put("starts", k.starts());
            foto.put("ends", k.ends());
            foto.put("running", k.running());
            foto.put("delayed", k.delayed());
            foto.put("unplanned", k.unplanned());
            ObjectNode motivos = foto.putObject("reasons");
            k.reasons().forEach(motivos::put);
            foto.put("delayedDeclared", k.delayed());
            foto.put("occBusy", ocupacion.busy());
            foto.put("occCap", ocupacion.cap());
            foto.put("occPct", ocupacion.pct());
            foto.set("occByGroup", porGrupo);
            fotos.add(foto);
        }
        return fotos;
    }
}
