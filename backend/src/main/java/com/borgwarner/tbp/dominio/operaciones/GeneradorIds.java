package com.borgwarner.tbp.dominio.operaciones;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Genera ids de ensayo con el mismo formato que la 4.9.4: «r» + 7 caracteres en base 36. */
public interface GeneradorIds {

    String nuevo();

    /** Formato de un id de ensayo: «r» + 7 caracteres en base 36 (minúsculas). */
    Pattern FORMATO = Pattern.compile("r[0-9a-z]{7}");

    static boolean formatoValido(String id) {
        return id != null && FORMATO.matcher(id).matches();
    }

    /**
     * Usa primero los ids que propone la interfaz (los de las filas que ya
     * enseña mientras se guarda el alta o la copia), en orden, si tienen el
     * formato correcto, no existen ya (libre) y no se repiten. Si falta alguno
     * o no vale, sigue con el generador de respaldo. Así la fila que el
     * usuario ya ve conserva su id al confirmarse.
     */
    static GeneradorIds conPropuestos(List<String> propuestos, Predicate<String> libre, GeneradorIds respaldo) {
        Deque<String> cola = new ArrayDeque<>(propuestos == null ? List.of() : propuestos);
        Set<String> dados = new HashSet<>();
        return () -> {
            while (!cola.isEmpty()) {
                String id = cola.poll();
                if (formatoValido(id) && libre.test(id) && dados.add(id)) {
                    return id;
                }
            }
            String id;
            do {
                id = respaldo.nuevo();
            } while (!dados.add(id));
            return id;
        };
    }

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
