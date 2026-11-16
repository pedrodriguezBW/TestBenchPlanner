package com.borgwarner.tbp.dominio.planificacion;

/**
 * Fila de la lista de bancos (TestBenchBenches).
 *
 * @param area código del área de la categoría (columna Area; vacío = Bancos Térmicos)
 */
public record BancoConfigurado(String name, String groupKey, String groupName, String dot, String org, Double seq, String area) {

    /** Fila sin área (formato de la 4.9.4): cuenta como Bancos Térmicos. */
    public BancoConfigurado(String name, String groupKey, String groupName, String dot, String org, Double seq) {
        this(name, groupKey, groupName, dot, org, seq, null);
    }
}
