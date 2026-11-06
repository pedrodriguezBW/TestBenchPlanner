package com.borgwarner.tbp.api.compat;

import com.borgwarner.tbp.TestBenchPlannerApplication;
import com.borgwarner.tbp.api.comun.CicloDeVida;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioListas;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * API compatible con el backend Python 4.9.4 (mismas rutas y mismos cuerpos).
 *
 * Permite servir la interfaz 4.9.4 original desde Java (/clasico) como
 * respaldo mientras se valida la interfaz nueva.
 *
 * LIMITACIÓN (D12): el guardado de ensayos (/api/save) mantiene el contrato de
 * la 4.9.4, «el último que guarda gana» (IF-MATCH: *, sin versiones ni 409),
 * porque la interfaz clásica no sabe enviar versiones ni mostrar el aviso de
 * conflicto. La protección completa (versión + ETag + 409) solo existe en
 * /api/v1. Las listas compartidas SÍ tienen aquí la protección D11.
 */
@RestController
@RequestMapping("/api")
public class ApiCompatibleController {

    private final AlmacenEnsayos ensayos;
    private final AlmacenCatalogos catalogos;
    private final AlmacenHistoricoKpi historico;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final ConsultaAprobaciones aprobaciones;
    private final Colaboracion colaboracion;
    private final ServicioSesion sesion;
    private final CicloDeVida cicloDeVida;
    private final ServicioListas listas;

    public ApiCompatibleController(AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenHistoricoKpi historico,
                                   AlmacenUsuarios usuarios, AlmacenRecursos recursos, ConsultaAprobaciones aprobaciones,
                                   Colaboracion colaboracion, ServicioSesion sesion, CicloDeVida cicloDeVida,
                                   ServicioListas listas) {
        this.listas = listas;
        this.ensayos = ensayos;
        this.catalogos = catalogos;
        this.historico = historico;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.aprobaciones = aprobaciones;
        this.colaboracion = colaboracion;
        this.sesion = sesion;
        this.cicloDeVida = cicloDeVida;
    }

    private static JsonNode cuerpo(JsonNode entrada) {
        return entrada == null ? JsonJs.objetoVacio() : entrada;
    }

    @GetMapping("/health")
    public ObjectNode salud() {
        try {
            ensayos.leer();
            return JsonJs.objetoVacio().put("session", true);
        } catch (RuntimeException e) {
            return JsonJs.objetoVacio().put("session", false).put("error", String.valueOf(e.getMessage()));
        }
    }

    @PostMapping("/load")
    public ArrayNode cargar() {
        cicloDeVida.latido(null); // /api/load y /api/save solo los usa la interfaz clásica
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        for (FilaGuardada fila : ensayos.leer()) {
            salida.addObject().put("uid", fila.uid()).put("data", fila.texto());
        }
        return salida;
    }

    @PostMapping("/save")
    public ObjectNode guardar(@RequestBody(required = false) JsonNode entrada) {
        cicloDeVida.latido(null);
        JsonNode p = cuerpo(entrada);
        List<CambioFila> cambios = new ArrayList<>();
        for (JsonNode u : p.path("upserts")) {
            String uid = u.path("uid").asText("");
            if (uid.isEmpty()) {
                continue;
            }
            JsonNode data = u.get("data");
            String texto = data == null || data.isNull() ? null : (data.isTextual() ? data.textValue() : JsonJs.escribir(data));
            cambios.add(new CambioFila(uid, texto, null));
        }
        for (JsonNode d : p.path("deletes")) {
            cambios.add(new CambioFila(d.asText(), null, null));
        }
        JsonNode actor = p.path("actor");
        AlmacenEnsayos.ResumenGuardado r = ensayos.guardar(cambios, false,
                new AlmacenEnsayos.Actor(actor.path("email").asText(""), actor.path("name").asText("")));
        return JsonJs.objetoVacio().put("ok", true).put("created", r.creados()).put("updated", r.actualizados())
                .put("deleted", r.borrados());
    }

    @PostMapping("/whoami")
    public ObjectNode quienSoy() {
        Usuario u = sesion.identificar();
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("email", u.email());
        salida.put("displayName", u.displayName());
        salida.put("role", u.rol());
        salida.put("responsibleName", u.responsibleName());
        salida.put("isAdmin", u.esAdmin());
        salida.set("users", sesion.usuarios());
        return salida;
    }

    @GetMapping("/whoami")
    public ObjectNode quienSoyGet() {
        return quienSoy();
    }

    @PostMapping("/save_users")
    public ObjectNode guardarUsuarios(@RequestBody(required = false) JsonNode entrada) {
        return listas.guardarUsuarios(cuerpo(entrada));
    }

    @PostMapping("/load_config")
    public ObjectNode cargarConfiguracion() {
        return listas.leerCatalogos();
    }

    @GetMapping("/load_config")
    public ObjectNode cargarConfiguracionGet() {
        return listas.leerCatalogos();
    }

    @PostMapping("/save_config")
    public ObjectNode guardarConfiguracion(@RequestBody(required = false) JsonNode entrada) {
        return listas.guardarCatalogos(cuerpo(entrada));
    }

    @PostMapping("/load_kpis")
    public List<JsonNode> cargarKpis() {
        return historico.leer();
    }

    @GetMapping("/load_kpis")
    public List<JsonNode> cargarKpisGet() {
        return historico.leer();
    }

    @PostMapping("/save_kpis")
    public ObjectNode guardarKpis(@RequestBody(required = false) JsonNode entrada) {
        List<ObjectNode> fotos = new ArrayList<>();
        for (JsonNode s : cuerpo(entrada).path("snapshots")) {
            if (s instanceof ObjectNode o) {
                fotos.add(o);
            }
        }
        return historico.guardarFotos(fotos);
    }

