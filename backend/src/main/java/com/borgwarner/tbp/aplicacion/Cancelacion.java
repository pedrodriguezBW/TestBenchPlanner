package com.borgwarner.tbp.aplicacion;

import java.util.function.Supplier;

/**
 * Cancelación de las CONSULTAS de una pestaña que ya no existe.
 *
 * Al recargar la página, el navegador abandona las peticiones de la página
 * anterior, pero el backend seguía haciendo su trabajo contra SharePoint (por
 * ejemplo, el estado de todos los Approval ID) y la página nueva tenía que
 * esperar detrás. Ahora cada petición de solo lectura de la interfaz React
 * lleva el código de su pestaña; cuando esa pestaña avisa de que se cierra
 * (/api/shutdown), sus consultas pendientes se descartan.
 *
 * Nunca se cancela una escritura: un guardado ya enviado termina siempre.
 *
 * El testigo de la petición en curso viaja en el hilo (ThreadLocal) y la cola
 * de SharePoint lo copia a cada tarea que encola.
 */
public final class Cancelacion {

    /** Testigo de una pestaña: se marca una sola vez y no se desmarca. */
    public static final class Testigo {
        private volatile boolean cancelado;

        public boolean cancelado() {
            return cancelado;
        }

        public void cancelar() {
            cancelado = true;
        }
    }

    private static final ThreadLocal<Testigo> ACTUAL = new ThreadLocal<>();

    private Cancelacion() {
    }

    /** Testigo de la petición que se está atendiendo en este hilo (null = no cancelable). */
    public static Testigo actual() {
        return ACTUAL.get();
    }

    /** Asocia un testigo al hilo actual y devuelve el que había, para restaurarlo. */
    public static Testigo vincular(Testigo testigo) {
        Testigo anterior = ACTUAL.get();
        if (testigo == null) {
            ACTUAL.remove();
        } else {
            ACTUAL.set(testigo);
        }
        return anterior;
    }

    /** Ejecuta un bloque con un testigo concreto (o ninguno) y restaura el anterior. */
    public static <T> T con(Testigo testigo, Supplier<T> bloque) {
        Testigo anterior = vincular(testigo);
        try {
            return bloque.get();
        } finally {
            vincular(anterior);
        }
    }

    /**
     * Ejecuta un bloque que NO se puede cancelar: lecturas compartidas por
     * varias peticiones y operaciones que acaban escribiendo.
     */
    public static <T> T sinCancelar(Supplier<T> bloque) {
        return con(null, bloque);
    }

    public static boolean cancelada() {
        Testigo t = ACTUAL.get();
        return t != null && t.cancelado();
    }

    /** Lanza PeticionCancelada si la pestaña que pidió este trabajo ya no existe. */
    public static void comprobar() {
        if (cancelada()) {
            throw new PeticionCancelada();
        }
    }
}
