package com.borgwarner.tbp.dominio.usuarios;

/**
 * Usuario conectado: identidad SSO y rol de TestBenchUsers.
 *
 * @param rol admin, editor o readonly (cualquiera que no esté dado de alta es readonly)
 */
public record Usuario(String email, String displayName, String responsibleName, String rol) {

    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String SOLO_LECTURA = "readonly";

    public boolean esAdmin() {
        return ADMIN.equals(rol);
    }

    public boolean soloLectura() {
        return SOLO_LECTURA.equals(rol) || rol == null;
    }

    /** Nombre que firma cierres y correcciones: responsable, nombre visible o correo. */
    public String firma() {
        if (responsibleName != null && !responsibleName.isEmpty()) {
            return responsibleName;
        }
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return email == null ? "" : email;
    }

    /** Responsable que se pone por defecto en un ensayo nuevo (vacío para un Admin). */
    public String responsablePorDefecto() {
        return esAdmin() ? "" : (responsibleName == null ? "" : responsibleName);
    }

    /** Normaliza el texto de rol de la lista (Admin / Editor / ReadOnly…). */
    public static String normalizarRol(String valor) {
        String rol = valor == null ? "" : valor.trim().toLowerCase().replace(" ", "");
        if (rol.equals("admin") || rol.equals("administrator")) {
            return ADMIN;
        }
        if (rol.equals("editor") || rol.equals("edit") || rol.equals("write")) {
            return EDITOR;
        }
        return SOLO_LECTURA;
    }
}
