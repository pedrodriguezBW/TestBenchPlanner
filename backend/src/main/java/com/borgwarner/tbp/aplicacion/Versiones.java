package com.borgwarner.tbp.aplicacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Versión de un ensayo para el control de concurrencia: una huella del texto
 * DataJson. Es igual en todos los almacenes (local, SharePoint o SQL), así que
 * la interfaz no necesita saber dónde están los datos.
 */
public final class Versiones {

    private Versiones() {
    }

    public static String de(String textoDataJson) {
        if (textoDataJson == null) {
            return null;
        }
        try {
            byte[] huella = MessageDigest.getInstance("SHA-256").digest(textoDataJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(huella, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
