package com.borgwarner.tbp.paridad;

import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.kpi.Estadisticas;
import com.borgwarner.tbp.dominio.kpi.FiltroOrganizacion;
import com.borgwarner.tbp.dominio.kpi.FotoSemanal;
import com.borgwarner.tbp.dominio.kpi.HistoricoKpi;
import com.borgwarner.tbp.dominio.kpi.IndicadoresSemana;
import com.borgwarner.tbp.dominio.kpi.Ocupacion;
import com.borgwarner.tbp.dominio.kpi.PanelDashboard;
import com.borgwarner.tbp.dominio.operaciones.Cierre;
import com.borgwarner.tbp.dominio.operaciones.EdicionEnsayo;
import com.borgwarner.tbp.dominio.operaciones.GeneradorIds;
import com.borgwarner.tbp.dominio.operaciones.OperacionesBanco;
import com.borgwarner.tbp.dominio.operaciones.ReglaNoCumplida;
import com.borgwarner.tbp.dominio.planificacion.BancoConfigurado;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.borgwarner.tbp.dominio.planificacion.ContextoPlan;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.borgwarner.tbp.dominio.planificacion.MotorFechas;
import com.borgwarner.tbp.dominio.planificacion.PlanEnsayo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.planificacion.Sugerencias;
import com.borgwarner.tbp.dominio.ensayo.EstadosEnsayo;
import com.borgwarner.tbp.dominio.ensayo.Duraciones;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ejecuta un caso del laboratorio (herramientas/laboratorio/casos) sobre el
 * dominio Java y saca la misma FOTO que el controlador de la 4.9.4, para
 * compararla con su referencia.
 *
 * Reproduce las condiciones del laboratorio: PC sin caché (calendario del año
 * de la fecha), usuario «Laboratorio» y reloj parado que avanza 1,5 s por paso.
 * Las confirmaciones las responde el caso (aceptarDialogos); si se cancelan, la
 * operación no se ejecuta, igual que en la interfaz.
 */
final class LaboratorioJava {

    private final JsonNode caso;
    private final JsonNode escenario;
    private final Planificacion planificacion;
    private final ContextoPlan contexto;
    private final Usuario usuario;
    private final boolean aceptarDialogos;
    private final Set<String> seleccion = new LinkedHashSet<>();
    private final Map<String, String> textosIniciales;
    private final Set<String> idsDelEscenario = new LinkedHashSet<>();
    private final List<String> avisos = new ArrayList<>();
    private Instant ahora;
    private String bancoActual;
    private int contadorIds = 0;
    private ObjectNode cierreAbierto = JsonJs.objetoVacio().put("abierta", false);

    LaboratorioJava(JsonNode caso, Path carpetaEscenarios) throws IOException {
        this.caso = caso;
        Path carpeta = carpetaEscenarios.resolve(caso.path("escenario").asText());
        this.escenario = leerEscenario(carpeta);
        LocalDate hoy = LocalDate.parse(caso.path("fecha").asText());
        this.contexto = new ContextoPlan(hoy, Calendario.porDefecto(hoy));
        String rol = caso.path("rol").asText("admin");
        this.usuario = new Usuario("laboratorio@ejemplo.local", "Laboratorio", "Laboratorio", rol);
        this.aceptarDialogos = caso.path("aceptarDialogos").asBoolean(true);
        // Instante del primer paso: 10:00:00 + 0,1 s (lo que tarda el arranque del laboratorio).
        this.ahora = hoy.atTime(LocalTime.of(10, 0)).atZone(Fechas.ZONA).toInstant().plusMillis(100);

        List<FilaGuardada> filas = new ArrayList<>();
        for (JsonNode f : escenario.get("planner")) {
            filas.add(new FilaGuardada(f.path("uid").asText(), f.path("data").asText()));
            idsDelEscenario.add(f.path("uid").asText());
        }
        List<BancoConfigurado> bancos = new ArrayList<>();
        for (JsonNode b : escenario.path("configuracion").path("benches")) {
            bancos.add(new BancoConfigurado(b.path("name").asText(), texto(b, "groupKey"), texto(b, "groupName"),
                    texto(b, "dot"), texto(b, "org"), b.hasNonNull("seq") ? b.get("seq").asDouble() : null));
        }
        this.planificacion = Planificacion.desdeFilas(filas, ConfiguracionBancos.desdeLista(bancos));
        this.textosIniciales = planificacion.textosActuales();
    }

