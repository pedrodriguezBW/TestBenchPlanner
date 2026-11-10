package com.borgwarner.tbp.aplicacion.puertos;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Persistencia de la facturación, separada de la planificación: documentos JSON
 * por clave (maestros, un documento por periodo, auditoría por año) y copia de
 * los ficheros exportados.
 *
 * Adaptadores: ficheros locales (por defecto) y una lista de SharePoint propia
 * (Title = clave, DataJson = documento). Nunca se escribe en las listas de la
 * planificación.
 */
public interface AlmacenFacturacion {

    /** Documento leído y su versión (huella o ETag) para la concurrencia optimista. */
    record Documento(ObjectNode contenido, String version) {
    }

    /** null si no existe. */
    Documento leer(String clave);

    /** Claves que empiezan por el prefijo. */
    List<String> claves(String prefijo);

    /**
     * Guarda el documento si nadie lo ha cambiado desde la versión indicada
     * (null = debe no existir todavía). Devuelve la versión nueva.
     *
     * @throws ConflictoFacturacion si otra persona lo ha guardado antes
     */
    String guardar(String clave, ObjectNode contenido, String versionEsperada);

    /** Copia del fichero exportado (no se borra nunca). */
    void guardarArchivo(String nombre, byte[] contenido);

    /** Otra persona ha guardado el documento antes. */
    class ConflictoFacturacion extends RuntimeException {
        public ConflictoFacturacion(String mensaje) {
            super(mensaje);
        }
    }
}
