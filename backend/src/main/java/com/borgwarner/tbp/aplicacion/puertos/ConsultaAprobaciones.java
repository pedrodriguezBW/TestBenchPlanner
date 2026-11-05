package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Base de aprobaciones de servicios (SOLO LECTURA, otro sitio de SharePoint). */
public interface ConsultaAprobaciones {

    /** Todas: [{id, status, color, raw, requester, service}] de la más reciente a la más antigua. */
    ArrayNode todas();

    /** Estado de cada Approval ID pedido: {id: {input, resolvedId, status, color, raw…}}. */
    ObjectNode estados(List<JsonNode> ids);
}
