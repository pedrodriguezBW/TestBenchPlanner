package com.borgwarner.tbp.dominio.planificacion;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.borgwarner.tbp.dominio.ensayo.Ensayo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estado de la planificación: los ensayos de cada banco en orden de prioridad
 * (primero los activos, después los cerrados), igual que state.benches.
 *
 * Además recuerda el texto DataJson con el que se leyó cada fila, para poder
 * calcular exactamente qué filas hay que guardar tras una operación (lo que la
 * 4.9.4 hacía comparando con LB_LAST).
 *
 * Las filas de bancos que no existen en la configuración no se cargan, igual
 * que en la 4.9.4, y por tanto nunca se reescriben ni se borran.
 */
public final class Planificacion {

    private final ConfiguracionBancos configuracion;
    private final Map<String, List<Ensayo>> porBanco = new LinkedHashMap<>();
    private final Map<String, String> textoLeido = new LinkedHashMap<>();
    private final List<FilaGuardada> filasSinBanco = new ArrayList<>();

    private Planificacion(ConfiguracionBancos configuracion) {
        this.configuracion = configuracion;
        for (String banco : configuracion.bancos()) {
            porBanco.put(banco, new ArrayList<>());
        }
    }

    /** Construye el estado a partir de las filas guardadas (lbApply de la 4.9.4). */
    public static Planificacion desdeFilas(List<FilaGuardada> filas, ConfiguracionBancos configuracion) {
        Planificacion plan = new Planificacion(configuracion);
        record Leida(FilaGuardada fila, ObjectNode datos) {
        }
        List<Leida> validas = new ArrayList<>();
        for (FilaGuardada fila : filas) {
            ObjectNode datos = JsonJs.leerObjeto(fila.texto());
            if (fila.uid() == null || fila.uid().isEmpty() || datos == null || !ValorJs.verdadero(datos.get("_bench"))) {
                plan.filasSinBanco.add(fila);
                continue;
            }
            validas.add(new Leida(fila, datos));
        }
        // Orden global por _seq, estable respecto al orden de lectura.
        validas.sort(Comparator.comparingDouble(l -> ValorJs.numeroOCero(l.datos().get("_seq"))));
        for (Leida leida : validas) {
            String banco = ValorJs.texto(leida.datos().get("_bench"));
            List<Ensayo> lista = plan.porBanco.get(banco);
            if (lista == null) {
                plan.filasSinBanco.add(leida.fila());
                continue;
            }
            ObjectNode datos = leida.datos();
            datos.remove("_bench");
            datos.remove("_seq");
            datos.set("id", TextNode.valueOf(leida.fila().uid()));
            lista.add(new Ensayo(datos));
            plan.textoLeido.put(leida.fila().uid(), leida.fila().texto());
        }
        return plan;
    }

    /**
     * La misma planificación con otra configuración de bancos (Test Benches de la
     * 4.9.4). Cada banco nuevo se queda con los ensayos de su nombre o, si se ha
     * renombrado, con los del nombre anterior (renameBench conserva los ensayos).
     * Los ensayos de un banco que ya no existe desaparecen y se borrarán al
     * guardar (delBench / delCategory). Lo leído y las filas sin banco se
     * conservan, así que solo cambia lo que de verdad cambia.
     *
     * @param renombrados nombre anterior → nombre nuevo
     */
    public Planificacion reconfigurar(ConfiguracionBancos nueva, Map<String, String> renombrados) {
        Map<String, String> anteriorDe = new LinkedHashMap<>();
        renombrados.forEach((anterior, nuevo) -> anteriorDe.put(nuevo, anterior));
        Planificacion otra = new Planificacion(nueva);
        for (String banco : nueva.bancos()) {
            if (renombrados.containsKey(banco) && !anteriorDe.containsKey(banco)) {
                // Su nombre lo usa ahora un banco nuevo: sus ensayos se fueron con el renombrado.
                continue;
            }
            String origen = anteriorDe.getOrDefault(banco, banco);
            List<Ensayo> lista = porBanco.get(origen);
            if (lista != null) {
                otra.porBanco.get(banco).addAll(lista);
            }
        }
        otra.textoLeido.putAll(textoLeido);
        otra.filasSinBanco.addAll(filasSinBanco);
        return otra;
    }

    /**
     * Planificación a partir de una copia de seguridad JSON (importJSON de la
     * 4.9.4): los ensayos de cada banco de la copia sustituyen a los actuales.
     * Lo leído de la planificación actual se conserva para saber qué filas
     * cambian, cuáles son nuevas y cuáles desaparecen (se borrarán al guardar).
     * Las filas que ya estaban sin banco no se tocan.
     *
     * @param ensayos banco → DataJson de sus ensayos, en orden (sin _bench ni _seq)
     */
    public static Planificacion desdeCopia(ConfiguracionBancos configuracion, Map<String, List<ObjectNode>> ensayos, Planificacion actual) {
        Planificacion copia = new Planificacion(configuracion);
        for (String banco : configuracion.bancos()) {
            for (ObjectNode datos : ensayos.getOrDefault(banco, List.of())) {
                copia.porBanco.get(banco).add(new Ensayo(datos));
            }
        }
        copia.textoLeido.putAll(actual.textoLeido);
        copia.filasSinBanco.addAll(actual.filasSinBanco);
        return copia;
    }

