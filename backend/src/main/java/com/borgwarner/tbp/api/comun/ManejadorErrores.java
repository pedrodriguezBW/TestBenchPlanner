package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.aplicacion.Versiones;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada;
import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce las excepciones a respuestas HTTP con un cuerpo JSON uniforme:
 *   401 sin sesión con SharePoint ({"error":"no-session"}, igual que la 4.9.4)
 *   403 sin permiso · 409 conflicto de edición · 422 regla de negocio
 *   503 escritura en SharePoint desactivada · 500 cualquier otro error
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(SinSesionAlmacen.class)
    public ResponseEntity<ObjectNode> sinSesion(SinSesionAlmacen e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(JsonJs.objetoVacio().put("error", "no-session"));
    }

    @ExceptionHandler(SinPermiso.class)
    public ResponseEntity<ObjectNode> sinPermiso(SinPermiso e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(JsonJs.objetoVacio().put("error", "sin-permiso").put("mensaje", e.getMessage()));
    }

    @ExceptionHandler(ReglaNoCumplida.class)
    public ResponseEntity<ObjectNode> regla(ReglaNoCumplida e) {
        return ResponseEntity.unprocessableEntity().body(JsonJs.objetoVacio().put("error", "regla").put("mensaje", e.getMessage()));
    }

    @ExceptionHandler(ConflictoEdicion.class)
    public ResponseEntity<ObjectNode> conflicto(ConflictoEdicion e) {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("error", "conflicto").put("mensaje", "Otra persona ha cambiado este ensayo");
        ArrayNode filas = cuerpo.putArray("filas");
        for (ConflictoEdicion.FilaEnConflicto fila : e.filas()) {
            ObjectNode f = filas.addObject();
            f.put("id", fila.uid());
            f.put("version", Versiones.de(fila.textoActual()));
            f.put("borrado", fila.textoActual() == null);
            f.set("datos", fila.textoActual() == null ? null : JsonJs.leer(fila.textoActual()));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(cuerpo);
    }

    @ExceptionHandler(EscrituraDesactivada.class)
    public ResponseEntity<ObjectNode> escrituraDesactivada(EscrituraDesactivada e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(JsonJs.objetoVacio().put("error", "escritura-desactivada").put("mensaje", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ObjectNode> peticionIncorrecta(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(JsonJs.objetoVacio().put("error", "peticion").put("mensaje", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ObjectNode> otro(RuntimeException e) {
        LOG.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(JsonJs.objetoVacio().put("error", String.valueOf(e.getMessage())));
    }
}
