package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;

import java.util.List;
import java.util.function.Predicate;

/** Filtro de bancos por organización: ALL, BW o CTAG. */
public final class FiltroOrganizacion {

    public static final List<String> ORGANIZACIONES = List.of("ALL", "BW", "CTAG");

    private FiltroOrganizacion() {
    }

    public static Predicate<String> de(String organizacion, ConfiguracionBancos configuracion) {
        if (organizacion == null || "ALL".equals(organizacion)) {
            return banco -> true;
        }
        return banco -> organizacion.equals(configuracion.organizacion(banco));
    }
}
