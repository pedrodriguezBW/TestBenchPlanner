package com.borgwarner.tbp.infraestructura.local;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.listas.ProteccionListas;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.borgwarner.tbp.infraestructura.comun.FilaKpi;
import com.borgwarner.tbp.infraestructura.comun.Versionado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Implementaciones locales de los almacenes auxiliares (catálogos, KPI,
 * usuarios, recursos, aprobaciones, presencia, registro y versión). Son
 * sencillas a propósito: sirven para desarrollar y probar sin SharePoint.
 */
public final class AlmacenesAuxiliaresLocales {

    private AlmacenesAuxiliaresLocales() {
    }

    /** Claves no vacías de una lista: el texto de cada elemento o el de su campo `campo`. */
    static List<String> textos(JsonNode lista, String campo) {
        List<String> claves = new ArrayList<>();
        for (JsonNode n : lista) {
            String clave = campo == null ? n.asText("") : n.path(campo).asText("");
            if (!clave.isEmpty()) {
                claves.add(clave);
            }
        }
        return claves;
    }

    /** Catálogos en configuracion.json. Guardar sustituye (equivale a reconciliar). */
    public static class Catalogos implements AlmacenCatalogos {
        private final DatosLocales datos;
        private final boolean permitirVacias;

        public Catalogos(DatosLocales datos, boolean permitirVacias) {
            this.datos = datos;
            this.permitirVacias = permitirVacias;
        }

        @Override
        public ObjectNode leer() {
            ObjectNode actual = (ObjectNode) datos.leer("configuracion.json", JsonJs.objetoVacio()).deepCopy();
            for (String lista : List.of("responsibles", "projects", "benches")) {
                if (!actual.has(lista)) {
                    actual.putArray(lista);
                }
            }
            return actual;
        }

        @Override
        public ObjectNode guardar(JsonNode catalogos) {
            ObjectNode actual = leer();
            // D11: se comprueban todas las listas antes de escribir nada.
            if (catalogos.path("responsibles").isArray() && catalogos.path("responsibles").size() > 0) {
                ProteccionListas.comprobar("TestBenchResponsibles", textos(actual.path("responsibles"), null),
                        textos(catalogos.path("responsibles"), null), permitirVacias);
            }
            ProteccionListas.comprobar("TestBenchProjects", textos(actual.path("projects"), null),
                    textos(catalogos.path("projects"), null), permitirVacias);
            ProteccionListas.comprobar("TestBenchBenches", textos(actual.path("benches"), "name"),
                    textos(catalogos.path("benches"), "name"), permitirVacias);
            ObjectNode resultado = JsonJs.objetoVacio().put("ok", true);
            // Responsables: congelada; solo se toca si llega una lista no vacía.
            if (catalogos.path("responsibles").isArray() && catalogos.path("responsibles").size() > 0) {
                actual.set("responsibles", catalogos.get("responsibles"));
            } else {
                resultado.putObject("responsibles").put("skipped", "frozen (people come from TestBenchUsers)");
            }
            actual.set("projects", catalogos.path("projects").isArray() ? catalogos.get("projects") : JsonJs.mapper().createArrayNode());
            ArrayNode bancos = JsonJs.mapper().createArrayNode();
            for (JsonNode b : catalogos.path("benches")) {
                if (b.hasNonNull("name") && !b.path("name").asText().isEmpty()) {
                    bancos.add(b);
                }
            }
            actual.set("benches", bancos);
            datos.escribir("configuracion.json", actual);
            return resultado;
        }
    }

    /** Histórico de KPI en kpis.json con las columnas de la lista. */
    public static class HistoricoKpi implements AlmacenHistoricoKpi {
        private final DatosLocales datos;

        public HistoricoKpi(DatosLocales datos) {
            this.datos = datos;
        }

