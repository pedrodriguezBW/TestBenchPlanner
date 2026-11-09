package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.borgwarner.tbp.dominio.planificacion.Grupo;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copia de seguridad JSON (Tools → Export / Import de la 4.9.4).
 *
 * El fichero tiene el formato del «state» de la 4.9.4 (benches, groups,
 * benchOrg, projects, responsibles, settings, holidays), así que una copia
 * hecha con la versión nueva se puede abrir con la clásica y al revés.
 *
 * Importar sustituye la planificación completa por la del fichero, igual que
 * hoy (D10): los ensayos que no estén en la copia se borran. Se mantienen las
 * protecciones de la versión nueva: solo Admin, y la lista de bancos y la de
 * proyectos no se pueden vaciar (D11). Orden: catálogo, ensayos y calendario.
 */
@Service
public class ServicioCopiaSeguridad {

    /** Campos de cálculo que la 4.9.4 tiene en memoria y nunca se guardan. */
    private static final Set<String> CAMPOS_DE_CALCULO = Set.of("_ovl", "_start", "_end", "_planStart", "_planEnd", "_confirmed",
            "_startWhy", "_bench", "_seq");

    public record ResultadoImportacion(int ensayosGuardados, int ensayosBorrados, Vista vista) {
    }

    private final ServicioPlanificacion planificacion;
    private final ServicioListas listas;
    private final ServicioSesion sesion;
    private final AlmacenEnsayos ensayos;
    private final AlmacenCatalogos catalogos;
    private final AlmacenCalendario calendario;
    private final Reloj reloj;

    public ServicioCopiaSeguridad(ServicioPlanificacion planificacion, ServicioListas listas, ServicioSesion sesion,
                                  AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenCalendario calendario, Reloj reloj) {
        this.planificacion = planificacion;
        this.listas = listas;
        this.sesion = sesion;
        this.ensayos = ensayos;
        this.catalogos = catalogos;
        this.calendario = calendario;
        this.reloj = reloj;
    }

    /** Estado completo con el formato de la 4.9.4. */
    public ObjectNode exportar() {
        Vista vista = planificacion.vista();
        Planificacion p = vista.planificacion();
        ObjectNode estado = JsonJs.objetoVacio();
        ObjectNode bancos = estado.putObject("benches");
        ObjectNode organizaciones = JsonJs.objetoVacio();
        ArrayNode grupos = JsonJs.mapper().createArrayNode();
        for (Grupo g : p.configuracion().grupos()) {
            ObjectNode grupo = grupos.addObject().put("key", g.key()).put("name", g.name()).put("dot", g.dot());
            ArrayNode lista = grupo.putArray("benches");
            for (String banco : g.benches()) {
                lista.add(banco);
                organizaciones.put(banco, p.configuracion().organizacion(banco));
                ArrayNode filas = bancos.putArray(banco);
                p.ensayos(banco).forEach(e -> filas.add(e.datos().deepCopy()));
            }
        }
        Calendario c = vista.contexto().calendario();
        ArrayNode festivos = estado.putArray("holidays");
        c.festivos().forEach(f -> festivos.addObject().put("date", f.date()).put("name", f.name()));
        estado.putObject("settings").put("year", c.anio()).put("skipHolidays", c.saltarFestivos());
        estado.set("groups", grupos);
        JsonNode catalogo = catalogos.leer();
        estado.set("projects", catalogo.path("projects").isArray() ? catalogo.get("projects").deepCopy() : JsonJs.mapper().createArrayNode());
        estado.set("responsibles", catalogo.path("responsibles").isArray() ? catalogo.get("responsibles").deepCopy() : JsonJs.mapper().createArrayNode());
        estado.set("benchOrg", organizaciones);
        return estado;
    }

    /** Sustituye la planificación por la de la copia (importJSON). */
    public ResultadoImportacion importar(Usuario usuario, JsonNode d) {
        sesion.exigirAdmin(usuario);
        if (d == null || !d.path("benches").isObject()) {
            throw new IllegalArgumentException("Invalid file");
        }
        // Grupos de la copia o, si no trae, los de serie (como importJSON).
        List<Grupo> grupos = new ArrayList<>();
        for (JsonNode g : d.path("groups")) {
            List<String> lista = new ArrayList<>();
            g.path("benches").forEach(b -> lista.add(b.asText()));
            grupos.add(new Grupo(g.path("key").asText(""), g.path("name").asText(""), g.path("dot").asText("#23BCA3"), lista));
        }
        if (grupos.isEmpty()) {
            grupos.addAll(ConfiguracionBancos.GRUPOS_DE_SERIE);
        }
        Map<String, String> organizaciones = new LinkedHashMap<>();
        d.path("benchOrg").fields().forEachRemaining(e -> organizaciones.put(e.getKey(), e.getValue().asText()));
        ConfiguracionBancos configuracion = new ConfiguracionBancos(grupos, organizaciones);

        Map<String, List<ObjectNode>> filas = new LinkedHashMap<>();
        for (String banco : configuracion.bancos()) {
            List<ObjectNode> lista = new ArrayList<>();
            for (JsonNode r : d.path("benches").path(banco)) {
                if (!r.isObject() || r.path("id").asText("").isEmpty()) {
                    continue; // sin id no hay fila de SharePoint a la que corresponda
                }
                ObjectNode datos = ((ObjectNode) r).deepCopy();
                CAMPOS_DE_CALCULO.forEach(datos::remove);
                lista.add(datos);
            }
            filas.put(banco, lista);
        }

        Vista vista = planificacion.vista();
        Planificacion copia = Planificacion.desdeCopia(configuracion, filas, vista.planificacion());
        List<CambioFila> cambios = copia.cambiosPendientes();

        // 1. Catálogo (bancos de la copia y sus proyectos). D11 puede rechazarlo: entonces no se escribe nada más.
        ObjectNode catalogo = JsonJs.objetoVacio();
        ArrayNode bancos = catalogo.putArray("benches");
        int seq = 0;
        for (Grupo g : configuracion.grupos()) {
            for (String banco : g.benches()) {
                bancos.addObject().put("name", banco).put("groupKey", g.key()).put("groupName", g.name()).put("dot", g.dot())
                        .put("org", configuracion.organizacion(banco)).put("seq", ++seq);
            }
        }
        JsonNode proyectos = d.path("projects").isArray() ? d.get("projects") : catalogos.leer().path("projects");
        catalogo.set("projects", proyectos.deepCopy());
        listas.guardarCatalogos(catalogo);

        // 2. Ensayos que cambian, nuevos o que ya no están.
        if (!cambios.isEmpty()) {
            ensayos.guardar(cambios, true, ServicioPlanificacion.actor(usuario));
        }

        // 3. Calendario de este PC (ajustes y festivos de la copia).
        Calendario actual = calendario.leer(reloj.hoy());
        JsonNode ajustes = d.path("settings");
        List<Festivo> festivos = new ArrayList<>();
        if (d.path("holidays").isArray()) {
            d.get("holidays").forEach(h -> festivos.add(new Festivo(h.path("date").asText(""), h.path("name").asText(""))));
        } else {
            festivos.addAll(actual.festivos());
        }
        calendario.guardar(new Calendario(ajustes.path("year").asInt(actual.anio()),
                ajustes.has("skipHolidays") ? ajustes.path("skipHolidays").asBoolean(true) : actual.saltarFestivos(), festivos));

        int borrados = (int) cambios.stream().filter(CambioFila::esBorrado).count();
        return new ResultadoImportacion(cambios.size() - borrados, borrados, planificacion.vista());
    }
}
