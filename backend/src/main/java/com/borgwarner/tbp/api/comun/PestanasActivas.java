package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Testigo de cancelación de cada pestaña React (por su código de pestaña).
 *
 * Cuando una pestaña avisa de que se cierra o se recarga (/api/shutdown), su
 * testigo se cancela: las consultas que aún esperan turno para SharePoint se
 * descartan y la página nueva no tiene que esperar detrás de ellas. Si la
 * misma pestaña vuelve (caché del navegador), su siguiente petición recibe un
 * testigo nuevo.
 */
@Component
public class PestanasActivas {

    private final Map<String, Cancelacion.Testigo> testigos = new ConcurrentHashMap<>();

    /** Testigo vigente de una pestaña (null si la petición no trae código). */
    public Cancelacion.Testigo testigoDe(String pestana) {
        if (pestana == null || pestana.isBlank()) {
            return null;
        }
        return testigos.computeIfAbsent(pestana, p -> new Cancelacion.Testigo());
    }

    /** La pestaña se ha cerrado o recargado: sus consultas pendientes ya no hacen falta. */
    public void cancelar(String pestana) {
        if (pestana == null || pestana.isBlank()) {
            return;
        }
        Cancelacion.Testigo testigo = testigos.remove(pestana);
        if (testigo != null) {
            testigo.cancelar();
        }
    }

    /** Pestañas con testigo (diagnóstico y pruebas). */
    public int conTestigo() {
        return testigos.size();
    }
}
