package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;

import java.util.ArrayList;
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
 */
public class AlmacenEnsayosSharePoint implements AlmacenEnsayos {

    public static final String LISTA = "TestBenchPlanner";

    private final ClienteSharePoint cliente;
    private final Colaboracion colaboracion;
    private final EspejoSharePoint espejo;
    /** Caché uid -> Id de SharePoint (los Id son estables). */
    private final Map<String, Integer> ids = new ConcurrentHashMap<>();

    public AlmacenEnsayosSharePoint(ClienteSharePoint cliente, Colaboracion colaboracion, EspejoSharePoint espejo) {
        this.cliente = cliente;
        this.colaboracion = colaboracion;
        this.espejo = espejo;
    }

    @Override
    public List<FilaGuardada> leer() {
        return cliente.conSesion(ConexionEdge.PRIORIDAD_INTERFAZ, contexto -> {
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
        ResumenGuardado resumen = cliente.conSesion(ConexionEdge.PRIORIDAD_GUARDADO, contexto -> {
            String digest = cliente.digest(contexto);
            return comprobarVersion ? guardarConVersion(contexto, digest, cambios) : guardarUltimoGana(contexto, digest, cambios);
        });
        if (resumen.creados() + resumen.actualizados() + resumen.borrados() > 0) {
            colaboracion.anotarActividad(actor.email(), actor.nombre(), "edit", "created " + resumen.creados()
                    + ", updated " + resumen.actualizados() + ", deleted " + resumen.borrados());
            espejo.encolar(cambios.stream().filter(c -> !c.esBorrado()).toList(), ids);
        }
        return resumen;
    }

    private ResumenGuardado guardarConVersion(BrowserContext contexto, String digest, List<CambioFila> cambios) {
        // 1) Comprobar TODAS las filas antes de escribir ninguna.
        List<Elemento> actuales = new ArrayList<>();
        List<ConflictoEdicion.FilaEnConflicto> conflictos = new ArrayList<>();
        for (CambioFila cambio : cambios) {
            Elemento actual = elemento(contexto, cambio.uid());
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