        @Override
        public List<JsonNode> leer() {
            List<JsonNode> filas = new ArrayList<>();
            datos.leer("kpis.json", JsonJs.mapper().createArrayNode()).forEach(filas::add);
            filas.sort(Comparator.comparingDouble((JsonNode f) -> f.path("Year").asDouble(-1))
                    .thenComparingDouble(f -> f.path("CW").asDouble(-1)));
            return filas;
        }

        @Override
        public ObjectNode guardarFotos(List<ObjectNode> fotos) {
            ArrayNode filas = (ArrayNode) datos.leer("kpis.json", JsonJs.mapper().createArrayNode()).deepCopy();
            int creadas = 0, actualizadas = 0;
            for (ObjectNode foto : fotos) {
                ObjectNode fila = FilaKpi.desdeFoto(foto);
                boolean sustituida = false;
                for (int i = 0; i < filas.size(); i++) {
                    JsonNode f = filas.get(i);
                    if (f.path("Year").asDouble() == fila.path("Year").asDouble()
                            && f.path("CW").asDouble() == fila.path("CW").asDouble()
                            && f.path("Org").asText().equals(fila.path("Org").asText())) {
                        fila.remove("Title");
                        filas.set(i, fila);
                        sustituida = true;
                        actualizadas++;
                        break;
                    }
                }
                if (!sustituida) {
                    fila.remove("Title");
                    filas.add(fila);
                    creadas++;
                }
            }
            datos.escribir("kpis.json", filas);
            return JsonJs.objetoVacio().put("ok", true).put("created", creadas).put("updated", actualizadas);
        }
    }

    /** Usuarios en usuarios.json; la identidad es la configurada para este PC. */
    public static class Usuarios implements AlmacenUsuarios {
        private final DatosLocales datos;
        private final Identidad identidad;
        private final boolean permitirVacias;

        public Usuarios(DatosLocales datos, Identidad identidad, boolean permitirVacias) {
            this.datos = datos;
            this.identidad = identidad;
            this.permitirVacias = permitirVacias;
        }

        @Override
        public Identidad identidadActual() {
            return identidad;
        }

        @Override
        public ArrayNode leer() {
            ArrayNode salida = JsonJs.mapper().createArrayNode();
            for (JsonNode u : datos.leer("usuarios.json", JsonJs.mapper().createArrayNode())) {
                ObjectNode copia = salida.addObject();
                copia.put("title", u.path("title").asText(""));
                copia.put("email", u.path("email").asText("").trim().toLowerCase());
                copia.put("role", Usuario.normalizarRol(u.path("role").asText("")));
                copia.put("responsibleName", u.path("responsibleName").asText(""));
            }
            return salida;
        }

        @Override
        public ObjectNode guardar(JsonNode usuarios) {
            ArrayNode filas = JsonJs.mapper().createArrayNode();
            for (JsonNode u : usuarios.path("users")) {
                String email = u.path("email").asText("").trim().toLowerCase();
                if (email.isEmpty()) {
                    continue;
                }
                ObjectNode fila = filas.addObject();
                fila.put("title", u.path("title").asText(email).isEmpty() ? email : u.path("title").asText(email));
                fila.put("email", email);
                String rol = Usuario.normalizarRol(u.path("role").asText(""));
                fila.put("role", rol.equals("readonly") ? "ReadOnly" : Character.toUpperCase(rol.charAt(0)) + rol.substring(1));
                fila.put("responsibleName", u.path("responsibleName").asText(""));
            }
            ProteccionListas.comprobar("TestBenchUsers", textos(leer(), "email"), textos(filas, "email"), permitirVacias);
            datos.escribir("usuarios.json", filas);
            return JsonJs.objetoVacio().put("ok", true);
        }
    }

    /** Equipos auxiliares y bloqueos en recursos.json. */
    public static class Recursos implements AlmacenRecursos {
        private final DatosLocales datos;
        private final Colaboracion colaboracion;
        private final boolean permitirVacias;

        public Recursos(DatosLocales datos, Colaboracion colaboracion, boolean permitirVacias) {
            this.datos = datos;
            this.colaboracion = colaboracion;
            this.permitirVacias = permitirVacias;
        }

