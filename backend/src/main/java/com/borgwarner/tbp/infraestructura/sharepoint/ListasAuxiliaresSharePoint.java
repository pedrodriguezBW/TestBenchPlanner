package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.comun.FilaKpi;
import com.borgwarner.tbp.infraestructura.comun.Versionado;
import com.borgwarner.tbp.infraestructura.local.Presencia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.microsoft.playwright.BrowserContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listas auxiliares de SharePoint: catálogos, histórico de KPI, usuarios,
 * recursos, presencia, registro y versión publicada. Cada clase reproduce la
 * función equivalente del backend 4.9.4 (indicada en su comentario).
 *
 * Todas las escrituras pasan por ClienteSharePoint, que las bloquea si la
 * escritura está desactivada.
 */
public final class ListasAuxiliaresSharePoint {

    public static final String BANCOS = "TestBenchBenches";
    public static final String PROYECTOS = "TestBenchProjects";
    public static final String RESPONSABLES = "TestBenchResponsibles";
    public static final String KPI = "TestBenchKPIHistory";
    public static final String USUARIOS = "TestBenchUsers";
    public static final String CONFIG_APP = "TestBenchAppConfig";
    public static final String PRESENCIA = "TestBenchPresence";
    public static final String ACTIVIDAD = "TestBenchActivityLog";
    public static final String RECURSOS = "TestBenchResources";
    public static final String BLOQUEOS = "TestBenchResourceBlocks";

    private ListasAuxiliaresSharePoint() {
    }

    private static Double seq(JsonNode it) {
        JsonNode s = it.get("Seq");
        return s == null || s.isNull() ? null : s.asDouble();
    }

    /** Orden por Seq (sin Seq al final) y después por Id. */
    private static List<JsonNode> ordenarPorSeq(List<JsonNode> filas) {
        List<JsonNode> copia = new ArrayList<>(filas);
        copia.sort(Comparator.comparingDouble((JsonNode it) -> seq(it) == null ? 1e9 : seq(it))
                .thenComparingInt(it -> it.path("Id").asInt()));
        return copia;
    }

    private static JsonNode numeroONulo(JsonNode v) {
        if (v == null || v.isNull() || (v.isTextual() && v.textValue().isEmpty())) {
            return NullNode.getInstance();
        }
        if (v.isTextual()) {
            try {
                return DoubleNode.valueOf(Double.parseDouble(v.textValue()));
            } catch (NumberFormatException e) {
                return NullNode.getInstance();
            }
        }
        return v;
    }

    // ---- catálogos (do_load_config / do_save_config) -------------------------------

    public static class Catalogos implements AlmacenCatalogos {
        private final ClienteSharePoint cliente;

        public Catalogos(ClienteSharePoint cliente) {
            this.cliente = cliente;
        }

