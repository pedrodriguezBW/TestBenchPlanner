package com.borgwarner.tbp.infraestructura.local;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Carpeta de datos del modo local. Cada «lista» es un fichero JSON con el mismo
 * formato que los escenarios de herramientas/importar-sharepoint, así que un
 * escenario se puede copiar tal cual como punto de partida.
 *
 * Las escrituras son atómicas (fichero temporal + renombrado) y se serializan
 * con un cerrojo: el modo local admite varias pestañas a la vez.
 */
public class DatosLocales {

    public static final List<String> FICHEROS = List.of(
            "planner.json", "configuracion.json", "kpis.json", "usuarios.json",
            "recursos.json", "aprobaciones.json", "version.json");

    private static final ObjectWriter ESCRITOR = JsonJs.mapper().writer().with(SerializationFeature.INDENT_OUTPUT);

    private final Path carpeta;
    private final Object cerrojo = new Object();

    public DatosLocales(Path carpeta, Path escenarioInicial, boolean reiniciar) {
        this.carpeta = carpeta;
        try {
            Files.createDirectories(carpeta);
            if (reiniciar || !Files.exists(carpeta.resolve("planner.json"))) {
                copiarEscenario(escenarioInicial);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede preparar la carpeta de datos locales " + carpeta, e);
        }
    }

    /** Vuelve a dejar los datos como en el escenario inicial. */
    public void copiarEscenario(Path escenario) {
        synchronized (cerrojo) {
            try {
                if (escenario == null || !Files.isDirectory(escenario)) {
                    throw new IllegalStateException("No existe el escenario inicial: " + escenario);
                }
                for (String fichero : FICHEROS) {
                    Path origen = escenario.resolve(fichero);
                    if (Files.exists(origen)) {
                        Files.copy(origen, carpeta.resolve(fichero), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.deleteIfExists(carpeta.resolve("actividad.json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public JsonNode leer(String fichero, JsonNode siNoExiste) {
        synchronized (cerrojo) {
            Path ruta = carpeta.resolve(fichero);
            if (!Files.exists(ruta)) {
                return siNoExiste;
            }
            try {
                return JsonJs.mapper().readTree(ruta.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException("No se puede leer " + ruta, e);
            }
        }
    }

    public void escribir(String fichero, JsonNode contenido) {
        synchronized (cerrojo) {
            Path ruta = carpeta.resolve(fichero);
            Path temporal = carpeta.resolve(fichero + ".tmp");
            try {
                ESCRITOR.writeValue(temporal.toFile(), contenido);
                Files.move(temporal, ruta, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("No se puede escribir " + ruta, e);
            }
        }
    }

    /** Ejecuta un bloque con el cerrojo tomado (lectura + comprobación + escritura). */
    public <T> T conCerrojo(java.util.function.Supplier<T> bloque) {
        synchronized (cerrojo) {
            return bloque.get();
        }
    }

    public Path carpeta() {
        return carpeta;
    }
}
