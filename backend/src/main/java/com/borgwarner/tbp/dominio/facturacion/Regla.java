package com.borgwarner.tbp.dominio.facturacion;

import java.util.Locale;

/**
 * Reglas de cálculo del coste de una línea, las mismas que las fórmulas de la
 * columna «Coste total» del Excel de facturación. Se asignan a cada Equipment
 * Type; no se editan como texto.
 */
public enum Regla {
    LINEAR("Lineal", "Coste = horas totales × tarifa", "HORAS"),
    ASINH_100("Reductora /100", "Coste = tarifa × ASINH(horas totales / 100) × 100", "HORAS"),
    ASINH_200("Reductora /200", "Coste = tarifa × ASINH(horas totales / 200) × 200", "HORAS"),
    PER_PIECE("Por pieza", "Coste = piezas × tarifa (RACS)", "PIEZAS"),
    NOT_BILLABLE("No facturable", "La línea se excluye: no suma horas ni importe", "HORAS");

    private final String nombre;
    private final String calculo;
    private final String unidad;

    Regla(String nombre, String calculo, String unidad) {
        this.nombre = nombre;
        this.calculo = calculo;
        this.unidad = unidad;
    }

    public String nombre() {
        return nombre;
    }

    public String calculo() {
        return calculo;
    }

    /** Unidad habitual de la regla (HORAS o PIEZAS). */
    public String unidad() {
        return unidad;
    }

    /** Regla por su código (sin distinguir mayúsculas); null si no existe. */
    public static Regla de(String codigo) {
        if (codigo == null) {
            return null;
        }
        try {
            return valueOf(codigo.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    /**
     * Coste de una cantidad (horas totales o piezas) con una tarifa, en el mismo
     * orden de operaciones que la fórmula del Excel. Cantidad 0 → coste 0.
     */
    public double coste(double cantidad, double tarifa) {
        if (cantidad == 0) {
            return 0;
        }
        return switch (this) {
            case LINEAR, PER_PIECE -> cantidad * tarifa;
            case ASINH_100 -> cantidad * tarifa * asinh(cantidad / 100) * 100 / cantidad;
            case ASINH_200 -> cantidad * tarifa * asinh(cantidad / 200) * 200 / cantidad;
            case NOT_BILLABLE -> 0;
        };
    }
}
