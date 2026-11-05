package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Histórico semanal de KPI (TestBenchKPIHistory). Una fila por semana y
 * organización con las columnas de la lista (Year, CW, Org, Benches…).
 */
public interface AlmacenHistoricoKpi {

    /** Filas ordenadas por Year y CW, con los nombres de columna de la lista. */
    List<JsonNode> leer();

    /** Guarda (sustituye) las fotos semanales; formato de snapshotKPIs. */
    ObjectNode guardarFotos(List<ObjectNode> fotos);
}
