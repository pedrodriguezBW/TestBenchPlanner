package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.Areas;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.comun.FilaKpi;
import com.borgwarner.tbp.infraestructura.comun.LecturaCompartida;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listas auxiliares de SharePoint: catálogos, histórico de KPI, usuarios,
 * recursos, presencia, registro y versión publicada. Cada clase reproduce la
 * función equivalente del backend 4.9.4 (indicada en su comentario).
 *
 * Todas las escrituras pasan por ClienteSharePoint, que las bloquea si la
 * escritura está desactivada.
 *
 * Las lecturas van por los carriles de lectura (no esperan detrás de los
 * guardados) y se comparten unos segundos: al arrancar, la interfaz pedía los
 * catálogos dos veces seguidas y los usuarios otras dos. Lo que escribe este PC
 * invalida su lectura en el acto; los cambios de otras personas se ven, como
 * mucho, al cabo de esa vigencia (siempre menor que los sondeos de la interfaz).
 */
public final class ListasAuxiliaresSharePoint {

    public static final String BANCOS = "TestBenchBenches";
    public static final String PROYECTOS = "TestBenchProjects";
    /** Columnas de facturación (texto de una línea) que crea a mano un Admin del sitio. */
    public static final String COLUMNA_ORDEN = "BillingOrder";
    public static final String COLUMNA_TIPO_EQUIPO = "EquipmentType";
    /** Área de la categoría del banco (vacío = Bancos Térmicos). */
    public static final String COLUMNA_AREA = "Area";
    /** Prefijo del Title de las filas de TestBenchAppConfig con el nombre de un área. */
    public static final String PREFIJO_NOMBRE_AREA = "AreaName:";
    public static final String RESPONSABLES = "TestBenchResponsibles";
    public static final String KPI = "TestBenchKPIHistory";
    public static final String USUARIOS = "TestBenchUsers";
    public static final String CONFIG_APP = "TestBenchAppConfig";
    public static final String PRESENCIA = "TestBenchPresence";
    public static final String ACTIVIDAD = "TestBenchActivityLog";
    public static final String RECURSOS = "TestBenchResources";
    public static final String BLOQUEOS = "TestBenchResourceBlocks";

    /** Vigencia de las lecturas compartidas. */
    static final long VIGENCIA_CATALOGOS_MS = 15_000;
    static final long VIGENCIA_USUARIOS_MS = 15_000;
    static final long VIGENCIA_IDENTIDAD_MS = 10 * 60_000;
    static final long VIGENCIA_RECURSOS_MS = 10_000;
    static final long VIGENCIA_KPI_MS = 30_000;
    static final long VIGENCIA_VERSION_MS = 5 * 60_000;

    private ListasAuxiliaresSharePoint() {
    }

