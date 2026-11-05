package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.microsoft.playwright.BrowserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Columnas «espejo» de la lista principal (safe_body del backend 4.9.4).
 *
 * La aplicación no las lee: solo sirven para filtrar y crear vistas en
 * SharePoint. Se escriben en segundo plano, agrupando los cambios de 2 s y en
 * lotes de 5 filas con la prioridad más baja, para no retrasar los guardados.
 */
public class EspejoSharePoint {

    private static final Logger LOG = LoggerFactory.getLogger(EspejoSharePoint.class);
    private static final long ESPERA_MS = 2000;
    private static final int LOTE = 5;
    private static final Map<String, String> ETIQUETA_MANUAL = Map.of("ok", "on_track", "risk", "delayed", "delayed", "delayed");

    private final ClienteSharePoint cliente;
    private final AprobacionesSharePoint aprobaciones;
    private final boolean habilitado;
    private final Map<String, String> pendientes = new LinkedHashMap<>();
    private final Map<String, Integer> idsPendientes = new HashMap<>();
    private volatile boolean choiceEnLinea = true;
    private Thread trabajador;

    public EspejoSharePoint(ClienteSharePoint cliente, AprobacionesSharePoint aprobaciones, boolean habilitado) {
        this.cliente = cliente;
        this.aprobaciones = aprobaciones;
        this.habilitado = habilitado;
    }

    /** Acumula filas (la última versión de cada uid gana) y despierta al trabajador. */
    public synchronized void encolar(List<CambioFila> cambios, Map<String, Integer> ids) {
        if (!habilitado || cambios.isEmpty() || !cliente.escrituraHabilitada()) {
            return;
        }
        for (CambioFila c : cambios) {
            pendientes.put(c.uid(), c.textoNuevo());
            Integer id = ids.get(c.uid());
            if (id != null) {
                idsPendientes.put(c.uid(), id);
            }
        }
        if (trabajador == null || !trabajador.isAlive()) {
            trabajador = new Thread(this::bucle, "mirror-worker");
            trabajador.setDaemon(true);
            trabajador.start();
        }
        notifyAll();
    }

