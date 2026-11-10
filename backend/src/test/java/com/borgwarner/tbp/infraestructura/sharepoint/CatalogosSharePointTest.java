package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regresión de la doble lectura de catálogos y usuarios al arrancar la interfaz. */
class CatalogosSharePointTest {

    @SuppressWarnings("unchecked")
    private static ClienteSharePoint cliente() {
        ClienteSharePoint cliente = mock(ClienteSharePoint.class);
        BrowserContext contexto = mock(BrowserContext.class);
        when(cliente.consultar(anyInt(), any())).thenAnswer(inv -> ((Function<BrowserContext, Object>) inv.getArgument(1)).apply(contexto));
        JsonNode fila = JsonJs.objetoVacio().put("Id", 1).put("Title", "Elemento").put("Email", "ana@ejemplo.local").put("Role", "Editor");
        when(cliente.leerLista(any(), anyString(), anyList())).thenReturn(List.of(fila));
        return cliente;
    }

    @Test
    void losCatalogosSeLeenUnaVezYCadaPeticionRecibeSuCopia() {
        ClienteSharePoint cliente = cliente();
        ListasAuxiliaresSharePoint.Catalogos catalogos = new ListasAuxiliaresSharePoint.Catalogos(cliente, false);
        var primera = catalogos.leer();
        ((ArrayNode) primera.get("projects")).add("Cambio local");
        var segunda = catalogos.leer();
        assertEquals(1, segunda.path("projects").size());
        assertEquals("Elemento", segunda.path("projects").get(0).asText());
        verify(cliente, times(1)).consultar(anyInt(), any());
        verify(cliente, times(3)).leerLista(any(), anyString(), anyList());
        // Leer nunca pasa por el carril de escritura.
        verify(cliente, never()).conSesion(anyInt(), any());
    }

    @Test
    void unaLecturaConAlgunaListaFallidaNoSeReutiliza() {
        ClienteSharePoint cliente = cliente();
        when(cliente.leerLista(any(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("403"))
                .thenReturn(List.of(JsonJs.objetoVacio().put("Id", 1).put("Title", "Elemento")));
        ListasAuxiliaresSharePoint.Catalogos catalogos = new ListasAuxiliaresSharePoint.Catalogos(cliente, false);
        assertEquals(true, catalogos.leer().has("responsibles_error"));
        assertEquals(false, catalogos.leer().has("responsibles_error"));
        verify(cliente, times(2)).consultar(anyInt(), any());
    }

    @Test
    void losUsuariosTambienSeComparten() {
        ClienteSharePoint cliente = cliente();
        ListasAuxiliaresSharePoint.Usuarios usuarios = new ListasAuxiliaresSharePoint.Usuarios(cliente, false);
        assertEquals("ana@ejemplo.local", usuarios.leer().get(0).path("email").asText());
        assertEquals("editor", usuarios.leer().get(0).path("role").asText());
        verify(cliente, times(1)).consultar(anyInt(), any());
    }
}
