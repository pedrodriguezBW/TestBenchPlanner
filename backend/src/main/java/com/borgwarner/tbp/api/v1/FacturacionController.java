package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.facturacion.ServicioFacturacion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * API de la facturación (sección «Billing» de la interfaz). Es independiente de
 * la planificación: solo la ficha del ensayo escribe en el ensayo
 * (DataJson.billing), con la misma concurrencia por versión de fila.
 *
 * Los cambios de periodo llevan la "version" del documento que vio el usuario:
 * si otra persona lo ha cambiado, 409 «conflicto-facturacion».
 */
@RestController
@RequestMapping("/api/v1/facturacion")
public class FacturacionController {

    private final ServicioFacturacion servicio;

    public FacturacionController(ServicioFacturacion servicio) {
        this.servicio = servicio;
    }

    private static String version(JsonNode cuerpo) {
        return cuerpo == null ? null : cuerpo.path("version").asText(null);
    }

    // ---- maestros ---------------------------------------------------------------------------

    @GetMapping("/maestros")
    public ObjectNode maestros() {
        return servicio.vistaMaestros();
    }

    @PutMapping("/maestros")
    public ObjectNode guardarMaestros(@RequestBody JsonNode cuerpo) {
        JsonNode m = cuerpo.get("maestros");
        if (!(m instanceof ObjectNode maestros)) {
            throw new ReglaNoCumplida("Faltan los maestros.");
        }
        return servicio.guardarMaestros(maestros.deepCopy(), version(cuerpo));
    }

    // ---- periodos ---------------------------------------------------------------------------

    @GetMapping("/periodos")
    public ArrayNode periodos() {
        return servicio.periodos();
    }

    @PostMapping("/periodos")
    public ObjectNode crear(@RequestBody JsonNode cuerpo) {
        return servicio.crearPeriodo(cuerpo.path("anio").asInt(0), cuerpo.path("mes").asInt(0));
    }

    @GetMapping("/periodos/{id}")
    public ObjectNode periodo(@PathVariable String id) {
        return servicio.periodo(id);
    }

    @PostMapping("/periodos/{id}/recalcular")
    public ObjectNode recalcular(@PathVariable String id, @RequestBody(required = false) JsonNode cuerpo) {
        return servicio.recalcular(id, version(cuerpo));
    }

    @PostMapping("/periodos/{id}/enviar")
    public ObjectNode enviar(@PathVariable String id, @RequestBody(required = false) JsonNode cuerpo) {
        return servicio.enviarRevision(id, version(cuerpo));
    }

    @PostMapping("/periodos/{id}/aprobar")
    public ObjectNode aprobar(@PathVariable String id, @RequestBody JsonNode cuerpo) {
        return servicio.aprobar(id, version(cuerpo), cuerpo.path("confirmacion").asBoolean(false));
    }

    @PostMapping("/periodos/{id}/reabrir")
    public ObjectNode reabrir(@PathVariable String id, @RequestBody JsonNode cuerpo) {
        return servicio.reabrir(id, version(cuerpo), cuerpo.path("justificacion").asText(""));
    }

    /** Acción sobre una línea: {"accion": "...", "datos": {...}, "version": "..."}. */
    @PostMapping("/periodos/{id}/lineas/{lineaId}")
    public ObjectNode linea(@PathVariable String id, @PathVariable String lineaId, @RequestBody JsonNode cuerpo) {
        return servicio.accionLinea(id, lineaId, cuerpo.path("accion").asText(""), cuerpo.get("datos"), version(cuerpo));
    }

    /** Exportar (solo Admin): {"tipos": ["excel", "controlling", "html"] | ["todos"], "version": "..."}. */
    @PostMapping("/periodos/{id}/exportar")
    public ObjectNode exportar(@PathVariable String id, @RequestBody JsonNode cuerpo) {
        List<String> tipos = new ArrayList<>();
        cuerpo.path("tipos").forEach(t -> tipos.add(t.asText()));
        List<ServicioFacturacion.Exportacion> archivos = servicio.exportar(id, tipos, version(cuerpo));
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode lista = salida.putArray("exportaciones");
        archivos.forEach(a -> lista.add(ServicioFacturacion.comoJson(a)));
        salida.set("periodo", servicio.periodo(id));
        return salida;
    }

    @GetMapping("/periodos/{id}/exportaciones/{exportId}")
    public ObjectNode descargar(@PathVariable String id, @PathVariable String exportId) {
        return ServicioFacturacion.comoJson(servicio.descargar(id, exportId));
    }

    @GetMapping("/auditoria")
    public ArrayNode auditoria(@RequestParam(required = false) String periodo) {
        return servicio.auditoria(periodo);
    }

    // ---- ficha de facturación del ensayo ------------------------------------------------------

    @GetMapping("/ensayos/estados")
    public ObjectNode estados() {
        return servicio.estadosEnsayos();
    }

    @GetMapping("/bancos/{banco}/ensayos/{id}")
    public ObjectNode ficha(@PathVariable String banco, @PathVariable String id) {
        return servicio.ficha(banco, id, null);
    }

    /** Vista previa con los datos que se están escribiendo (validación y horas por periodo). */
    @PostMapping("/bancos/{banco}/ensayos/{id}/vista-previa")
    public ObjectNode vistaPrevia(@PathVariable String banco, @PathVariable String id, @RequestBody JsonNode cuerpo) {
        return servicio.ficha(banco, id, cuerpo.get("billing"), cuerpo.path("fechaInicio").asText(null), cuerpo.path("fechaFin").asText(null));
    }

    /** Guarda «Billing Data»: {"billing": {...}, "version": "...", "forzar": false}. */
    @PutMapping("/bancos/{banco}/ensayos/{id}")
    public ObjectNode guardar(@PathVariable String banco, @PathVariable String id, @RequestBody JsonNode cuerpo) {
        JsonNode billing = cuerpo.get("billing");
        if (billing == null || !billing.isObject()) {
            throw new ReglaNoCumplida("Faltan los datos de facturación.");
        }
        var r = servicio.guardarEnsayo(banco, id, billing, version(cuerpo), cuerpo.path("forzar").asBoolean(false));
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("filasGuardadas", r.cambios().size());
        salida.set("vista", RepresentacionVista.de(r.vista()));
        return salida;
    }
}
