package com.borgwarner.tbp.infraestructura.local;

/** Iniciales y color de la burbuja de presencia (_initials y _color_for del backend 4.9.4). */
public final class Presencia {

    private static final String[] PALETA = {"#23BCA3", "#4C8DFF", "#E0A458", "#E86A92", "#B08BEA",
            "#F2A64B", "#3DD68C", "#6BB5FF", "#F5A623", "#9AD14B"};

    private Presencia() {
    }

    public static String iniciales(String nombre, String email) {
        String origen = nombre == null ? "" : nombre.trim();
        if (!origen.isEmpty()) {
            String[] partes = origen.split("\\s+");
            if (partes.length >= 2) {
                return (partes[0].substring(0, 1) + partes[partes.length - 1].substring(0, 1)).toUpperCase();
            }
            return partes[0].substring(0, Math.min(2, partes[0].length())).toUpperCase();
        }
        String correo = (email == null || email.isEmpty()) ? "?" : email;
        return correo.substring(0, Math.min(2, correo.length())).toUpperCase();
    }

    public static String color(String email) {
        String s = (email == null || email.isEmpty()) ? "x" : email;
        long h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = (h * 31 + s.charAt(i)) & 0xFFFFFFFFL;
        }
        return PALETA[(int) (h % PALETA.length)];
    }
}
