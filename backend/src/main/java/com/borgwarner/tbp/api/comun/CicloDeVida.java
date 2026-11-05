package com.borgwarner.tbp.api.comun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Latido de las pestañas y apagado del backend al cerrar la última.
 *
 * La 4.9.4 se apagaba a los 0,5 s de recibir /api/shutdown, también al recargar
 * la página (deuda técnica del manual, apartado 11). Aquí el apagado espera unos
 * segundos y se cancela si alguna pestaña sigue enviando latidos: recargar ya no
 * apaga el backend. No cambia nada visible salvo ese efecto indeseado.
 */
@Component
public class CicloDeVida {

    private static final Logger LOG = LoggerFactory.getLogger(CicloDeVida.class);
    private static final long ESPERA_APAGADO_MS = 8000;

    private final ApplicationContext contexto;
    private final ScheduledExecutorService temporizador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "apagado");
        hilo.setDaemon(true);
        return hilo;
    });
    private volatile long ultimoLatido = System.currentTimeMillis();

    public CicloDeVida(ApplicationContext contexto) {
        this.contexto = contexto;
    }

    public void latido() {
        ultimoLatido = System.currentTimeMillis();
    }

    public void solicitarApagado() {
        long pedido = System.currentTimeMillis();
        temporizador.schedule(() -> {
            if (ultimoLatido <= pedido) {
                LOG.info("Sin pestañas abiertas: se cierra el backend. Hasta luego.");
                System.exit(SpringApplication.exit(contexto, () -> 0));
            }
        }, ESPERA_APAGADO_MS, TimeUnit.MILLISECONDS);
    }
}
