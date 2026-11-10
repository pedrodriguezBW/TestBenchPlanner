package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regresión de la lentitud de los estados de aprobación: antes una petición por
 * Approval ID, en serie y delante de todo; ahora por lotes, recordados unos
 * segundos y con el MISMO resultado por ID que antes.
 */
class AprobacionesSharePointTest {

    private ClienteSharePoint cliente;
    private final AtomicLong ahora = new AtomicLong(1_000_000);
    private AprobacionesSharePoint aprobaciones;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        cliente = mock(ClienteSharePoint.class);
        BrowserContext contexto = mock(BrowserContext.class);
        when(cliente.consultar(anyInt(), any())).thenAnswer(inv -> ((Function<BrowserContext, Object>) inv.getArgument(1)).apply(contexto));
        ObjectNode campos = JsonJs.objetoVacio();
        campos.putArray("value").addObject().put("InternalName", "Status").put("Title", "Global status");
        when(cliente.get(any(), anyString())).thenReturn(campos);
        // Solo existen los Id pares; el 8 está pendiente y el resto aprobados.
        when(cliente.leerPaginado(any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(1);
            List<JsonNode> filas = new ArrayList<>();
            Matcher ids = Pattern.compile("Id eq (\\d+)").matcher(url);
            while (ids.find()) {
                int id = Integer.parseInt(ids.group(1));
                if (id % 2 == 0) {
                    filas.add(JsonJs.objetoVacio().put("Id", id).put("Status", id == 8 ? "Pending" : "Approved"));
                }
            }
            return filas;
        });
        aprobaciones = new AprobacionesSharePoint(cliente, "https://aprobaciones", "Services approvals database V2", ahora::get);
    }

    private static List<JsonNode> ids(String... valores) {
        List<JsonNode> salida = new ArrayList<>();
        for (String v : valores) {
            salida.add(TextNode.valueOf(v));
        }
        return salida;
    }

    private static List<JsonNode> muchos() {
        List<JsonNode> salida = new ArrayList<>();
        for (int i = 1; i <= 121; i++) {
            salida.add(TextNode.valueOf(String.valueOf(i)));
        }
        salida.add(TextNode.valueOf("sin-id"));
        salida.add(TextNode.valueOf("ID 8 / prueba"));
        return salida;
    }

    @Test
    void muchosIdsSeLeenEnLotesYNoUnoAUno() {
        ObjectNode salida = aprobaciones.estados(muchos());
        assertEquals(123, salida.size());
        verify(cliente, times(3)).leerPaginado(any(), anyString(), anyString(), anyString()); // 50 + 50 + 21
        verify(cliente, never()).get(any(), org.mockito.ArgumentMatchers.contains("/items("));
    }

    @Test
    void elResultadoDeCadaIdEsElDeSiempre() {
        ObjectNode salida = aprobaciones.estados(ids("2", "3", "sin-id", "ID 8 / prueba"));
        ObjectNode aprobado = (ObjectNode) salida.get("2");
        assertEquals(List.of("input", "resolvedId", "status", "color", "raw", "requester", "service"), nombres(aprobado));
        assertEquals("2", aprobado.path("input").asText());
        assertEquals(2, aprobado.path("resolvedId").asInt());
        assertEquals("approved", aprobado.path("status").asText());
        assertEquals("green", aprobado.path("color").asText());
        assertEquals("Approved", aprobado.path("raw").asText());

        ObjectNode inexistente = (ObjectNode) salida.get("3");
        assertEquals(List.of("input", "resolvedId", "status", "color", "raw"), nombres(inexistente));
        assertEquals(3, inexistente.path("resolvedId").asInt());
        assertEquals("not_created", inexistente.path("status").asText());
        assertTrue(inexistente.path("raw").isNull());

        ObjectNode sinNumero = (ObjectNode) salida.get("sin-id");
        assertTrue(sinNumero.path("resolvedId").isNull());
        assertEquals("not_created", sinNumero.path("status").asText());

        ObjectNode conTexto = (ObjectNode) salida.get("ID 8 / prueba");
        assertEquals("ID 8 / prueba", conTexto.path("input").asText());
        assertEquals("pending", conTexto.path("status").asText());
        assertEquals("amber", conTexto.path("color").asText());
    }

    private static List<String> nombres(ObjectNode nodo) {
        List<String> salida = new ArrayList<>();
        nodo.fieldNames().forEachRemaining(salida::add);
        return salida;
    }

    @Test
    void losEstadosSeRecuerdanVeinteSegundos() {
        aprobaciones.estados(ids("2", "4"));
        aprobaciones.estados(ids("4", "2"));
        verify(cliente, times(1)).leerPaginado(any(), anyString(), anyString(), anyString());
        ahora.addAndGet(AprobacionesSharePoint.VIGENCIA_ESTADO_MS);
        aprobaciones.estados(ids("2", "4"));
        verify(cliente, times(2)).leerPaginado(any(), anyString(), anyString(), anyString());
    }

    @Test
    void siLaPestanaSeHaCerradoNoSePidenMasLotes() {
        Cancelacion.Testigo pestana = new Cancelacion.Testigo();
        pestana.cancelar();
        assertThrows(PeticionCancelada.class, () -> Cancelacion.con(pestana, () -> aprobaciones.estados(muchos())));
        verify(cliente, never()).leerPaginado(any(), anyString(), anyString(), anyString());
    }

    @Test
    void siFallaElLoteSeResuelveCadaIdComoAntes() {
        when(cliente.leerPaginado(any(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("400"));
        ObjectNode item = JsonJs.objetoVacio().put("Id", 6).put("Status", "Rejected");
        when(cliente.get(any(), org.mockito.ArgumentMatchers.contains("/items(6)"))).thenReturn(item);
        ObjectNode salida = aprobaciones.estados(ids("6"));
        assertEquals("rejected", salida.path("6").path("status").asText());
        assertEquals("6", salida.path("6").path("input").asText());
        assertFalse(salida.path("6").has("error"));
    }

    @Test
    void extraeElPrimerNumeroComoLaVersion494() {
        assertEquals(123, AprobacionesSharePoint.numeroDe("ID 123 / prueba"));
        assertNull(AprobacionesSharePoint.numeroDe("sin identificador"));
        assertNull(AprobacionesSharePoint.numeroDe(null));
    }
}
