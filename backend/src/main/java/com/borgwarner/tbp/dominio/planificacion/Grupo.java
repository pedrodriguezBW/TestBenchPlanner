package com.borgwarner.tbp.dominio.planificacion;

import java.util.List;

/** Tipo de banco (agrupación del menú): clave, nombre, color y bancos en orden. */
public record Grupo(String key, String name, String dot, List<String> benches) {
}
