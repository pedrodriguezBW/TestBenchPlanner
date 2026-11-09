package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioListas;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Catálogos, usuarios, recursos, calendario, aprobaciones y presencia. Los
 * cuerpos usan el formato de intercambio de la 4.9.4 (ver los puertos).
 */
@RestController
@RequestMapping("/api/v1")
public class ConfiguracionController {

    private final AlmacenCatalogos catalogos;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final AlmacenCalendario calendario;
    private final ConsultaAprobaciones aprobaciones;
    private final Colaboracion colaboracion;
    private final ServicioSesion sesion;
    private final Reloj reloj;
    private final ServicioListas listas;

    public ConfiguracionController(AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                                   AlmacenCalendario calendario, ConsultaAprobaciones aprobaciones,
                                   Colaboracion colaboracion, ServicioSesion sesion, Reloj reloj, ServicioListas listas) {
        this.listas = listas;
        this.catalogos = catalogos;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.calendario = calendario;
        this.aprobaciones = aprobaciones;
        this.colaboracion = colaboracion;
        this.sesion = sesion;
        this.reloj = reloj;
    }

    @GetMapping("/catalogos")
    public ObjectNode leerCatalogos() {
        return listas.leerCatalogos();
    }

    /** Guardar la configuración de bancos y proyectos (en la 4.9.4 no exigía rol: se conserva). */
    @PutMapping("/catalogos")
    public ObjectNode guardarCatalogos(@RequestBody JsonNode cuerpo) {
        sesion.exigirEscritura(sesion.usuarioActual());
        return listas.guardarCatalogos(cuerpo);
    }

    @GetMapping("/usuarios")
    public ArrayNode leerUsuarios() {
        return usuarios.leer();
    }

    @PutMapping("/usuarios")
    public ObjectNode guardarUsuarios(@RequestBody JsonNode cuerpo) {
        sesion.exigirAdmin(sesion.usuarioActual());
        ObjectNode r = listas.guardarUsuarios(cuerpo);
        sesion.identificar();
        return r;
    }

    @GetMapping("/recursos")
    public ObjectNode leerRecursos() {
        return listas.leerRecursos();
    }

    @PutMapping("/recursos")
    public ObjectNode guardarRecursos(@RequestBody JsonNode cuerpo) {
        sesion.exigirEscritura(sesion.usuarioActual());
        return listas.guardarRecursos(cuerpo);
    }

    @PostMapping("/recursos/bloqueos")
    public ObjectNode guardarBloqueo(@RequestBody JsonNode cuerpo) {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        return recursos.guardarBloqueo(cuerpo, ServicioPlanificacion.actor(u));
    }

    @PostMapping("/recursos/bloqueos/borrar")
    public ObjectNode borrarBloqueo(@RequestBody JsonNode cuerpo) {
        sesion.exigirEscritura(sesion.usuarioActual());
        JsonNode id = cuerpo.get("id");
        return recursos.borrarBloqueo(id == null || id.isNull() ? null : id.asText());
    }

    @GetMapping("/calendario")
    public ObjectNode leerCalendario() {
        Calendario c = calendario.leer(reloj.hoy());
        ObjectNode salida = JsonJs.objetoVacio().put("anio", c.anio()).put("saltarFestivos", c.saltarFestivos());
        ArrayNode lista = salida.putArray("festivos");
        c.festivos().forEach(f -> lista.addObject().put("fecha", f.date()).put("nombre", f.name()));
        return salida;
    }

    /** Guarda el calendario de este PC (no se comparte con nadie, como en la 4.9.4). */
    @PutMapping("/calendario")
    public ObjectNode guardarCalendario(@RequestBody JsonNode cuerpo) {
        List<Festivo> festivos = new ArrayList<>();
        cuerpo.path("festivos").forEach(f -> festivos.add(new Festivo(f.path("fecha").asText(), f.path("nombre").asText(""))));
        calendario.guardar(new Calendario(cuerpo.path("anio").asInt(reloj.hoy().getYear()), cuerpo.path("saltarFestivos").asBoolean(true), festivos));
        return leerCalendario();
    }

    /** Cambiar de año regenera los festivos de Vigo de ese año (como «Year» en Configuration → Calendar). */
    @PostMapping("/calendario/anio")
    public ObjectNode cambiarAnio(@RequestBody JsonNode cuerpo) {
        int anio = cuerpo.path("anio").asInt(reloj.hoy().getYear());
        Calendario actual = calendario.leer(reloj.hoy());
        calendario.guardar(new Calendario(anio, actual.saltarFestivos(), Calendario.festivosDeVigo(anio)));
        return leerCalendario();
    }

    @GetMapping("/aprobaciones")
    public ArrayNode todasAprobaciones() {
        return aprobaciones.todas();
    }

    @PostMapping("/aprobaciones/estados")
    public ObjectNode estados(@RequestBody JsonNode cuerpo) {
        List<JsonNode> ids = new ArrayList<>();
        cuerpo.path("ids").forEach(ids::add);
        return aprobaciones.estados(ids);
    }

    @PostMapping("/presencia")
    public ObjectNode presencia() {
        Usuario u = sesion.usuarioActual();
        return colaboracion.latidoPresencia(new AlmacenUsuarios.Identidad(u.email(), u.displayName()));
    }

    /** Versión de esta aplicación y la publicada en TestBenchAppConfig (aviso de versión antigua). */
    @GetMapping("/version")
    public ObjectNode version() {
        String publicada = colaboracion.versionPublicada();
        return JsonJs.objetoVacio().put("backend", com.borgwarner.tbp.TestBenchPlannerApplication.VERSION)
                .put("published", publicada == null ? com.borgwarner.tbp.TestBenchPlannerApplication.VERSION : publicada);
    }

    @GetMapping("/actividad")
    public ObjectNode actividad() {
        return colaboracion.leerActividad(200);
    }
}
