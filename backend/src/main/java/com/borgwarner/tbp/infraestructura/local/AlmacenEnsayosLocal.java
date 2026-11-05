package com.borgwarner.tbp.infraestructura.local;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensayos en el fichero planner.json: [{uid, data}] (lo que devolvía /api/load).
 * Las bajas se eliminan del fichero (en SharePoint irían a la papelera).
 */
public class AlmacenEnsayosLocal implements AlmacenEnsayos {

    private final DatosLocales datos;
    private final Colaboracion colaboracion;

    public AlmacenEnsayosLocal(DatosLocales datos, Colaboracion colaboracion) {
        this.datos = datos;
        this.colaboracion = colaboracion;
    }

    @Override
    public List<FilaGuardada> leer() {
        List<FilaGuardada> filas = new ArrayList<>();
        for (JsonNode fila : datos.leer("planner.json", JsonJs.mapper().createArrayNode())) {
            JsonNode data = fila.get("data");
            String texto = data == null || data.isNull() ? null : (data.isTextual() ? data.textValue() : JsonJs.escribir(data));
            filas.add(new FilaGuardada(fila.path("uid").asText(null), texto));
        }
        return filas;
    }

    @Override
    public ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor) {
        ResumenGuardado resumen = datos.conCerrojo(() -> {
            Map<String, String> actuales = new LinkedHashMap<>();
            for (FilaGuardada fila : leer()) {
                actuales.put(fila.uid(), fila.texto());
            }
            if (comprobarVersion) {
                List<ConflictoEdicion.FilaEnConflicto> conflictos = new ArrayList<>();
                for (CambioFila cambio : cambios) {
                    String actual = actuales.get(cambio.uid());
                    boolean igual = cambio.textoAnterior() == null ? actual == null : cambio.textoAnterior().equals(actual);
                    if (!igual) {
                        conflictos.add(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), actual));
                    }
                }
                if (!conflictos.isEmpty()) {
                    throw new ConflictoEdicion(conflictos);
                }
            }
            int creados = 0, actualizados = 0, borrados = 0;
            for (CambioFila cambio : cambios) {
                if (cambio.esBorrado()) {
                    if (actuales.remove(cambio.uid()) != null) {
                        borrados++;
                    }
                } else if (actuales.containsKey(cambio.uid())) {
                    actuales.put(cambio.uid(), cambio.textoNuevo());
                    actualizados++;
                } else {
                    actuales.put(cambio.uid(), cambio.textoNuevo());
                    creados++;
                }
            }
            ArrayNode salida = JsonJs.mapper().createArrayNode();
            actuales.forEach((uid, texto) -> {
                ObjectNode fila = salida.addObject();
                fila.put("uid", uid);
                fila.put("data", texto);
            });
            datos.escribir("planner.json", salida);
            return new ResumenGuardado(creados, actualizados, borrados);
        });
        if (resumen.creados() + resumen.actualizados() + resumen.borrados() > 0) {
            colaboracion.anotarActividad(actor.email(), actor.nombre(), "edit",
                    "created " + resumen.creados() + ", updated " + resumen.actualizados() + ", deleted " + resumen.borrados());
        }
        return resumen;
    }
}