    private void bucle() {
        while (true) {
            Map<String, String> lote;
            Map<String, Integer> ids;
            synchronized (this) {
                while (pendientes.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            try {
                Thread.sleep(ESPERA_MS); // ventana de fusión
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                lote = new LinkedHashMap<>(pendientes);
                ids = new HashMap<>(idsPendientes);
                pendientes.clear();
                idsPendientes.clear();
            }
            final Map<String, Integer> idsLote = ids;
            List<Map.Entry<String, String>> filas = new ArrayList<>(lote.entrySet());
            for (int i = 0; i < filas.size(); i += LOTE) {
                List<Map.Entry<String, String>> trozo = filas.subList(i, Math.min(filas.size(), i + LOTE));
                try {
                    cliente.conSesion(ConexionEdge.PRIORIDAD_FONDO, contexto -> {
                        String digest = cliente.digest(contexto);
                        trozo.forEach(fila -> espejar(contexto, digest, fila.getKey(), fila.getValue(), idsLote.get(fila.getKey())));
                        return null;
                    });
                } catch (RuntimeException e) {
                    LOG.warn("Espejo: lote fallido {}", e.getMessage());
                }
            }
        }
    }

    private void espejar(BrowserContext contexto, String digest, String uid, String texto, Integer id) {
        if (id == null) {
            return;
        }
        ObjectNode fila = JsonJs.leerObjeto(texto);
        if (fila == null) {
            return;
        }
        String banco = ValorJs.texto(fila.get("_bench"));
        Map<String, String> cache = new HashMap<>();
        String estadoAprobacion = ValorJs.verdadero(fila.get("approvalId"))
                ? aprobaciones.estadoTexto(contexto, ValorJs.texto(fila.get("approvalId")), cache) : null;
        ObjectNode cuerpo = cuerpoEspejo(banco, fila.get("_seq"), fila, estadoAprobacion);
        ObjectNode choices = JsonJs.objetoVacio();
        ponerSi(choices, "Bench", banco);
        ponerSi(choices, "Mode", ValorJs.texto(fila.get("durMode")));
        ponerSi(choices, "Status", estadoGrueso(fila));
        ponerSi(choices, "ApprovalStatus", estadoAprobacion);
        String base = cliente.lista(AlmacenEnsayosSharePoint.LISTA);
        boolean hecho = false;
        if (choiceEnLinea && choices.size() > 0) {
            ObjectNode todo = cuerpo.deepCopy();
            todo.setAll(choices);
            if (cliente.merge(contexto, digest, base, id, todo, "*").ok()) {
                hecho = true;
            } else {
                // Un Choice con un valor no permitido tumba el MERGE entero: modo separado desde ahora.
                choiceEnLinea = false;
            }
        }
        if (!hecho) {
            cliente.merge(contexto, digest, base, id, cuerpo, "*");
            choices.fields().forEachRemaining(c -> {
                ObjectNode uno = JsonJs.objetoVacio();
                uno.set(c.getKey(), c.getValue());
                cliente.merge(contexto, digest, base, id, uno, "*");
            });
        }
    }

    private static void ponerSi(ObjectNode destino, String campo, String valor) {
        if (valor != null && !valor.isEmpty()) {
            destino.put(campo, valor);
        }
    }

    /** _coarse_status: basado en el campo legado launchConfirmed (D13, se conserva). */
    static String estadoGrueso(JsonNode fila) {
        if (ValorJs.verdadero(fila.get("done"))) {
            return "completed";
        }
        JsonNode lanzado = fila.get("launchConfirmed");
        if (ValorJs.verdadero(lanzado)) {
            try {
                LocalDate dia = LocalDate.parse(lanzado.asText().substring(0, 10));
                return dia.isAfter(LocalDate.now()) ? "confirmed" : "running";
            } catch (RuntimeException e) {
                return "confirmed";
            }
        }
        return "queued";
    }

    /** _manual_status: estado legible para las vistas de SharePoint. */
    static String estadoManual(JsonNode fila) {
        if (ValorJs.verdadero(fila.get("done"))) {
            String fin = ValorJs.verdadero(fila.get("finalStatus")) ? fila.get("finalStatus").asText().trim().toLowerCase() : "ok";
            return "completed_" + ETIQUETA_MANUAL.getOrDefault(fin, "on_track");
        }
        boolean arrancado = ValorJs.verdadero(fila.get("actualStart"));
        if (!arrancado && !ValorJs.verdadero(fila.get("confirmedStart"))) {
            return "queued";
        }
        String st = ValorJs.verdadero(fila.get("manualState")) ? fila.get("manualState").asText().trim().toLowerCase() : "ok";
        if (st.equals("ok")) {
            return arrancado ? "running" : "confirmed";
        }
        return ETIQUETA_MANUAL.getOrDefault(st, "running");
    }

    /** safe_body: columnas espejo de una fila. */
    static ObjectNode cuerpoEspejo(String banco, JsonNode seq, JsonNode r, String estadoAprobacion) {
        ObjectNode b = JsonJs.objetoVacio();
        b.set("TestType", textoONulo(r, "type"));
        b.set("Project", textoONulo(r, "project"));
        b.set("ApprovalID", textoONulo(r, "approvalId"));
        b.set("Priority", seq);
        b.set("Seq", seq);
        b.put("WeekendRun", ValorJs.verdadero(r.get("weekend")));
        b.put("Done", ValorJs.verdadero(r.get("done")));
        b.set("ResponsibleName", textoONulo(r, "resp"));
        b.set("Comments", textoONulo(r, "comments"));
        b.set("SamplesAvail", fecha(r, "avail"));
        b.set("LaunchConfirmed", fecha(r, "launchConfirmed"));
        b.set("StartOverride", fecha(r, "startOverride"));
        b.set("DurManual", numero(r.get("durManual")));
        b.set("ManualUnit", textoONulo(r, "manualUnit"));
        b.set("Cycles", numero(r.get("cycles")));
        b.set("CycleVal", numero(r.get("cycleVal")));
        b.set("CycleUnit", textoONulo(r, "cycleUnit"));
        b.put("InputsReady", r.has("inputs") ? ValorJs.verdadero(r.get("inputs")) : true);
        b.set("ProgressActual", numero(r.get("progressActual")));
        b.set("LastUpdate", fecha(r, "lastUpdate"));
        b.set("HistStart", fecha(r, "histStart"));
        b.set("HistEnd", fecha(r, "histEnd"));
        b.set("DoneDate", fecha(r, "doneDate"));
        b.set("ApprovalStatusText", estadoAprobacion == null ? NullNode.getInstance() : TextNode.valueOf(estadoAprobacion));
        b.set("BenchText", banco == null || banco.isEmpty() ? NullNode.getInstance() : TextNode.valueOf(banco));
        b.set("ModeText", textoONulo(r, "durMode"));
        b.put("StatusText", estadoGrueso(r));
        b.set("DelayReason", textoONulo(r, "delayReason"));
        b.set("ActualStart", fecha(r, "actualStart"));
        b.set("Progress100Date", fecha(r, "progress100Date"));
        b.set("ManualState", ValorJs.verdadero(r.get("manualState")) ? r.get("manualState") : TextNode.valueOf("ok"));
        b.put("StatusManual", estadoManual(r));
        b.set("FinalStatus", textoONulo(r, "finalStatus"));
        b.set("FinalReason", textoONulo(r, "finalReason"));
        b.set("FinalBy", textoONulo(r, "finalBy"));
        b.set("FinalActualStart", fecha(r, "finalActualStart"));
        b.set("FinalCommittedEnd", fecha(r, "finalCommittedEnd"));
        b.set("TrueEnd", fecha(r, "trueEnd"));
        b.set("DowntimeDays", numero(r.get("downtimeDays")));
        b.set("DowntimeNote", textoONulo(r, "downtimeNote"));
        b.set("FinalTrueStart", fecha(r, "finalTrueStart"));
        b.set("FinalTrueEnd", fecha(r, "finalTrueEnd"));
        b.set("FinalDelayDays", numero(r.get("finalDelayDays")));
        b.set("FinalReasonCode", textoONulo(r, "finalReasonCode"));
        b.set("FinalComment", textoONulo(r, "finalComment"));
        b.set("FinalDowntimeDays", numero(r.get("finalDowntimeDays")));
        b.set("FinalConfirmedBy", textoONulo(r, "finalConfirmedBy"));
        b.set("FinalConfirmedAt", fecha(r, "finalConfirmedAt"));
        b.set("BenchOrg", textoONulo(r, "_benchOrg"));
        b.set("Contingency", numero(r.get("cf")));
        return b;
    }

    /** row.get(x) or None */
    private static JsonNode textoONulo(JsonNode r, String campo) {
        JsonNode v = r.get(campo);
        return ValorJs.verdadero(v) ? v : NullNode.getInstance();
    }

    /** _dt: el valor tal cual o null. */
    private static JsonNode fecha(JsonNode r, String campo) {
        return textoONulo(r, campo);
    }

    /** _num: '' o null -> null; texto -> float. */
    private static JsonNode numero(JsonNode v) {
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
        if (v.isBoolean()) {
            return BooleanNode.valueOf(v.booleanValue());
        }
        return v;
    }
}
