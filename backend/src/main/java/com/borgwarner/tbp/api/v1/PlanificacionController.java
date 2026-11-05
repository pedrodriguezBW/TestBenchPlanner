package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.operaciones.Cierre;
import com.borgwarner.tbp.dominio.operaciones.EdicionEnsayo;
import com.borgwarner.tbp.dominio.operaciones.GeneradorIds;
import com.borgwarner.tbp.dominio.operaciones.OperacionesBanco;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * API de la planificación para la interfaz React.
 *
 * Cada operación lleva las versiones de los ensayos que el usuario tenía en
 * pantalla; si otra persona los ha cambiado, la respuesta es 409 con su
 * versión («Otra persona ha cambiado este ensayo»). Con "forzar": true se
 * guarda igualmente («Guardar la mía encima»).
 *
 * La respuesta de una operación incluye la vista completa actualizada para
 * que la interfaz no tenga que volver a pedirla.
 */
@RestController
@RequestMapping("/api/v1")
public class PlanificacionController {

    private final ServicioPlanificacion servicio;
    private final ServicioSesion sesion;
    private final Reloj reloj;
    private final GeneradorIds ids = GeneradorIds.aleatorio();

    public PlanificacionController(ServicioPlanificacion servicio, ServicioSesion sesion, Reloj reloj) {
        this.servicio = servicio;
        this.sesion = sesion;
        this.reloj = reloj;
    }

    @GetMapping("/sesion")
    public ObjectNode sesionActual() {
        Usuario u = sesion.identificar();
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("email", u.email()).put("nombre", u.displayName()).put("responsable", u.responsibleName())
                .put("rol", u.rol()).put("esAdmin", u.esAdmin()).put("soloLectura", u.soloLectura());
        return salida;
    }

    @GetMapping("/planificacion")
    public ObjectNode planificacion() {
        return RepresentacionVista.de(servicio.vista());
    }

    // ---- edición de campos --------------------------------------------------

    /**
     * Cambios de celdas de un ensayo: {"cambios":[{"campo":"comments","valor":"…"}], "version":"…", "forzar":false}.
     * Se aplican en orden, igual que si se hubiesen escrito uno tras otro.
     */
    @PostMapping("/bancos/{banco}/ensayos/{id}/campos")
    public ObjectNode editarCampos(@PathVariable String banco, @PathVariable String id, @RequestBody JsonNode cuerpo) {
        Usuario usuario = sesion.usuarioActual();
        Map<String, String> versiones = new HashMap<>();
        if (cuerpo.hasNonNull("version")) {
            versiones.put(id, cuerpo.get("version").asText());
        }
        var resultado = servicio.ejecutar(usuario, versiones, cuerpo.path("forzar").asBoolean(false), (p, plan) -> {
            Ensayo e = p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
            ResultadoPlan actual = plan;
            for (JsonNode cambio : cuerpo.path("cambios")) {
                EdicionEnsayo.editar(e, cambio.path("campo").asText(), cambio.get("valor"), actual.de(e), plan.contexto().hoy());
                actual = ResultadoPlan.calcular(p, plan.contexto()); // el siguiente cambio ve las fechas nuevas
            }
            return null;
        });
        return respuesta(resultado.vista(), null, resultado.cambios().size());
    }

    // ---- operaciones de banco ----------------------------------------------

    /**
     * Operación sobre un banco. Cuerpo:
     * {"tipo": "...", "ids": [...], "id": "...", "destino": "...", "bancoDestino": "...", "posicion": 1,
     *  "estado": "ok|delayed", "recurso": "...", "versiones": {"id": "version"}, "forzar": false}
     */
    @PostMapping("/bancos/{banco}/operaciones")
    public ObjectNode operar(@PathVariable String banco, @RequestBody JsonNode cuerpo) {
        Usuario usuario = sesion.usuarioActual();
        String tipo = cuerpo.path("tipo").asText();
        String id = cuerpo.path("id").asText(null);
        List<String> seleccion = new ArrayList<>();
        cuerpo.path("ids").forEach(n -> seleccion.add(n.asText()));
        var resultado = servicio.ejecutar(usuario, versiones(cuerpo), cuerpo.path("forzar").asBoolean(false), (p, plan) -> switch (tipo) {
            case "comprometer" -> { OperacionesBanco.comprometer(p, plan, banco, id); yield null; }
            case "deshacerCompromiso" -> { OperacionesBanco.deshacerCompromiso(p, banco, id); yield null; }
            case "comprometerTodos" -> { OperacionesBanco.comprometerTodos(p, plan, banco); yield null; }
            case "estado" -> { OperacionesBanco.declararEstado(p, banco, id, cuerpo.path("estado").asText("")); yield null; }
            case "aplicarSugerencia" -> { OperacionesBanco.aplicarSugerencia(p, plan, banco, id); yield null; }
            case "reordenar" -> { OperacionesBanco.reordenar(p, banco, id, cuerpo.path("destino").asText(), seleccion); yield null; }
            case "duplicar" -> String.join(",", OperacionesBanco.duplicar(p, banco, seleccion, ids));
            case "borrarSeleccion" -> { OperacionesBanco.borrarSeleccion(p, banco, seleccion); yield null; }
            case "borrar" -> { OperacionesBanco.borrar(p, banco, id); yield null; }
            case "mover" -> OperacionesBanco.mover(p, banco, seleccion, cuerpo.path("bancoDestino").asText(""),
                    cuerpo.hasNonNull("posicion") ? cuerpo.get("posicion").asInt() : null);
            case "anadir" -> OperacionesBanco.anadir(p, banco, usuario, ids);
            case "alternarRecurso" -> { OperacionesBanco.alternarRecurso(p, banco, id, cuerpo.path("recurso").asText()); yield null; }
            case "restaurar" -> { Cierre.restaurar(p, banco, id); yield null; }
            case "reutilizar" -> Cierre.reutilizar(p, banco, id, ids);
            default -> throw new IllegalArgumentException("Operación desconocida: " + tipo);
        });
        return respuesta(resultado.vista(), resultado.valor(), resultado.cambios().size());
    }

