package com.borgwarner.tbp.aplicacion;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo sin conexión (H-04, como la 4.9.4 v4.9): qué listas se están sirviendo
 * desde la última copia buena guardada en este PC porque SharePoint no responde.
 *
 * Mientras alguna lista venga de la copia, la sesión es de solo consulta y
 * ninguna lectura cuenta como «lectura correcta» para guardar (D11): con datos
 * viejos no se reconcilia nada.
 */
@Component
public class EstadoConexion {

    /** lista → fecha de la copia que se está mostrando. */
    private final Map<String, Instant> deCopia = new ConcurrentHashMap<>();

    public void servidaDesdeCopia(String lista, Instant fechaCopia) {
        deCopia.put(lista, fechaCopia);
    }

    public void leidaDelServidor(String lista) {
        deCopia.remove(lista);
    }

    public boolean sinConexion() {
        return !deCopia.isEmpty();
    }

    /** Fecha de la copia más antigua que se está mostrando (null si hay conexión). */
    public Instant copiaDe() {
        return deCopia.values().stream().min(Instant::compareTo).orElse(null);
    }

    /** Olvida el estado (pruebas y reinicio local). */
    public void olvidar() {
        deCopia.clear();
    }
}
