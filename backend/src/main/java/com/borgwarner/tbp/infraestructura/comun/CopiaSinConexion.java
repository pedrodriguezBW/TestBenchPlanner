package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Última copia buena de la planificación, los catálogos y los usuarios en este
 * PC, para seguir CONSULTANDO cuando SharePoint no responde (H-04).
 *
 * Es lo que hacía la 4.9.4 con su copia del navegador (bw_bench_planner_v10 y
 * la copia de usuarios de la v4.9): tras cada lectura buena se guarda; si una
 * lectura falla y hay copia, se devuelve la copia y se anota en EstadoConexion,
 * que pone la sesión en solo lectura. Escribir no pasa nunca por la copia.
 *
 * Solo se usa con SharePoint; el modo local no tiene red que perder.
 */
public class CopiaSinConexion {

    private static final Logger LOG = LoggerFactory.getLogger(CopiaSinConexion.class);

    static final String ENSAYOS = "ensayos";
    static final String CATALOGOS = "catalogos";
    static final String USUARIOS = "usuarios";

    private final Path carpeta;
    private final EstadoConexion estado;
    private final Clock reloj;

    public CopiaSinConexion(Path carpetaUsuario, EstadoConexion estado) {
        this(carpetaUsuario, estado, Clock.systemUTC());
    }

    CopiaSinConexion(Path carpetaUsuario, EstadoConexion estado, Clock reloj) {
        this.carpeta = carpetaUsuario.resolve("copia-sin-conexion");
        this.estado = estado;
        this.reloj = reloj;
    }

    // ---- envoltorios de los almacenes ---------------------------------------------

    public AlmacenEnsayos ensayos(AlmacenEnsayos real) {
        return new AlmacenEnsayos() {
            @Override
            public List<FilaGuardada> leer() {
                JsonNode copia = leerConCopia(ENSAYOS, () -> {
                    ArrayNode filas = JsonJs.mapper().createArrayNode();
                    real.leer().forEach(f -> filas.addObject().put("uid", f.uid()).put("texto", f.texto()));
                    return filas;
                }, filas -> filas.size() > 0);
                List<FilaGuardada> salida = new ArrayList<>();
                // Una fila sin DataJson llega con texto null y así debe seguir (no la cadena "null").
                copia.forEach(f -> salida.add(new FilaGuardada(f.path("uid").asText(), f.hasNonNull("texto") ? f.get("texto").asText() : null)));
                return salida;
            }

            @Override
            public ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor) {
                return real.guardar(cambios, comprobarVersion, actor);
            }
        };
    }

    public AlmacenCatalogos catalogos(AlmacenCatalogos real) {
        return new AlmacenCatalogos() {
            @Override
            public ObjectNode leer() {
                // Una lectura parcial (alguna lista con *_error) no sustituye a la copia buena.
                return (ObjectNode) leerConCopia(CATALOGOS, real::leer, c -> c.path("benches").size() > 0
                        && !c.has("benches_error") && !c.has("projects_error") && !c.has("responsibles_error"));
            }

            @Override
            public ObjectNode guardar(JsonNode catalogos) {
                return real.guardar(catalogos);
            }
        };
    }

    /**
     * Usuarios e identidad. Una lista vacía también se trata como fallo (la
     * lectura de SharePoint devuelve [] si no puede leer, ver H2b), igual que la
     * 4.9.4 recurría a la copia cuando USERS_CACHE quedaba vacío.
     */
    public AlmacenUsuarios usuarios(AlmacenUsuarios real) {
        return new AlmacenUsuarios() {
            @Override
            public Identidad identidadActual() {
                JsonNode copia = leerConCopia("identidad", () -> {
                    Identidad i = real.identidadActual();
                    if (i == null || i.email() == null || i.email().isBlank()) {
                        throw new IllegalStateException("Sin identidad");
                    }
                    return JsonJs.objetoVacio().put("email", i.email()).put("nombre", i.nombre());
                }, i -> true);
                return new Identidad(copia.path("email").asText(""), copia.path("nombre").asText(""));
            }

            @Override
            public ArrayNode leer() {
                return (ArrayNode) leerConCopia(USUARIOS, () -> {
                    ArrayNode lista = real.leer();
                    if (lista.isEmpty() && existe(USUARIOS)) {
                        throw new IllegalStateException("Lista de usuarios vacía");
                    }
                    return lista;
                }, l -> l.size() > 0);
            }

            @Override
            public ObjectNode guardar(JsonNode usuarios) {
                return real.guardar(usuarios);
            }
        };
    }

    // ---- lectura con copia ---------------------------------------------------------

    /**
     * Lee del servidor; si va bien, guarda la copia (solo si el resultado merece
     * guardarse: nunca se pisa una copia buena con una lista vacía). Si falla y
     * hay copia, la devuelve y lo anota; si no hay copia, el fallo sigue su curso.
     */
    private JsonNode leerConCopia(String nombre, Supplier<JsonNode> servidor, java.util.function.Predicate<JsonNode> guardable) {
        JsonNode valor;
        try {
            valor = servidor.get();
        } catch (RuntimeException fallo) {
            ObjectNode copia = cargar(nombre);
            if (copia == null) {
                throw fallo;
            }
            Instant fecha = Instant.ofEpochMilli(copia.path("ts").asLong());
            if (!estado.sinConexion()) {
                LOG.warn("SharePoint no responde ({}): se muestra la última copia de {} ({})", fallo.getMessage(), nombre, fecha);
            }
            estado.servidaDesdeCopia(nombre, fecha);
            return copia.get("datos");
        }
        estado.leidaDelServidor(nombre);
        if (guardable.test(valor)) {
            guardar(nombre, valor);
        }
        return valor;
    }

    private boolean existe(String nombre) {
        return Files.exists(carpeta.resolve(nombre + ".json"));
    }

    private ObjectNode cargar(String nombre) {
        Path f = carpeta.resolve(nombre + ".json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            JsonNode n = JsonJs.mapper().readTree(f.toFile());
            return n instanceof ObjectNode o && o.has("datos") ? o : null;
        } catch (IOException e) {
            LOG.warn("No se pudo leer la copia sin conexión {}: {}", f, e.getMessage());
            return null;
        }
    }

    /** Escritura atómica (temporal y renombrar): una copia a medias no sirve. */
    private void guardar(String nombre, JsonNode valor) {
        try {
            Files.createDirectories(carpeta);
            Path temporal = carpeta.resolve(nombre + ".json.tmp");
            ObjectNode sobre = JsonJs.objetoVacio().put("ts", reloj.millis());
            sobre.set("datos", valor);
            JsonJs.mapper().writeValue(temporal.toFile(), sobre);
            Files.move(temporal, carpeta.resolve(nombre + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("No se pudo guardar la copia sin conexión de {}: {}", nombre, e.getMessage());
        }
    }
}
