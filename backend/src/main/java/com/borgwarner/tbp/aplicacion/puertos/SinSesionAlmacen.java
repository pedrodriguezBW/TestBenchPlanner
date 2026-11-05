package com.borgwarner.tbp.aplicacion.puertos;

/** No hay sesión con el almacén (p. ej. SSO de SharePoint) (equivale al 401 «no-session» de la 4.9.4). */
public class SinSesionAlmacen extends RuntimeException {

    public SinSesionAlmacen() {
        super("no-session");
    }
}
