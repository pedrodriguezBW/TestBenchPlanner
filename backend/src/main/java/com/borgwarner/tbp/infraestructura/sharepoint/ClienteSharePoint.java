package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Llamadas REST a SharePoint (equivalente a los helpers del backend 4.9.4).
 *
 * TODAS las escrituras pasan por {@link #exigirEscritura(String)}: con la
 * escritura desactivada (valor por defecto) se lanza EscrituraDesactivada antes
 * de enviar nada. Así es imposible escribir en las listas por accidente
 * durante el desarrollo o las pruebas.
 */
public class ClienteSharePoint {

    public static final String JSON_SIN_METADATOS = "application/json;odata=nometadata";
    public static final String JSON_CON_ETAG = "application/json;odata=minimalmetadata";

    /** Respuesta simplificada. */
    public record Respuesta(int estado, String cuerpo) {
        public boolean ok() {
            return estado >= 200 && estado < 300;
        }

        public JsonNode json() {
            JsonNode nodo = JsonJs.leer(cuerpo);
            return nodo == null ? JsonJs.objetoVacio() : nodo;
        }
    }

    private final ConexionEdge conexion;
    private final String sitio;
    private final boolean escrituraHabilitada;

    public ClienteSharePoint(ConexionEdge conexion, String sitio, boolean escrituraHabilitada) {
        this.conexion = conexion;
        this.sitio = sitio;
        this.escrituraHabilitada = escrituraHabilitada;
    }

    public String sitio() {
        return sitio;
    }

    public boolean escrituraHabilitada() {
        return escrituraHabilitada;
    }

    public void exigirEscritura(String operacion) {
        if (!escrituraHabilitada) {
            throw new EscrituraDesactivada(operacion);
        }
    }

    /** Ejecuta un bloque con la sesión comprobada, en el hilo de SharePoint. */
    public <T> T conSesion(int prioridad, java.util.function.Function<BrowserContext, T> bloque) {
        return conexion.ejecutar(prioridad, contexto -> {
            if (!asegurarSesion(contexto)) {
                throw new SinSesionAlmacen();
            }
            return bloque.apply(contexto);
        });
    }

    /** URL base de una lista por su título. */
    public static String lista(String sitio, String titulo) {
        return sitio + "/_api/web/lists/GetByTitle('" + titulo.replace("'", "''") + "')";
    }

    public String lista(String titulo) {
        return lista(sitio, titulo);
    }

    // ---- sesión SSO ----------------------------------------------------------

    private boolean sesionActiva(BrowserContext contexto) {
        try {
            APIResponse r = contexto.request().get(sitio + "/_api/web/title",
                    RequestOptions.create().setHeader("Accept", JSON_SIN_METADATOS).setTimeout(8000));
            return r.ok() && JsonJs.leer(r.text()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Si no hay sesión, navega al sitio para que Edge complete el SSO y espera hasta 45 s. */
    private boolean asegurarSesion(BrowserContext contexto) {
        if (sesionActiva(contexto)) {
            return true;
        }
        Page pagina = contexto.pages().isEmpty() ? contexto.newPage() : contexto.pages().get(0);
        try {
            pagina.navigate(sitio, new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT).setTimeout(15000));
        } catch (RuntimeException ignorada) {
            // seguimos esperando a la sesión
        }
        for (int i = 0; i < 45; i++) {
            if (sesionActiva(contexto)) {
                return true;
            }
            pagina.waitForTimeout(1000);
        }
        return sesionActiva(contexto);
    }

    // ---- lecturas ------------------------------------------------------------

    public JsonNode get(BrowserContext contexto, String url) {
        return get(contexto, url, JSON_SIN_METADATOS);
    }

    public JsonNode get(BrowserContext contexto, String url, String accept) {
        APIResponse r = contexto.request().get(url, RequestOptions.create().setHeader("Accept", accept).setTimeout(60000));
        if (!r.ok()) {
            throw new IllegalStateException("GET " + url + " -> " + r.status());
        }
        JsonNode json = JsonJs.leer(r.text());
        if (json == null) {
            // Suele ser una página de inicio de sesión en lugar de JSON.
            throw new IllegalStateException("GET " + url + " no devolvió JSON");
        }
        return json;
    }

    /** Lee todas las páginas de una consulta de elementos ($top + nextLink). */
    public List<JsonNode> leerPaginado(BrowserContext contexto, String url, String sitioBase, String accept) {
        List<JsonNode> filas = new ArrayList<>();
        String siguiente = url;
        while (siguiente != null) {
            JsonNode datos = get(contexto, siguiente, accept);
            datos.path("value").forEach(filas::add);
            String enlace = datos.path("@odata.nextLink").asText(datos.path("odata.nextLink").asText(""));
            if (!enlace.isEmpty() && !enlace.startsWith("http")) {
                enlace = sitioBase + "/_api/" + enlace.replaceFirst("^/+", "");
            }
            siguiente = enlace.isEmpty() ? null : enlace;
        }
        return filas;
    }

    /** Nombres internos de las columnas de una lista. */
    public Set<String> columnas(BrowserContext contexto, String sitioLista, String titulo) {
        Set<String> nombres = new HashSet<>();
        get(contexto, lista(sitioLista, titulo) + "/fields?$select=InternalName&$top=500")
                .path("value").forEach(f -> nombres.add(f.path("InternalName").asText()));
        return nombres;
    }

    /**
     * Lectura robusta de una lista: solo pide las columnas que existen (si se
     * pide una que no existe, SharePoint devuelve error y no se leería nada).
     */
    public List<JsonNode> leerLista(BrowserContext contexto, String titulo, List<String> columnasDeseadas) {
        List<String> seleccion = new ArrayList<>(List.of("Id", "Title"));
        try {
            Set<String> existentes = columnas(contexto, sitio, titulo);
            columnasDeseadas.stream().filter(existentes::contains).forEach(seleccion::add);
        } catch (RuntimeException e) {
            // sin columnas: solo Id y Title
        }
        String url = lista(titulo) + "/items?$select=" + String.join(",", seleccion) + "&$top=2000&$orderby=Id asc";
        return leerPaginado(contexto, url, sitio, JSON_SIN_METADATOS);
    }

    // ---- escrituras ----------------------------------------------------------

    public String digest(BrowserContext contexto) {
        APIResponse r = contexto.request().post(sitio + "/_api/contextinfo",
                RequestOptions.create().setHeader("Accept", JSON_SIN_METADATOS).setTimeout(30000));
        if (!r.ok()) {
            throw new IllegalStateException("No se pudo obtener FormDigest.");
        }
        JsonNode j = JsonJs.leer(r.text());
        if (j == null) {
            throw new IllegalStateException("No se pudo obtener FormDigest (respuesta no JSON).");
        }
        String valor = j.path("FormDigestValue").asText("");
        return valor.isEmpty() ? j.path("d").path("GetContextWebInformation").path("FormDigestValue").asText("") : valor;
    }

    /** Crea un elemento y devuelve su Id (o null). */
    public Integer crear(BrowserContext contexto, String digest, String baseLista, ObjectNode cuerpo) {
        exigirEscritura("crear elemento en " + baseLista);
        APIResponse r = contexto.request().post(baseLista + "/items", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("Content-Type", JSON_SIN_METADATOS)
                .setHeader("X-RequestDigest", digest).setData(JsonJs.escribir(cuerpo)).setTimeout(60000));
        if (!(r.status() == 200 || r.status() == 201 || r.status() == 204)) {
            throw new IllegalStateException("crear " + r.status() + ": " + recortar(r.text(), 200));
        }
        JsonNode j = JsonJs.leer(r.text());
        return j != null && j.hasNonNull("Id") ? j.get("Id").asInt() : null;
    }

    /** MERGE de campos; etag = «*» para «el último gana». */
    public Respuesta merge(BrowserContext contexto, String digest, String baseLista, int id, ObjectNode cuerpo, String etag) {
        exigirEscritura("actualizar elemento " + id + " de " + baseLista);
        APIResponse r = contexto.request().post(baseLista + "/items(" + id + ")", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("Content-Type", JSON_SIN_METADATOS)
                .setHeader("X-RequestDigest", digest).setHeader("IF-MATCH", etag == null ? "*" : etag)
                .setHeader("X-HTTP-Method", "MERGE").setData(JsonJs.escribir(cuerpo)).setTimeout(60000));
        return new Respuesta(r.status(), r.status() == 200 || r.status() == 204 ? "" : recortar(r.text(), 180));
    }

    /** Envía el elemento a la papelera (recuperable). */
    public boolean reciclar(BrowserContext contexto, String digest, String baseLista, int id) {
        exigirEscritura("borrar elemento " + id + " de " + baseLista);
        APIResponse r = contexto.request().post(baseLista + "/items(" + id + ")/recycle", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("X-RequestDigest", digest).setTimeout(60000));
        return r.status() == 200 || r.status() == 204;
    }

    /** Crea un elemento solo con Title y devuelve su Id (_list_create_min). */
    public Integer crearMinimo(BrowserContext contexto, String digest, String baseLista, String titulo) {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("Title", titulo);
        Integer id = crear(contexto, digest, baseLista, cuerpo);
        if (id != null) {
            return id;
        }
        String filtro = "Title eq '" + titulo.replace("'", "''") + "'";
        JsonNode valores = get(contexto, baseLista + "/items?$select=Id&$filter=" + filtro + "&$top=1&$orderby=Id desc").path("value");
        return valores.size() == 0 ? null : valores.get(0).path("Id").asInt();
    }

    /** Cambios campo a campo para que una columna problemática no bloquee la fila. */
    public Map<String, String> mergeCampoACampo(BrowserContext contexto, String digest, String baseLista, int id, ObjectNode campos) {
        Map<String, String> errores = new java.util.LinkedHashMap<>();
        campos.fields().forEachRemaining(campo -> {
            if (campo.getKey().equals("Title")) {
                return;
            }
            ObjectNode uno = JsonJs.objetoVacio();
            uno.set(campo.getKey(), campo.getValue());
            Respuesta r = merge(contexto, digest, baseLista, id, uno, "*");
            if (!r.ok()) {
                errores.put(campo.getKey(), r.estado() + " " + r.cuerpo());
            }
        });
        return errores;
    }

    /**
     * Reconciliación de una lista (_reconcile_list): crea o actualiza las filas
     * recibidas y manda a la papelera las que no vienen.
     */
    public ObjectNode reconciliar(BrowserContext contexto, String digest, String titulo, List<String> columnas, List<ObjectNode> filas) {
        exigirEscritura("reconciliar " + titulo);
        String base = lista(titulo);
        Map<String, Integer> existentes = new java.util.LinkedHashMap<>();
        for (JsonNode it : leerLista(contexto, titulo, columnas)) {
            if (it.hasNonNull("Title")) {
                existentes.put(it.get("Title").asText(), it.path("Id").asInt());
            }
        }
        Set<String> recibidas = new HashSet<>();
        int creadas = 0, actualizadas = 0, borradas = 0;
        ObjectNode errores = JsonJs.objetoVacio();
        for (ObjectNode fila : filas) {
            if (!fila.hasNonNull("Title")) {
                continue;
            }
            String clave = fila.get("Title").asText();
            recibidas.add(clave);
            Integer id = existentes.get(clave);
            if (id == null) {
                id = crearMinimo(contexto, digest, base, clave);
                if (id == null) {
                    continue;
                }
                creadas++;
            } else {
                actualizadas++;
            }
            mergeCampoACampo(contexto, digest, base, id, fila).forEach(errores::put);
        }
        for (Map.Entry<String, Integer> existente : existentes.entrySet()) {
            if (!recibidas.contains(existente.getKey())) {
                try {
                    reciclar(contexto, digest, base, existente.getValue());
                    borradas++;
                } catch (RuntimeException ignorada) {
                    // como en la 4.9.4: se sigue con el resto
                }
            }
        }
        return JsonJs.objetoVacio().put("created", creadas).put("updated", actualizadas).put("deleted", borradas)
                .set("field_errors", errores);
    }

    static String recortar(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
