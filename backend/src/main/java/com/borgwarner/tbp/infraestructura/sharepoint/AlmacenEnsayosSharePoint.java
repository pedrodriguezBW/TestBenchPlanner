package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.infraestructura.comun.LecturaCompartida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lista principal TestBenchPlanner en SharePoint: Title = uid, DataJson = ensayo.
 *
 * Dos modos de guardado:
 *  - Con comprobación de versión (interfaz nueva): antes de escribir cada fila
 *    se relee su DataJson y su ETag; si el texto ya no es el que vio el usuario
 *    hay conflicto. La escritura usa IF-MATCH con ese ETag, de modo que un
 *    cambio que llegue entre la lectura y la escritura también se detecta. Si
 *    el ETag ha cambiado pero el DataJson no (lo ha tocado el espejo), se
 *    reintenta: no es un conflicto real.
 *  - Sin comprobación (interfaz clásica 4.9.4 por /api/save): «el último gana»,
 *    exactamente como el backend Python (IF-MATCH: *).
 *
 * Rendimiento:
 *  - La lectura completa va por un carril de lectura y se COMPARTE entre las
 *    peticiones que llegan a la vez (al arrancar o al recargar la página no se
 *    lee la lista dos veces). Al guardar se invalida: nadie recibe una lectura
 *    empezada antes de su propio guardado.
 *  - La comprobación previa de versiones lee todas las filas afectadas en una
 *    sola consulta (antes, una por fila).
 *  - El registro de actividad se anota en segundo plano: el guardado no espera.
 */
public class AlmacenEnsayosSharePoint implements AlmacenEnsayos {

    public static final String LISTA = "TestBenchPlanner";

    private final ClienteSharePoint cliente;
    private final Colaboracion colaboracion;
    private final EspejoSharePoint espejo;
    /** Filas por consulta al comprobar versiones (la URL debe seguir siendo corta). */
    static final int FILAS_POR_CONSULTA = 20;

    /** Caché uid -> Id de SharePoint (los Id son estables). */
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    /** Lectura completa en curso, compartida (sin reutilizar resultados: siempre autoritativa). */
    private final LecturaCompartida<List<FilaGuardada>> lectura = new LecturaCompartida<>(0, List::copyOf);

    public AlmacenEnsayosSharePoint(ClienteSharePoint cliente, Colaboracion colaboracion, EspejoSharePoint espejo) {
        this.cliente = cliente;
        this.colaboracion = colaboracion;
        this.espejo = espejo;
    }

    @Override
    public List<FilaGuardada> leer() {
        return lectura.leer(this::leerDeSharePoint);
    }