    private static String texto(JsonNode n, String campo) {
        return n.hasNonNull(campo) ? n.get(campo).asText() : null;
    }

    private static JsonNode leerEscenario(Path carpeta) throws IOException {
        ObjectNode e = JsonJs.objetoVacio();
        for (String[] f : new String[][]{{"planner", "planner.json"}, {"configuracion", "configuracion.json"}, {"kpis", "kpis.json"}}) {
            e.set(f[0], JsonJs.mapper().readTree(Files.readString(carpeta.resolve(f[1]))));
        }
        return e;
    }

    /** Ejecuta todos los pasos y devuelve la foto normalizada. */
    ObjectNode ejecutar() {
        for (JsonNode paso : caso.path("pasos")) {
            try {
                ejecutarPaso(paso);
            } catch (ReglaNoCumplida e) {
                avisos.add(e.getMessage()); // en la 4.9.4 es un alert() y no cambia nada
            }
            ahora = ahora.plusMillis(1500);
        }
        return foto();
    }

    private GeneradorIds ids() {
        return () -> "rjava" + String.format("%03d", ++contadorIds);
    }

    private ResultadoPlan plan() {
        return ResultadoPlan.calcular(planificacion, contexto);
    }

    private boolean puedeEscribir() {
        // En la 4.9.4 un usuario de solo lectura no ve los botones de edición.
        return !usuario.soloLectura();
    }

