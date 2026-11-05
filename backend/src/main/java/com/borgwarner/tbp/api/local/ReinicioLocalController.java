package com.borgwarner.tbp.api.local;

import com.borgwarner.tbp.aplicacion.RegistroLecturas;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.comun.RelojConfigurable;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reinicio de los datos locales para las pruebas de paridad de la interfaz
 * (herramientas/laboratorio/controlador-react.cjs).
 *
 * SOLO existe con planner.almacen=local: con SharePoint este controlador no se
 * crea, así que no hay forma de llamarlo contra datos reales.
 *
 * Cuerpo (todo opcional): {"escenario": "carpeta", "fecha": "aaaa-mm-dd", "email": "usuario@…"}
 */
@RestController
@RequestMapping("/api/v1/local")
@ConditionalOnProperty(name = "planner.almacen", havingValue = "local", matchIfMissing = true)
public class ReinicioLocalController {

    private final DatosLocales datos;
    private final PropiedadesPlanner propiedades;
    private final RelojConfigurable reloj;
    private final AlmacenesAuxiliaresLocales.Usuarios usuarios;
    private final ServicioSesion sesion;
    private final RegistroLecturas lecturas;

    public ReinicioLocalController(DatosLocales datos, PropiedadesPlanner propiedades, RelojConfigurable reloj,
                                   AlmacenesAuxiliaresLocales.Usuarios usuarios, ServicioSesion sesion, RegistroLecturas lecturas) {
        this.datos = datos;
        this.propiedades = propiedades;
        this.reloj = reloj;
        this.usuarios = usuarios;
        this.sesion = sesion;
        this.lecturas = lecturas;
    }

    @PostMapping("/reinicio")
    public ObjectNode reiniciar(@RequestBody(required = false) JsonNode cuerpo) {
        JsonNode p = cuerpo == null ? JsonJs.objetoVacio() : cuerpo;
        Path escenario = Path.of(propiedades.local().escenarioInicial());
        if (p.hasNonNull("escenario")) {
            // Solo se admite el nombre de una carpeta hermana del escenario configurado.
            String nombre = p.get("escenario").asText();
            if (!nombre.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Nombre de escenario no válido");
            }
            escenario = escenario.resolveSibling(nombre);
        }
        datos.copiarEscenario(escenario);
        reloj.fijarFecha(p.path("fecha").asText(propiedades.fechaFija()));
        // Un PC «sin caché»: el calendario de festivos se vuelve a crear para el año de la fecha.
        try {
            Files.deleteIfExists(Path.of(propiedades.carpetaUsuario()).resolve("calendario.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (p.hasNonNull("email")) {
            usuarios.cambiarIdentidad(new AlmacenUsuarios.Identidad(p.get("email").asText(), p.path("nombre").asText("")));
        }
        lecturas.olvidarTodo();
        sesion.olvidar();
        return JsonJs.objetoVacio().put("ok", true).put("hoy", reloj.hoy().toString());
    }
}