    @PostMapping("/approvals")
    public ObjectNode estadosAprobacion(@RequestBody(required = false) JsonNode entrada) {
        List<JsonNode> ids = new ArrayList<>();
        cuerpo(entrada).path("ids").forEach(ids::add);
        return aprobaciones.estados(ids);
    }

    @PostMapping("/approvals_all")
    public ArrayNode todasAprobaciones() {
        return aprobaciones.todas();
    }

    @GetMapping("/approvals_all")
    public ArrayNode todasAprobacionesGet() {
        return aprobaciones.todas();
    }

    @PostMapping("/presence")
    public ObjectNode presencia() {
        Usuario u = sesion.usuarioActual();
        return colaboracion.latidoPresencia(new AlmacenUsuarios.Identidad(u.email(), u.displayName()));
    }

    @PostMapping("/activity_log")
    public ObjectNode actividad() {
        return colaboracion.leerActividad(200);
    }

    @GetMapping("/activity_log")
    public ObjectNode actividadGet() {
        return colaboracion.leerActividad(200);
    }

    @PostMapping("/log_event")
    public ObjectNode anotar(@RequestBody(required = false) JsonNode entrada) {
        JsonNode p = cuerpo(entrada);
        boolean ok = colaboracion.anotarActividad(p.path("email").asText(""), p.path("name").asText(""),
                p.path("action").asText("event"), p.path("detail").asText(""));
        return JsonJs.objetoVacio().put("ok", ok);
    }

    @PostMapping("/load_resources")
    public ObjectNode cargarRecursos() {
        return listas.leerRecursos();
    }

    @PostMapping("/save_resources")
    public ObjectNode guardarRecursos(@RequestBody(required = false) JsonNode entrada) {
        return listas.guardarRecursos(cuerpo(entrada));
    }

    @PostMapping("/save_resource_block")
    public ObjectNode guardarBloqueo(@RequestBody(required = false) JsonNode entrada) {
        JsonNode p = cuerpo(entrada);
        JsonNode actor = p.path("actor");
        return recursos.guardarBloqueo(p, new AlmacenEnsayos.Actor(actor.path("email").asText(""), actor.path("name").asText("")));
    }

    @PostMapping("/delete_resource_block")
    public ObjectNode borrarBloqueo(@RequestBody(required = false) JsonNode entrada) {
        JsonNode id = cuerpo(entrada).get("id");
        return recursos.borrarBloqueo(id == null || id.isNull() ? null : id.asText());
    }

    /** Exportación del Gantt a Excel: se migra en el bloque F de la interfaz (H3-F). */
    @PostMapping("/export_gantt")
    public ResponseEntity<ObjectNode> exportarGantt() {
        return ResponseEntity.status(501).body(JsonJs.objetoVacio().put("ok", false)
                .put("error", "La exportación a Excel aún no está migrada (bloque F)."));
    }

    @PostMapping("/app_version")
    public ObjectNode version() {
        String publicada = colaboracion.versionPublicada();
        return JsonJs.objetoVacio().put("backend", TestBenchPlannerApplication.VERSION)
                .put("published", publicada == null ? TestBenchPlannerApplication.VERSION : publicada);
    }

    @GetMapping("/app_version")
    public ObjectNode versionGet() {
        return version();
    }

    /** Autoactualización: desactivada, como en la 4.9.4 (RELEASE_FOLDER vacío). */
    @PostMapping("/update_info")
    public ObjectNode informacionActualizacion() {
        return JsonJs.objetoVacio().put("backend", TestBenchPlannerApplication.VERSION)
                .put("release_version", "").put("canServerUpdate", false);
    }

    @GetMapping("/update_info")
    public ObjectNode informacionActualizacionGet() {
        return informacionActualizacion();
    }

    @PostMapping("/self_update")
    public ObjectNode autoactualizar() {
        return JsonJs.objetoVacio().put("ok", false).put("error", "RELEASE_FOLDER no configurado en el backend.");
    }

    @PostMapping("/release_file")
    public ObjectNode ficheroVersion() {
        return JsonJs.objetoVacio().put("ok", false).put("error", "RELEASE_FOLDER no configurado en el backend.");
    }

    /** Latido. La interfaz React envía {"pestana": código}; la clásica, «{}». */
    @PostMapping("/heartbeat")
    public ObjectNode latido(@RequestBody(required = false) String cuerpo) {
        cicloDeVida.latido(codigoPestana(cuerpo));
        return JsonJs.objetoVacio().put("ok", true).put("pestanas", cicloDeVida.pestanasAbiertas());
    }

    /** Cierre de una pestaña (sendBeacon al cerrarla de verdad, no al minimizar). */
    @PostMapping("/shutdown")
    public ObjectNode apagar(@RequestBody(required = false) String cuerpo) {
        cicloDeVida.solicitarApagado(codigoPestana(cuerpo));
        return JsonJs.objetoVacio().put("ok", true);
    }

    /**
     * Código de pestaña del cuerpo. Se lee como texto porque sendBeacon puede
     * llegar sin tipo de contenido; un cuerpo vacío o ilegible es la clásica.
     */
    private static String codigoPestana(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            String codigo = JsonJs.mapper().readTree(cuerpo).path("pestana").asText("");
            return codigo.isBlank() ? null : codigo;
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/diag")
    public ObjectNode diagnostico() {
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("version", TestBenchPlannerApplication.VERSION);
        try {
            List<FilaGuardada> filas = ensayos.leer();
            salida.put("filas", filas.size());
            salida.put("lectura", "ok");
        } catch (RuntimeException e) {
            salida.put("lectura", String.valueOf(e.getMessage()));
        }
        return salida;
    }
}