    private static List<JsonNode> copiaLista(List<JsonNode> filas) {
        return filas.stream().<JsonNode>map(JsonNode::deepCopy).toList();
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
        private final boolean permitirVacias;
        /** Una lectura con alguna lista fallida (*_error) no se reutiliza. */
        private final LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(VIGENCIA_CATALOGOS_MS,
                ObjectNode::deepCopy, c -> !c.has("benches_error") && !c.has("projects_error") && !c.has("responsibles_error"));

        public Catalogos(ClienteSharePoint cliente, boolean permitirVacias) {
            this.cliente = cliente;
            this.permitirVacias = permitirVacias;
        }

        @Override
        public ObjectNode leer() {
            return lectura.leer(this::leerDeSharePoint);
        }

        private ObjectNode leerDeSharePoint() {
            return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                ObjectNode salida = JsonJs.objetoVacio();
                salida.set("responsibles", titulos(contexto, RESPONSABLES, List.of("Org", "Seq"), salida, "responsibles_error"));
                ArrayNode proyectos = JsonJs.mapper().createArrayNode();
                ObjectNode ordenes = JsonJs.objetoVacio();
                try {
                    for (JsonNode it : ordenarPorSeq(cliente.leerLista(contexto, PROYECTOS, List.of("Seq", COLUMNA_ORDEN)))) {
                        if (!it.hasNonNull("Title") || it.get("Title").asText().isEmpty()) {
                            continue;
                        }
                        proyectos.add(it.get("Title").asText());
                        String orden = it.path(COLUMNA_ORDEN).asText("").trim();
                        if (!orden.isEmpty()) {
                            ordenes.put(it.get("Title").asText(), orden);
                        }
                    }
                } catch (RuntimeException e) {
                    salida.put("projects_error", ClienteSharePoint.recortar(e.getMessage(), 120));
                }
                salida.set("projects", proyectos);
                salida.set("projectOrders", ordenes);
                ArrayNode bancos = JsonJs.mapper().createArrayNode();
                try {
                    for (JsonNode it : ordenarPorSeq(cliente.leerLista(contexto, BANCOS,
                            List.of("GroupKey", "GroupName", "Dot", "Org", "Seq", COLUMNA_TIPO_EQUIPO, COLUMNA_AREA)))) {
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
                        b.put("equipmentType", it.path(COLUMNA_TIPO_EQUIPO).asText("").trim());
                        // Vacío se deja vacío: así la fila hereda el área de su categoría (como en local).
                        String area = it.path(COLUMNA_AREA).asText("").trim();
                        b.put("area", area.isEmpty() ? "" : Areas.normalizar(area));
                    }
                } catch (RuntimeException e) {
                    salida.put("benches_error", ClienteSharePoint.recortar(e.getMessage(), 120));
                }
                salida.set("benches", bancos);
                salida.set("areaNames", nombresAreas(contexto));
                return salida;
            });
        }

        /**
         * Nombres de área guardados en TestBenchAppConfig (Title «AreaName:THERMAL»,
         * Value = nombre). Si la lista no se puede leer se usan los de serie: el
         * nombre de un área no es un dato que se pueda perder.
         */
        private ObjectNode nombresAreas(BrowserContext contexto) {
            ObjectNode nombres = JsonJs.objetoVacio();
            try {
                for (JsonNode it : filasNombresAreas(contexto)) {
                    String codigo = it.path("Title").asText("").substring(PREFIJO_NOMBRE_AREA.length());
                    String valor = it.path("Value").asText("").trim();
                    if (Areas.CODIGOS.contains(codigo) && !valor.isEmpty()) {
                        nombres.put(codigo, valor);
                    }
                }
            } catch (RuntimeException e) {
                // sin nombres guardados: los de serie
            }
            return nombres;
        }

        private List<JsonNode> filasNombresAreas(BrowserContext contexto) {
            List<JsonNode> filas = new ArrayList<>();
            cliente.get(contexto, cliente.lista(CONFIG_APP) + "/items?$select=Id,Title,Value&$filter=startswith(Title,'"
                    + PREFIJO_NOMBRE_AREA + "')&$top=50").path("value").forEach(filas::add);
            return filas;
        }

        /** Guarda solo los nombres de área que cambian (una fila por área en TestBenchAppConfig). */
        private ObjectNode guardarNombresAreas(BrowserContext contexto, String digest, JsonNode pedidos) {
            ObjectNode resultado = JsonJs.objetoVacio();
            Map<String, JsonNode> actuales = new LinkedHashMap<>();
            for (JsonNode it : filasNombresAreas(contexto)) {
                actuales.put(it.path("Title").asText(""), it);
            }
            String base = cliente.lista(CONFIG_APP);
            int cambiados = 0;
            for (String codigo : Areas.CODIGOS) {
                if (!pedidos.has(codigo)) {
                    continue;
                }
                String nombre = pedidos.path(codigo).asText("").trim();
                String titulo = PREFIJO_NOMBRE_AREA + codigo;
                JsonNode actual = actuales.get(titulo);
                String guardado = actual == null ? "" : actual.path("Value").asText("").trim();
                String deSerie = Areas.NOMBRES_DE_SERIE.get(codigo);
                if (nombre.equals(guardado) || (actual == null && (nombre.isEmpty() || nombre.equals(deSerie)))) {
                    continue;
                }
                Integer id = actual != null ? Integer.valueOf(actual.path("Id").asInt()) : cliente.crearMinimo(contexto, digest, base, titulo);
                if (id == null || !cliente.merge(contexto, digest, base, id, JsonJs.objetoVacio().put("Value", nombre), "*").ok()) {
                    throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("El resto se ha guardado, pero no se ha podido guardar el nombre del área «"
                            + nombre + "» en " + CONFIG_APP + ".");
                }
                cambiados++;
            }
            return resultado.put("updated", cambiados);
        }

        /** La lista no tiene la columna y no hace falta escribir nada en ella. */
        private static final Map<String, String> SIN_COLUMNA = java.util.Collections.unmodifiableMap(new java.util.HashMap<>());

        private static ObjectNode tiposPedidos(JsonNode catalogos) {
            ObjectNode o = JsonJs.objetoVacio();
            for (JsonNode b : catalogos.path("benches")) {
                if (b.has("equipmentType")) {
                    o.put(b.path("name").asText(""), b.path("equipmentType").asText(""));
                }
            }
            return o;
        }

        /**
         * Valor actual de una columna de facturación por Title. Si la lista no
         * tiene la columna: SIN_COLUMNA cuando no se pide ningún valor y, si se
         * pide alguno, error claro ANTES de escribir nada (la columna la crea un
         * Admin del sitio; la aplicación no cambia el esquema de las listas).
         */
        private Map<String, String> columnaActual(BrowserContext contexto, String lista, String columna, JsonNode pedidos) {
            if (!cliente.columnas(contexto, cliente.sitio(), lista).contains(columna)) {
                boolean alguno = false;
                for (java.util.Iterator<JsonNode> it = pedidos.elements(); it.hasNext(); ) {
                    alguno |= !it.next().asText("").trim().isEmpty();
                }
                if (alguno) {
                    throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("Falta la columna «" + columna + "» en la lista " + lista
                            + " de SharePoint. Créala (una línea de texto, nombre exacto " + columna + ") y vuelve a guardar.");
                }
                return SIN_COLUMNA;
            }
            Map<String, String> actual = new java.util.HashMap<>();
            for (JsonNode it : cliente.leerLista(contexto, lista, List.of(columna))) {
                if (it.hasNonNull("Title")) {
                    actual.put(it.get("Title").asText(), it.path(columna).asText("").trim());
                }
            }
            return actual;
        }

        /** Valor de la columna Area: el código del área, vacío para Bancos Térmicos. */
        private static String valorArea(JsonNode banco) {
            String area = Areas.normalizar(banco.path("area").asText(""));
            return Areas.TERMICOS.equals(area) ? "" : area;
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

        /**
         * do_save_config. Primero se leen y comprueban TODAS las listas
         * afectadas (D11) y solo después se escribe; si una lectura falla o una
         * comprobación no pasa, no se escribe ninguna.
         */
        @Override
        public ObjectNode guardar(JsonNode catalogos) {
            cliente.exigirEscritura("guardar catálogos");
            lectura.invalidar();
            try {
                return guardarEnSharePoint(catalogos);
            } finally {
                lectura.invalidar();
            }
        }

        private ObjectNode guardarEnSharePoint(JsonNode catalogos) {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                ObjectNode resultado = JsonJs.objetoVacio().put("ok", true);
                ClienteSharePoint.PlanReconciliacion planResponsables = null;
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
                    planResponsables = cliente.prepararReconciliacion(contexto, RESPONSABLES, List.of("Org", "Seq"), filas, permitirVacias);
                }
                // Columnas de facturación: solo se tocan si llegan (la interfaz clásica no las conoce)
                // y solo en las filas en las que cambian (menos escrituras).
                JsonNode ordenes = catalogos.get("projectOrders");
                boolean conOrdenes = ordenes != null && ordenes.isObject();
                boolean conTipos = false;
                for (JsonNode b : catalogos.path("benches")) {
                    conTipos |= b.has("equipmentType");
                }
                Map<String, String> ordenesActuales = conOrdenes
                        ? columnaActual(contexto, PROYECTOS, COLUMNA_ORDEN, ordenes) : Map.of();
                Map<String, String> tiposActuales = conTipos
                        ? columnaActual(contexto, BANCOS, COLUMNA_TIPO_EQUIPO, tiposPedidos(catalogos)) : Map.of();
                // Área: solo si llega (la interfaz clásica no la conoce). Bancos Térmicos se guarda vacío,
                // así que mientras no haya bancos HVCH no hace falta la columna.
                boolean conAreas = false;
                boolean areaNoVacia = false;
                for (JsonNode b : catalogos.path("benches")) {
                    if (b.has("area")) {
                        conAreas = true;
                        areaNoVacia |= !valorArea(b).isEmpty();
                    }
                }
                boolean hayColumnaArea = conAreas && cliente.columnas(contexto, cliente.sitio(), BANCOS).contains(COLUMNA_AREA);
                if (conAreas && !hayColumnaArea && areaNoVacia) {
                    throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("Falta la columna «" + COLUMNA_AREA + "» en la lista " + BANCOS
                            + " de SharePoint. Créala (una línea de texto, nombre exacto " + COLUMNA_AREA + ") y vuelve a guardar.");
                }
                List<ObjectNode> proyectos = new ArrayList<>();
                int i = 0;
                for (JsonNode n : catalogos.path("projects")) {
                    i++;
                    if (!n.asText("").isEmpty()) {
                        ObjectNode fila = JsonJs.objetoVacio().put("Title", n.asText()).put("Seq", i);
                        if (conOrdenes) {
                            String orden = ordenes.path(n.asText()).asText("").trim();
                            if (!orden.equals(ordenesActuales.getOrDefault(n.asText(), "")) && ordenesActuales != SIN_COLUMNA) {
                                fila.put(COLUMNA_ORDEN, orden);
                            }
                        }
                        proyectos.add(fila);
                    }
                }
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
                    if (b.has("area") && hayColumnaArea) {
                        fila.put(COLUMNA_AREA, valorArea(b)); // prepararReconciliacion solo envía lo que cambia
                    }
                    if (b.has("equipmentType") && tiposActuales != SIN_COLUMNA) {
                        String categoria = b.path("equipmentType").asText("").trim();
                        if (!categoria.equals(tiposActuales.getOrDefault(b.get("name").asText(), ""))) {
                            fila.put(COLUMNA_TIPO_EQUIPO, categoria);
                        }
                    }
                    bancos.add(fila);
                }
                ClienteSharePoint.PlanReconciliacion planProyectos =
                        cliente.prepararReconciliacion(contexto, PROYECTOS, List.of("Seq"), proyectos, permitirVacias);
                ClienteSharePoint.PlanReconciliacion planBancos = cliente.prepararReconciliacion(contexto, BANCOS,
                        List.of("GroupKey", "GroupName", "Dot", "Org", "Seq"), bancos, permitirVacias);

                // Todas las comprobaciones han pasado: ahora sí se escribe.
                String digest = cliente.digest(contexto);
                if (planResponsables != null) {
                    resultado.set("responsibles", cliente.aplicarReconciliacion(contexto, digest, planResponsables));
                } else {
                    resultado.putObject("responsibles").put("skipped", "frozen (people come from TestBenchUsers)");
                }
                resultado.set("projects", cliente.aplicarReconciliacion(contexto, digest, planProyectos));
                resultado.set("benches", cliente.aplicarReconciliacion(contexto, digest, planBancos));
                if (catalogos.path("areaNames").isObject()) {
                    resultado.set("areaNames", guardarNombresAreas(contexto, digest, catalogos.get("areaNames")));
                }
                // Un fallo al escribir una columna de facturación no puede pasar desapercibido.
                for (String[] c : new String[][]{{"projects", COLUMNA_ORDEN, PROYECTOS}, {"benches", COLUMNA_TIPO_EQUIPO, BANCOS},
                        {"benches", COLUMNA_AREA, BANCOS}}) {
                    JsonNode error = resultado.path(c[0]).path("field_errors").get(c[1]);
                    if (error != null) {
                        throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("El resto se ha guardado, pero SharePoint no ha aceptado la columna «"
                                + c[1] + "» de " + c[2] + ": " + ClienteSharePoint.recortar(error.asText(), 160));
                    }
                }
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
        private final LecturaCompartida<List<JsonNode>> lectura =
                new LecturaCompartida<>(VIGENCIA_KPI_MS, ListasAuxiliaresSharePoint::copiaLista);

        public HistoricoKpi(ClienteSharePoint cliente) {
            this.cliente = cliente;
        }

        @Override
        public List<JsonNode> leer() {
            return lectura.leer(this::leerDeSharePoint);
        }

        private List<JsonNode> leerDeSharePoint() {
            return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
            lectura.invalidar();
            try {
                return guardarFotosEnSharePoint(fotos);
            } finally {
                lectura.invalidar();
            }
        }

        private ObjectNode guardarFotosEnSharePoint(List<ObjectNode> fotos) {
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
        private final boolean permitirVacias;
        private final LecturaCompartida<ArrayNode> lectura = new LecturaCompartida<>(VIGENCIA_USUARIOS_MS, ArrayNode::deepCopy);
        /** La persona que usa este PC no cambia: solo se reutiliza una identidad con email. */
        private final LecturaCompartida<Identidad> lecturaIdentidad = new LecturaCompartida<>(VIGENCIA_IDENTIDAD_MS, i -> i,
                i -> i.email() != null && !i.email().isBlank());

        public Usuarios(ClienteSharePoint cliente, boolean permitirVacias) {
            this.cliente = cliente;
            this.permitirVacias = permitirVacias;
        }

        @Override
        public Identidad identidadActual() {
            return lecturaIdentidad.leer(() -> cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, this::identidad));
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
            return lectura.leer(this::leerDeSharePoint);
        }

        private ArrayNode leerDeSharePoint() {
            return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
            lectura.invalidar();
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                    ClienteSharePoint.PlanReconciliacion plan = cliente.prepararReconciliacion(contexto, USUARIOS,
                            List.of("Email", "Role", "ResponsibleName"), filas, permitirVacias);
                    return JsonJs.objetoVacio().put("ok", true)
                            .set("users", cliente.aplicarReconciliacion(contexto, cliente.digest(contexto), plan));
                });
            } finally {
                lectura.invalidar();
            }
        }
    }

    // ---- recursos (do_load_resources / do_save_resources / bloqueos) ----------------

    public static class Recursos implements AlmacenRecursos {
        private static final List<String> COLUMNAS_RECURSOS = List.of("GroupKey", "GroupName", "Dot", "EquipmentCode", "Org", "Seq");
        private static final List<String> COLUMNAS_BLOQUEOS = List.of("Resource", "StartDate", "EndDate", "Reason", "Note", "Author");
        private final ClienteSharePoint cliente;
        private final Colaboracion colaboracion;
        private final boolean permitirVacias;
        private final LecturaCompartida<ObjectNode> lectura = new LecturaCompartida<>(VIGENCIA_RECURSOS_MS,
                ObjectNode::deepCopy, r -> !r.has("resources_error") && !r.has("blocks_error"));

        public Recursos(ClienteSharePoint cliente, Colaboracion colaboracion, boolean permitirVacias) {
            this.cliente = cliente;
            this.colaboracion = colaboracion;
            this.permitirVacias = permitirVacias;
        }

        /**
         * Antes iba con la prioridad más baja del único hilo de SharePoint y
         * esperaba detrás de todo lo demás; ahora es una consulta normal de la
         * interfaz en un carril de lectura.
         */
        @Override
        public ObjectNode leer() {
            return lectura.leer(this::leerDeSharePoint);
        }

        private ObjectNode leerDeSharePoint() {
            return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
            lectura.invalidar();
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                    ClienteSharePoint.PlanReconciliacion plan =
                            cliente.prepararReconciliacion(contexto, RECURSOS, COLUMNAS_RECURSOS, filas, permitirVacias);
                    return JsonJs.objetoVacio().put("ok", true)
                            .set("resources", cliente.aplicarReconciliacion(contexto, cliente.digest(contexto), plan));
                });
            } finally {
                lectura.invalidar();
            }
        }

        @Override
        public ObjectNode guardarBloqueo(JsonNode p, AlmacenEnsayos.Actor actor) {
            cliente.exigirEscritura("guardar bloqueo de recurso");
            lectura.invalidar();
            ObjectNode resultado;
            try {
                resultado = guardarBloqueoEnSharePoint(p, actor);
            } finally {
                lectura.invalidar();
            }
            colaboracion.anotarActividadSinEsperar(actor.email(), actor.nombre(), "res_block", p.path("resource").asText() + " "
                    + p.path("start").asText() + "->" + p.path("end").asText() + " (" + p.path("reason").asText() + ")");
            return resultado;
        }

        private ObjectNode guardarBloqueoEnSharePoint(JsonNode p, AlmacenEnsayos.Actor actor) {
            return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
        }

        @Override
        public ObjectNode borrarBloqueo(Object id) {
            if (id == null) {
                return JsonJs.objetoVacio().put("ok", false).put("error", "sin id");
            }
            cliente.exigirEscritura("borrar bloqueo de recurso");
            lectura.invalidar();
            try {
                return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> JsonJs.objetoVacio()
                        .put("ok", cliente.reciclar(contexto, cliente.digest(contexto), cliente.lista(BLOQUEOS), Integer.parseInt(id.toString()))));
            } finally {
                lectura.invalidar();
            }
        }
    }

    // ---- presencia, registro y versión ----------------------------------------------

    public static class ColaboracionSharePoint implements Colaboracion {
        private static final int TTL_PRESENCIA_S = 75;
        private final ClienteSharePoint cliente;
        private final boolean publicar;
        /** Registro de actividad en segundo plano, en orden, sin hacer esperar a nadie. */
        private final ExecutorService registro = Executors.newSingleThreadExecutor(r -> {
            Thread hilo = new Thread(r, "registro-actividad");
            hilo.setDaemon(true);
            return hilo;
        });
        private final LecturaCompartida<String> lecturaVersion = new LecturaCompartida<>(VIGENCIA_VERSION_MS, v -> v);

        public ColaboracionSharePoint(ClienteSharePoint cliente, boolean publicar) {
            this.cliente = cliente;
            this.publicar = publicar;
        }

        @Override
        public void anotarActividadSinEsperar(String email, String nombre, String accion, String detalle) {
            if (!cliente.escrituraHabilitada()) {
                return;
            }
            try {
                registro.execute(() -> anotarActividad(email, nombre, accion, detalle));
            } catch (RuntimeException e) {
                // cola cerrada al apagar: el registro nunca impide nada
            }
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
                return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
            } catch (com.borgwarner.tbp.aplicacion.PeticionCancelada e) {
                throw e;
            } catch (RuntimeException e) {
                return JsonJs.objetoVacio().put("error", ClienteSharePoint.recortar(e.getMessage(), 200))
                        .set("rows", JsonJs.mapper().createArrayNode());
            }
        }

        @Override
        public String versionPublicada() {
            try {
                return lecturaVersion.leer(this::versionDeSharePoint);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private String versionDeSharePoint() {
            return cliente.consultar(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                JsonNode valores = cliente.get(contexto, cliente.lista(CONFIG_APP)
                        + "/items?$select=Title,Value&$filter=Title eq 'CurrentVersion'&$top=1").path("value");
                String v = valores.size() > 0 ? valores.get(0).path("Value").asText("").trim() : "";
                return v.isEmpty() ? null : v;
            });
        }

        /** Publica la versión solo si es estrictamente mayor (nunca la rebaja). */
        @Override
        public boolean publicarVersion(String version) {
            if (!publicar || !cliente.escrituraHabilitada()) {
                return false;
            }
            lecturaVersion.invalidar();
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

    // ---- Equipment Types de facturación (lista nueva, sin equivalente en la 4.9.4) --------

    /**
     * Lista TestBenchEquipmentTypes: una fila por Equipment Type y año. La crea a
     * mano un Admin del sitio (la aplicación no cambia el esquema de las listas):
     * Title (Equipment Type), EquipmentGroup (texto), Year (número),
     * WithoutHV (número), WithHV (número), Units (texto: HORAS o PIEZAS) y
     * BillingRule (texto: LINEAR, ASINH_100, ASINH_200, PER_PIECE o NOT_BILLABLE).
     */
    public static class TiposEquipo implements com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo {
        public static final String LISTA = "TestBenchEquipmentTypes";
        public static final List<String> COLUMNAS = List.of("EquipmentGroup", "Year", "WithoutHV", "WithHV", "Units", "BillingRule");
        private final ClienteSharePoint cliente;
        private final LecturaCompartida<List<JsonNode>> lectura =
                new LecturaCompartida<>(VIGENCIA_CATALOGOS_MS, ListasAuxiliaresSharePoint::copiaLista);

        public TiposEquipo(ClienteSharePoint cliente) {
            this.cliente = cliente;
        }

        private static com.borgwarner.tbp.dominio.facturacion.TipoEquipo tipo(JsonNode it) {
            ObjectNode n = JsonJs.objetoVacio();
            n.put("titulo", it.path("Title").asText(""));
            n.put("grupo", it.path("EquipmentGroup").asText(""));
            n.set("anio", it.get("Year"));
            n.set("sinHv", it.get("WithoutHV"));
            n.set("conHv", it.get("WithHV"));
            n.put("unidad", it.path("Units").asText(""));
            n.put("regla", it.path("BillingRule").asText(""));
            return com.borgwarner.tbp.dominio.facturacion.TipoEquipo.desdeJson(n);
        }

        private static ObjectNode fila(com.borgwarner.tbp.dominio.facturacion.TipoEquipo t) {
            ObjectNode f = JsonJs.objetoVacio();
            f.put("Title", t.titulo());
            f.put("EquipmentGroup", t.grupo());
            f.put("Year", t.anio());
            if (t.sinHv() == null) {
                f.putNull("WithoutHV");
            } else {
                f.put("WithoutHV", t.sinHv());
            }
            if (t.conHv() == null) {
                f.putNull("WithHV");
            } else {
                f.put("WithHV", t.conHv());
            }
            f.put("Units", t.unidad());
            f.put("BillingRule", t.regla().name());
            return f;
        }

        private List<JsonNode> leerFilas(BrowserContext contexto) {
            Set<String> existentes = cliente.columnas(contexto, cliente.sitio(), LISTA);
            List<String> faltan = COLUMNAS.stream().filter(c -> !existentes.contains(c)).toList();
            if (!faltan.isEmpty()) {
                throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("A la lista " + LISTA + " de SharePoint le faltan las columnas "
                        + String.join(", ", faltan) + ". Créalas con ese nombre exacto y vuelve a intentarlo.");
            }
            return cliente.leerLista(contexto, LISTA, COLUMNAS);
        }

        @Override
        public List<com.borgwarner.tbp.dominio.facturacion.TipoEquipo> leer() {
            List<JsonNode> filas;
            try {
                filas = lectura.leer(() -> cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, this::leerFilas));
            } catch (com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida | com.borgwarner.tbp.aplicacion.PeticionCancelada e) {
                throw e;
            } catch (RuntimeException e) {
                throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("No se ha podido leer la lista " + LISTA
                        + " de SharePoint (¿existe?): " + ClienteSharePoint.recortar(String.valueOf(e.getMessage()), 160));
            }
            List<com.borgwarner.tbp.dominio.facturacion.TipoEquipo> tipos = new ArrayList<>();
            for (JsonNode it : filas) {
                if (!it.path("Title").asText("").isBlank()) {
                    tipos.add(tipo(it));
                }
            }
            return tipos;
        }

        /** Lee la lista y solo escribe lo que cambia: filas nuevas, campos distintos y filas que sobran (a la papelera). */
        @Override
        public void guardar(List<com.borgwarner.tbp.dominio.facturacion.TipoEquipo> tipos) {
            cliente.exigirEscritura("guardar Equipment Types");
            lectura.invalidar();
            try {
                cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
                    Map<String, JsonNode> actuales = new LinkedHashMap<>();
                    List<Integer> duplicados = new ArrayList<>();
                    for (JsonNode it : leerFilas(contexto)) {
                        String clave = tipo(it).clave();
                        if (actuales.containsKey(clave)) {
                            duplicados.add(it.path("Id").asInt());
                        } else {
                            actuales.put(clave, it);
                        }
                    }
                    String base = cliente.lista(LISTA);
                    String digest = cliente.digest(contexto);
                    List<String> errores = new ArrayList<>();
                    for (com.borgwarner.tbp.dominio.facturacion.TipoEquipo t : tipos) {
                        ObjectNode nueva = fila(t);
                        JsonNode actual = actuales.remove(t.clave());
                        if (actual == null) {
                            cliente.crear(contexto, digest, base, nueva);
                            continue;
                        }
                        ObjectNode cambios = ClienteSharePoint.camposCambiados(nueva, actual);
                        if (!t.titulo().equals(actual.path("Title").asText(""))) {
                            cambios.put("Title", t.titulo());
                        }
                        if (!cambios.isEmpty()) {
                            ClienteSharePoint.Respuesta r = cliente.merge(contexto, digest, base, actual.path("Id").asInt(), cambios, "*");
                            if (!r.ok()) {
                                errores.add(t.titulo() + " " + t.anio() + ": " + r.estado() + " " + r.cuerpo());
                            }
                        }
                    }
                    for (JsonNode sobra : actuales.values()) {
                        duplicados.add(sobra.path("Id").asInt());
                    }
                    for (Integer id : duplicados) {
                        if (!cliente.reciclar(contexto, digest, base, id)) {
                            errores.add("borrar la fila " + id);
                        }
                    }
                    if (!errores.isEmpty()) {
                        throw new com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida("SharePoint no ha aceptado parte de los cambios de "
                                + LISTA + ": " + ClienteSharePoint.recortar(String.join("; ", errores), 300));
                    }
                    return null;
                });
            } finally {
                lectura.invalidar();
            }
        }
    }
}
