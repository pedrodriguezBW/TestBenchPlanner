package com.borgwarner.tbp.dominio.ensayo;

import java.util.List;

/** Tipos de ensayo que bloquean el banco (SPECIAL_TYPES de la 4.9.4). */
public final class TiposEspeciales {

    public static final List<String> TIPOS = List.of("Calibration", "Maintenance", "Breakdown", "Not available");

    private TiposEspeciales() {
    }

    public static boolean contiene(String tipo) {
        return TIPOS.contains(tipo);
    }
}