        @Override
        public ObjectNode leer() {
            ObjectNode r = (ObjectNode) datos.leer("recursos.json", JsonJs.objetoVacio()).deepCopy();
            if (!r.has("resources")) {
                r.putArray("resources");
            }
            if (!r.has("blocks")) {
                r.putArray("blocks");
            }
            return r;
        }

        @Override
        public ObjectNode guardarCatalogo(JsonNode recursos) {
            ObjectNode actual = leer();
            ArrayNode lista = JsonJs.mapper().createArrayNode();
            int i = 0;
            for (JsonNode r : recursos.path("resources")) {
                i++;
                if (!r.hasNonNull("name") || r.path("name").asText().isEmpty()) {
                    continue;
                }
                ObjectNode copia = r.deepCopy();
                if (!copia.hasNonNull("seq")) {
                    copia.put("seq", i);
                }
                lista.add(copia);
            }
            ProteccionListas.comprobar("TestBenchResources", textos(actual.path("resources"), "name"),
                    textos(lista, "name"), permitirVacias);
            actual.set("resources", lista);
            datos.escribir("recursos.json", actual);
            return JsonJs.objetoVacio().put("ok", true);
        }

        @Override
        public ObjectNode guardarBloqueo(JsonNode p, AlmacenEnsayos.Actor actor) {
            ObjectNode actual = leer();
            ArrayNode bloques = (ArrayNode) actual.get("blocks");
            int id = p.path("id").asInt(0);
            ObjectNode bloque = null;
            for (JsonNode b : bloques) {
                if (id != 0 && b.path("id").asInt() == id) {
                    bloque = (ObjectNode) b;
                }
            }
            if (bloque == null) {
                id = 1;
                for (JsonNode b : bloques) {
                    id = Math.max(id, b.path("id").asInt() + 1);
                }
                bloque = bloques.addObject();
                bloque.put("id", id);
                bloque.put("title", p.path("title").asText("BLK-" + Instant.now().toEpochMilli()));
            }
            bloque.put("resource", p.path("resource").asText(""));
            bloque.put("start", p.path("start").asText(""));
            bloque.put("end", p.path("end").asText(""));
            bloque.put("reason", p.path("reason").asText(""));
            bloque.put("note", p.path("note").asText(""));
            bloque.put("author", actor.nombre() != null && !actor.nombre().isEmpty() ? actor.nombre() : actor.email());
            datos.escribir("recursos.json", actual);
            colaboracion.anotarActividad(actor.email(), actor.nombre(), "res_block",
                    p.path("resource").asText() + " " + p.path("start").asText() + "->" + p.path("end").asText()
                            + " (" + p.path("reason").asText() + ")");
            return JsonJs.objetoVacio().put("ok", true).put("id", id).put("status", 204);
        }

        @Override
        public ObjectNode borrarBloqueo(Object id) {
            if (id == null) {
                return JsonJs.objetoVacio().put("ok", false).put("error", "sin id");
            }
            ObjectNode actual = leer();
            ArrayNode bloques = (ArrayNode) actual.get("blocks");
            for (int i = 0; i < bloques.size(); i++) {
                if (bloques.get(i).path("id").asText().equals(String.valueOf(id))) {
                    bloques.remove(i);
                    datos.escribir("recursos.json", actual);
                    return JsonJs.objetoVacio().put("ok", true);
                }
            }
            return JsonJs.objetoVacio().put("ok", false);
        }
    }

    /** Aprobaciones (solo lectura) desde aprobaciones.json. */
    public static class Aprobaciones implements ConsultaAprobaciones {
        private final DatosLocales datos;

        public Aprobaciones(DatosLocales datos) {
            this.datos = datos;
        }

        @Override
        public ArrayNode todas() {
            return (ArrayNode) datos.leer("aprobaciones.json", JsonJs.mapper().createArrayNode()).deepCopy();
        }

