package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.infraestructura.comun.LecturaCompartida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base de aprobaciones de servicios (otro sitio). SOLO LECTURA: este adaptador
 * no tiene ningún método de escritura. Los nombres internos de las columnas se
 * detectan por su título, como en la 4.9.4.
 *
 * Rendimiento: antes se pedía el estado de cada Approval ID con una petición
 * propia, una detrás de otra, en el único hilo de SharePoint (cientos de
 * peticiones al abrir la aplicación, delante de todo lo demás). Ahora:
 *  - se piden en lotes ($filter=Id eq … or Id eq …) por un carril de lectura,
 *    con prioridad secundaria para no retrasar la planificación;
 *  - cada estado se recuerda 20 s (la interfaz lo vuelve a pedir cada 60 s y
 *    cada vez que cambia la lista de IDs);
 *  - si la pestaña que lo pidió se cierra, no se piden los lotes restantes.
 * El resultado de cada ID es exactamente el mismo que antes.
 */
public class AprobacionesSharePoint implements ConsultaAprobaciones {

    private static final List<String> TITULOS_ESTADO = List.of("Global status", "GlobalStatus", "Global Status");
    private static final List<String> TITULOS_SOLICITANTE = List.of("Requester name", "Requester Name", "RequesterName", "Requester");
    private static final List<String> TITULOS_SERVICIO = List.of("Service details", "Service Details", "ServiceDetails", "Service");

    /** IDs por consulta (la URL del filtro debe seguir siendo corta). */
    static final int IDS_POR_LOTE = 50;
    static final long VIGENCIA_ESTADO_MS = 20_000;
    static final long VIGENCIA_TODAS_MS = 20_000;

    private record EstadoRecordado(ObjectNode estado, long leido) {
    }

    private final ClienteSharePoint cliente;
    private final String sitio;
    private final String lista;
    private final LongSupplier relojMs;
    private volatile String[] campos; // estado, solicitante, servicio
    /** Id numérico -> estado leído (sin «input», que depende de cómo lo escribió el usuario). */
    private final Map<Integer, EstadoRecordado> recordados = new ConcurrentHashMap<>();
    private final LecturaCompartida<ArrayNode> lecturaTodas = new LecturaCompartida<>(VIGENCIA_TODAS_MS, ArrayNode::deepCopy);

    public AprobacionesSharePoint(ClienteSharePoint cliente, String sitio, String lista) {
        this(cliente, sitio, lista, System::currentTimeMillis);
    }

    AprobacionesSharePoint(ClienteSharePoint cliente, String sitio, String lista, LongSupplier relojMs) {
        this.cliente = cliente;
        this.sitio = sitio;
        this.lista = lista;
        this.relojMs = relojMs;
    }

