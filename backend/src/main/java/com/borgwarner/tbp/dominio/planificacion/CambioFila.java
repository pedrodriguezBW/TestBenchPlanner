package com.borgwarner.tbp.dominio.planificacion;

/**
 * Cambio a guardar en una fila.
 *
 * @param uid            id del ensayo (Title en SharePoint)
 * @param textoNuevo     DataJson nuevo; null si la fila se borra
 * @param textoAnterior  DataJson del que se partió; null si la fila es nueva
 */
public record CambioFila(String uid, String textoNuevo, String textoAnterior) {

    public boolean esBorrado() {
        return textoNuevo == null;
    }

    public boolean esAlta() {
        return textoAnterior == null && textoNuevo != null;
    }
}
