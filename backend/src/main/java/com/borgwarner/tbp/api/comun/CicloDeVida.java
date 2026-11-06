package com.borgwarner.tbp.api.comun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Pestañas abiertas y apagado del backend al cerrar la última.
 *
 * Cada pestaña de la interfaz React se identifica con un código propio que
 * envía en cada latido. Una pestaña SOLO deja de contar cuando avisa de que se
 * cierra de verdad (/api/shutdown con su código). Minimizar el navegador, dejar
 * la pestaña en segundo plano o que Edge la «duerma» frena o detiene los
 * latidos, pero no da de baja la pestaña: por eso no se mira cuánto hace del
 * último latido de una pestaña registrada.
 *
 * Al cerrarse la última pestaña se esperan unos segundos y se vuelve a mirar;
 * así una recarga (que cierra la pestaña y la vuelve a abrir con otro código)
 * no apaga el backend.
 *
 * La interfaz clásica (/clasico) es el HTML de la 4.9.4 y no envía código. Sus
 * latidos se tratan como una pestaña más mientras lleguen con una cadencia
 * razonable: el navegador espacia los temporizadores de las pestañas ocultas
 * hasta uno por minuto, así que se le da un margen holgado.
 */
@Component
public class CicloDeVida {

    private static final Logger LOG = LoggerFactory.getLogger(CicloDeVida.class);
    static final long ESPERA_APAGADO_MS = 8000;
    /** Margen para la interfaz clásica oculta (los navegadores espacian sus latidos hasta 1/min). */
    static final long MARGEN_CLASICA_MS = 150_000;

    private final Runnable apagar;
    private final LongSupplier reloj;
    private final ScheduledExecutorService temporizador;

    /** Pestañas React abiertas: código → momento del último latido (solo informativo). */
    private final Map<String, Long> pestanas = new ConcurrentHashMap<>();
    /** Sin latidos de la interfaz clásica (o ya cerrada). */
    private static final long SIN_LATIDO = -1;
    private volatile long ultimoLatidoClasica = SIN_LATIDO;
    private volatile boolean apagado = false;

    @Autowired
    public CicloDeVida(ApplicationContext contexto) {
        this(() -> {
            LOG.info("Sin pestañas abiertas: se cierra el backend. Hasta luego.");
            System.exit(SpringApplication.exit(contexto, () -> 0));
        }, System::currentTimeMillis, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread hilo = new Thread(r, "apagado");
            hilo.setDaemon(true);
            return hilo;
        }));
    }

    /** Para las pruebas: apagado, reloj y temporizador sustituibles. */
    CicloDeVida(Runnable apagar, LongSupplier reloj, ScheduledExecutorService temporizador) {
        this.apagar = apagar;
        this.reloj = reloj;
        this.temporizador = temporizador;
    }

    /** Latido de una pestaña. Sin código = interfaz clásica (4.9.4). */
    public void latido(String pestana) {
        if (pestana == null || pestana.isBlank()) {
            ultimoLatidoClasica = reloj.getAsLong();
        } else {
            pestanas.put(pestana, reloj.getAsLong());
        }
    }

    /**
     * Aviso de cierre de una pestaña. Con código, esa pestaña deja de contar. Sin
     * código (interfaz clásica), su último latido deja de valer.
     */
    public void solicitarApagado(String pestana) {
        if (pestana == null || pestana.isBlank()) {
            ultimoLatidoClasica = SIN_LATIDO;
        } else {
            pestanas.remove(pestana);
        }
        if (hayPestanas()) {
            LOG.debug("Se cierra una pestaña; quedan {} abiertas", pestanas.size());
            return;
        }
        temporizador.schedule(this::comprobarApagado, ESPERA_APAGADO_MS, TimeUnit.MILLISECONDS);
    }

    /** Pasado el margen: se apaga solo si sigue sin haber ninguna pestaña. */
    void comprobarApagado() {
        if (!apagado && !hayPestanas()) {
            apagado = true;
            apagar.run();
        }
    }

    boolean hayPestanas() {
        long clasica = ultimoLatidoClasica;
        return !pestanas.isEmpty() || (clasica != SIN_LATIDO && reloj.getAsLong() - clasica <= MARGEN_CLASICA_MS);
    }

    /** Número de pestañas React registradas (diagnóstico). */
    public int pestanasAbiertas() {
        return pestanas.size();
    }
}
