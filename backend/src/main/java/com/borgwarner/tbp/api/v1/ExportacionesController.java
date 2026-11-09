package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioCopiaSeguridad;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.excel.ExcelGantt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * Exportaciones e importación (bloque F):
 *  - Gantt a Excel (exportGanttExcel de la 4.9.4): {ok, b64, filename};
 *  - copia de seguridad JSON (exportJSON / importJSON).
 * El CSV de estadísticas y la foto HTML de solo lectura se montan en la
 * interfaz con datos que ya da la API.
 */
@RestController
@RequestMapping("/api/v1")
public class ExportacionesController {

    private final ServicioCopiaSeguridad copias;
    private final ServicioSesion sesion;
    private final Reloj reloj;

    public ExportacionesController(ServicioCopiaSeguridad copias, ServicioSesion sesion, Reloj reloj) {
        this.copias = copias;
        this.sesion = sesion;
        this.reloj = reloj;
    }

    /** Respuesta con el mismo formato que /api/export_gantt de la 4.9.4. */
    public static ObjectNode excel(JsonNode matriz, Reloj reloj) {
        ExcelGantt.Resultado r = ExcelGantt.generar(matriz == null ? JsonJs.objetoVacio() : matriz, reloj.hoy());
        return JsonJs.objetoVacio().put("ok", true).put("b64", Base64.getEncoder().encodeToString(r.contenido()))
                .put("filename", r.nombre());
    }

    @PostMapping("/exportaciones/gantt")
    public ObjectNode gantt(@RequestBody(required = false) JsonNode matriz) {
        return excel(matriz, reloj);
    }

    @GetMapping("/exportaciones/copia")
    public ObjectNode copia() {
        return copias.exportar();
    }

    /** Import (JSON): sustituye la planificación por la de la copia (solo Admin, D10/D11). */
    @PostMapping("/importaciones/copia")
    public ObjectNode importar(@RequestBody JsonNode estado) {
        var r = copias.importar(sesion.usuarioActual(), estado);
        ObjectNode salida = JsonJs.objetoVacio().put("ok", true).put("ensayosGuardados", r.ensayosGuardados())
                .put("ensayosBorrados", r.ensayosBorrados());
        salida.set("vista", RepresentacionVista.de(r.vista()));
        return salida;
    }
}