    /** Primer número de un Approval ID («ID 123 / x» -> 123), como resolve_status; null si no hay. */
    static Integer numeroDe(String entrada) {
        Matcher m = Pattern.compile("\\d+").matcher(entrada == null ? "" : entrada);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return null; // número demasiado largo: ningún elemento tiene ese Id
        }
    }

    /** Estado normalizado y color (map_status). */
    static String[] estadoYColor(String bruto) {
        String s = bruto == null ? "" : bruto.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "approved" -> new String[]{"approved", "green"};
            case "pending" -> new String[]{"pending", "amber"};
            case "rejected" -> new String[]{"rejected", "red"};
            case "na", "n/a" -> new String[]{"na", "red"};
            case "" -> new String[]{"not_created", "red"};
            default -> new String[]{"other:" + s, "red"};
        };
    }

    private String[] campos(BrowserContext contexto) {
        String[] actuales = campos;
        if (actuales != null) {
            return actuales;
        }
        String estado = "Status", solicitante = null, servicio = null;
        try {
            JsonNode definiciones = cliente.get(contexto, ClienteSharePoint.lista(sitio, lista) + "/fields?$select=InternalName,Title&$top=500").path("value");
            estado = buscar(definiciones, TITULOS_ESTADO, "Status");
            solicitante = buscar(definiciones, TITULOS_SOLICITANTE, null);
            servicio = buscar(definiciones, TITULOS_SERVICIO, null);
        } catch (RuntimeException e) {
            // se usa el campo de estado legado
        }
        campos = new String[]{estado, solicitante, servicio};
        return campos;
    }

    private static String buscar(JsonNode definiciones, List<String> titulos, String porDefecto) {
        List<String> buscados = titulos.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        for (JsonNode f : definiciones) {
            if (buscados.contains(f.path("Title").asText("").trim().toLowerCase(Locale.ROOT))) {
                return f.path("InternalName").asText();
            }
        }
        for (JsonNode f : definiciones) {
            if (buscados.contains(f.path("InternalName").asText("").trim().toLowerCase(Locale.ROOT))) {
                return f.path("InternalName").asText();
            }
        }
        return porDefecto;
    }

    private String seleccion(String[] c) {
        TreeSet<String> columnas = new TreeSet<>();
        columnas.add("Id");
        for (String campo : c) {
            if (campo != null) {
                columnas.add(campo);
            }
        }
        return String.join(",", columnas);
    }

    @Override
    public ArrayNode todas() {
        return lecturaTodas.leer(this::todasDeSharePoint);
    }

    private ArrayNode todasDeSharePoint() {
        return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            String[] c = campos(contexto);
            String url = ClienteSharePoint.lista(sitio, lista) + "/items?$select=" + seleccion(c) + "&$top=2000&$orderby=Id desc";
            ArrayNode salida = JsonJs.mapper().createArrayNode();
            for (JsonNode it : cliente.leerPaginado(contexto, url, sitio, ClienteSharePoint.JSON_SIN_METADATOS)) {
                if (!it.hasNonNull("Id")) {
                    continue;
                }
                JsonNode bruto = it.get(c[0]);
                String[] ec = estadoYColor(bruto == null || bruto.isNull() ? null : bruto.asText());
                ObjectNode a = salida.addObject();
                a.put("id", it.get("Id").asInt());
                a.put("status", ec[0]);
                a.put("color", ec[1]);
                a.set("raw", bruto);
                a.set("requester", c[1] != null ? it.get(c[1]) : null);
                a.set("service", c[2] != null ? it.get(c[2]) : null);
            }
            return salida;
        });
    }

    @Override
    public ObjectNode estados(List<JsonNode> ids) {
        List<String> claves = new ArrayList<>();
        Set<Integer> pendientes = new LinkedHashSet<>();
        long ahora = relojMs.getAsLong();
        for (JsonNode id : ids) {
            String clave = id == null || id.isNull() ? "" : id.asText();
            if (claves.contains(clave)) {
                continue;
            }
            claves.add(clave);
            Integer numero = numeroDe(clave);
            if (numero != null) {
                EstadoRecordado r = recordados.get(numero);
                if (r == null || ahora - r.leido() >= VIGENCIA_ESTADO_MS) {
                    pendientes.add(numero);
                }
            }
        }
        Map<Integer, ObjectNode> leidos = new HashMap<>();
        List<Integer> porLeer = new ArrayList<>(pendientes);
        for (int i = 0; i < porLeer.size(); i += IDS_POR_LOTE) {
            Cancelacion.comprobar(); // la pestaña ya no existe: no se piden más lotes
            List<Integer> lote = porLeer.subList(i, Math.min(porLeer.size(), i + IDS_POR_LOTE));
            Map<Integer, ObjectNode> estadosLote = cliente.consultar(ConexionEdge.PRIORIDAD_SECUNDARIA, contexto -> leerLote(contexto, lote));
            long leido = relojMs.getAsLong();
            estadosLote.forEach((numero, estado) -> {
                recordados.put(numero, new EstadoRecordado(estado, leido));
                leidos.put(numero, estado);
            });
        }
        ObjectNode salida = JsonJs.objetoVacio();
        for (String clave : claves) {
            Integer numero = numeroDe(clave);
            ObjectNode r = JsonJs.objetoVacio();
            r.put("input", clave);
            if (numero == null) {
                r.putNull("resolvedId");
                r.put("status", "not_created").put("color", "red").putNull("raw");
            } else {
                ObjectNode estado = leidos.get(numero);
                if (estado == null) {
                    EstadoRecordado recordado = recordados.get(numero);
                    estado = recordado == null ? noCreado(numero) : recordado.estado();
                }
                r.setAll(estado.deepCopy());
            }
            salida.set(clave, r);
        }
        return salida;
    }

    /** Estado de un Id que no existe o no se ha podido leer (como resolve_status). */
    private static ObjectNode noCreado(int numero) {
        ObjectNode r = JsonJs.objetoVacio();
        r.put("resolvedId", numero);
        r.put("status", "not_created").put("color", "red").putNull("raw");
        return r;
    }

    /**
     * Estados de un lote de Ids con una sola consulta. Si la consulta del lote
     * falla, se resuelve cada Id por separado, igual que antes.
     */
    Map<Integer, ObjectNode> leerLote(BrowserContext contexto, List<Integer> lote) {
        String[] c = campos(contexto);
        Map<Integer, ObjectNode> salida = new HashMap<>();
        List<JsonNode> filas;
        try {
            String filtro = String.join(" or ", lote.stream().map(n -> "Id eq " + n).toList());
            filas = cliente.leerPaginado(contexto, ClienteSharePoint.lista(sitio, lista) + "/items?$select=" + seleccion(c)
                    + "&$filter=" + filtro + "&$top=" + lote.size(), sitio, ClienteSharePoint.JSON_SIN_METADATOS);
        } catch (com.borgwarner.tbp.aplicacion.PeticionCancelada e) {
            throw e;
        } catch (RuntimeException e) {
            for (Integer numero : lote) {
                ObjectNode uno = resolver(contexto, String.valueOf(numero));
                uno.remove("input");
                salida.put(numero, uno);
            }
            return salida;
        }
        for (JsonNode it : filas) {
            if (!it.hasNonNull("Id")) {
                continue;
            }
            int numero = it.get("Id").asInt();
            ObjectNode r = JsonJs.objetoVacio();
            r.put("resolvedId", numero);
            rellenar(r, it, c);
            salida.put(numero, r);
        }
        for (Integer numero : lote) {
            salida.computeIfAbsent(numero, AprobacionesSharePoint::noCreado); // no existe: como el 404 de antes
        }
        return salida;
    }

    private static void rellenar(ObjectNode r, JsonNode it, String[] c) {
        JsonNode bruto = it.get(c[0]);
        String[] ec = estadoYColor(bruto == null || bruto.isNull() ? null : bruto.asText());
        r.put("status", ec[0]).put("color", ec[1]);
        r.set("raw", bruto);
        r.set("requester", c[1] != null ? it.get(c[1]) : null);
        r.set("service", c[2] != null ? it.get(c[2]) : null);
    }

    /** Estado de un Approval ID (resolve_status). Se usa también para las columnas espejo. */
    ObjectNode resolver(BrowserContext contexto, String entrada) {
        ObjectNode r = JsonJs.objetoVacio();
        r.put("input", entrada);
        Integer id = numeroDe(entrada);
        if (id == null) {
            r.putNull("resolvedId");
            r.put("status", "not_created").put("color", "red").putNull("raw");
            return r;
        }
        r.put("resolvedId", id);
        String[] c = campos(contexto);
        try {
            JsonNode it = cliente.get(contexto, ClienteSharePoint.lista(sitio, lista) + "/items(" + id + ")?$select=" + seleccion(c));
            rellenar(r, it, c);
        } catch (RuntimeException e) {
            r.put("status", "not_created").put("color", "red").putNull("raw");
        }
        return r;
    }

    /** Estado de varios ids con una caché por lote (para el espejo). */
    String estadoTexto(BrowserContext contexto, String entrada, Map<String, String> cache) {
        return cache.computeIfAbsent(entrada, e -> resolver(contexto, e).path("status").asText(null));
    }
}
