package com.borgwarner.tbp.aplicacion.puertos;

/**
 * Se ha intentado escribir en el almacén (SharePoint) con la escritura desactivada
 * (planner.sharepoint.escritura-habilitada=false, valor por defecto).
 */
public class EscrituraDesactivada extends RuntimeException {

    public EscrituraDesactivada(String operacion) {
        super("La escritura en el almacén está desactivada: no se ha hecho «" + operacion
                + "». Para activarla: --planner.sharepoint.escritura-habilitada=true");
    }
}
