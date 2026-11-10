package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.puertos.SinSesionAlmacen;
import com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Llamadas REST a SharePoint (equivalente a los helpers del backend 4.9.4).
 *
 * TODAS las escrituras pasan por {@link #exigirEscritura(String)}: con la
 * escritura desactivada (valor por defecto) se lanza EscrituraDesactivada antes
 * de enviar nada. Así es imposible escribir en las listas por accidente
 * durante el desarrollo o las pruebas.
 *
 * Rendimiento (antes cada llamada repetía trabajo que no cambia):
 *  - La sesión SSO se comprueba como mucho una vez por minuto, no antes de
 *    cada llamada. Si una respuesta indica que la sesión ha caducado (401/403 o
 *    una página de inicio de sesión en lugar de JSON) y el bloque no había
 *    escrito nada, se recupera la sesión y el bloque se repite una vez.
 *  - El FormDigest se reutiliza mientras es válido (si SharePoint lo rechaza,
 *    se pide otro y se reintenta: la petición rechazada no llegó a escribir).
 *  - Las columnas de cada lista se recuerdan (el esquema no lo cambia la
 *    aplicación); si una consulta falla por una columna, se vuelven a leer.
 *  - Las consultas ({@link #consultar}) van por los carriles de lectura y no
 *    esperan detrás de los guardados; desde ellas no se puede escribir.
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

    static final long VIGENCIA_SESION_MS = 60_000;
    static final long VIGENCIA_COLUMNAS_MS = 30 * 60_000;
    static final long VIGENCIA_MAXIMA_DIGEST_MS = 10 * 60_000;

    /** Marcas del bloque que se está ejecutando en este hilo. */
    private static final class Marcas {
        boolean sesionSospechosa;
        boolean escrito;
        boolean soloLectura;
    }

    private static final ThreadLocal<Marcas> MARCAS = new ThreadLocal<>();

    private record Digest(String valor, long caduca) {
    }

    private record Columnas(Set<String> nombres, long leidas) {
    }

    private final ConexionEdge conexion;
    private final String sitio;
    private final boolean escrituraHabilitada;
    private final LongSupplier relojMs;
    private final Object cerrojoSesion = new Object();
    private volatile long sesionVerificadaHasta;
    private volatile Digest digest;
    private final Map<String, Columnas> columnas = new ConcurrentHashMap<>();

    public ClienteSharePoint(ConexionEdge conexion, String sitio, boolean escrituraHabilitada) {
        this(conexion, sitio, escrituraHabilitada, System::currentTimeMillis);
    }

    ClienteSharePoint(ConexionEdge conexion, String sitio, boolean escrituraHabilitada, LongSupplier relojMs) {
        this.conexion = conexion;
        this.sitio = sitio;
        this.escrituraHabilitada = escrituraHabilitada;
        this.relojMs = relojMs;
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

    /** Justo antes de enviar una escritura: no desde una consulta y anotando que el bloque ya ha escrito. */
    private void antesDeEscribir(String operacion) {
        exigirEscritura(operacion);
        Marcas marcas = MARCAS.get();
        if (marcas != null && marcas.soloLectura) {
            throw new IllegalStateException("Escritura dentro de una consulta (solo lectura): " + operacion);
        }
        if (marcas != null) {
            marcas.escrito = true;
        }
    }

    /**
     * Ejecuta un bloque con la sesión comprobada en el carril PRINCIPAL de
     * SharePoint. Es el que se usa para escribir (y para las lecturas que forman
     * parte de una escritura).
     */
    public <T> T conSesion(int prioridad, Function<BrowserContext, T> bloque) {
        return conexion.ejecutar(prioridad, contexto -> ejecutarBloque(contexto, bloque, false));
    }

    /**
     * Ejecuta una CONSULTA (sin escrituras) en un carril de lectura: no espera
     * detrás de los guardados ni de otras consultas largas, y se descarta si la
     * pestaña que la pidió se ha cerrado.
     */
    public <T> T consultar(int prioridad, Function<BrowserContext, T> bloque) {
        return conexion.consultar(prioridad, contexto -> ejecutarBloque(contexto, bloque, true));
    }

    private <T> T ejecutarBloque(BrowserContext contexto, Function<BrowserContext, T> bloque, boolean soloLectura) {
        Marcas anteriores = MARCAS.get();
        Marcas marcas = new Marcas();
        marcas.soloLectura = soloLectura || (anteriores != null && anteriores.soloLectura);
        MARCAS.set(marcas);
        try {
            verificarSesion(contexto, false);
            T resultado;
            try {
                resultado = bloque.apply(contexto);
            } catch (RuntimeException e) {
                if (!repetirTrasRecuperarSesion(contexto, marcas)) {
                    throw e;
                }
                marcas.sesionSospechosa = false;
                return bloque.apply(contexto);
            }
            if (repetirTrasRecuperarSesion(contexto, marcas)) {
                // Lo leído pudo salir de una sesión caducada (listas vacías o con *_error): se lee otra vez.
                marcas.sesionSospechosa = false;
                return bloque.apply(contexto);
            }
            return resultado;
        } finally {
            if (anteriores != null) {
                anteriores.escrito |= marcas.escrito;
                MARCAS.set(anteriores);
            } else {
                MARCAS.remove();
            }
        }
    }

    /** Si el bloque vio señales de sesión caducada y no escribió nada, recupera la sesión y pide repetirlo. */
    private boolean repetirTrasRecuperarSesion(BrowserContext contexto, Marcas marcas) {
        if (!marcas.sesionSospechosa || marcas.escrito) {
            return false;
        }
        sesionVerificadaHasta = 0;
        verificarSesion(contexto, true);
        return true;
    }

    /** Comprueba la sesión si no se ha comprobado en el último minuto. Un solo carril navega a la vez. */
    private void verificarSesion(BrowserContext contexto, boolean forzar) {
        if (!forzar && relojMs.getAsLong() < sesionVerificadaHasta) {
            return;
        }
        synchronized (cerrojoSesion) {
            if (!forzar && relojMs.getAsLong() < sesionVerificadaHasta) {
                return;
            }
            if (!asegurarSesion(contexto)) {
                sesionVerificadaHasta = 0;
                throw new SinSesionAlmacen();
            }
            sesionVerificadaHasta = relojMs.getAsLong() + VIGENCIA_SESION_MS;
        }
    }

    private static void sospecharDeLaSesion() {
        Marcas marcas = MARCAS.get();
        if (marcas != null) {
            marcas.sesionSospechosa = true;
        }
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
            if (r.status() == 401 || r.status() == 403) {
                sospecharDeLaSesion();
            }
            throw new IllegalStateException("GET " + url + " -> " + r.status());
        }
        JsonNode json = JsonJs.leer(r.text());
        if (json == null) {
            // Suele ser una página de inicio de sesión en lugar de JSON.
            sospecharDeLaSesion();
            throw new IllegalStateException("GET " + url + " no devolvió JSON");
        }
        return json;
    }

    /** Lee todas las páginas de una consulta de elementos ($top + nextLink). */
    public List<JsonNode> leerPaginado(BrowserContext contexto, String url, String sitioBase, String accept) {
        List<JsonNode> filas = new ArrayList<>();
        String siguiente = url;
        while (siguiente != null) {
            Cancelacion.comprobar(); // la pestaña que lo pidió ya no existe: no se piden más páginas
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

    /** Nombres internos de las columnas de una lista (recordados 30 min: el esquema no lo toca la aplicación). */
    public Set<String> columnas(BrowserContext contexto, String sitioLista, String titulo) {
        String clave = sitioLista + "|" + titulo;
        Columnas recordadas = columnas.get(clave);
        if (recordadas != null && relojMs.getAsLong() - recordadas.leidas() < VIGENCIA_COLUMNAS_MS) {
            return recordadas.nombres();
        }
        Set<String> nombres = new HashSet<>();
        get(contexto, lista(sitioLista, titulo) + "/fields?$select=InternalName&$top=500")
                .path("value").forEach(f -> nombres.add(f.path("InternalName").asText()));
        Set<String> inmutables = Set.copyOf(nombres);
        columnas.put(clave, new Columnas(inmutables, relojMs.getAsLong()));
        return inmutables;
    }

    /** Olvida las columnas recordadas de una lista (tras un error que puede deberse a una columna). */
    public void olvidarColumnas(String sitioLista, String titulo) {
        columnas.remove(sitioLista + "|" + titulo);
    }

    /**
     * Lectura robusta de una lista: solo pide las columnas que existen (si se
     * pide una que no existe, SharePoint devuelve error y no se leería nada).
     */
    public List<JsonNode> leerLista(BrowserContext contexto, String titulo, List<String> columnasDeseadas) {
        boolean recordadas = columnas.containsKey(sitio + "|" + titulo);
        try {
            return leerListaUnaVez(contexto, titulo, columnasDeseadas);
        } catch (RuntimeException e) {
            if (!recordadas || e instanceof com.borgwarner.tbp.aplicacion.PeticionCancelada) {
                throw e;
            }
            // Puede que una columna recordada ya no exista: se vuelven a leer las columnas.
            olvidarColumnas(sitio, titulo);
            return leerListaUnaVez(contexto, titulo, columnasDeseadas);
        }
    }

    private List<JsonNode> leerListaUnaVez(BrowserContext contexto, String titulo, List<String> columnasDeseadas) {
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

    /** FormDigest para escribir; se reutiliza mientras SharePoint lo da por válido (con margen). */
    public String digest(BrowserContext contexto) {
        Digest actual = digest;
        if (actual != null && relojMs.getAsLong() < actual.caduca()) {
            return actual.valor();
        }
        return digestNuevo(contexto);
    }

    private String digestNuevo(BrowserContext contexto) {
        APIResponse r = contexto.request().post(sitio + "/_api/contextinfo",
                RequestOptions.create().setHeader("Accept", JSON_SIN_METADATOS).setTimeout(30000));
        if (!r.ok()) {
            digest = null;
            throw new IllegalStateException("No se pudo obtener FormDigest.");
        }
        JsonNode j = JsonJs.leer(r.text());
        if (j == null) {
            digest = null;
            throw new IllegalStateException("No se pudo obtener FormDigest (respuesta no JSON).");
        }
        JsonNode info = j.has("FormDigestValue") ? j : j.path("d").path("GetContextWebInformation");
        String valor = info.path("FormDigestValue").asText("");
        long segundos = info.path("FormDigestTimeoutSeconds").asLong(1800);
        long vigencia = Math.min(VIGENCIA_MAXIMA_DIGEST_MS, Math.max(0, segundos * 1000 - 120_000));
        digest = valor.isEmpty() ? null : new Digest(valor, relojMs.getAsLong() + vigencia);
        return valor;
    }

    /** SharePoint ha rechazado el FormDigest (la petición no se ha aplicado). */
    private static boolean digestRechazado(APIResponse r) {
        if (r.status() != 403) {
            return false;
        }
        String texto = String.valueOf(r.text()).toLowerCase(java.util.Locale.ROOT);
        return texto.contains("security validation") || texto.contains("x-requestdigest") || texto.contains("formdigest");
    }

    /** Envía una escritura; si el FormDigest recordado ya no vale, pide otro y la repite una vez. */
    private APIResponse escribir(BrowserContext contexto, String digestUsado, Function<String, APIResponse> envio) {
        APIResponse r = envio.apply(digestUsado);
        if (digestRechazado(r)) {
            digest = null;
            r = envio.apply(digestNuevo(contexto));
        }
        return r;
    }

    /** Crea un elemento y devuelve su Id (o null). */
    public Integer crear(BrowserContext contexto, String digest, String baseLista, ObjectNode cuerpo) {
        antesDeEscribir("crear elemento en " + baseLista);
        String datos = JsonJs.escribir(cuerpo);
        APIResponse r = escribir(contexto, digest, d -> contexto.request().post(baseLista + "/items", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("Content-Type", JSON_SIN_METADATOS)
                .setHeader("X-RequestDigest", d).setData(datos).setTimeout(60000)));
        if (!(r.status() == 200 || r.status() == 201 || r.status() == 204)) {
            throw new IllegalStateException("crear " + r.status() + ": " + recortar(r.text(), 200));
        }
        JsonNode j = JsonJs.leer(r.text());
        return j != null && j.hasNonNull("Id") ? j.get("Id").asInt() : null;
    }

    /** MERGE de campos; etag = «*» para «el último gana». */
    public Respuesta merge(BrowserContext contexto, String digest, String baseLista, int id, ObjectNode cuerpo, String etag) {
        antesDeEscribir("actualizar elemento " + id + " de " + baseLista);
        String datos = JsonJs.escribir(cuerpo);
        APIResponse r = escribir(contexto, digest, d -> contexto.request().post(baseLista + "/items(" + id + ")", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("Content-Type", JSON_SIN_METADATOS)
                .setHeader("X-RequestDigest", d).setHeader("IF-MATCH", etag == null ? "*" : etag)
                .setHeader("X-HTTP-Method", "MERGE").setData(datos).setTimeout(60000)));
        return new Respuesta(r.status(), r.status() == 200 || r.status() == 204 ? "" : recortar(r.text(), 180));
    }

    /** Envía el elemento a la papelera (recuperable). */
    public boolean reciclar(BrowserContext contexto, String digest, String baseLista, int id) {
        antesDeEscribir("borrar elemento " + id + " de " + baseLista);
        APIResponse r = escribir(contexto, digest, d -> contexto.request().post(baseLista + "/items(" + id + ")/recycle", RequestOptions.create()
                .setHeader("Accept", JSON_SIN_METADATOS).setHeader("X-RequestDigest", d).setTimeout(60000)));
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

    /** Reconciliación preparada: lo que hay ahora en la lista y lo que se quiere guardar. */
    public record PlanReconciliacion(String titulo, Map<String, Integer> existentes, List<ObjectNode> filas) {
    }

    /**
     * Fase 1 de la reconciliación (_reconcile_list): lee la lista y comprueba la
     * protección D11. No escribe nada; si la lectura falla, lanza la excepción.
     */
    public PlanReconciliacion prepararReconciliacion(BrowserContext contexto, String titulo, List<String> columnas,
                                                     List<ObjectNode> filas, boolean permitirVacias) {
        Map<String, Integer> existentes = new java.util.LinkedHashMap<>();
        for (JsonNode it : leerLista(contexto, titulo, columnas)) {
            if (it.hasNonNull("Title")) {
                existentes.put(it.get("Title").asText(), it.path("Id").asInt());
            }
        }
        List<String> entrantes = filas.stream().filter(f -> f.hasNonNull("Title")).map(f -> f.get("Title").asText()).toList();
        ProteccionListas.comprobar(titulo, existentes.keySet(), entrantes, permitirVacias);
        return new PlanReconciliacion(titulo, existentes, filas);
    }

    /**
     * Fase 2: crea o actualiza las filas recibidas y manda a la papelera las
     * que no vienen. Solo se llama cuando TODAS las listas de la operación han
     * pasado la fase 1.
     */
    public ObjectNode aplicarReconciliacion(BrowserContext contexto, String digest, PlanReconciliacion plan) {
        exigirEscritura("reconciliar " + plan.titulo());
        String base = lista(plan.titulo());
        Set<String> recibidas = new HashSet<>();
        int creadas = 0, actualizadas = 0, borradas = 0;
        ObjectNode errores = JsonJs.objetoVacio();
        for (ObjectNode fila : plan.filas()) {
            if (!fila.hasNonNull("Title")) {
                continue;
            }
            String clave = fila.get("Title").asText();
            recibidas.add(clave);
            Integer id = plan.existentes().get(clave);
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
        for (Map.Entry<String, Integer> existente : plan.existentes().entrySet()) {
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
