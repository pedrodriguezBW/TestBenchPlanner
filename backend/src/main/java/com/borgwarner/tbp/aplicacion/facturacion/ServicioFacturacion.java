package com.borgwarner.tbp.aplicacion.facturacion;

import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.borgwarner.tbp.aplicacion.ServicioListas;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.SinPermiso;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenTiposEquipo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.facturacion.HojaMensual;
import com.borgwarner.tbp.dominio.facturacion.Regla;
import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.facturacion.HojaExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facturación: Equipment Types por año (lista TestBenchEquipmentTypes) y hoja
 * mensual calculada al momento a partir de las tareas, el Equipment Type de
 * cada banco (columna EquipmentType de TestBenchBenches) y la Billing Order de
 * cada proyecto (columna BillingOrder de TestBenchProjects). No guarda nada
 * más: no hay periodos ni documentos propios.
 */
@Service
public class ServicioFacturacion {

    private final ServicioPlanificacion planificacion;
    private final ServicioSesion sesion;
    private final ServicioListas listas;
    private final AlmacenTiposEquipo almacen;
    private final EstadoConexion conexion;

    public ServicioFacturacion(ServicioPlanificacion planificacion, ServicioSesion sesion, ServicioListas listas,
                               AlmacenTiposEquipo almacen, EstadoConexion conexion) {
        this.planificacion = planificacion;
        this.sesion = sesion;
        this.listas = listas;
        this.almacen = almacen;
        this.conexion = conexion;
    }

    // ---- listas -----------------------------------------------------------------------

    private JsonNode catalogos() {
        try {
            ObjectNode c = listas.leerCatalogos();
            if (c.has("projects_error") || c.has("benches_error")) {
                throw new ReglaNoCumplida("No se han podido leer las listas de proyectos o bancos: vuelve a intentarlo en un momento.");
            }
            return c;
        } catch (ReglaNoCumplida | PeticionCancelada e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ReglaNoCumplida("No se han podido leer las listas de proyectos o bancos: " + e.getMessage());
        }
    }

    private static Map<String, String> tipoPorBanco(JsonNode catalogos) {
        Map<String, String> m = new HashMap<>();
        for (JsonNode b : catalogos.path("benches")) {
            m.put(b.path("name").asText(""), b.path("equipmentType").asText("").trim());
        }
        return m;
    }

    private static Map<String, String> ordenes(JsonNode catalogos) {
        Map<String, String> m = new HashMap<>();
        catalogos.path("projectOrders").fields().forEachRemaining(x -> m.put(x.getKey(), x.getValue().asText("").trim()));
        return m;
    }

    // ---- configuración ----------------------------------------------------------------

    public static ArrayNode reglasJson() {
        ArrayNode a = JsonJs.mapper().createArrayNode();
        for (Regla r : Regla.values()) {
            a.addObject().put("codigo", r.name()).put("nombre", r.nombre()).put("calculo", r.calculo()).put("unidad", r.unidad());
        }
        return a;
    }

    /** Equipment Types, reglas, valores sugeridos y Equipment Type de cada banco. */
    public ObjectNode configuracion() {
        ObjectNode salida = JsonJs.objetoVacio();
        ArrayNode tipos = salida.putArray("tipos");
        try {
            almacen.leer().forEach(t -> tipos.add(t.json()));
        } catch (PeticionCancelada e) {
            throw e;
        } catch (RuntimeException e) {
            salida.put("error", e.getMessage());
        }
        salida.set("reglas", reglasJson());
        ArrayNode grupos = salida.putArray("grupos");
        HojaMensual.GRUPOS_EXCEL.forEach(grupos::add);
        ArrayNode lineas = salida.putArray("productLines");
        HojaMensual.PRODUCT_LINES.forEach(lineas::add);
        ArrayNode bancos = salida.putArray("bancos");
        Vista v = planificacion.vista();
        Map<String, String> tipoDe = Map.of();
        try {
            tipoDe = tipoPorBanco(listas.leerCatalogos());
        } catch (PeticionCancelada e) {
            throw e;
        } catch (RuntimeException e) {
            // sin la lista de bancos se muestran sin Equipment Type
        }
        for (String b : v.planificacion().bancos()) {
            bancos.addObject().put("nombre", b).put("organizacion", v.planificacion().configuracion().organizacion(b))
                    .put("equipmentType", tipoDe.getOrDefault(b, ""));
        }
        return salida;
    }

    /** Valida y guarda TODOS los Equipment Types ({"tipos": [...]}). Solo Admin. */
    public ObjectNode guardarTipos(JsonNode cuerpo) {
        Usuario u = sesion.usuarioActual();
        sesion.exigirEscritura(u);
        sesion.exigirAdmin(u);
        if (conexion.sinConexion()) {
            throw new SinPermiso("Modo sin conexión: la configuración de facturación solo se puede consultar.");
        }
        List<TipoEquipo> tipos = validar(cuerpo.path("tipos"));
        almacen.guardar(tipos);
        return configuracion();
    }

