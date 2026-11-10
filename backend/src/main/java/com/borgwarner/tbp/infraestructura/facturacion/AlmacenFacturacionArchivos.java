package com.borgwarner.tbp.infraestructura.facturacion;

import com.borgwarner.tbp.aplicacion.Versiones;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenFacturacion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Facturación en ficheros de este PC: un .json por documento y una carpeta
 * «exportaciones» con los ficheros generados. Escritura atómica (temporal propio
 * + renombrado) y comprobación de versión dentro del cerrojo.
 */
public class AlmacenFacturacionArchivos implements AlmacenFacturacion {

    private static final ObjectWriter ESCRITOR = JsonJs.mapper().writerWithDefaultPrettyPrinter();
    private final Path carpeta;
    private final Object cerrojo = new Object();

    public AlmacenFacturacionArchivos(Path carpeta) {
        this.carpeta = carpeta;
        try {
            Files.createDirectories(carpeta.resolve("exportaciones"));
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede crear " + carpeta, e);
        }
    }

    private Path ruta(String clave) {
        if (!clave.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Clave no válida: " + clave);
        }
        return carpeta.resolve(clave + ".json");
    }

    @Override
    public Documento leer(String clave) {
        synchronized (cerrojo) {
            Path r = ruta(clave);
            if (!Files.exists(r)) {
                return null;
            }
            try {
                String texto = Files.readString(r, StandardCharsets.UTF_8);
                JsonNode n = JsonJs.leer(texto);
                if (!(n instanceof ObjectNode o)) {
                    throw new IllegalStateException("El fichero " + r + " no es un documento JSON válido");
                }
                return new Documento(o, Versiones.de(texto));
            } catch (IOException e) {
                throw new UncheckedIOException("No se puede leer " + r, e);
            }
        }
    }

    @Override
    public List<String> claves(String prefijo) {
        List<String> salida = new ArrayList<>();
        try (Stream<Path> s = Files.list(carpeta)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json") && n.startsWith(prefijo))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().forEach(salida::add);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede leer " + carpeta, e);
        }
        return salida;
    }

    @Override
    public String guardar(String clave, ObjectNode contenido, String versionEsperada) {
        synchronized (cerrojo) {
            Documento actual = leer(clave);
            String versionActual = actual == null ? null : actual.version();
            if (versionEsperada == null ? versionActual != null : !versionEsperada.equals(versionActual)) {
                throw new ConflictoFacturacion("Otra persona ha cambiado los datos de facturación. Vuelve a cargarlos.");
            }
            Path r = ruta(clave);
            Path temporal = carpeta.resolve(clave + ".json." + Thread.currentThread().getId() + ".tmp");
            try {
                String texto = ESCRITOR.writeValueAsString(contenido);
                Files.writeString(temporal, texto, StandardCharsets.UTF_8);
                Files.move(temporal, r, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return Versiones.de(texto);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (IOException ignorado) {
                    // el temporal se queda; no afecta a los datos
                }
                throw new UncheckedIOException("No se puede escribir " + r, e);
            }
        }
    }

    @Override
    public void guardarArchivo(String nombre, byte[] contenido) {
        if (!nombre.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Nombre de fichero no válido: " + nombre);
        }
        try {
            Files.write(carpeta.resolve("exportaciones").resolve(nombre), contenido);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede guardar la exportación " + nombre, e);
        }
    }

    public Path carpeta() {
        return carpeta;
    }
}
