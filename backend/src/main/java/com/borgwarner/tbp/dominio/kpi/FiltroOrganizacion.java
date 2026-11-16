package com.borgwarner.tbp.dominio.kpi;

import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;

import java.util.List;
import java.util.function.Predicate;

/**
 * Filtro de bancos del Dashboard, «By Responsible» y KPI. Un solo código
 * reúne los dos desplegables (área y, dentro de Bancos Térmicos, organización):
 *
 * <ul>
 *   <li>ALL: todos los bancos.</li>
 *   <li>THERMAL: Bancos Térmicos (las dos organizaciones).</li>
 *   <li>BW / CTAG: Bancos Térmicos de esa organización. Son los códigos de la
 *       4.9.4: mientras todos los bancos eran térmicos significaban lo mismo,
 *       así que el histórico guardado con ellos sigue valiendo.</li>
 *   <li>HVCH-CTAG / HVCH-STC: los bancos de esa área (sin organización).</li>
 * </ul>
 */
public final class FiltroOrganizacion {

    /** Los de la 4.9.4 (los que conoce la interfaz clásica). */
    public static final List<String> ORGANIZACIONES = List.of("ALL", "BW", "CTAG");

    /** Todos los filtros, en el orden de las fotos semanales. */
    public static final List<String> FILTROS = List.of("ALL", "BW", "CTAG", Areas.TERMICOS, Areas.HVCH_CTAG, Areas.HVCH_STC);

    private FiltroOrganizacion() {
    }

    /** Código de filtro válido (uno desconocido se trata como ALL). */
    public static String normalizar(String filtro) {
        if (filtro == null) {
            return "ALL";
        }
        String f = filtro.trim().toUpperCase(java.util.Locale.ROOT);
        return FILTROS.contains(f) ? f : "ALL";
    }

    public static Predicate<String> de(String organizacion, ConfiguracionBancos configuracion) {
        String filtro = normalizar(organizacion);
        return switch (filtro) {
            case "ALL" -> banco -> true;
            case "BW", "CTAG" -> banco -> Areas.TERMICOS.equals(configuracion.area(banco))
                    && filtro.equals(configuracion.organizacion(banco));
            default -> banco -> filtro.equals(configuracion.area(banco));
        };
    }
}