    public ConfiguracionBancos configuracion() {
        return configuracion;
    }

    public List<String> bancos() {
        return configuracion.bancos();
    }

    /** Ensayos del banco (activos y cerrados) en su orden. Lista modificable. */
    public List<Ensayo> ensayos(String banco) {
        List<Ensayo> lista = porBanco.get(banco);
        if (lista == null) {
            throw new IllegalArgumentException("El banco «" + banco + "» no existe");
        }
        return lista;
    }

    public boolean existeBanco(String banco) {
        return porBanco.containsKey(banco);
    }

    public List<Ensayo> activos(String banco) {
        return ensayos(banco).stream().filter(e -> !e.cerrado()).toList();
    }

    public List<Ensayo> cerrados(String banco) {
        return ensayos(banco).stream().filter(Ensayo::cerrado).toList();
    }

    /** Sustituye la lista del banco (las operaciones de cola trabajan así). */
    public void reemplazar(String banco, List<Ensayo> nuevaLista) {
        List<Ensayo> lista = ensayos(banco);
        lista.clear();
        lista.addAll(nuevaLista);
    }

    /** Reordena el banco: activos primero y cerrados después, conservando el orden relativo. */
    public void activosPrimero(String banco) {
        List<Ensayo> lista = ensayos(banco);
        List<Ensayo> nueva = new ArrayList<>();
        lista.stream().filter(e -> !e.cerrado()).forEach(nueva::add);
        lista.stream().filter(Ensayo::cerrado).forEach(nueva::add);
        reemplazar(banco, nueva);
    }

    public Optional<Ensayo> buscar(String banco, String id) {
        return ensayos(banco).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Banco en el que está un ensayo, si está en alguno. */
    public Optional<String> bancoDe(String id) {
        for (Map.Entry<String, List<Ensayo>> entrada : porBanco.entrySet()) {
            for (Ensayo e : entrada.getValue()) {
                if (e.id().equals(id)) {
                    return Optional.of(entrada.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** Filas que no se han podido colocar (banco desconocido o DataJson no válido). */
    public List<FilaGuardada> filasSinBanco() {
        return Collections.unmodifiableList(filasSinBanco);
    }

    /** Texto con el que se leyó la fila (null si es nueva). */
    public String textoLeido(String uid) {
        return textoLeido.get(uid);
    }

    // ---- serialización (lbBuild) --------------------------------------------

    /**
     * Texto DataJson de un ensayo en una posición del banco. Igual que lbBuild:
     * {_bench, _seq, _benchOrg} seguidos de los campos del ensayo. Si el ensayo
     * ya traía _benchOrg se conserva su valor (la 4.9.4 no lo actualiza nunca).
     */
    public String textoParaGuardar(String banco, int posicion, Ensayo e) {
        ObjectNode salida = JsonJs.objetoVacio();
        salida.set("_bench", TextNode.valueOf(banco));
        salida.set("_seq", IntNode.valueOf(posicion + 1));
        JsonNode organizacion = e.tiene("_benchOrg") ? e.campo("_benchOrg") : TextNode.valueOf(configuracion.organizacion(banco));
        salida.set("_benchOrg", organizacion);
        e.datos().fields().forEachRemaining(campo -> {
            if (!campo.getKey().equals("_benchOrg")) {
                salida.set(campo.getKey(), campo.getValue());
            }
        });
        return JsonJs.escribir(salida);
    }

    /** Texto de todas las filas tal como se guardarían ahora (uid -> DataJson). */
    public Map<String, String> textosActuales() {
        Map<String, String> textos = new LinkedHashMap<>();
        for (String banco : configuracion.bancos()) {
            List<Ensayo> lista = porBanco.get(banco);
            for (int i = 0; i < lista.size(); i++) {
                textos.put(lista.get(i).id(), textoParaGuardar(banco, i, lista.get(i)));
            }
        }
        return textos;
    }

    /**
     * Filas que hay que guardar respecto a lo leído (lbPush): las que han
     * cambiado de texto, las nuevas y las que han desaparecido.
     */
    public List<CambioFila> cambiosPendientes() {
        Map<String, String> actuales = textosActuales();
        List<CambioFila> cambios = new ArrayList<>();
        actuales.forEach((uid, texto) -> {
            String anterior = textoLeido.get(uid);
            if (!texto.equals(anterior)) {
                cambios.add(new CambioFila(uid, texto, anterior));
            }
        });
        textoLeido.forEach((uid, anterior) -> {
            if (!actuales.containsKey(uid)) {
                cambios.add(new CambioFila(uid, null, anterior));
            }
        });
        return cambios;
    }

    /** Marca los cambios como guardados (el nuevo texto pasa a ser «lo leído»). */
    public void confirmarGuardado(List<CambioFila> cambios) {
        for (CambioFila cambio : cambios) {
            if (cambio.esBorrado()) {
                textoLeido.remove(cambio.uid());
            } else {
                textoLeido.put(cambio.uid(), cambio.textoNuevo());
            }
        }
    }
}
