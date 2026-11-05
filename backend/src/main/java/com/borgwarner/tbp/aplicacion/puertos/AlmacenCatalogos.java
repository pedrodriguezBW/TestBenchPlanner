package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Catálogos compartidos: bancos (TestBenchBenches), proyectos (TestBenchProjects)
 * y responsables (TestBenchResponsibles, congelada desde la v3.7).
 *
 * Formato de intercambio: el de /api/load_config de la 4.9.4
 * {responsibles:[…], projects:[…], benches:[{name, groupKey, groupName, dot, org, seq}]}.
 */
public interface AlmacenCatalogos {

    ObjectNode leer();

    /**
     * Reconcilia las listas con lo recibido (lo que no viene se manda a la
     * papelera). Responsables solo se toca si viene una lista no vacía.
     */
    ObjectNode guardar(JsonNode catalogos);
}
