package com.borgwarner.tbp.dominio.operaciones;

import java.security.SecureRandom;

/** Genera ids de ensayo con el mismo formato que la 4.9.4: «r» + 7 caracteres en base 36. */
public interface GeneradorIds {

    String nuevo();

    static GeneradorIds aleatorio() {
        SecureRandom azar = new SecureRandom();
        String alfabeto = "0123456789abcdefghijklmnopqrstuvwxyz";
        return () -> {
            StringBuilder id = new StringBuilder("r");
            for (int i = 0; i < 7; i++) {
                id.append(alfabeto.charAt(azar.nextInt(alfabeto.length())));
            }
            return id.toString();
        };
    }
}
