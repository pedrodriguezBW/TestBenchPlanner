package com.borgwarner.tbp.dominio.ensayo;

import com.borgwarner.tbp.dominio.comun.ValorJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Un ensayo tal y como se guarda en DataJson.
 *
 * Decisión de diseño: los datos se guardan en un objeto JSON ordenado en vez de
 * en una clase con un campo por propiedad. Motivos:
 *   - DataJson contiene campos que esta versión no usa (legados como launched,
 *     kpiCall…, o futuros de otras versiones) y deben conservarse intactos.
 *   - El orden de las claves forma parte del texto guardado y la 4.9.4 lo
 *     compara literalmente (ver JsonJs).
 *   - Los tipos están mezclados (número o texto) y se interpretan con ValorJs.
 * Los métodos con nombre dan acceso legible a los campos que usan las reglas.
 *
 * El objeto NO incluye _bench ni _seq: se añaden al construir el texto para
 * guardar (SerializadorDataJson), igual que hacía la 4.9.4.
 */
public final class Ensayo {

    private final ObjectNode datos;

    public Ensayo(ObjectNode datos) {
        this.datos = datos;
    }

    public ObjectNode datos() {
        return datos;
    }

    public Ensayo copia() {
        return new Ensayo(datos.deepCopy());
    }

    // ---- acceso genérico ---------------------------------------------------

    /** Valor del campo o null si no existe (undefined). */
    public JsonNode campo(String nombre) {
        return datos.get(nombre);
    }

    public boolean tiene(String nombre) {
        return datos.has(nombre);
    }

    /** r.campo || '' */
    public String texto(String nombre) {
        return ValorJs.texto(datos.get(nombre));
    }

    /** if (r.campo) */
    public boolean verdadero(String nombre) {
        return ValorJs.verdadero(datos.get(nombre));
    }

    /** Asigna conservando la posición de la clave si ya existía (como en JavaScript). */
    public void poner(String nombre, JsonNode valor) {
        datos.set(nombre, valor == null ? NullNode.getInstance() : valor);
    }

    public void poner(String nombre, String valor) {
        poner(nombre, valor == null ? NullNode.getInstance() : TextNode.valueOf(valor));
    }

    public void poner(String nombre, boolean valor) {
        poner(nombre, BooleanNode.valueOf(valor));
    }

    public void ponerNumero(String nombre, double valor) {
        poner(nombre, ValorJs.nodoNumero(valor));
    }

    /** delete r.campo */
    public void quitar(String nombre) {
        datos.remove(nombre);
    }

    // ---- campos con nombre -------------------------------------------------

    public String id() {
        return texto("id");
    }

    public boolean cerrado() {
        return verdadero("done");
    }

    public String tipo() {
        return texto("type");
    }

    public boolean finDeSemana() {
        return verdadero("weekend");
    }

    /** Tipos especiales: bloquean el banco y no cuentan como carga. */
    public boolean esTipoEspecial() {
        return TiposEspeciales.contiene(tipo());
    }

    @Override
    public String toString() {
        return "Ensayo[" + id() + "]";
    }
}
