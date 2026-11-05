package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Equipos auxiliares (TestBenchResources) y sus bloqueos (TestBenchResourceBlocks).
 * Formato de intercambio: el de /api/load_resources de la 4.9.4.
 */
public interface AlmacenRecursos {

    ObjectNode leer();

    ObjectNode guardarCatalogo(JsonNode recursos);

    ObjectNode guardarBloqueo(JsonNode bloqueo, AlmacenEnsayos.Actor actor);

    ObjectNode borrarBloqueo(Object id);
}
