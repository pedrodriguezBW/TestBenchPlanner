package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Identidad de quien usa la aplicación y lista de usuarios y roles (TestBenchUsers). */
public interface AlmacenUsuarios {

    /** Identidad SSO: email y nombre visible. */
    Identidad identidadActual();

    /** Usuarios {title, email, role (admin|editor|readonly), responsibleName}. */
    ArrayNode leer();

    /** Reconcilia la lista (formato de /api/save_users). */
    ObjectNode guardar(JsonNode usuarios);

    record Identidad(String email, String nombre) {
    }
}