    private List<FilaGuardada> leerDeSharePoint() {
        return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
            String url = cliente.lista(LISTA) + "/items?$select=Id,Title,DataJson&$top=2000";
            List<FilaGuardada> filas = new ArrayList<>();
            for (JsonNode it : cliente.leerPaginado(contexto, url, cliente.sitio(), ClienteSharePoint.JSON_SIN_METADATOS)) {
                String uid = it.path("Title").asText("");
                if (uid.isEmpty()) {
                    continue;
                }
                ids.put(uid, it.path("Id").asInt());
                JsonNode datos = it.get("DataJson");
                filas.add(new FilaGuardada(uid, datos == null || datos.isNull() ? null : datos.asText()));
            }
            return filas;
        });
    }

    /** Elemento actual: Id, DataJson y ETag (null si no existe). */
    private record Elemento(int id, String texto, String etag) {
    }

    /** Id, DataJson y ETag de varias filas con una consulta por cada FILAS_POR_CONSULTA (las que no existen no aparecen). */
    private Map<String, Elemento> elementos(BrowserContext contexto, List<String> uids) {
        Map<String, Elemento> salida = new LinkedHashMap<>();
        List<String> distintos = uids.stream().distinct().toList();
        for (int i = 0; i < distintos.size(); i += FILAS_POR_CONSULTA) {
            List<String> trozo = distintos.subList(i, Math.min(distintos.size(), i + FILAS_POR_CONSULTA));
            String filtro = String.join(" or ", trozo.stream().map(u -> "Title eq '" + u.replace("'", "''") + "'").toList());
            JsonNode valores = cliente.get(contexto, cliente.lista(LISTA) + "/items?$select=Id,Title,DataJson&$filter="
                    + filtro + "&$top=" + (trozo.size() * 5) + "&$orderby=Id asc", ClienteSharePoint.JSON_CON_ETAG).path("value");
            for (JsonNode it : valores) {
                String uid = it.path("Title").asText("");
                if (uid.isEmpty() || salida.containsKey(uid)) {
                    continue; // como $top=1: vale la primera fila con ese Title
                }
                JsonNode datos = it.get("DataJson");
                salida.put(uid, new Elemento(it.path("Id").asInt(), datos == null || datos.isNull() ? null : datos.asText(),
                        it.path("odata.etag").asText("*")));
            }
        }
        return salida;
    }

    private Elemento elemento(BrowserContext contexto, String uid) {
        String filtro = "Title eq '" + uid.replace("'", "''") + "'";
        JsonNode valores = cliente.get(contexto, cliente.lista(LISTA) + "/items?$select=Id,Title,DataJson&$filter="
                + filtro + "&$top=1", ClienteSharePoint.JSON_CON_ETAG).path("value");
        if (valores.size() == 0) {
            return null;
        }
        JsonNode it = valores.get(0);
        JsonNode datos = it.get("DataJson");
        return new Elemento(it.path("Id").asInt(), datos == null || datos.isNull() ? null : datos.asText(),
                it.path("odata.etag").asText("*"));
    }

    @Override
    public ResumenGuardado guardar(List<CambioFila> cambios, boolean comprobarVersion, Actor actor) {
        cliente.exigirEscritura("guardar ensayos");
        ResumenGuardado resumen;
        lectura.invalidar();
        try {
            resumen = cliente.conSesion(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> {
                String digest = cliente.digest(contexto);
                return comprobarVersion ? guardarConVersion(contexto, digest, cambios) : guardarUltimoGana(contexto, digest, cambios);
            });
        } finally {
            lectura.invalidar(); // las lecturas empezadas durante el guardado no se reparten
        }
        if (resumen.creados() + resumen.actualizados() + resumen.borrados() > 0) {
            colaboracion.anotarActividadSinEsperar(actor.email(), actor.nombre(), "edit", "created " + resumen.creados()
                    + ", updated " + resumen.actualizados() + ", deleted " + resumen.borrados());
            espejo.encolar(cambios.stream().filter(c -> !c.esBorrado()).toList(), ids);
        }
        return resumen;
    }

    private ResumenGuardado guardarConVersion(BrowserContext contexto, String digest, List<CambioFila> cambios) {
        // 1) Comprobar TODAS las filas antes de escribir ninguna (una consulta por cada 20 filas).
        Map<String, Elemento> leidos = elementos(contexto, cambios.stream().map(CambioFila::uid).toList());
        List<Elemento> actuales = new ArrayList<>();
        List<ConflictoEdicion.FilaEnConflicto> conflictos = new ArrayList<>();
        for (CambioFila cambio : cambios) {
            Elemento actual = leidos.get(cambio.uid());
            actuales.add(actual);
            String texto = actual == null ? null : actual.texto();
            boolean igual = cambio.textoAnterior() == null ? actual == null : cambio.textoAnterior().equals(texto);
            if (!igual) {
                conflictos.add(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), texto));
            }
        }
        if (!conflictos.isEmpty()) {
            throw new ConflictoEdicion(conflictos);
        }
        // 2) Escribir con IF-MATCH.
        int creados = 0, actualizados = 0, borrados = 0;
        for (int i = 0; i < cambios.size(); i++) {
            CambioFila cambio = cambios.get(i);
            Elemento actual = actuales.get(i);
            if (cambio.esBorrado()) {
                if (actual != null && cliente.reciclar(contexto, digest, cliente.lista(LISTA), actual.id())) {
                    borrados++;
                }
                ids.remove(cambio.uid());
            } else if (actual == null) {
                Integer id = cliente.crear(contexto, digest, cliente.lista(LISTA), cuerpo(cambio));
                if (id != null) {
                    ids.put(cambio.uid(), id);
                }
                creados++;
            } else {
                actualizarConEtag(contexto, digest, cambio, actual);
                actualizados++;
            }
        }
        return new ResumenGuardado(creados, actualizados, borrados);
    }

    private void actualizarConEtag(BrowserContext contexto, String digest, CambioFila cambio, Elemento actual) {
        Elemento objetivo = actual;
        for (int intento = 0; intento < 3; intento++) {
            ClienteSharePoint.Respuesta r = cliente.merge(contexto, digest, cliente.lista(LISTA), objetivo.id(), cuerpo(cambio), objetivo.etag());
            if (r.ok()) {
                return;
            }
            if (r.estado() != 412) {
                throw new IllegalStateException("actualizar DataJson " + r.estado() + ": " + r.cuerpo());
            }
            // ETag distinto: ¿ha cambiado DataJson o solo las columnas espejo?
            Elemento releido = elemento(contexto, cambio.uid());
            String texto = releido == null ? null : releido.texto();
            if (releido == null || !cambio.textoAnterior().equals(texto)) {
                throw new ConflictoEdicion(List.of(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), texto)));
            }
            objetivo = releido;
        }
        throw new IllegalStateException("No se pudo guardar " + cambio.uid() + " tras varios intentos");
    }

    /** Comportamiento exacto del backend 4.9.4 (do_save). */
    private ResumenGuardado guardarUltimoGana(BrowserContext contexto, String digest, List<CambioFila> cambios) {
        int creados = 0, actualizados = 0, borrados = 0;
        for (CambioFila cambio : cambios) {
            if (cambio.esBorrado()) {
                Integer id = idDe(contexto, cambio.uid());
                if (id != null && cliente.reciclar(contexto, digest, cliente.lista(LISTA), id)) {
                    borrados++;
                }
                ids.remove(cambio.uid());
                continue;
            }
            Integer id = idDe(contexto, cambio.uid());
            if (id == null) {
                id = cliente.crear(contexto, digest, cliente.lista(LISTA), cuerpo(cambio));
                if (id != null) {
                    ids.put(cambio.uid(), id);
                }
                creados++;
                continue;
            }
            ClienteSharePoint.Respuesta r = cliente.merge(contexto, digest, cliente.lista(LISTA), id, cuerpo(cambio), "*");
            if (!r.ok() && (r.estado() == 404 || r.estado() == 400)) {
                // El Id de la caché ya no vale: rebuscar; si no existe, se crea (como la 4.9.4).
                ids.remove(cambio.uid());
                Elemento fresco = elemento(contexto, cambio.uid());
                if (fresco == null) {
                    Integer nuevo = cliente.crear(contexto, digest, cliente.lista(LISTA), cuerpo(cambio));
                    if (nuevo != null) {
                        ids.put(cambio.uid(), nuevo);
                    }
                    actualizados++;
                    continue;
                }
                ids.put(cambio.uid(), fresco.id());
                r = cliente.merge(contexto, digest, cliente.lista(LISTA), fresco.id(), cuerpo(cambio), "*");
            }
            if (!r.ok()) {
                throw new IllegalStateException("actualizar DataJson " + r.estado() + ": " + r.cuerpo());
            }
            actualizados++;
        }
        return new ResumenGuardado(creados, actualizados, borrados);
    }

    private Integer idDe(BrowserContext contexto, String uid) {
        Integer id = ids.get(uid);
        if (id != null) {
            return id;
        }
        Elemento e = elemento(contexto, uid);
        if (e != null) {
            ids.put(uid, e.id());
            return e.id();
        }
        return null;
    }

    private static ObjectNode cuerpo(CambioFila cambio) {
        return JsonJs.objetoVacio().put("Title", cambio.uid()).put("DataJson", cambio.textoNuevo());
    }
}
