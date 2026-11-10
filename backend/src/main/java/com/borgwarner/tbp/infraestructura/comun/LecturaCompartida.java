package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.Cancelacion;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Una lectura de SharePoint que se comparte en lugar de repetirse.
 *
 *  - Si llegan varias peticiones a la vez (al arrancar la interfaz, al
 *    recargar la página…), solo la primera lee de SharePoint; las demás
 *    esperan a esa misma lectura.
 *  - Opcionalmente, el resultado se reutiliza durante unos segundos
 *    (vigencia). Con vigencia 0 solo se comparte la lectura en curso.
 *  - {@link #invalidar()} se llama al escribir: a partir de ese momento nadie
 *    se une a una lectura empezada antes ni reutiliza un resultado anterior.
 *  - Cada llamada recibe su PROPIA copia (el resultado guardado no se toca).
 *  - Un fallo no se guarda: la siguiente llamada vuelve a leer.
 *  - La lectura compartida no se cancela aunque la pestaña que la empezó se
 *    cierre (la necesitan las demás); quien solo espera sí deja de esperar.
 */
public final class LecturaCompartida<T> {

    private final long vigenciaMs;
    private final UnaryOperator<T> copiar;
    private final Predicate<T> guardable;
    private final LongSupplier relojMs;

    private final Object cerrojo = new Object();
    private long generacion;
    private CompletableFuture<T> enCurso;
    private long generacionEnCurso;
    private T valor;
    private long leidoEn;
    private long generacionValor = -1;
    private long lecturasReales;

    public LecturaCompartida(long vigenciaMs, UnaryOperator<T> copiar) {
        this(vigenciaMs, copiar, v -> true, System::currentTimeMillis);
    }

    public LecturaCompartida(long vigenciaMs, UnaryOperator<T> copiar, Predicate<T> guardable) {
        this(vigenciaMs, copiar, guardable, System::currentTimeMillis);
    }

    public LecturaCompartida(long vigenciaMs, UnaryOperator<T> copiar, Predicate<T> guardable, LongSupplier relojMs) {
        this.vigenciaMs = Math.max(0, vigenciaMs);
        this.copiar = copiar;
        this.guardable = guardable;
        this.relojMs = relojMs;
    }

    /** Devuelve una copia del valor vigente o lee (una sola vez para todos los que lleguen a la vez). */
    public T leer(Supplier<T> cargar) {
        CompletableFuture<T> futuro;
        boolean lector = false;
        long generacionLectura;
        synchronized (cerrojo) {
            if (valor != null && generacionValor == generacion && relojMs.getAsLong() - leidoEn < vigenciaMs) {
                return copiar.apply(valor);
            }
            if (enCurso != null && generacionEnCurso == generacion) {
                futuro = enCurso;
            } else {
                futuro = new CompletableFuture<>();
                enCurso = futuro;
                generacionEnCurso = generacion;
                lector = true;
            }
            generacionLectura = generacionEnCurso;
        }
        if (lector) {
            long inicio = relojMs.getAsLong();
            T leido;
            try {
                leido = Cancelacion.sinCancelar(cargar);
            } catch (RuntimeException | Error fallo) {
                synchronized (cerrojo) {
                    if (enCurso == futuro) {
                        enCurso = null;
                    }
                }
                futuro.completeExceptionally(fallo);
                throw fallo;
            }
            synchronized (cerrojo) {
                lecturasReales++;
                if (enCurso == futuro) {
                    enCurso = null;
                }
                if (vigenciaMs > 0 && generacionLectura == generacion && leido != null && guardable.test(leido)) {
                    valor = leido;
                    leidoEn = inicio;
                    generacionValor = generacionLectura;
                }
            }
            futuro.complete(leido);
            return leido == null ? null : copiar.apply(leido);
        }
        T compartido = esperar(futuro);
        return compartido == null ? null : copiar.apply(compartido);
    }

    /** Espera la lectura de otro; si la pestaña de ESTA petición se cierra, deja de esperar. */
    private T esperar(CompletableFuture<T> futuro) {
        while (true) {
            Cancelacion.comprobar();
            try {
                return futuro.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException sigue) {
                // se vuelve a comprobar la cancelación
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrumpido esperando una lectura", e);
            } catch (ExecutionException e) {
                Throwable causa = e.getCause();
                if (causa instanceof RuntimeException re) {
                    throw re;
                }
                if (causa instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(causa);
            }
        }
    }

    /** Tras escribir: lo leído antes ya no vale. */
    public void invalidar() {
        synchronized (cerrojo) {
            generacion++;
            valor = null;
            generacionValor = -1;
        }
    }

    /** Lecturas que han llegado de verdad al almacén (diagnóstico y pruebas). */
    public long lecturasReales() {
        synchronized (cerrojo) {
            return lecturasReales;
        }
    }
}