    private void ejecutarPaso(JsonNode paso) {
        String op = paso.path("op").asText();
        String banco = paso.path("banco").asText(null);
        String id = paso.path("ensayo").asText(null);
        if (banco != null && !banco.equals(bancoActual)) {
            // Abrir otro banco limpia la selección, como al navegar en la 4.9.4.
            bancoActual = banco;
            seleccion.clear();
        }
        switch (op) {
            case "editar" -> {
                if (!puedeEscribir()) {
                    return;
                }
                Ensayo e = planificacion.buscar(banco, id).orElseThrow();
                JsonNode valor = paso.get("valor");
                if (valor != null && valor.isNumber()) {
                    valor = TextNode.valueOf(valor.asText()); // lo que contiene la caja de texto
                }
                EdicionEnsayo.editar(e, paso.path("campo").asText(), valor, plan().de(e), contexto.hoy());
            }
            case "estado" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.declararEstado(planificacion, banco, id, paso.path("valor").asText());
                }
            }
            case "aplicarSugerencia" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.aplicarSugerencia(planificacion, plan(), banco, id);
                }
            }
            case "comprometer" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.comprometer(planificacion, plan(), banco, id);
                }
            }
            case "deshacerCompromiso" -> {
                if (puedeEscribir() && aceptarDialogos) {
                    OperacionesBanco.deshacerCompromiso(planificacion, banco, id);
                }
            }
            case "confirmarTodas" -> {
                if (puedeEscribir() && aceptarDialogos) {
                    OperacionesBanco.comprometerTodos(planificacion, plan(), banco);
                }
            }
            case "borrar" -> {
                if (puedeEscribir() && aceptarDialogos) {
                    OperacionesBanco.borrar(planificacion, banco, id);
                }
            }
            case "seleccionar" -> {
                seleccion.clear();
                paso.path("ensayos").forEach(n -> seleccion.add(n.asText()));
            }
            case "duplicar" -> {
                if (puedeEscribir()) {
                    List<String> nuevos = OperacionesBanco.duplicar(planificacion, banco, seleccion, ids());
                    seleccion.clear();
                    seleccion.addAll(nuevos);
                }
            }
            case "borrarSeleccion" -> {
                if (puedeEscribir() && aceptarDialogos) {
                    OperacionesBanco.borrarSeleccion(planificacion, banco, seleccion);
                    seleccion.clear();
                }
            }
            case "mover" -> {
                if (puedeEscribir()) {
                    String aviso = OperacionesBanco.mover(planificacion, banco, seleccion, paso.path("destino").asText(),
                            paso.path("posicion").asInt());
                    if (aviso != null) {
                        avisos.add(aviso);
                        seleccion.clear();
                        bancoActual = paso.path("destino").asText(); // la 4.9.4 abre el banco de destino
                    }
                }
            }
            case "arrastrar" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.reordenar(planificacion, banco, paso.path("origen").asText(), paso.path("destino").asText(), seleccion);
                }
            }
            case "anadir" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.anadir(planificacion, banco, usuario, ids());
                }
            }
            case "restaurar" -> {
                if (puedeEscribir()) {
                    Cierre.restaurar(planificacion, banco, id);
                }
            }
            case "reutilizar" -> {
                if (puedeEscribir()) {
                    Cierre.reutilizar(planificacion, banco, id, ids());
                }
            }
            case "cerrar", "corregir" -> cerrar(paso, op.equals("corregir"), banco, id);
            case "recurso" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.alternarRecurso(planificacion, banco, id, paso.path("recurso").asText());
                }
            }
            case "renombrarRecurso" -> {
                if (puedeEscribir()) {
                    OperacionesBanco.renombrarRecurso(planificacion, paso.path("anterior").asText(), paso.path("nuevo").asText());
                }
            }
            case "fotoKpi", "esperar" -> {
                // La foto semanal no cambia el estado: se observa en el apartado kpi.
            }
            default -> throw new IllegalArgumentException("Paso desconocido: " + op);
        }
    }

    private void cerrar(JsonNode paso, boolean correccion, String banco, String id) {
        Ensayo e = planificacion.buscar(banco, id).orElseThrow();
        Cierre.Valores propuesta = Cierre.propuesta(e, plan().de(e), correccion, usuario, contexto.hoy());
        JsonNode v = paso.path("valores");
        Cierre.Valores valores = new Cierre.Valores(
                v.has("arranqueReal") ? v.get("arranqueReal").asText() : propuesta.arranqueReal(),
                v.has("finReal") ? v.get("finReal").asText() : propuesta.finReal(),
                v.has("estadoFinal") ? v.get("estadoFinal").asText() : propuesta.estadoFinal(),
                v.has("motivo") ? v.get("motivo").asText() : propuesta.motivo(),
                v.has("explicacion") ? v.get("explicacion").asText() : propuesta.explicacion(),
                v.has("diasParada") ? v.get("diasParada").asText() : propuesta.diasParada(),
                v.has("notaParada") ? v.get("notaParada").asText() : propuesta.notaParada());
        Cierre.Validacion validacion = Cierre.validar(e, valores, contexto.calendario(), contexto.hoy());
        boolean confirmar = paso.path("confirmar").asBoolean(true);
        if (confirmar && validacion.error().isEmpty() && puedeEscribir()) {
            Cierre.confirmar(planificacion, banco, id, valores, correccion, usuario, contexto.calendario(), contexto.hoy(), ahora);
            cierreAbierto = JsonJs.objetoVacio().put("abierta", false);
            return;
        }
        cierreAbierto = JsonJs.objetoVacio();
        cierreAbierto.put("abierta", true);
        cierreAbierto.put("arranqueReal", valores.arranqueReal());
        cierreAbierto.put("finReal", valores.finReal());
        cierreAbierto.put("estadoFinal", valores.estadoFinal());
        cierreAbierto.put("motivo", valores.motivo());
        cierreAbierto.put("diasParada", valores.diasParada());
        cierreAbierto.put("error", validacion.error());
    }

    // ---- foto (mismo formato que controlador-4.9.4.cjs) ------------------------

    private ObjectNode foto() {
        List<String> apartados = new ArrayList<>();
        caso.path("observar").forEach(n -> apartados.add(n.asText()));
        if (apartados.isEmpty()) {
            apartados.add("planificacion");
        }
        ResultadoPlan plan = plan();
        ObjectNode foto = JsonJs.objetoVacio();
        if (apartados.contains("planificacion")) {
            ObjectNode porBanco = foto.putObject("planificacion");
            for (String banco : planificacion.bancos()) {
                ArrayNode lista = porBanco.putArray(banco);
                for (Ensayo e : planificacion.activos(banco)) {
                    lista.add(filaPlan(e, plan.de(e)));
                }
            }
        }
        Map<String, String> textos = planificacion.textosActuales();
        if (apartados.contains("persistido")) {
            ObjectNode persistido = foto.putObject("persistido");
            textos.forEach(persistido::put);
        }
        if (apartados.contains("kpi")) {
            foto.set("kpi", kpi(plan));
        }
        if (apartados.contains("kpiHistorico")) {
            foto.set("kpiHistorico", kpiHistorico());
        }
        if (apartados.contains("panel")) {
            foto.set("panel", panel(plan));
        }
        if (apartados.contains("estadisticas")) {
            int anio = contexto.hoy().getYear();
            ArrayNode lista = foto.putArray("estadisticas");
            for (Estadisticas.Fila f : Estadisticas.filas(planificacion, LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31), "ALL", "")) {
                ObjectNode fila = lista.addObject();
                fila.put("banco", f.banco()).put("id", f.ensayo().id()).put("fecha", Fechas.iso(f.fecha()))
                        .put("estadoFinal", f.estadoFinal()).put("motivo", f.motivo()).put("confirmado", f.confirmado());
            }
        }
        if (apartados.contains("historicoKpi")) {
            List<JsonNode> filas = new ArrayList<>();
            escenario.path("kpis").forEach(filas::add);
            foto.set("historicoKpi", JsonJs.mapper().valueToTree(HistoricoKpi.semanas(filas, "ALL")));
        }
        if (apartados.contains("cierre")) {
            foto.set("cierre", cierreAbierto);
        }
        ObjectNode orden = foto.putObject("orden");
        for (String banco : planificacion.bancos()) {
            ArrayNode lista = orden.putArray(banco);
            planificacion.ensayos(banco).forEach(e -> lista.add(e.id()));
        }
        foto.set("cambiosPersistidos", Normalizacion.diferencias(textosIniciales, textos));
        ArrayNode dialogos = foto.putArray("avisosJava");
        avisos.forEach(dialogos::add);
        return Normalizacion.renombrarIdsNuevos(foto, idsDelEscenario, textos.keySet());
    }

    private ObjectNode filaPlan(Ensayo e, PlanEnsayo p) {
        ObjectNode f = JsonJs.objetoVacio();
        f.put("id", e.id());
        f.put("inicio", Fechas.iso(p.inicio()));
        f.put("fin", Fechas.iso(p.fin()));
        f.put("criterioInicio", p.criterio());
        f.put("estado", EstadosEnsayo.estado(e, contexto.hoy()));
        ArrayNode sugerencias = f.putArray("sugerencias");
        Sugerencias.de(e, p, contexto).forEach(s -> sugerencias.add(s.clave()));
        f.put("sugerenciaAbierta", Sugerencias.abierta(e, p, contexto));
        ArrayNode solapes = f.putArray("solapes");
        p.solapes().forEach(s -> solapes.add(Fechas.iso(s.inicio()) + ".." + Fechas.iso(s.fin())));
        f.put("diasPlanificados", Duraciones.diasPlanificados(e));
        f.put("finTolerado", Fechas.iso(MotorFechas.finTolerado(e, contexto.calendario())));
        f.put("finPlanificado", Fechas.iso(MotorFechas.finPlanificado(e, contexto)));
        f.put("arranqueEfectivo", EstadosEnsayo.arranqueEfectivo(e, contexto.hoy()));
        f.put("diasSinAvance", MotorFechas.diasSinAvance(e, contexto.hoy()));
        return f;
    }

    /** KPI History (bloque C): fotos por organización y estadísticas con los mismos filtros que la 4.9.4. */
    private ObjectNode kpiHistorico() {
        ObjectNode salida = JsonJs.objetoVacio();
        List<JsonNode> filas = new ArrayList<>();
        escenario.path("kpis").forEach(filas::add);
        ObjectNode historico = salida.putObject("historico");
        for (String org : FiltroOrganizacion.ORGANIZACIONES) {
            historico.set(org, JsonJs.mapper().valueToTree(HistoricoKpi.semanas(filas, org)));
        }
        int anio = contexto.calendario().anio();
        String[][] filtros = {
                {"ALL", "", anio + "-01-01", anio + "-12-31"}, {"BW", "", anio + "-01-01", anio + "-12-31"},
                {"CTAG", "", anio + "-01-01", anio + "-12-31"}, {"ALL", "Responsable 27", anio + "-01-01", anio + "-12-31"},
                {"ALL", "", anio + "-09-01", anio + "-10-15"},
        };
        ObjectNode estadisticas = salida.putObject("estadisticas");
        for (String[] f : filtros) {
            ArrayNode lista = estadisticas.putArray(String.join("|", f));
            for (Estadisticas.Fila fila : Estadisticas.filas(planificacion, LocalDate.parse(f[2]), LocalDate.parse(f[3]), f[0], f[1])) {
                lista.addObject().put("banco", fila.banco()).put("id", fila.ensayo().id()).put("fecha", Fechas.iso(fila.fecha()))
                        .put("estadoFinal", fila.estadoFinal()).put("motivo", fila.motivo()).put("confirmado", fila.confirmado());
            }
        }
        return salida;
    }

    /** Dashboard (bloque B): lo mismo que lee la 4.9.4 de utilCardHTML, trendData y blockKPIs. */
    private ObjectNode panel(ResultadoPlan plan) {
        LocalDate lunes = Fechas.lunesDe(contexto.hoy());
        int anio = contexto.calendario().anio();
        LocalDate semanaUno = PanelDashboard.lunesSemanaUno(anio);
        int semanas = PanelDashboard.semanasDelAnio(anio);
        ObjectNode panel = JsonJs.objetoVacio();
        ObjectNode tablas = panel.putObject("tabla");
        ObjectNode tendencias = panel.putObject("tendencia");
        for (String org : FiltroOrganizacion.ORGANIZACIONES) {
            var filtro = FiltroOrganizacion.de(org, planificacion.configuracion());
            ObjectNode t = tablas.putObject(org);
            t.set("semana", tablaComoLa494(PanelDashboard.tabla(planificacion, plan, lunes, lunes.plusDays(6), filtro)));
            t.set("anual", tablaComoLa494(PanelDashboard.tabla(planificacion, plan, semanaUno,
                    semanaUno.plusDays(7L * (semanas - 1) + 6), filtro)));
            t.set("rango", tablaComoLa494(PanelDashboard.tabla(planificacion, plan, semanaUno.plusDays(7L * 9),
                    semanaUno.plusDays(7L * 13 + 6), filtro)));
            PanelDashboard.Tendencia tendencia = PanelDashboard.tendencia(planificacion, plan, anio, 1, 53, filtro);
            ObjectNode g = tendencias.putObject(org);
            g.put("desde", tendencia.desde()).put("hasta", tendencia.hasta()).put("semanas", tendencia.semanasDelAnio());
            g.set("lunes", JsonJs.mapper().valueToTree(tendencia.lunes()));
            ArrayNode series = g.putArray("series");
            for (PanelDashboard.Serie serie : tendencia.series()) {
                ObjectNode sr = series.addObject();
                sr.put("clave", serie.clave()).put("bancos", serie.bancos());
                sr.set("pct", JsonJs.mapper().valueToTree(serie.pct()));
                sr.set("busy", JsonJs.mapper().valueToTree(serie.busy()));
            }
        }
        ObjectNode responsables = panel.putObject("responsables");
        java.util.TreeSet<String> nombres = new java.util.TreeSet<>();
        for (String banco : planificacion.bancos()) {
            planificacion.activos(banco).forEach(e -> nombres.add(e.texto("resp")));
        }
        nombres.remove("");
        for (String nombre : nombres) {
            ObjectNode r = responsables.putObject(nombre);
            r.set("estaSemana", JsonJs.mapper().valueToTree(IndicadoresSemana.bloque(planificacion, plan, lunes, b -> true,
                    e -> e.texto("resp").equals(nombre))));
            r.set("siguiente", JsonJs.mapper().valueToTree(IndicadoresSemana.bloque(planificacion, plan, lunes.plusDays(7), b -> true,
                    e -> e.texto("resp").equals(nombre))));
        }
        return panel;
    }

    /** La tabla con los mismos campos que se leen de la tarjeta de la 4.9.4. */
    private static ObjectNode tablaComoLa494(PanelDashboard.TablaOcupacion tabla) {
        ObjectNode t = JsonJs.objetoVacio();
        ArrayNode grupos = t.putArray("grupos");
        for (PanelDashboard.FilaOcupacion f : tabla.grupos()) {
            grupos.addObject().put("nombre", f.nombre()).put("bancos", f.bancos()).put("trabajando", f.trabajando())
                    .put("busy", f.busy()).put("cap", f.cap()).put("pct", f.pct()).put("inicia", f.inicia()).put("acaba", f.acaba());
        }
        t.put("busy", tabla.busy()).put("cap", tabla.cap()).put("pct", tabla.pct());
        return t;
    }

    private ObjectNode kpi(ResultadoPlan plan) {
        LocalDate lunes = Fechas.lunesDe(contexto.hoy());
        ObjectNode kpi = JsonJs.objetoVacio();
        ObjectNode bloques = kpi.putObject("bloques");
        ObjectNode semana = kpi.putObject("semana");
        for (String org : FiltroOrganizacion.ORGANIZACIONES) {
            var filtro = FiltroOrganizacion.de(org, planificacion.configuracion());
            ObjectNode b = bloques.putObject(org);
            b.set("estaSemana", JsonJs.mapper().valueToTree(IndicadoresSemana.bloque(planificacion, plan, lunes, filtro)));
            b.set("siguiente", JsonJs.mapper().valueToTree(IndicadoresSemana.bloque(planificacion, plan, lunes.plusDays(7), filtro)));
            semana.set(org, JsonJs.mapper().valueToTree(IndicadoresSemana.semana(planificacion, plan, lunes, filtro)));
        }
        ArrayNode fotos = kpi.putArray("fotoSemanal");
        FotoSemanal.tomar(planificacion, plan).forEach(fotos::add);
        ObjectNode ocupacion = kpi.putObject("ocupacion");
        int anio = contexto.hoy().getYear();
        for (Grupo g : planificacion.configuracion().grupos()) {
            ObjectNode o = ocupacion.putObject(g.key());
            o.set("semana", JsonJs.mapper().valueToTree(Ocupacion.de(planificacion, plan, g.benches(), lunes, lunes.plusDays(6))));
            o.set("anio", JsonJs.mapper().valueToTree(Ocupacion.de(planificacion, plan, g.benches(), LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31))));
        }
        return kpi;
    }
}