        @Override
        public ObjectNode estados(List<JsonNode> ids) {
            ArrayNode todas = todas();
            ObjectNode salida = JsonJs.objetoVacio();
            for (JsonNode id : ids) {
                String entrada = id.isNull() ? "" : id.asText();
                var numero = java.util.regex.Pattern.compile("\\d+").matcher(entrada);
                ObjectNode r = salida.putObject(entrada);
                r.put("input", entrada);
                if (!numero.find()) {
                    r.putNull("resolvedId");
                    r.put("status", "not_created").put("color", "red").putNull("raw");
                    continue;
                }
                int resuelto = Integer.parseInt(numero.group());
                r.put("resolvedId", resuelto);
                JsonNode encontrada = null;
                for (JsonNode a : todas) {
                    if (a.path("id").asInt() == resuelto) {
                        encontrada = a;
                    }
                }
                if (encontrada == null) {
                    r.put("status", "not_created").put("color", "red").putNull("raw");
                } else {
                    r.set("status", encontrada.get("status"));
                    r.set("color", encontrada.get("color"));
                    r.set("raw", encontrada.get("raw"));
                    r.set("requester", encontrada.get("requester"));
                    r.set("service", encontrada.get("service"));
                }
            }
            return salida;
        }
    }

    /** Presencia, registro y versión en modo local. */
    public static class ColaboracionLocal implements Colaboracion {
        private final DatosLocales datos;

        public ColaboracionLocal(DatosLocales datos) {
            this.datos = datos;
        }

        @Override
        public ObjectNode latidoPresencia(AlmacenUsuarios.Identidad identidad) {
            ObjectNode salida = JsonJs.objetoVacio().put("me", identidad.email());
            ObjectNode yo = salida.putArray("online").addObject();
            yo.put("email", identidad.email());
            yo.put("name", identidad.nombre());
            yo.put("initials", Presencia.iniciales(identidad.nombre(), identidad.email()));
            yo.put("color", Presencia.color(identidad.email()));
            yo.put("me", true);
            return salida;
        }

        @Override
        public boolean anotarActividad(String email, String nombre, String accion, String detalle) {
            try {
                ArrayNode filas = (ArrayNode) datos.leer("actividad.json", JsonJs.mapper().createArrayNode()).deepCopy();
                ObjectNode fila = filas.addObject();
                fila.put("email", email);
                fila.put("name", nombre == null || nombre.isEmpty() ? email : nombre);
                fila.put("action", accion);
                fila.put("detail", detalle);
                fila.put("when", Versionado.instanteSinMilisegundos(Instant.now()));
                datos.escribir("actividad.json", filas);
                return true;
            } catch (RuntimeException e) {
                return false; // el registro nunca debe impedir un guardado
            }
        }

        @Override
        public ObjectNode leerActividad(int maximo) {
            JsonNode filas = datos.leer("actividad.json", JsonJs.mapper().createArrayNode());
            ObjectNode salida = JsonJs.objetoVacio();
            ArrayNode lista = salida.putArray("rows");
            for (int i = filas.size() - 1; i >= 0 && lista.size() < maximo; i--) {
                lista.add(filas.get(i));
            }
            ArrayNode columnas = salida.putArray("columns");
            List.of("Action", "Detail", "DisplayName", "Email", "WhenTs").forEach(columnas::add);
            return salida;
        }

        @Override
        public String versionPublicada() {
            JsonNode v = datos.leer("version.json", JsonJs.objetoVacio()).get("published");
            return v == null || v.isNull() ? null : v.asText();
        }

        @Override
        public boolean publicarVersion(String version) {
            String actual = versionPublicada();
            if (actual != null && !actual.isEmpty() && Versionado.comparar(version, actual) <= 0) {
                return true; // nunca se rebaja la versión publicada
            }
            datos.escribir("version.json", JsonJs.objetoVacio().put("published", version));
            return true;
        }
    }
}
