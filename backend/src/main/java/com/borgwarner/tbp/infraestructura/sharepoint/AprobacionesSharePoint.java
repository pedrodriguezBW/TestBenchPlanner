package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base de aprobaciones de servicios (otro sitio). SOLO LECTURA: este adaptador
 * no tiene ningún método de escritura. Los nombres internos de las columnas se
 * detectan por su título, como en la 4.9.4.
 */
public class AprobacionesSharePoint implements ConsultaAprobaciones {

    private static final List<String> TITULOS_ESTADO = List.of("Global status", "GlobalStatus", "Global Status");
    private static final List<String> TITULOS_SOLICITANTE = List.of("Requester name", "Requester Name", "RequesterName", "Requester");
    private static final List<String> TITULOS_SERVICIO = List.of("Service details", "Service Details", "ServiceDetails", "Service");

    private final ClienteSharePoint cliente;
    private final String sitio;
    private final String lista;
    private volatile String[] campos; // estado, solicitante, servicio

    public AprobacionesSharePoint(ClienteSharePoint cliente, String sitio, String lista) {
        this.cliente = cliente;
        this.sitio = sitio;
        this.lista = lista;
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
        return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
        return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            ObjectNode salida = JsonJs.objetoVacio();
            for (JsonNode id : ids) {
                String clave = id == null || id.isNull() ? "" : id.asText();
                if (!salida.has(clave)) {
                    salida.set(clave, resolver(contexto, clave));
                }
            }
            return salida;
        });
    }

    /** Estado de un Approval ID (resolve_status). Se usa también para las columnas espejo. */
    ObjectNode resolver(BrowserContext contexto, String entrada) {
        ObjectNode r = JsonJs.objetoVacio();
        r.put("input", entrada);
        Matcher m = Pattern.compile("\\d+").matcher(entrada == null ? "" : entrada);
        if (!m.find()) {
            r.putNull("resolvedId");
            r.put("status", "not_created").put("color", "red").putNull("raw");
            return r;
        }
        int id = Integer.parseInt(m.group());
        r.put("resolvedId", id);
        String[] c = campos(contexto);
        try {
            JsonNode it = cliente.get(contexto, ClienteSharePoint.lista(sitio, lista) + "/items(" + id + ")?$select=" + seleccion(c));
            JsonNode bruto = it.get(c[0]);
            String[] ec = estadoYColor(bruto == null || bruto.isNull() ? null : bruto.asText());
            r.put("status", ec[0]).put("color", ec[1]);
            r.set("raw", bruto);
            r.set("requester", c[1] != null ? it.get(c[1]) : null);
            r.set("service", c[2] != null ? it.get(c[2]) : null);
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
