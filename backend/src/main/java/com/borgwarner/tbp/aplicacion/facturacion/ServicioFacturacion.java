package com.borgwarner.tbp.aplicacion.facturacion;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenFacturacion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenFacturacion.ConflictoFacturacion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.facturacion.EnsayosFacturables;
import com.borgwarner.tbp.dominio.facturacion.FichaFacturacion;
import com.borgwarner.tbp.dominio.facturacion.FlujoFacturacion;
import com.borgwarner.tbp.dominio.facturacion.FlujoFacturacion.Firmante;
import com.borgwarner.tbp.dominio.facturacion.Maestros;
import com.borgwarner.tbp.dominio.facturacion.MotorFacturacion;
import com.borgwarner.tbp.dominio.facturacion.Nodos;
import com.borgwarner.tbp.dominio.facturacion.Periodos;
import com.borgwarner.tbp.dominio.facturacion.ResumenFacturacion;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.facturacion.ExportacionesFacturacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Casos de uso de la facturación. Es un módulo separado de la planificación:
 *   - lee los ensayos de la planificación (solo lectura) y los convierte en
 *     ensayos facturables;
 *   - guarda sus propios documentos (maestros, periodos, auditoría) en su
 *     almacén;
 *   - solo escribe en el ensayo el objeto DataJson.billing de la ficha
 *     («Billing Data»), con la concurrencia de siempre (versión de fila / 409).
 *
 * El cálculo y las reglas están en dominio/facturacion; aquí se comprueban los
 * permisos, la versión de los documentos y se registra la auditoría.
 */
@Service
public class ServicioFacturacion {

    private static final Logger LOG = LoggerFactory.getLogger(ServicioFacturacion.class);
    static final String MAESTROS = "maestros";
    private static final String PREFIJO_PERIODO = "periodo-";
    private static final DateTimeFormatter AHORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final long VIGENCIA_REQUESTERS_MS = 5 * 60_000L;

    private final ServicioPlanificacion planificacion;
    private final ServicioSesion sesion;
    private final Reloj reloj;
    private final AlmacenFacturacion almacen;
    private final ConsultaAprobaciones aprobaciones;
    private final EstadoConexion conexion;
    private final Object operaciones = new Object();
    private volatile Map<String, String> requesters = Map.of();
    private volatile long requestersLeidos;

    public ServicioFacturacion(ServicioPlanificacion planificacion, ServicioSesion sesion, Reloj reloj, AlmacenFacturacion almacen,
                               ConsultaAprobaciones aprobaciones, EstadoConexion conexion) {
        this.planificacion = planificacion;
        this.sesion = sesion;
        this.reloj = reloj;
        this.almacen = almacen;
        this.aprobaciones = aprobaciones;
        this.conexion = conexion;
    }

    // ---- utilidades -----------------------------------------------------------------------

    static Firmante firmante(Usuario u) {
        return new Firmante(ServicioPlanificacion.actor(u).nombre(), u.rol());
    }

    MotorFacturacion.Contexto contexto() {
        return new MotorFacturacion.Contexto(LocalDateTime.ofInstant(reloj.ahora(), Fechas.ZONA).format(AHORA));
    }

    /** Quien prepara la facturación (Usuario/Editor o Admin) y no está en modo sin conexión. */
    private Usuario preparador() {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        if (conexion.sinConexion()) {
            throw new SinPermiso("Modo sin conexión: la facturación solo se puede consultar.");
        }
        return u;
    }

    private Usuario administrador() {
        Usuario u = preparador();
        sesion.exigirAdmin(u);
        return u;
    }

    private static String clavePeriodo(String id) {
        Periodos.deId(id);
        return PREFIJO_PERIODO + id;
    }

    // ---- maestros -------------------------------------------------------------------------

