package com.borgwarner.tbp.dominio.listas;

import java.util.Collection;

/**
 * Protección de las listas compartidas que se guardan por RECONCILIACIÓN
 * (bancos, proyectos, responsables, usuarios y recursos): guardar una lista
 * significa crear lo que llega y mandar a la papelera lo que no llega.
 *
 * Decisión D11. En la 4.9.4, si la lectura de una lista fallaba o llegaba
 * vacía, la interfaz podía enviar sus valores de serie (o una lista vacía) y el
 * backend borraba de SharePoint todo lo que no venía. Ahora:
 *   1. Si la lista guardada está vacía (lectura vacía inesperada), no se escribe.
 *   2. Si lo que se quiere guardar está vacío y la lista no, no se escribe.
 * Las dos reglas se comprueban para TODAS las listas de una misma operación
 * antes de escribir ninguna, de modo que nunca queda una escritura a medias.
 * (La tercera protección, «leer antes de escribir», está en RegistroLecturas.)
 */
public final class ProteccionListas {

    private ProteccionListas() {
    }

    /**
     * @param lista            nombre legible de la lista (para el mensaje)
     * @param existentes       claves (Title) de lo que hay guardado AHORA
     * @param entrantes        claves de lo que se quiere guardar
     * @param permitirVacias   true solo para una instalación nueva con listas vacías
     */
    public static void comprobar(String lista, Collection<String> existentes, Collection<String> entrantes,
                                 boolean permitirVacias) {
        if (permitirVacias) {
            return;
        }
        if (existentes.isEmpty()) {
            throw new ReconciliacionBloqueada("No se ha guardado «" + lista + "»: la lectura de la lista ha llegado vacía "
                    + "y se podrían duplicar o perder datos. Vuelve a abrir la aplicación e inténtalo de nuevo.");
        }
        if (entrantes.isEmpty()) {
            throw new ReconciliacionBloqueada("No se ha guardado «" + lista + "»: se iba a dejar la lista vacía y se "
                    + "borrarían " + existentes.size() + " elementos compartidos.");
        }
    }
}