    static List<TipoEquipo> validar(JsonNode lista) {
        if (!lista.isArray()) {
            throw new ReglaNoCumplida("Faltan los Equipment Types.");
        }
        List<TipoEquipo> tipos = new ArrayList<>();
        Set<String> claves = new HashSet<>();
        List<String> errores = new ArrayList<>();
        int i = 0;
        for (JsonNode n : lista) {
            i++;
            String titulo = n.path("titulo").asText("").trim();
            String nombre = titulo.isEmpty() ? "Fila " + i : "«" + titulo + "»";
            if (titulo.isEmpty()) {
                errores.add(nombre + ": falta el Equipment Type.");
            }
            String reglaTexto = n.path("regla").asText("");
            if (Regla.de(reglaTexto) == null) {
                errores.add(nombre + ": regla de cálculo no válida («" + reglaTexto + "»).");
            }
            TipoEquipo t = TipoEquipo.desdeJson(n);
            if (t.anio() < 2000 || t.anio() > 2100) {
                errores.add(nombre + ": año no válido.");
            }
            for (String campo : List.of("sinHv", "conHv")) {
                JsonNode v = n.get(campo);
                boolean vacio = v == null || v.isNull() || v.asText("").isBlank();
                Double x = t.json().path(campo).isNull() ? null : t.json().path(campo).asDouble();
                if (!vacio && (x == null || x < 0 || x.isNaN() || x.isInfinite())) {
                    errores.add(nombre + ": la tarifa " + (campo.equals("conHv") ? "With HV" : "Without HV") + " debe ser un número ≥ 0 o quedar vacía.");
                }
            }
            if (!titulo.isEmpty() && !claves.add(t.clave())) {
                errores.add(nombre + " está repetido en " + t.anio() + ".");
            }
            tipos.add(t);
        }
        if (!errores.isEmpty()) {
            throw new ReglaNoCumplida(String.join(" ", errores));
        }
        return tipos;
    }

    // ---- hoja mensual -----------------------------------------------------------------

    static YearMonth mes(String texto) {
        try {
            return YearMonth.parse(texto == null ? "" : texto.trim());
        } catch (DateTimeParseException e) {
            throw new ReglaNoCumplida("Mes no válido (aaaa-mm): " + texto);
        }
    }

    /** «orden,planta,tipo» → niveles (null o vacío = todos, «-» = ninguno). */
    static List<HojaMensual.Nivel> niveles(String texto) {
        if (texto == null || texto.isBlank()) {
            return HojaMensual.NIVELES;
        }
        return HojaMensual.niveles(texto.trim().equals("-") ? List.of() : List.of(texto.split(",")));
    }

    HojaMensual.Hoja calcular(YearMonth mes, List<HojaMensual.Nivel> niveles) {
        JsonNode cat = catalogos();
        List<TipoEquipo> tipos = almacen.leer();
        Vista v = planificacion.vista();
        List<HojaMensual.Entrada> entradas = new ArrayList<>();
        for (String banco : v.planificacion().bancos()) {
            for (Ensayo e : v.planificacion().ensayos(banco)) {
                HojaMensual.Entrada en = HojaMensual.entrada(banco, e, v.plan().de(e), v.contexto().hoy());
                if (en != null) {
                    entradas.add(en);
                }
            }
        }
        return HojaMensual.calcular(entradas, mes, tipoPorBanco(cat), tipos, ordenes(cat), niveles);
    }

    /** Vista previa del mes: tabla con todas las líneas, tabla exportable y detalle de líneas. */
    public ObjectNode hoja(String mes, String niveles) {
        ObjectNode salida = HojaMensual.json(calcular(mes(mes), niveles(niveles)));
        ArrayNode disponibles = salida.putArray("nivelesDisponibles");
        HojaMensual.NIVELES.forEach(n -> disponibles.addObject().put("id", n.id()).put("nombre", n.nombre()));
        return salida;
    }

    /** Archivo de la hoja (solo líneas completas): formato «excel» o «html», con los niveles de fila elegidos. */
    public ObjectNode exportar(String mesTexto, String formato, String niveles) {
        YearMonth mes = mes(mesTexto);
        HojaMensual.Hoja h = calcular(mes, niveles(niveles));
        ObjectNode salida = JsonJs.objetoVacio().put("ok", true);
        byte[] bytes;
        if ("html".equalsIgnoreCase(formato)) {
            bytes = HojaExcel.html(mes, h.exportable()).getBytes(StandardCharsets.UTF_8);
            salida.put("filename", HojaExcel.nombreArchivo(mes) + ".html");
        } else {
            bytes = HojaExcel.excel(mes, h.exportable());
            salida.put("filename", HojaExcel.nombreArchivo(mes) + ".xlsx");
        }
        salida.put("b64", Base64.getEncoder().encodeToString(bytes));
        salida.put("excluidas", h.lineas().stream().filter(l -> !l.completa()).count());
        return salida;
    }
}
