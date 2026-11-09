package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.ServicioIndicadores;
import com.borgwarner.tbp.aplicacion.ServicioPlanificacion;
import com.borgwarner.tbp.aplicacion.ServicioSesion;
import com.borgwarner.tbp.aplicacion.Vista;
import com.borgwarner.tbp.dominio.calendario.Fechas;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.kpi.Estadisticas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** KPI, ocupación, histórico y estadísticas para el dashboard y KPI History. */
@RestController
@RequestMapping("/api/v1/indicadores")
public class IndicadoresController {

    private final ServicioIndicadores indicadores;
    private final ServicioPlanificacion planificacion;
    private final ServicioSesion sesion;

    public IndicadoresController(ServicioIndicadores indicadores, ServicioPlanificacion planificacion, ServicioSesion sesion) {
        this.indicadores = indicadores;
        this.planificacion = planificacion;
        this.sesion = sesion;
    }

    private static LocalDate fecha(String texto, LocalDate porDefecto) {
        return texto == null || texto.isBlank() ? porDefecto : LocalDate.parse(texto);
    }

    @GetMapping("/bloques")
    public JsonNode bloques(@RequestParam(required = false) String lunes, @RequestParam(defaultValue = "ALL") String org,
                            @RequestParam(required = false) String responsable) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.bloques(vista, fecha(lunes, vista.contexto().hoy()), org, responsable));
    }

    /** Tabla «Ocupación por tipo de banco» (una semana o el acumulado de un rango). */
    @GetMapping("/tabla-ocupacion")
    public JsonNode tablaOcupacion(@RequestParam String desde, @RequestParam String hasta, @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.tablaOcupacion(vista, LocalDate.parse(desde), LocalDate.parse(hasta), org));
    }

    /** Gráfica «% Ocupación por CW»: cwDesde/cwHasta = 0 significa «todo el año». */
    @GetMapping("/tendencia")
    public JsonNode tendencia(@RequestParam(defaultValue = "0") int cwDesde, @RequestParam(defaultValue = "0") int cwHasta,
                              @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.tendencia(vista, cwDesde, cwHasta, org));
    }

    @GetMapping("/ocupacion")
    public JsonNode ocupacion(@RequestParam String desde, @RequestParam String hasta, @RequestParam(defaultValue = "ALL") String org) {
        Vista vista = planificacion.vista();
        return JsonJs.mapper().valueToTree(indicadores.ocupacion(vista, LocalDate.parse(desde), LocalDate.parse(hasta), org));
    }

    /**
     * Fotos semanales de una organización, ordenadas por año y CW. {@code filas}
     * es el número total de filas leídas de la lista (todas las organizaciones),
     * que la pantalla muestra como «Source: SharePoint (N rows)».
     */
    @GetMapping("/historico")
    public ObjectNode historico(@RequestParam(defaultValue = "ALL") String org) {
        ServicioIndicadores.Historico h = indicadores.historicoCompleto(org);
        ObjectNode salida = JsonJs.objetoVacio();
        salida.put("filas", h.filas());
        salida.set("semanas", JsonJs.mapper().valueToTree(h.semanas()));
        return salida;
    }

    @GetMapping("/estadisticas")
    public ArrayNode estadisticas(@RequestParam(required = false) String desde, @RequestParam(required = false) String hasta,
                                  @RequestParam(defaultValue = "ALL") String org, @RequestParam(required = false) String responsable) {
        Vista vista = planificacion.vista();
        int anio = vista.contexto().calendario().anio();
        ArrayNode salida = JsonJs.mapper().createArrayNode();
        for (Estadisticas.Fila f : indicadores.estadisticas(vista, fecha(desde, LocalDate.of(anio, 1, 1)),
                fecha(hasta, LocalDate.of(anio, 12, 31)), org, responsable)) {
            ObjectNode fila = salida.addObject();
            fila.put("banco", f.banco());
            fila.put("id", f.ensayo().id());
            fila.put("fecha", Fechas.iso(f.fecha()));
            fila.put("estadoFinal", f.estadoFinal());
            fila.put("motivo", f.motivo());
            fila.put("confirmado", f.confirmado());
            fila.set("datos", f.ensayo().datos());
        }
        return salida;
    }

    /** 📊 Update Yearly Status. */
    @PostMapping("/foto-semanal")
    public JsonNode fotoSemanal() {
        sesion.exigirEscritura(sesion.usuarioActual());
        return JsonJs.mapper().valueToTree(indicadores.guardarFotoSemanal());
    }
}
