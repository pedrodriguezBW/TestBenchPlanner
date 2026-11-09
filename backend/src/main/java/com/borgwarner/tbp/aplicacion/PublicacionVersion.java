package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publica la versión de esta aplicación en TestBenchAppConfig al arrancar
 * (publish_version_bg del backend 4.9.4, 3 s después de arrancar y en segundo
 * plano). Nunca rebaja la versión publicada.
 *
 * Desactivado por defecto (planner.sharepoint.publicar-version=false): al
 * publicar 5.0.0, todos los PCs que sigan con la 4.9.4 verán el aviso de
 * versión antigua, así que se activa solo cuando se distribuya la nueva.
 */
@Component
public class PublicacionVersion {

    private static final Logger LOG = LoggerFactory.getLogger(PublicacionVersion.class);

    private final Colaboracion colaboracion;
    private final boolean activa;

    public PublicacionVersion(Colaboracion colaboracion, PropiedadesPlanner propiedades) {
        this.colaboracion = colaboracion;
        this.activa = propiedades.sharepoint() != null && propiedades.sharepoint().publicarVersion();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!activa) {
            return;
        }
        Thread hilo = new Thread(() -> {
            try {
                Thread.sleep(3000);
                boolean ok = colaboracion.publicarVersion(TestBenchPlannerApplication.VERSION);
                LOG.info("Versión {} {}", TestBenchPlannerApplication.VERSION, ok ? "publicada (o ya había una igual o mayor)" : "no publicada");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warn("No se pudo publicar la versión: {}", e.getMessage());
            }
        }, "publicar-version");
        hilo.setDaemon(true);
        hilo.start();
    }
}
