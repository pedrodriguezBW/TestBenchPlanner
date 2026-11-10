package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenFacturacion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.RequestOptions;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Facturación compartida en una carpeta de una biblioteca de documentos del
 * sitio (un .json por documento y los ficheros exportados en «exportaciones»).
 *
 * Se usa una biblioteca y no una lista porque un periodo con sus fotos puede
 * superar el tamaño razonable de una columna de texto. La carpeta NO se crea
 * desde la aplicación (igual que las listas): hay que crearla una vez en
 * SharePoint e indicarla en planner.facturacion.carpeta-sharepoint.
 *
 * Concurrencia: antes de guardar se compara el ETag del fichero con el leído;
 * si ha cambiado, ConflictoFacturacion (409). Toda escritura exige
 * planner.sharepoint.escritura-habilitada=true.
 */
public class AlmacenFacturacionSharePoint implements AlmacenFacturacion {

    private final ClienteSharePoint cliente;
    private final String carpeta;

    /**
     * @param carpetaRelativa carpeta dentro del sitio, p. ej. «Shared Documents/TestBenchPlanner/Facturacion»
     */
    public AlmacenFacturacionSharePoint(ClienteSharePoint cliente, String carpetaRelativa) {
        this.cliente = cliente;
        String rutaSitio = URI.create(cliente.sitio()).getPath().replaceAll("/+$", "");
        this.carpeta = rutaSitio + "/" + carpetaRelativa.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String comillas(String texto) {
        return texto.replace("'", "''");
    }

    private String urlFichero(String ruta) {
        return cliente.sitio() + "/_api/web/GetFileByServerRelativeUrl('" + URLEncoder.encode(comillas(ruta), StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/") + "')";
    }

    private String urlCarpeta(String ruta) {
        return cliente.sitio() + "/_api/web/GetFolderByServerRelativeUrl('" + URLEncoder.encode(comillas(ruta), StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/") + "')";
    }

    private record Leido(String texto, String etag) {
    }

    private Leido leerFichero(BrowserContext contexto, String ruta) {
        APIResponse meta = contexto.request().get(urlFichero(ruta), RequestOptions.create()
                .setHeader("Accept", ClienteSharePoint.JSON_SIN_METADATOS).setTimeout(60000));
        if (meta.status() == 404) {
            return null;
        }
        if (!meta.ok()) {
            throw new IllegalStateException("No se pudo leer " + ruta + " (" + meta.status() + ")");
        }
        JsonNode m = JsonJs.leer(meta.text());
        String etag = m == null ? "" : m.path("ETag").asText("");
        APIResponse r = contexto.request().get(urlFichero(ruta) + "/$value", RequestOptions.create().setTimeout(60000));
        if (!r.ok()) {
            throw new IllegalStateException("No se pudo leer " + ruta + " (" + r.status() + ")");
        }
        return new Leido(r.text(), etag);
    }

    @Override
    public Documento leer(String clave) {
        return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            Leido l = leerFichero(contexto, carpeta + "/" + clave + ".json");
            if (l == null) {
                return null;
            }
            JsonNode n = JsonJs.leer(l.texto());
            if (!(n instanceof ObjectNode o)) {
                throw new IllegalStateException("El documento de facturación " + clave + " no es JSON válido");
            }
            return new Documento(o, l.etag());
        });
    }

    @Override
    public List<String> claves(String prefijo) {
        return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            JsonNode datos = cliente.get(contexto, urlCarpeta(carpeta) + "/Files?$select=Name&$top=5000");
            List<String> salida = new ArrayList<>();
            for (JsonNode f : datos.path("value")) {
                String nombre = f.path("Name").asText("");
                if (nombre.endsWith(".json") && nombre.startsWith(prefijo)) {
                    salida.add(nombre.substring(0, nombre.length() - 5));
                }
            }
            salida.sort(String::compareTo);
            return salida;
        });
    }

    @Override
    public String guardar(String clave, ObjectNode contenido, String versionEsperada) {
        cliente.exigirEscritura("guardar " + clave + " de facturación");
        byte[] datos;
        try {
            datos = JsonJs.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(contenido);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("No se pudo preparar " + clave, e);
        }
        return cliente.conSesion(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> {
            Leido actual = leerFichero(contexto, carpeta + "/" + clave + ".json");
            String etagActual = actual == null ? null : actual.etag();
            if (versionEsperada == null ? etagActual != null : !versionEsperada.equals(etagActual)) {
                throw new ConflictoFacturacion("Otra persona ha cambiado los datos de facturación. Vuelve a cargarlos.");
            }
            if (actual == null) {
                // Nuevo: overwrite=false, así un alta simultánea de otra persona falla en vez de pisarse.
                subir(contexto, clave + ".json", carpeta, datos, false);
            } else {
                // Existente: se sustituye solo si el ETag sigue siendo el leído (412 = otra persona lo cambió).
                APIResponse r = contexto.request().post(urlFichero(carpeta + "/" + clave + ".json") + "/$value", RequestOptions.create()
                        .setHeader("Accept", ClienteSharePoint.JSON_SIN_METADATOS)
                        .setHeader("X-RequestDigest", cliente.digest(contexto))
                        .setHeader("X-HTTP-Method", "PUT")
                        .setHeader("IF-MATCH", etagActual)
                        .setData(datos).setTimeout(120000));
                if (r.status() == 412) {
                    throw new ConflictoFacturacion("Otra persona ha cambiado los datos de facturación. Vuelve a cargarlos.");
                }
                if (!r.ok()) {
                    throw new IllegalStateException("No se pudo guardar " + clave + " (" + r.status() + ")");
                }
            }
            Leido nuevo = leerFichero(contexto, carpeta + "/" + clave + ".json");
            return nuevo == null ? "" : nuevo.etag();
        });
    }

    @Override
    public void guardarArchivo(String nombre, byte[] contenido) {
        cliente.exigirEscritura("guardar la exportación " + nombre);
        cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
            subir(contexto, nombre, carpeta + "/exportaciones", contenido, true);
            return null;
        });
    }

    private void subir(BrowserContext contexto, String nombre, String enCarpeta, byte[] datos, boolean sobrescribir) {
        String url = urlCarpeta(enCarpeta) + "/Files/add(url='" + URLEncoder.encode(comillas(nombre), StandardCharsets.UTF_8).replace("+", "%20")
                + "',overwrite=" + sobrescribir + ")";
        APIResponse r = contexto.request().post(url, RequestOptions.create()
                .setHeader("Accept", ClienteSharePoint.JSON_SIN_METADATOS)
                .setHeader("X-RequestDigest", cliente.digest(contexto))
                .setData(datos).setTimeout(120000));
        if (!sobrescribir && (r.status() == 409 || r.status() == 400) && String.valueOf(r.text()).toLowerCase(java.util.Locale.ROOT).contains("exist")) {
            throw new ConflictoFacturacion("Otra persona ha creado estos datos de facturación a la vez. Vuelve a cargarlos.");
        }
        if (!r.ok()) {
            throw new IllegalStateException("No se pudo guardar " + nombre + " en " + enCarpeta + " (" + r.status() + "). "
                    + "Comprueba que la carpeta existe y que tienes permiso de escritura.");
        }
    }
}