    static ObjectNode maestrosIniciales() {
        try (InputStream in = ServicioFacturacion.class.getResourceAsStream("/facturacion/maestros-iniciales.json")) {
            if (in == null) {
                throw new IllegalStateException("Faltan los maestros iniciales de facturación");
            }
            return (ObjectNode) JsonJs.mapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    AlmacenFacturacion.Documento documentoMaestros() {
        AlmacenFacturacion.Documento d = almacen.leer(MAESTROS);
        return d != null ? d : new AlmacenFacturacion.Documento(maestrosIniciales(), null);
    }

    public ObjectNode maestros() {
        return documentoMaestros().contenido();
    }

    /** Maestros más lo que necesita la pantalla de configuración (bancos, tipos de ensayo, reglas). */
    public ObjectNode vistaMaestros() {
        AlmacenFacturacion.Documento d = documentoMaestros();
        ObjectNode salida = Nodos.objeto();
        salida.set("maestros", d.contenido());
        salida.put("version", d.version() == null ? "" : d.version());
        salida.put("guardados", d.version() != null);
        salida.set("reglas", Maestros.reglasJson());
        ArrayNode motivos = salida.putArray("motivosParada");
        Maestros.MOTIVOS_PARADA.forEach(motivos::add);
        Vista v = planificacion.vista();
        ArrayNode bancos = salida.putArray("bancos");
        Set<String> tipos = new LinkedHashSet<>();
        for (String b : v.planificacion().bancos()) {
            bancos.addObject().put("nombre", b).put("organizacion", v.planificacion().configuracion().organizacion(b));
            for (Ensayo e : v.planificacion().ensayos(b)) {
                if (!e.tipo().isEmpty()) {
                    tipos.add(e.tipo());
                }
            }
        }
        List<String> ordenados = new ArrayList<>(tipos);
        ordenados.sort(String.CASE_INSENSITIVE_ORDER);
        ArrayNode t = salida.putArray("tiposEnsayo");
        ordenados.forEach(t::add);
        return salida;
    }

    public ObjectNode guardarMaestros(ObjectNode nuevos, String version) {
        Usuario u = administrador();
        synchronized (operaciones) {
            AlmacenFacturacion.Documento actual = documentoMaestros();
            String esperada = actual.version();
            if (version != null && !version.isEmpty() && !version.equals(esperada == null ? "" : esperada)) {
                throw new ConflictoFacturacion("Otra persona ha cambiado los maestros de facturación. Vuelve a cargarlos.");
            }
            ValidacionMaestros.validar(nuevos);
            nuevos.put("revision", Nodos.entero(actual.contenido(), "revision") + 1);
            almacen.guardar(MAESTROS, nuevos, esperada);
            auditar(u, "MAESTROS_MODIFICADOS", "", "", ValidacionMaestros.resumenCambios(actual.contenido(), nuevos), "", null, null);
        }
        return vistaMaestros();
    }

    // ---- ensayos ------------------------------------------------------------------------

    /** Approval ID → requester de la base de aprobaciones (si no responde, sin requester). */
    Map<String, String> requesters() {
        long ahora = System.currentTimeMillis();
        if (ahora - requestersLeidos < VIGENCIA_REQUESTERS_MS) {
            return requesters;
        }
        try {
            Map<String, String> mapa = new HashMap<>();
            for (JsonNode a : aprobaciones.todas()) {
                String req = a.path("requester").asText("");
                if (!req.isEmpty()) {
                    mapa.put(a.path("id").asText(""), req);
                }
            }
            requesters = mapa;
            requestersLeidos = ahora;
        } catch (RuntimeException e) {
            LOG.warn("No se pudieron leer los requesters de las aprobaciones: {}", e.getMessage());
            requestersLeidos = ahora - VIGENCIA_REQUESTERS_MS + 30_000L;
        }
        return requesters;
    }

    List<ObjectNode> ensayosFacturables(Vista v, boolean conRequesters) {
        Map<String, String> req = conRequesters ? requesters() : Map.of();
        List<ObjectNode> salida = new ArrayList<>();
        for (String banco : v.planificacion().bancos()) {
            String org = v.planificacion().configuracion().organizacion(banco);
            for (Ensayo e : v.planificacion().ensayos(banco)) {
                salida.add(EnsayosFacturables.desdeEnsayo(banco, org, e, v.plan().de(e), v.contexto().hoy(), req));
            }
        }
        return salida;
    }

    private Map<String, ObjectNode> otrosPeriodos(String id) {
        Map<String, ObjectNode> otros = new LinkedHashMap<>();
        for (String clave : almacen.claves(PREFIJO_PERIODO)) {
            String otro = clave.substring(PREFIJO_PERIODO.length());
            if (!otro.equals(id)) {
                AlmacenFacturacion.Documento d = almacen.leer(clave);
                if (d != null) {
                    otros.put(otro, d.contenido());
                }
            }
        }
        return otros;
    }

    // ---- periodos -----------------------------------------------------------------------

    public ArrayNode periodos() {
        ArrayNode salida = Nodos.lista();
        List<String> claves = new ArrayList<>(almacen.claves(PREFIJO_PERIODO));
        claves.sort((a, b) -> b.compareTo(a));
        for (String clave : claves) {
            AlmacenFacturacion.Documento d = almacen.leer(clave);
            if (d == null) {
                continue;
            }
            JsonNode doc = d.contenido();
            ObjectNode p = salida.addObject();
            for (String campo : List.of("id", "nombre", "inicio", "fin", "estado", "creadoPor", "creadoEn", "ultimoCalculo")) {
                p.put(campo, Nodos.texto(doc, campo));
            }
            p.put("versionAprobada", Nodos.entero(doc, "versionAprobada"));
            p.set("resumenRapido", doc.get("resumenRapido") == null ? Nodos.objeto() : doc.get("resumenRapido").deepCopy());
            p.put("exportaciones", Nodos.arr(doc, "exportaciones").size());
        }
        return salida;
    }

    private AlmacenFacturacion.Documento leerPeriodo(String id, String version) {
        AlmacenFacturacion.Documento d = almacen.leer(clavePeriodo(id));
        if (d == null) {
            throw new ReglaNoCumplida("El periodo " + id + " no existe.");
        }
        if (version != null && !version.isEmpty() && !version.equals(d.version())) {
            throw new ConflictoFacturacion("Otra persona ha cambiado el periodo. Vuelve a cargarlo.");
        }
        return d;
    }

    public ObjectNode crearPeriodo(int anio, int mes) {
        Usuario u = preparador();
        if (mes < 1 || mes > 12 || anio < 2000 || anio > 2100) {
            throw new ReglaNoCumplida("Mes no válido.");
        }
        Periodos.Periodo per = Periodos.periodo(anio, mes);
        synchronized (operaciones) {
            if (almacen.leer(clavePeriodo(per.id())) != null) {
                throw new ReglaNoCumplida("El periodo " + per.nombre() + " ya existe.");
            }
            MotorFacturacion.Contexto ctx = contexto();
            Firmante f = firmante(u);
            ObjectNode doc = FlujoFacturacion.nuevoPeriodo(anio, mes, f, ctx);
            Vista v = planificacion.vista();
            FlujoFacturacion.recalcular(doc, ensayosFacturables(v, true), maestros(), otrosPeriodos(per.id()), f, ctx);
            almacen.guardar(clavePeriodo(per.id()), doc, null);
            auditar(u, "PERIODO_CREADO", per.id(), "", per.nombre() + " (" + per.inicio() + " – " + per.fin() + ")", "", null, null);
        }
        return periodo(per.id());
    }

    /** Vista completa de un periodo: documento, resúmenes, comprobaciones y alertas. */
    public ObjectNode periodo(String id) {
        AlmacenFacturacion.Documento d = leerPeriodo(id, null);
        ObjectNode doc = d.contenido();
        boolean aprobado = MotorFacturacion.fotoVigente(doc) != null;
        ObjectNode maestros = maestros();
        JsonNode efectivos = aprobado ? FlujoFacturacion.maestrosDeFoto(doc, maestros) : maestros;
        ObjectNode salida = Nodos.objeto();
        ObjectNode ligero = doc.deepCopy();
        ArrayNode fotos = ligero.putArray("fotos");
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            ObjectNode m = fotos.addObject();
            m.put("version", Nodos.entero(f, "version")).put("aprobadoPor", Nodos.texto(f, "aprobadoPor")).put("aprobadoEn", Nodos.texto(f, "aprobadoEn"));
            m.set("indicadores", f.get("indicadores"));
        }
        salida.set("periodo", ligero);
        salida.put("version", d.version());
        salida.set("resumen", ResumenFacturacion.resumen(doc, efectivos));
        ArrayNode envio = salida.putArray("comprobacionesEnvio");
        FlujoFacturacion.comprobacionesEnvio(doc).forEach(envio::add);
        ArrayNode aprobacion = salida.putArray("comprobacionesAprobacion");
        FlujoFacturacion.comprobacionesAprobacion(doc).forEach(aprobacion::add);
        if (aprobado) {
            salida.set("alertas", FlujoFacturacion.alertasTrasAprobar(doc, ensayosFacturables(planificacion.vista(), true), maestros, contexto()));
        } else {
            salida.putArray("alertas");
        }
        salida.put("anterior", Periodos.anterior(id));
        salida.put("siguiente", Periodos.siguiente(id));
        return salida;
    }

    private interface Cambio {
        void aplicar(ObjectNode doc, Firmante f, MotorFacturacion.Contexto ctx, ObjectNode maestros, List<ObjectNode> auditoria);
    }

    /** Lee el periodo, comprueba la versión, aplica el cambio, guarda y audita. */
    private ObjectNode cambiar(Usuario u, String id, String version, Cambio cambio) {
        synchronized (operaciones) {
            AlmacenFacturacion.Documento d = leerPeriodo(id, version);
            ObjectNode doc = d.contenido().deepCopy();
            MotorFacturacion.Contexto ctx = contexto();
            Firmante f = firmante(u);
            List<ObjectNode> auditoria = new ArrayList<>();
            ObjectNode maestros = maestros();
            cambio.aplicar(doc, f, ctx, maestros, auditoria);
            almacen.guardar(clavePeriodo(id), doc, d.version());
            for (ObjectNode e : auditoria) {
                Double antes = Nodos.numero(e, "costeAntes");
                Double despues = Nodos.numero(e, "costeDespues");
                auditar(u, Nodos.texto(e, "accion"), id, Nodos.texto(e, "linea"), Nodos.texto(e, "detalle"), Nodos.texto(e, "justificacion"),
                        antes, despues);
            }
        }
        return periodo(id);
    }

    public ObjectNode recalcular(String id, String version) {
        Usuario u = preparador();
        return cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            FlujoFacturacion.recalcular(doc, ensayosFacturables(planificacion.vista(), true), maestros, otrosPeriodos(id), f, ctx);
            auditoria.add(evento("PERIODO_RECALCULADO", "", "Importe: " + Nodos.texto(doc.path("resumenRapido").get("importe")), ""));
        });
    }

    public ObjectNode accionLinea(String id, String lineaId, String accion, JsonNode datos, String version) {
        Usuario u = preparador();
        return cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            FlujoFacturacion.ResultadoAccion r = FlujoFacturacion.ejecutarAccionLinea(doc, lineaId, accion, datos, f,
                    ensayosFacturables(planificacion.vista(), true), maestros, otrosPeriodos(id), ctx);
            for (ObjectNode e : r.auditoria()) {
                e.put("linea", lineaId);
                auditoria.add(e);
            }
            if (r.maestrosCambiados()) {
                AlmacenFacturacion.Documento dm = documentoMaestros();
                ObjectNode nuevos = r.maestros();
                nuevos.put("revision", Nodos.entero(dm.contenido(), "revision") + 1);
                almacen.guardar(MAESTROS, nuevos, dm.version());
            }
        });
    }

    public ObjectNode enviarRevision(String id, String version) {
        Usuario u = preparador();
        return cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            FlujoFacturacion.enviarRevision(doc, f, ctx);
            auditoria.add(evento("ENVIADO_A_REVISION", "", "Importe: " + Nodos.texto(doc.path("resumenRapido").get("importe")), ""));
        });
    }

    public ObjectNode aprobar(String id, String version, boolean confirmacion) {
        Usuario u = administrador();
        return cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            int v = FlujoFacturacion.aprobar(doc, f, ctx, confirmacion, maestros);
            auditoria.add(evento("PERIODO_APROBADO", "", "Versión " + v + " · importe "
                    + Nodos.texto(doc.path("resumenRapido").get("importe")) + " €", ""));
        });
    }

    public ObjectNode reabrir(String id, String version, String justificacion) {
        Usuario u = administrador();
        return cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            int v = Nodos.entero(doc, "versionAprobada");
            FlujoFacturacion.reabrir(doc, f, ctx, justificacion);
            auditoria.add(evento("PERIODO_REABIERTO", "", "Versión " + v + " reabierta; exportaciones marcadas como obsoletas",
                    justificacion == null ? "" : justificacion.trim()));
        });
    }

    // ---- exportaciones ----------------------------------------------------------------------

    /** Ficheros generados en una exportación (para descargarlos). */
    public record Exportacion(String id, String tipo, String nombre, byte[] contenido, String estado) {
    }

    private static List<String> tiposPedidos(List<String> tipos) {
        List<String> salida = new ArrayList<>();
        for (String t : tipos) {
            if (t.equals("todos")) {
                return List.of("excel", "controlling", "html");
            }
            if (!List.of("excel", "controlling", "html").contains(t)) {
                throw new ReglaNoCumplida("Tipo de exportación desconocido: " + t);
            }
            if (!salida.contains(t)) {
                salida.add(t);
            }
        }
        if (salida.isEmpty()) {
            throw new ReglaNoCumplida("Elige al menos un tipo de exportación.");
        }
        return salida;
    }

    private static ObjectNode docDeFoto(JsonNode doc, int version) {
        ObjectNode copia = (ObjectNode) doc.deepCopy();
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            if (Nodos.entero(f, "version") == version) {
                copia.set("lineas", f.get("lineas").deepCopy());
                copia.put("versionAprobada", version);
                return copia;
            }
        }
        throw new ReglaNoCumplida("No existe la versión aprobada " + version + ".");
    }

    private static JsonNode foto(JsonNode doc, int version) {
        for (JsonNode f : Nodos.arr(doc, "fotos")) {
            if (Nodos.entero(f, "version") == version) {
                return f;
            }
        }
        return Nodos.objeto();
    }

    private static ExportacionesFacturacion.Archivo generar(String tipo, JsonNode doc, JsonNode maestros, ExportacionesFacturacion.Meta meta) {
        return switch (tipo) {
            case "excel" -> ExportacionesFacturacion.excelCompleto(doc, maestros, meta);
            case "controlling" -> ExportacionesFacturacion.excelControlling(doc, maestros, meta);
            default -> ExportacionesFacturacion.htmlPresentacion(doc, maestros, meta);
        };
    }

    public List<Exportacion> exportar(String id, List<String> tiposPedidos, String version) {
        Usuario u = administrador();
        List<String> tipos = tiposPedidos(tiposPedidos);
        List<Exportacion> salida = new ArrayList<>();
        cambiar(u, id, version, (doc, f, ctx, maestros, auditoria) -> {
            String estado = Nodos.texto(doc, "estado");
            if (!estado.equals("APROBADO") && !estado.equals("EXPORTADO")) {
                throw new ReglaNoCumplida("Solo se exporta un periodo aprobado.");
            }
            int v = Nodos.entero(doc, "versionAprobada");
            JsonNode foto = foto(doc, v);
            ObjectNode base = docDeFoto(doc, v);
            JsonNode efectivos = FlujoFacturacion.maestrosDeFoto(doc, maestros);
            ArrayNode registro = Nodos.arrEn(doc, "exportaciones");
            for (String tipo : tipos) {
                String exportId = "EXP-" + id + "-v" + v + "-" + (registro.size() + 1);
                ExportacionesFacturacion.Meta meta = new ExportacionesFacturacion.Meta(exportId, v, Nodos.texto(foto, "aprobadoPor"),
                        Nodos.texto(foto, "aprobadoEn"), f.nombre(), ctx.ahora());
                ExportacionesFacturacion.Archivo a = generar(tipo, base, efectivos, meta);
                registro.addObject().put("id", exportId).put("version", v).put("tipo", tipo).put("nombre", a.nombre())
                        .put("por", f.nombre()).put("en", ctx.ahora()).put("estado", "VALIDA");
                salida.add(new Exportacion(exportId, tipo, a.nombre(), a.contenido(), "VALIDA"));
                auditoria.add(evento("EXPORTACION", "", exportId + " · " + a.nombre(), ""));
            }
            doc.put("estado", "EXPORTADO");
        });
        for (Exportacion e : salida) {
            try {
                almacen.guardarArchivo(e.nombre(), e.contenido());
            } catch (RuntimeException ex) {
                LOG.warn("No se pudo guardar la copia de {}: {}", e.nombre(), ex.getMessage());
            }
        }
        return salida;
    }

    /** Vuelve a generar una exportación anterior (válida u obsoleta) desde su versión aprobada. */
    public Exportacion descargar(String id, String exportId) {
        Usuario u = sesion.usuarioActual();
        sesion.exigirAdmin(u);
        JsonNode doc = leerPeriodo(id, null).contenido();
        for (JsonNode x : Nodos.arr(doc, "exportaciones")) {
            if (Nodos.texto(x, "id").equals(exportId)) {
                int v = Nodos.entero(x, "version");
                JsonNode foto = foto(doc, v);
                ExportacionesFacturacion.Meta meta = new ExportacionesFacturacion.Meta(exportId, v, Nodos.texto(foto, "aprobadoPor"),
                        Nodos.texto(foto, "aprobadoEn"), Nodos.texto(x, "por"), Nodos.texto(x, "en"));
                ObjectNode maestros = maestros();
                ObjectNode efectivos = maestros.deepCopy();
                if (foto.has("tarifas")) {
                    efectivos.set("tarifas", foto.get("tarifas").deepCopy());
                    efectivos.set("categorias", foto.get("categorias").deepCopy());
                }
                if (foto.has("controlling")) {
                    efectivos.set("controlling", foto.get("controlling").deepCopy());
                }
                ExportacionesFacturacion.Archivo a = generar(Nodos.texto(x, "tipo"), docDeFoto(doc, v), efectivos, meta);
                return new Exportacion(exportId, Nodos.texto(x, "tipo"), a.nombre(), a.contenido(), Nodos.texto(x, "estado"));
            }
        }
        throw new ReglaNoCumplida("La exportación " + exportId + " no existe.");
    }

    public static ObjectNode comoJson(Exportacion e) {
        ObjectNode o = Nodos.objeto();
        o.put("id", e.id()).put("tipo", e.tipo()).put("nombre", e.nombre()).put("estado", e.estado())
                .put("b64", Base64.getEncoder().encodeToString(e.contenido()));
        return o;
    }

    // ---- auditoría --------------------------------------------------------------------------

    private static ObjectNode evento(String accion, String linea, String detalle, String justificacion) {
        ObjectNode e = Nodos.objeto();
        e.put("accion", accion).put("linea", linea).put("detalle", detalle).put("justificacion", justificacion);
        return e;
    }

    void auditar(Usuario u, String accion, String periodo, String linea, String detalle, String justificacion, Double antes, Double despues) {
        MotorFacturacion.Contexto ctx = contexto();
        String clave = "auditoria-" + ctx.ahora().substring(0, 4);
        for (int intento = 0; intento < 3; intento++) {
            try {
                AlmacenFacturacion.Documento d = almacen.leer(clave);
                ObjectNode doc = d == null ? Nodos.objeto() : d.contenido();
                ObjectNode e = Nodos.arrEn(doc, "eventos").addObject();
                e.put("id", UUID.randomUUID().toString()).put("en", ctx.ahora()).put("por", ServicioPlanificacion.actor(u).nombre())
                        .put("email", u.email() == null ? "" : u.email()).put("rol", u.rol()).put("accion", accion).put("periodo", periodo)
                        .put("linea", linea).put("detalle", detalle).put("justificacion", justificacion);
                e.set("costeAntes", Nodos.num(antes));
                e.set("costeDespues", Nodos.num(despues));
                almacen.guardar(clave, doc, d == null ? null : d.version());
                return;
            } catch (ConflictoFacturacion conflicto) {
                // Otra persona acaba de auditar: se vuelve a leer y se añade el evento.
            }
        }
        LOG.warn("No se pudo registrar el evento de auditoría {} {}", accion, detalle);
    }

    public ArrayNode auditoria(String periodo) {
        ArrayNode salida = Nodos.lista();
        List<String> claves = new ArrayList<>(almacen.claves("auditoria-"));
        claves.sort((a, b) -> b.compareTo(a));
        for (String clave : claves) {
            AlmacenFacturacion.Documento d = almacen.leer(clave);
            if (d == null) {
                continue;
            }
            List<JsonNode> eventos = new ArrayList<>();
            Nodos.arr(d.contenido(), "eventos").forEach(eventos::add);
            for (int i = eventos.size() - 1; i >= 0; i--) {
                JsonNode e = eventos.get(i);
                if (periodo == null || periodo.isEmpty() || periodo.equals(Nodos.texto(e, "periodo"))) {
                    salida.add(e);
                }
            }
        }
        return salida;
    }

    // ---- ficha de facturación del ensayo -----------------------------------------------------

    private Ensayo buscar(Vista v, String banco, String id) {
        return v.planificacion().buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
    }

    /** Ficha «Billing Data» de un ensayo. */
    public ObjectNode ficha(String banco, String id, JsonNode propuesta) {
        return ficha(banco, id, propuesta, null, null);
    }

    /**
     * Ficha con unos datos propuestos (vista previa) y, en la ventana de cierre,
     * con el arranque y el fin real que se están escribiendo (null = los guardados).
     */
    public ObjectNode ficha(String banco, String id, JsonNode propuesta, String fechaInicio, String fechaFin) {
        Vista v = planificacion.vista();
        Ensayo e = buscar(v, banco, id);
        ObjectNode maestros = maestros();
        Ensayo copia = e.copia();
        if (propuesta != null && propuesta.isObject()) {
            ObjectNode anterior = Nodos.obj(e.datos(), "billing");
            copia.poner("billing", combinar(anterior, propuesta, null, maestros, e, false));
        }
        ObjectNode ef = EnsayosFacturables.desdeEnsayo(banco, v.planificacion().configuracion().organizacion(banco), copia, v.plan().de(e),
                v.contexto().hoy(), requesters());
        if (fechaInicio != null && !fechaInicio.isEmpty()) {
            ef.put("fechaInicio", fechaInicio);
        }
        if (fechaFin != null && !fechaFin.isEmpty()) {
            ef.put("fechaFin", fechaFin);
        }
        ObjectNode f = FichaFacturacion.ficha(ef, maestros, contexto());
        f.put("version", com.borgwarner.tbp.aplicacion.Versiones.de(v.planificacion().textoLeido(e.id())));
        f.put("puedeEditar", !sesion.usuarioActual().soloLectura());
        f.put("esAdmin", sesion.usuarioActual().esAdmin());
        return f;
    }

    /** Estado de los datos de facturación de todos los ensayos (columna «Billing» de los bancos). */
    public ObjectNode estadosEnsayos() {
        Vista v = planificacion.vista();
        ObjectNode maestros = maestros();
        ObjectNode salida = Nodos.objeto();
        for (ObjectNode ef : ensayosFacturables(v, false)) {
            salida.set(Nodos.texto(ef, "id"), FichaFacturacion.estadoDatos(ef, maestros));
        }
        return salida;
    }

    /** Guarda DataJson.billing del ensayo con la concurrencia de la planificación (409 si otro lo cambió). */
    public ServicioPlanificacion.Resultado<Void> guardarEnsayo(String banco, String id, JsonNode billing, String version, boolean forzar) {
        Usuario u = preparador();
        ObjectNode maestros = maestros();
        Map<String, String> versiones = new HashMap<>();
        if (version != null && !version.isEmpty()) {
            versiones.put(id, version);
        }
        String[] resumen = new String[1];
        ServicioPlanificacion.Resultado<Void> r = planificacion.ejecutar(u, versiones, forzar, (p, plan) -> {
            Ensayo e = p.buscar(banco, id).orElseThrow(() -> new ReglaNoCumplida("El ensayo ya no existe en " + banco + "."));
            ObjectNode anterior = Nodos.obj(e.datos(), "billing");
            ObjectNode nuevo = combinar(anterior, billing, u, maestros, e, true);
            Ensayo copia = e.copia();
            copia.poner("billing", nuevo);
            ObjectNode ef = EnsayosFacturables.desdeEnsayo(banco, p.configuracion().organizacion(banco), copia, plan.de(e),
                    plan.contexto().hoy(), Map.of());
            List<String> errores = EnsayosFacturables.validarDatos(ef);
            if (!errores.isEmpty()) {
                throw new ReglaNoCumplida(String.join(" ", errores));
            }
            resumen[0] = cambios(anterior, nuevo);
            e.poner("billing", nuevo);
            return null;
        });
        if (resumen[0] != null && !resumen[0].isEmpty()) {
            auditar(u, "DATOS_ENSAYO", "", id, banco + " · " + id + ": " + resumen[0], "", null, null);
        }
        return r;
    }

    private static String cambios(ObjectNode anterior, ObjectNode nuevo) {
        List<String> campos = new ArrayList<>();
        Iterator<String> it = nuevo.fieldNames();
        while (it.hasNext()) {
            String c = it.next();
            if (c.equals("updatedBy") || c.equals("updatedAt")) {
                continue;
            }
            JsonNode a = anterior == null ? null : anterior.get(c);
            if (a == null || !a.equals(nuevo.get(c))) {
                campos.add(c);
            }
        }
        return String.join(", ", campos);
    }

    /**
     * Datos de facturación que se guardan: los recibidos, normalizados, con las
     * reglas de permisos (orden excepcional solo Admin, quién marca no facturable).
     * Conserva los campos desconocidos del objeto anterior.
     *
     * @param usuario null en la vista previa (sin comprobar permisos)
     */
    ObjectNode combinar(ObjectNode anterior, JsonNode recibido, Usuario usuario, JsonNode maestros, Ensayo e, boolean comprobar) {
        ObjectNode n = anterior == null ? Nodos.objeto() : anterior.deepCopy();
        String ahora = contexto().ahora();
        String firma = usuario == null ? "" : ServicioPlanificacion.actor(usuario).nombre();
        n.put("startTime", Nodos.texto(recibido, "startTime").trim());
        n.put("endTime", Nodos.texto(recibido, "endTime").trim());
        ArrayNode paradas = n.putArray("stops");
        int i = 0;
        for (JsonNode p : Nodos.arr(recibido, "stops")) {
            i++;
            ObjectNode s = paradas.addObject();
            String pid = Nodos.texto(p, "id");
            s.put("id", pid.isEmpty() ? "p" + System.currentTimeMillis() + "-" + i : pid);
            s.put("from", Nodos.texto(p, "from").trim()).put("to", Nodos.texto(p, "to").trim()).put("reason", Nodos.texto(p, "reason"))
                    .put("billable", Nodos.cierto(p, "billable")).put("note", Nodos.texto(p, "note"));
        }
        n.set("setupHours", numeroOVacio(recibido.get("setupHours")));
        n.put("setupConfirmed", Nodos.cierto(recibido, "setupConfirmed"));
        String hv = Nodos.texto(recibido, "hv");
        n.put("hv", hv.equals("yes") || hv.equals("no") ? hv : "");
        JsonNode fact = recibido.get("billable");
        boolean facturable = !(fact != null && fact.isBoolean() && !fact.booleanValue());
        JsonNode antes = anterior == null ? null : anterior.get("billable");
        boolean eraFacturable = !(antes != null && antes.isBoolean() && !antes.booleanValue());
        n.put("billable", facturable);
        if (!facturable && eraFacturable) {
            n.put("billableBy", firma).put("billableAt", ahora);
        } else if (facturable) {
            n.remove("billableBy");
            n.remove("billableAt");
        }
        String orden = Nodos.texto(recibido, "order").trim();
        n.put("order", orden);
        ObjectNode excepcionRecibida = Nodos.obj(recibido, "orderException");
        ObjectNode excepcionAnterior = anterior == null ? null : Nodos.obj(anterior, "orderException");
        String justRecibida = excepcionRecibida == null ? "" : Nodos.texto(excepcionRecibida, "justification").trim();
        String justAnterior = excepcionAnterior == null ? "" : Nodos.texto(excepcionAnterior, "justification").trim();
        boolean esAdmin = usuario != null && usuario.esAdmin();
        if (!justRecibida.equals(justAnterior) && comprobar && !esAdmin) {
            throw new ReglaNoCumplida("Solo un Admin puede autorizar una orden que no está asociada al proyecto.");
        }
        if (justRecibida.isEmpty()) {
            n.remove("orderException");
        } else if (!justRecibida.equals(justAnterior)) {
            n.putObject("orderException").put("justification", justRecibida).put("by", firma).put("at", ahora);
        }
        if (comprobar && !orden.isEmpty()) {
            String numero = Maestros.proyectoDeTexto(e.texto("project"))[0];
            ObjectNode proy = numero.isEmpty() ? null : Maestros.buscarProyecto(maestros, numero);
            if (proy != null && !Maestros.ordenesActivas(proy).contains(orden) && justRecibida.isEmpty()) {
                throw new ReglaNoCumplida(esAdmin ? "La orden no está asociada al proyecto: indica la justificación de la orden excepcional."
                        : "La orden no está asociada al proyecto: solo un Admin puede usar otra, con justificación.");
            }
        }
        n.set("pieces", numeroOVacio(recibido.get("pieces")));
        n.put("note", Nodos.texto(recibido, "note"));
        n.put("updatedBy", firma);
        n.put("updatedAt", ahora);
        return n;
    }

    private static JsonNode numeroOVacio(JsonNode v) {
        Double d = Nodos.numeroOVacio(v);
        if (d == null) {
            return TextNode.valueOf("");
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new ReglaNoCumplida("Valor numérico no válido: " + Nodos.texto(v));
        }
        return ValorJs.nodoNumero(d);
    }
}