    // ---- ventana de cierre ---------------------------------------------------

    /** Valores con los que se abre la ventana de cierre y su validación inicial. */
    @GetMapping("/bancos/{banco}/ensayos/{id}/cierre")
    public ObjectNode propuestaCierre(@PathVariable String banco, @PathVariable String id,
                                      @RequestParam(defaultValue = "false") boolean correccion) {
        Vista vista = servicio.vista();
        Ensayo e = vista.planificacion().buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
        Cierre.Valores valores = Cierre.propuesta(e, vista.plan().de(e), correccion, sesion.usuarioActual(), vista.contexto().hoy());
        ObjectNode salida = JsonJs.objetoVacio();
        salida.set("valores", JsonJs.mapper().valueToTree(valores));
        salida.set("validacion", JsonJs.mapper().valueToTree(Cierre.validar(e, valores, vista.contexto().calendario(), vista.contexto().hoy())));
        salida.put("comprometidoInicio", e.texto("confirmedStart"));
        salida.put("comprometidoFin", e.texto("confirmedEnd"));
        return salida;
    }

    /** Validación en vivo mientras se rellena la ventana. */
    @PostMapping("/bancos/{banco}/ensayos/{id}/cierre/validacion")
    public JsonNode validarCierre(@PathVariable String banco, @PathVariable String id, @RequestBody JsonNode cuerpo) {
        Vista vista = servicio.vista();
        Ensayo e = vista.planificacion().buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
        return JsonJs.mapper().valueToTree(Cierre.validar(e, valores(cuerpo.path("valores")), vista.contexto().calendario(), vista.contexto().hoy()));
    }

    /** Confirmar el cierre (o la corrección de un cerrado, solo Admin). */
    @PostMapping("/bancos/{banco}/ensayos/{id}/cierre")
    public ObjectNode cerrar(@PathVariable String banco, @PathVariable String id, @RequestBody JsonNode cuerpo) {
        Usuario usuario = sesion.usuarioActual();
        boolean correccion = cuerpo.path("correccion").asBoolean(false);
        Cierre.Valores valores = valores(cuerpo.path("valores"));
        var resultado = servicio.ejecutar(usuario, versiones(cuerpo), cuerpo.path("forzar").asBoolean(false), (p, plan) -> {
            Cierre.confirmar(p, banco, id, valores, correccion, usuario, plan.contexto().calendario(), plan.contexto().hoy(), reloj.ahora());
            return null;
        });
        return respuesta(resultado.vista(), null, resultado.cambios().size());
    }

    // ---- utilidades -----------------------------------------------------------

    private static Cierre.Valores valores(JsonNode v) {
        return new Cierre.Valores(v.path("arranqueReal").asText(""), v.path("finReal").asText(""),
                v.path("estadoFinal").asText("ok"), v.path("motivo").asText(""), v.path("explicacion").asText(""),
                v.path("diasParada").asText(""), v.path("notaParada").asText(""));
    }

    private static Map<String, String> versiones(JsonNode cuerpo) {
        Map<String, String> versiones = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = cuerpo.path("versiones").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            versiones.put(e.getKey(), e.getValue().asText());
        }
        return versiones;
    }

    private static ObjectNode respuesta(Vista vista, Object valor, int filasGuardadas) {
        ObjectNode salida = JsonJs.objetoVacio();
        if (valor != null) {
            salida.put("resultado", valor.toString());
        }
        salida.put("filasGuardadas", filasGuardadas);
        salida.set("vista", RepresentacionVista.de(vista));
        return salida;
    }

    /** Lista de ids como ArrayNode (utilidad para pruebas). */
    static ArrayNode lista(List<String> ids) {
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        ids.forEach(salida::add);
        return salida;
    }
}
