package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresión del aviso «AsyncRequestNotUsableException: ServletOutputStream
 * failed to write … Se ha anulado una conexión establecida…» al recargar.
 */
class ManejadorErroresTest {

    @Test
    void unaRespuestaCanceladaSeDescartaSinIntentarEscribirOtra() {
        ManejadorErrores manejador = new ManejadorErrores();
        assertDoesNotThrow(() -> manejador.peticionCancelada(
                new AsyncRequestNotUsableException("La conexión del cliente ya está cerrada")));
        assertDoesNotThrow(() -> manejador.consultaDescartada(new PeticionCancelada()));
    }

    @Test
    void elNavegadorQueAbandonaLaPeticionNoEsUnError() {
        RespuestasAbandonadas resolutor = new RespuestasAbandonadas();
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/api/v1/planificacion");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AsyncRequestNotUsableException abandono = new AsyncRequestNotUsableException("ServletOutputStream failed to write",
                new IOException("Se ha anulado una conexión establecida por el software en su equipo host"));
        ModelAndView atendida = resolutor.resolveException(peticion, respuesta, null, abandono);
        assertNotNull(atendida);
        assertTrue(atendida.isEmpty(), "no se escribe nada en una conexión cerrada");
        assertEquals(0, respuesta.getContentLength());
        assertNotNull(resolutor.resolveException(peticion, respuesta, null, new PeticionCancelada()));
        assertNotNull(resolutor.resolveException(peticion, respuesta, null,
                new IllegalStateException("envuelta", new PeticionCancelada())));
    }

    @Test
    void losErroresDeVerdadSiguenSuCurso() {
        RespuestasAbandonadas resolutor = new RespuestasAbandonadas();
        MockHttpServletRequest peticion = new MockHttpServletRequest("POST", "/api/v1/bancos/MD2/operaciones");
        assertNull(resolutor.resolveException(peticion, new MockHttpServletResponse(), null, new IllegalStateException("fallo")));
        assertNull(resolutor.resolveException(peticion, new MockHttpServletResponse(), null, new IOException("disco lleno")));
        assertFalse(RespuestasAbandonadas.esAbandono(new RuntimeException("x")));
        assertTrue(resolutor.getOrder() < 0, "va antes que el manejador por defecto de Spring");
    }
}