        @Override
        public ObjectNode leer() {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                ObjectNode salida = JsonJs.objetoVacio();
                salida.set("responsibles", titulos(contexto, RESPONSABLES, List.of("Org", "Seq"), salida, "responsibles_error"));
                salida.set("projects", titulos(contexto, PROYECTOS, List.of("Seq"), salida, "projects_error"));
                ArrayNode bancos = JsonJs.mapper().createArrayNode();
                try {
                    for (JsonNode it : ordenarPorSeq(cliente.leerLista(contexto, BANCOS, List.of("GroupKey", "GroupName", "Dot", "Org", "Seq")))) {
                        if (!it.hasNonNull("Title")) {
                            continue;
                        }
                        ObjectNode b = bancos.addObject();
                        b.set("name", it.get("Title"));
                        b.set("groupKey", it.get("GroupKey"));
                        b.set("groupName", it.get("GroupName"));
                        b.set("dot", it.get("Dot"));
                        b.set("org", it.get("Org"));
                        b.set("seq", it.get("Seq"));
                    }
                } catch (RuntimeException e) {
                    salida.put("benches_error", ClienteSharePoint.recortar(e.getMessage(), 120));
                }
                salida.set("benches", bancos);
                return salida;
            });
        }

        private ArrayNode titulos(BrowserContext contexto, String lista, List<String> columnas, ObjectNode salida, String claveError) {
            ArrayNode titulos = JsonJs.mapper().createArrayNode();
            try {
                ordenarPorSeq(cliente.leerLista(contexto, lista, columnas)).stream()
                        .filter(it -> it.hasNonNull("Title") && !it.get("Title").asText().isEmpty())
                        .forEach(it -> titulos.add(it.get("Title").asText()));
            } catch (RuntimeException e) {
                salida.put(claveError, ClienteSharePoint.recortar(e.getMessage(), 120));
            }
            return titulos;
        }

        @Override
        public ObjectNode guardar(JsonNode catalogos) {
            cliente.exigirEscritura("guardar catálogos");
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                String digest = cliente.digest(contexto);
                ObjectNode resultado = JsonJs.objetoVacio().put("ok", true);
                JsonNode responsables = catalogos.path("responsibles");
                if (responsables.isArray() && responsables.size() > 0) {
                    List<ObjectNode> filas = new ArrayList<>();
                    int i = 0;
                    for (JsonNode n : responsables) {
                        i++;
                        if (!n.asText("").isEmpty()) {
                            filas.add(JsonJs.objetoVacio().put("Title", n.asText()).put("Seq", i));
                        }
                    }
                    resultado.set("responsibles", cliente.reconciliar(contexto, digest, RESPONSABLES, List.of("Org", "Seq"), filas));
                } else {
                    resultado.putObject("responsibles").put("skipped", "frozen (people come from TestBenchUsers)");
                }
                List<ObjectNode> proyectos = new ArrayList<>();
                int i = 0;
                for (JsonNode n : catalogos.path("projects")) {
                    i++;
                    if (!n.asText("").isEmpty()) {
                        proyectos.add(JsonJs.objetoVacio().put("Title", n.asText()).put("Seq", i));
                    }
                }
                resultado.set("projects", cliente.reconciliar(contexto, digest, PROYECTOS, List.of("Seq"), proyectos));
                List<ObjectNode> bancos = new ArrayList<>();
                for (JsonNode b : catalogos.path("benches")) {
                    if (!b.hasNonNull("name") || b.get("name").asText().isEmpty()) {
                        continue;
                    }
                    ObjectNode fila = JsonJs.objetoVacio();
                    fila.set("Title", b.get("name"));
                    fila.set("GroupKey", b.get("groupKey"));
                    fila.set("GroupName", b.get("groupName"));
                    fila.set("Dot", b.get("dot"));
                    fila.set("Org", b.get("org"));
                    fila.set("Seq", numeroONulo(b.get("seq")));
                    bancos.add(fila);
                }
                resultado.set("benches", cliente.reconciliar(contexto, digest, BANCOS,
                        List.of("GroupKey", "GroupName", "Dot", "Org", "Seq"), bancos));
                return resultado;
            });
        }
    }

    // ---- histórico de KPI (do_load_kpis / do_save_kpis) ---------------------------

    public static class HistoricoKpi implements AlmacenHistoricoKpi {
        private static final List<String> COLUMNAS = List.of("Year", "CW", "SnapDate", "Org", "Benches", "TotalBenches",
                "Launches", "Finishing", "AccruingHours", "NoApprovedID", "Behind", "NotStarted", "RiskBehind",
                "Unplanned", "ReasonResources", "ReasonParts", "ReasonBreakdown", "ReasonTestDrop", "DelayedDeclared",
                "OccHoursBusy", "OccHoursCap", "OccPct", "ReasonPriority", "ReasonOther", "KPIJson");
        private final ClienteSharePoint cliente;

        public HistoricoKpi(ClienteSharePoint cliente) {
            this.cliente = cliente;
        }

        @Override
        public List<JsonNode> leer() {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                Set<String> existentes;
                try {
                    existentes = cliente.columnas(contexto, cliente.sitio(), KPI);
                } catch (RuntimeException e) {
                    existentes = Set.of();
                }
                final Set<String> hay = existentes;
                List<String> seleccion = new ArrayList<>(COLUMNAS.stream().filter(c -> hay.isEmpty() || hay.contains(c)).toList());
                String url = cliente.lista(KPI) + "/items?$select=" + String.join(",", seleccion) + "&$top=2000&$orderby=Year asc,CW asc";
                return cliente.leerPaginado(contexto, url, cliente.sitio(), ClienteSharePoint.JSON_SIN_METADATOS);
            });
        }

        @Override
        public ObjectNode guardarFotos(List<ObjectNode> fotos) {
            cliente.exigirEscritura("guardar la foto semanal de KPI");
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                String digest = cliente.digest(contexto);
                String base = cliente.lista(KPI);
                int creadas = 0, actualizadas = 0;
                ObjectNode errores = JsonJs.objetoVacio();
                for (ObjectNode foto : fotos) {
                    ObjectNode fila = FilaKpi.desdeFoto(foto);
                    String filtro = "Year eq " + foto.path("year").asInt() + " and CW eq " + foto.path("cw").asInt()
                            + " and Org eq '" + foto.path("org").asText().replace("'", "''") + "'";
                    JsonNode valores = cliente.get(contexto, base + "/items?$select=Id&$filter=" + filtro + "&$top=1").path("value");
                    Integer id = valores.size() > 0 ? valores.get(0).path("Id").asInt() : null;
                    if (id == null) {
                        id = cliente.crearMinimo(contexto, digest, base, fila.get("Title").asText());
                        if (id == null) {
                            continue;
                        }
                        creadas++;
                    } else {
                        actualizadas++;
                    }
                    cliente.mergeCampoACampo(contexto, digest, base, id, fila).forEach(errores::put);
                }
                return JsonJs.objetoVacio().put("ok", true).put("created", creadas).put("updated", actualizadas)
                        .set("field_errors", errores);
            });
        }
    }

    // ---- usuarios (do_whoami / do_save_users) ---------------------------------------

    public static class Usuarios implements AlmacenUsuarios {
        private final ClienteSharePoint cliente;

        public Usuarios(ClienteSharePoint cliente) {
            this.cliente = cliente;
        }

        @Override
        public Identidad identidadActual() {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, this::identidad);
        }

        private Identidad identidad(BrowserContext contexto) {
            try {
                JsonNode it = cliente.get(contexto, cliente.sitio() + "/_api/web/currentuser?$select=Title,Email,LoginName,UserPrincipalName");
                return new Identidad(emailDe(it), it.path("Title").asText("").trim());
            } catch (RuntimeException e) {
                return new Identidad("", "");
            }
        }

        /** Email del usuario actual: Email, UserPrincipalName o el final de LoginName. */
        static String emailDe(JsonNode it) {
            for (String campo : List.of("Email", "UserPrincipalName")) {
                String v = it.path(campo).asText("").trim();
                if (!v.isEmpty() && v.contains("@")) {
                    return v.toLowerCase();
                }
            }
            String login = it.path("LoginName").asText("").trim();
            if (login.contains("|")) {
                String cola = login.substring(login.lastIndexOf('|') + 1);
                if (cola.contains("@")) {
                    return cola.toLowerCase();
                }
            }
            return "";
        }

        @Override
        public ArrayNode leer() {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                ArrayNode salida = JsonJs.mapper().createArrayNode();
                try {
                    for (JsonNode it : cliente.leerLista(contexto, USUARIOS, List.of("Email", "Role", "ResponsibleName"))) {
                        ObjectNode u = salida.addObject();
                        u.put("title", it.path("Title").asText(""));
                        u.put("email", it.path("Email").asText("").trim().toLowerCase());
                        u.put("role", Usuario.normalizarRol(it.path("Role").asText("")));
                        u.put("responsibleName", it.path("ResponsibleName").asText(""));
                    }
                } catch (RuntimeException e) {
                    // lista ausente: nadie tiene rol (todos solo lectura)
                }
                return salida;
            });
        }

        @Override
        public ObjectNode guardar(JsonNode usuarios) {
            cliente.exigirEscritura("guardar usuarios");
            List<ObjectNode> filas = new ArrayList<>();
            for (JsonNode u : usuarios.path("users")) {
                String email = u.path("email").asText("").trim().toLowerCase();
                if (email.isEmpty()) {
                    continue;
                }
                String titulo = u.path("title").asText("");
                String rol = Usuario.normalizarRol(u.path("role").asText(""));
                ObjectNode fila = JsonJs.objetoVacio();
                fila.put("Title", titulo.isEmpty() ? email : titulo);
                fila.put("Email", email);
                fila.put("Role", rol.equals("readonly") ? "ReadOnly" : Character.toUpperCase(rol.charAt(0)) + rol.substring(1));
                String responsable = u.path("responsibleName").asText("");
                fila.set("ResponsibleName", responsable.isEmpty() ? NullNode.getInstance() : TextNode.valueOf(responsable));
                filas.add(fila);
            }
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> JsonJs.objetoVacio().put("ok", true)
                    .set("users", cliente.reconciliar(contexto, cliente.digest(contexto), USUARIOS,
                            List.of("Email", "Role", "ResponsibleName"), filas)));
        }
    }

    // ---- recursos (do_load_resources / do_save_resources / bloqueos) ----------------

    public static class Recursos implements AlmacenRecursos {
        private static final List<String> COLUMNAS_RECURSOS = List.of("GroupKey", "GroupName", "Dot", "EquipmentCode", "Org", "Seq");
        private static final List<String> COLUMNAS_BLOQUEOS = List.of("Resource", "StartDate", "EndDate", "Reason", "Note", "Author");
        private final ClienteSharePoint cliente;
        private final Colaboracion colaboracion;

        public Recursos(ClienteSharePoint cliente, Colaboracion colaboracion) {
            this.cliente = cliente;
            this.colaboracion = colaboracion;
        }

        @Override
        public ObjectNode leer() {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                ObjectNode salida = JsonJs.objetoVacio();
                ArrayNode recursos = salida.putArray("resources");
                ArrayNode bloqueos = salida.putArray("blocks");
                try {
                    for (JsonNode it : ordenarPorSeq(cliente.leerLista(contexto, RECURSOS, COLUMNAS_RECURSOS))) {
                        if (!it.hasNonNull("Title")) {
                            continue;
                        }
                        ObjectNode r = recursos.addObject();
                        r.set("name", it.get("Title"));
                        r.set("groupKey", it.get("GroupKey"));
                        r.set("groupName", it.get("GroupName"));
                        r.set("dot", it.get("Dot"));
                        r.set("code", it.get("EquipmentCode"));
                        r.set("org", it.get("Org"));
                        r.set("seq", it.get("Seq"));
                    }
                } catch (RuntimeException e) {
                    salida.put("resources_error", ClienteSharePoint.recortar(e.getMessage(), 120));
                }
                try {
                    for (JsonNode it : cliente.leerLista(contexto, BLOQUEOS, COLUMNAS_BLOQUEOS)) {
                        ObjectNode b = bloqueos.addObject();
                        b.set("id", it.get("Id"));
                        b.set("title", it.get("Title"));
                        b.set("resource", it.get("Resource"));
                        b.put("start", primeros10(it.get("StartDate")));
                        b.put("end", primeros10(it.get("EndDate")));
                        b.set("reason", it.get("Reason"));
                        b.set("note", it.get("Note"));
                        b.set("author", it.get("Author"));
                    }
                } catch (RuntimeException e) {
                    salida.put("blocks_error", ClienteSharePoint.recortar(e.getMessage(), 120));
                }
                return salida;
            });
        }

        private static String primeros10(JsonNode v) {
            String s = v == null || v.isNull() ? "" : v.asText();
            return s.length() > 10 ? s.substring(0, 10) : s;
        }

        private static JsonNode fechaIso(JsonNode v) {
            String s = v == null || v.isNull() ? "" : v.asText();
            if (s.isEmpty()) {
                return NullNode.getInstance();
            }
            return TextNode.valueOf(s.length() == 10 ? s + "T00:00:00Z" : s);
        }

        @Override
        public ObjectNode guardarCatalogo(JsonNode entrada) {
            cliente.exigirEscritura("guardar recursos");
            List<ObjectNode> filas = new ArrayList<>();
            int i = 0;
            for (JsonNode r : entrada.path("resources")) {
                i++;
                if (!r.hasNonNull("name") || r.get("name").asText().isEmpty()) {
                    continue;
                }
                ObjectNode fila = JsonJs.objetoVacio();
                fila.set("Title", r.get("name"));
                fila.set("GroupKey", r.get("groupKey"));
                fila.set("GroupName", r.get("groupName"));
                fila.set("Dot", r.get("dot"));
                fila.set("EquipmentCode", r.get("code"));
                fila.set("Org", r.get("org"));
                fila.set("Seq", r.hasNonNull("seq") ? numeroONulo(r.get("seq")) : com.fasterxml.jackson.databind.node.IntNode.valueOf(i));
                filas.add(fila);
            }
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> JsonJs.objetoVacio().put("ok", true)
                    .set("resources", cliente.reconciliar(contexto, cliente.digest(contexto), RECURSOS, COLUMNAS_RECURSOS, filas)));
        }

        @Override
        public ObjectNode guardarBloqueo(JsonNode p, AlmacenEnsayos.Actor actor) {
            cliente.exigirEscritura("guardar bloqueo de recurso");
            ObjectNode resultado = cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                String digest = cliente.digest(contexto);
                String base = cliente.lista(BLOQUEOS);
                Integer id = p.hasNonNull("id") && p.get("id").asInt() != 0 ? p.get("id").asInt() : null;
                if (id == null) {
                    String titulo = p.path("title").asText("");
                    id = cliente.crearMinimo(contexto, digest, base, titulo.isEmpty() ? "BLK-" + Instant.now().toEpochMilli() : titulo);
                    if (id == null) {
                        return JsonJs.objetoVacio().put("ok", false).put("error", "no se pudo crear el bloqueo");
                    }
                }
                ObjectNode cuerpo = JsonJs.objetoVacio();
                cuerpo.put("Resource", p.path("resource").asText(""));
                cuerpo.set("StartDate", fechaIso(p.get("start")));
                cuerpo.set("EndDate", fechaIso(p.get("end")));
                cuerpo.put("Reason", p.path("reason").asText(""));
                cuerpo.put("Note", p.path("note").asText(""));
                cuerpo.put("Author", actor.nombre() != null && !actor.nombre().isEmpty() ? actor.nombre() : actor.email());
                ClienteSharePoint.Respuesta r = cliente.merge(contexto, digest, base, id, cuerpo, "*");
                return JsonJs.objetoVacio().put("ok", r.ok()).put("id", id).put("status", r.estado());
            });
            colaboracion.anotarActividad(actor.email(), actor.nombre(), "res_block", p.path("resource").asText() + " "
                    + p.path("start").asText() + "->" + p.path("end").asText() + " (" + p.path("reason").asText() + ")");
            return resultado;
        }

        @Override
        public ObjectNode borrarBloqueo(Object id) {
            if (id == null) {
                return JsonJs.objetoVacio().put("ok", false).put("error", "sin id");
            }
            cliente.exigirEscritura("borrar bloqueo de recurso");
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> JsonJs.objetoVacio()
                    .put("ok", cliente.reciclar(contexto, cliente.digest(contexto), cliente.lista(BLOQUEOS), Integer.parseInt(id.toString()))));
        }
    }

    // ---- presencia, registro y versión ----------------------------------------------

    public static class ColaboracionSharePoint implements Colaboracion {
        private static final int TTL_PRESENCIA_S = 75;
        private final ClienteSharePoint cliente;
        private final boolean publicar;

        public ColaboracionSharePoint(ClienteSharePoint cliente, boolean publicar) {
            this.cliente = cliente;
            this.publicar = publicar;
        }

        /**
         * do_presence. Igual que la 4.9.4 (discrepancia D9, se imita por ahora):
         * LastSeen se interpreta como hora LOCAL y se le resta el desfase de
         * invierno, de modo que en horario de verano queda una hora desplazado.
         * Con la escritura desactivada solo se lee la lista.
         */
        @Override
        public ObjectNode latidoPresencia(AlmacenUsuarios.Identidad identidad) {
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                    String base = cliente.lista(PRESENCIA);
                    Instant ahora = Instant.now();
                    String email = identidad.email() == null ? "" : identidad.email();
                    if (!email.isEmpty() && cliente.escrituraHabilitada()) {
                        String digest = cliente.digest(contexto);
                        JsonNode valores = cliente.get(contexto, base + "/items?$select=Id&$filter=Title eq '"
                                + email.replace("'", "''") + "'&$top=1").path("value");
                        Integer id;
                        if (valores.size() > 0) {
                            id = valores.get(0).path("Id").asInt();
                        } else {
                            id = cliente.crearMinimo(contexto, digest, base, email);
                        }
                        if (id != null) {
                            ObjectNode cuerpo = JsonJs.objetoVacio();
                            String nombre = identidad.nombre() == null || identidad.nombre().isEmpty() ? email : identidad.nombre();
                            cuerpo.put("Email", email).put("DisplayName", nombre)
                                    .put("Initials", Presencia.iniciales(identidad.nombre(), email))
                                    .put("Color", Presencia.color(email))
                                    .put("LastSeen", Versionado.instanteSinMilisegundos(ahora));
                            cliente.merge(contexto, digest, base, id, cuerpo, "*");
                        }
                    }
                    ObjectNode salida = JsonJs.objetoVacio().put("me", email);
                    ArrayNode conectados = salida.putArray("online");
                    List<ObjectNode> lista = new ArrayList<>();
                    for (JsonNode it : cliente.get(contexto, base + "/items?$select=Title,Email,DisplayName,Initials,Color,LastSeen&$top=200").path("value")) {
                        String visto = it.path("LastSeen").asText("");
                        if (visto.length() < 19) {
                            continue;
                        }
                        long instante = LocalDateTime.parse(visto.substring(0, 19)).toEpochSecond(ZoneOffset.UTC)
                                + desfaseInviernoSegundos(); // mktime(local) - time.timezone de la 4.9.4
                        if (ahora.getEpochSecond() - instante > TTL_PRESENCIA_S) {
                            continue;
                        }
                        ObjectNode u = JsonJs.objetoVacio();
                        String correo = it.path("Email").asText(it.path("Title").asText(""));
                        u.put("email", correo);
                        u.put("name", it.path("DisplayName").asText(it.path("Title").asText("")));
                        u.put("initials", it.hasNonNull("Initials") ? it.get("Initials").asText() : Presencia.iniciales(it.path("DisplayName").asText(""), correo));
                        u.put("color", it.hasNonNull("Color") ? it.get("Color").asText() : Presencia.color(correo));
                        u.put("me", correo.equalsIgnoreCase(email));
                        lista.add(u);
                    }
                    lista.sort(Comparator.comparing((ObjectNode u) -> !u.path("me").asBoolean())
                            .thenComparing(u -> u.path("name").asText("").toLowerCase()));
                    lista.forEach(conectados::add);
                    return salida;
                });
            } catch (RuntimeException e) {
                return JsonJs.objetoVacio().put("me", "").put("error", ClienteSharePoint.recortar(e.getMessage(), 120))
                        .set("online", JsonJs.mapper().createArrayNode());
            }
        }

        /**
         * Reproduce time.mktime(hora local) - time.timezone: el resultado es el
         * instante UTC si en ese momento rige el horario de invierno, y una hora
         * antes si rige el de verano (D9).
         */
        private static long desfaseInviernoSegundos() {
            int desfaseActual = com.borgwarner.tbp.dominio.calendario.Fechas.ZONA.getRules().getOffset(Instant.now()).getTotalSeconds();
            int desfaseInvierno = com.borgwarner.tbp.dominio.calendario.Fechas.ZONA.getRules().getStandardOffset(Instant.now()).getTotalSeconds();
            return -(desfaseActual - desfaseInvierno);
        }

        @Override
        public boolean anotarActividad(String email, String nombre, String accion, String detalle) {
            if (!cliente.escrituraHabilitada()) {
                return false;
            }
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                    String digest = cliente.digest(contexto);
                    String base = cliente.lista(ACTIVIDAD);
                    String cuando = Versionado.instanteSinMilisegundos(Instant.now());
                    Integer id = cliente.crearMinimo(contexto, digest, base, cuando + " | " + (email == null || email.isEmpty() ? "?" : email));
                    if (id == null) {
                        return false;
                    }
                    ObjectNode cuerpo = JsonJs.objetoVacio();
                    cuerpo.put("Email", email == null ? "" : email);
                    cuerpo.put("DisplayName", nombre != null && !nombre.isEmpty() ? nombre : (email == null ? "" : email));
                    cuerpo.put("Action", accion == null ? "" : accion);
                    cuerpo.put("Detail", detalle == null ? "" : detalle);
                    cuerpo.put("WhenTs", cuando);
                    cliente.merge(contexto, digest, base, id, cuerpo, "*");
                    return true;
                });
            } catch (RuntimeException e) {
                return false; // el registro nunca debe impedir un guardado
            }
        }

        @Override
        public ObjectNode leerActividad(int maximo) {
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                    Set<String> columnas;
                    try {
                        columnas = cliente.columnas(contexto, cliente.sitio(), ACTIVIDAD);
                    } catch (RuntimeException e) {
                        columnas = Set.of();
                    }
                    final Set<String> hay = columnas;
                    List<String> seleccion = new ArrayList<>(List.of("Id", "Title"));
                    List.of("Email", "DisplayName", "Action", "Detail", "WhenTs").stream().filter(hay::contains).forEach(seleccion::add);
                    ObjectNode salida = JsonJs.objetoVacio();
                    ArrayNode filas = salida.putArray("rows");
                    for (JsonNode it : cliente.get(contexto, cliente.lista(ACTIVIDAD) + "/items?$select=" + String.join(",", seleccion)
                            + "&$top=" + maximo + "&$orderby=Id desc").path("value")) {
                        String titulo = it.path("Title").asText("");
                        String cuando = it.path("WhenTs").asText("");
                        String email = it.path("Email").asText("");
                        if ((cuando.isEmpty() || email.isEmpty()) && titulo.contains(" | ")) {
                            String[] partes = titulo.split(" \\| ", 2);
                            cuando = cuando.isEmpty() ? partes[0] : cuando;
                            email = email.isEmpty() ? partes[1] : email;
                        }
                        ObjectNode f = filas.addObject();
                        f.put("email", email);
                        f.put("name", it.path("DisplayName").asText("").isEmpty() ? email : it.path("DisplayName").asText());
                        f.set("action", it.get("Action"));
                        f.set("detail", it.get("Detail"));
                        f.put("when", cuando);
                    }
                    ArrayNode lista = salida.putArray("columns");
                    hay.stream().sorted().forEach(lista::add);
                    return salida;
                });
            } catch (RuntimeException e) {
                return JsonJs.objetoVacio().put("error", ClienteSharePoint.recortar(e.getMessage(), 200))
                        .set("rows", JsonJs.mapper().createArrayNode());
            }
        }

        @Override
        public String versionPublicada() {
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                    JsonNode valores = cliente.get(contexto, cliente.lista(CONFIG_APP)
                            + "/items?$select=Title,Value&$filter=Title eq 'CurrentVersion'&$top=1").path("value");
                    String v = valores.size() > 0 ? valores.get(0).path("Value").asText("").trim() : "";
                    return v.isEmpty() ? null : v;
                });
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Publica la versión solo si es estrictamente mayor (nunca la rebaja). */
        @Override
        public boolean publicarVersion(String version) {
            if (!publicar || !cliente.escrituraHabilitada()) {
                return false;
            }
            return cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                String digest = cliente.digest(contexto);
                String base = cliente.lista(CONFIG_APP);
                JsonNode valores = cliente.get(contexto, base + "/items?$select=Id,Title,Value&$filter=Title eq 'CurrentVersion'&$top=1").path("value");
                Integer id;
                if (valores.size() == 0) {
                    id = cliente.crearMinimo(contexto, digest, base, "CurrentVersion");
                    if (id == null) {
                        return false;
                    }
                } else {
                    id = valores.get(0).path("Id").asInt();
                    String actual = valores.get(0).path("Value").asText("").trim();
                    if (!actual.isEmpty() && Versionado.comparar(version, actual) <= 0) {
                        return true;
                    }
                }
                return cliente.merge(contexto, digest, base, id, JsonJs.objetoVacio().put("Value", version), "*").ok();
            });
        }

        /** Diagnóstico mínimo de las listas (para /api/diag). */
        public Map<String, Object> diagnostico() {
            return Map.of("sitio", cliente.sitio(), "escrituraHabilitada", cliente.escrituraHabilitada());
        }
    }
}
