package com.borgwarner.tbp.dominio;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.kpi.FiltroOrganizacion;
import com.borgwarner.tbp.dominio.kpi.HistoricoKpi;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.BancoConfigurado;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Áreas de bancos: lectura de la columna Area, filtros y lectura del histórico de KPI. */
class AreasTest {

    private static ConfiguracionBancos configuracion() {
        return ConfiguracionBancos.desdeLista(List.of(
                new BancoConfigurado("MD1", "dur", "Durability", "#111111", "BW", 1.0),
                new BancoConfigurado("2000 CTAG", "dur", "Durability", "#111111", "CTAG", 2.0, ""),
                new BancoConfigurado("H1", "hc", "HVCH climatic", "#222222", "CTAG", 3.0, "HVCH-CTAG"),
                new BancoConfigurado("H2", "hc", "HVCH climatic", "#222222", "CTAG", 4.0, null),
                new BancoConfigurado("S1", "hs", "STC", "#333333", "STC", 5.0, "hvch - stc")), Map.of("HVCH-STC", "STC benches"));
    }

    @Test
    void elAreaEsDeLaCategoriaYVacioEsTermicos() {
        ConfiguracionBancos c = configuracion();
        assertEquals(Areas.TERMICOS, c.area("MD1"));
        assertEquals(Areas.TERMICOS, c.area("2000 CTAG"));
        assertEquals(Areas.HVCH_CTAG, c.area("H1"));
        assertEquals(Areas.HVCH_CTAG, c.area("H2"), "la segunda fila de la categoría hereda el área");
        assertEquals(Areas.HVCH_STC, c.area("S1"), "el valor guardado se normaliza");
        assertEquals(Areas.TERMICOS, Areas.normalizar("cualquier cosa"));
        assertEquals("Thermal Benches", c.nombreArea(Areas.TERMICOS));
        assertEquals("STC benches", c.nombreArea(Areas.HVCH_STC));
        assertEquals(List.of(Areas.TERMICOS, Areas.HVCH_CTAG, Areas.HVCH_STC), List.copyOf(c.nombresAreas().keySet()));
        assertEquals("CTAG", Areas.organizacionFija(Areas.HVCH_CTAG));
        assertEquals("STC", Areas.organizacionFija(Areas.HVCH_STC));
        assertEquals(null, Areas.organizacionFija(Areas.TERMICOS));
    }

    @Test
    void filtrosPorAreaYOrganizacion() {
        ConfiguracionBancos c = configuracion();
        List<String> bancos = c.bancos();
        assertEquals(List.of("MD1", "2000 CTAG", "H1", "H2", "S1"), seleccion(bancos, FiltroOrganizacion.de("ALL", c)));
        assertEquals(List.of("MD1", "2000 CTAG"), seleccion(bancos, FiltroOrganizacion.de("THERMAL", c)));
        assertEquals(List.of("MD1"), seleccion(bancos, FiltroOrganizacion.de("BW", c)));
        assertEquals(List.of("2000 CTAG"), seleccion(bancos, FiltroOrganizacion.de("CTAG", c)),
                "CTAG son solo los térmicos de CTAG, no los de HVCH - CTAG");
        assertEquals(List.of("H1", "H2"), seleccion(bancos, FiltroOrganizacion.de("HVCH-CTAG", c)));
        assertEquals(List.of("S1"), seleccion(bancos, FiltroOrganizacion.de("HVCH-STC", c)));
        assertEquals(6, FiltroOrganizacion.FILTROS.size());
        assertEquals(List.of("ALL", "BW", "CTAG"), FiltroOrganizacion.ORGANIZACIONES);
    }

    @Test
    void sinAreasLosFiltrosDeSiempreNoCambian() {
        ConfiguracionBancos c = ConfiguracionBancos.desdeLista(List.of(
                new BancoConfigurado("A", "g", "G", "#000000", "BW", 1.0),
                new BancoConfigurado("B", "g", "G", "#000000", "CTAG", 2.0),
                new BancoConfigurado("C", "g", "G", "#000000", "", 3.0)));
        assertEquals(List.of("A", "C"), seleccion(c.bancos(), FiltroOrganizacion.de("BW", c)));
        assertEquals(List.of("B"), seleccion(c.bancos(), FiltroOrganizacion.de("CTAG", c)));
        assertEquals(List.of("A", "B", "C"), seleccion(c.bancos(), FiltroOrganizacion.de("THERMAL", c)));
        assertTrue(seleccion(c.bancos(), FiltroOrganizacion.de("HVCH-STC", c)).isEmpty());
    }

    @Test
    void historicoTermicosUsaAllEnLasSemanasAntiguas() {
        List<JsonNode> filas = List.of(
                fila(2026, 40, "ALL", 10), fila(2026, 40, "BW", 7), fila(2026, 40, "CTAG", 3),
                fila(2026, 41, "ALL", 12), fila(2026, 41, "THERMAL", 9), fila(2026, 41, "HVCH-STC", 3));
        List<HistoricoKpi.Semana> termicos = HistoricoKpi.semanas(filas, "THERMAL");
        assertEquals(2, termicos.size());
        assertEquals(10, termicos.get(0).benches(), "semana sin fila THERMAL: se usa ALL");
        assertEquals("THERMAL", termicos.get(0).org());
        assertEquals(9, termicos.get(1).benches(), "semana con fila THERMAL: la suya");
        assertEquals(2, HistoricoKpi.semanas(filas, "ALL").size());
        assertEquals(1, HistoricoKpi.semanas(filas, "BW").size());
        assertEquals(1, HistoricoKpi.semanas(filas, "HVCH-STC").size());
        assertFalse(HistoricoKpi.semanas(filas, "HVCH-CTAG").iterator().hasNext());
    }

    private static JsonNode fila(int anio, int cw, String org, int bancos) {
        return JsonJs.objetoVacio().put("Year", anio).put("CW", cw).put("Org", org).put("Benches", bancos);
    }

    private static List<String> seleccion(List<String> bancos, Predicate<String> filtro) {
        return bancos.stream().filter(filtro).toList();
    }
}
