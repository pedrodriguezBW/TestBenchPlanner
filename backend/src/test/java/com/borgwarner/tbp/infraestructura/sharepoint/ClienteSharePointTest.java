package com.borgwarner.tbp.infraestructura.sharepoint;

import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regresión de las peticiones repetidas a SharePoint: comprobación de la
 * sesión, FormDigest y columnas ya no se piden en cada llamada. Sin Edge: las
 * respuestas HTTP están simuladas.
 */
class ClienteSharePointTest {

    private static final String SITIO = "https://sitio";

    private final List<String> peticiones = new ArrayList<>();
    private final AtomicLong ahora = new AtomicLong(1_000_000);
    /** Respuesta de la primera lectura de elementos (para simular una sesión caducada). */
    private int estadoPrimeraLectura = 200;
    private boolean primeraLecturaHecha;
    private ConexionEdge conexion;
    private ClienteSharePoint cliente;

    private static APIResponse respuesta(int estado, String cuerpo) {
        APIResponse r = mock(APIResponse.class);
        when(r.status()).thenReturn(estado);
        when(r.ok()).thenReturn(estado >= 200 && estado < 300);
        when(r.text()).thenReturn(cuerpo);
        return r;
    }

    @BeforeEach
    void preparar() {
        APIRequestContext http = mock(APIRequestContext.class);
        BrowserContext contexto = mock(BrowserContext.class);
        when(contexto.request()).thenReturn(http);
        when(http.get(anyString(), any(RequestOptions.class))).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            peticiones.add("GET " + url);
            if (url.endsWith("/_api/web/title")) {
                return respuesta(200, "{\"value\":\"Testing Site\"}");
            }
            if (url.contains("/fields?")) {
                return respuesta(200, "{\"value\":[{\"InternalName\":\"Seq\"},{\"InternalName\":\"Org\"}]}");
            }
            if (url.contains("/items?") && !primeraLecturaHecha) {
                primeraLecturaHecha = true;
                if (estadoPrimeraLectura != 200) {
                    return respuesta(estadoPrimeraLectura, "<html>login</html>");
                }
            }
            return respuesta(200, "{\"value\":[{\"Id\":1,\"Title\":\"P1\",\"Seq\":1}]}");
        });
        when(http.post(anyString(), any(RequestOptions.class))).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            peticiones.add("POST " + url);
            if (url.endsWith("/_api/contextinfo")) {
                return respuesta(200, "{\"FormDigestValue\":\"digest-" + peticiones.size() + "\",\"FormDigestTimeoutSeconds\":1800}");
            }
            return respuesta(204, "");
        });
        conexion = new ConexionEdge(1, () -> new ConexionEdge.Enlace() {
            @Override
            public BrowserContext contexto() {
                return contexto;
            }

            @Override
            public void reconectar() {
            }

            @Override
            public void cerrar() {
            }
        });
        cliente = new ClienteSharePoint(conexion, SITIO, true, ahora::get);
    }

    @AfterEach
    void cerrar() {
        conexion.close();
    }

    private long contar(String fragmento) {
        return peticiones.stream().filter(p -> p.contains(fragmento)).count();
    }

    private List<JsonNode> leerProyectos() {
        return cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ, c -> cliente.leerLista(c, "TestBenchProjects", List.of("Seq")));
    }

    @Test
    void laSesionSeCompruebaComoMuchoUnaVezPorMinuto() {
        leerProyectos();
        leerProyectos();
        leerProyectos();
        assertEquals(1, contar("/_api/web/title"));
        ahora.addAndGet(ClienteSharePoint.VIGENCIA_SESION_MS + 1);
        leerProyectos();
        assertEquals(2, contar("/_api/web/title"));
    }

    @Test
    void lasColumnasDeUnaListaSeLeenUnaSolaVez() {
        leerProyectos();
        leerProyectos();
        assertEquals(1, contar("/fields?"));
        assertEquals(2, contar("/items?"));
    }

    @Test
    void siLaSesionHaCaducadoSeRecuperaYSeRepiteLaLectura() {
        estadoPrimeraLectura = 403;
        List<JsonNode> filas = leerProyectos();
        assertEquals(1, filas.size());
        assertEquals("P1", filas.get(0).path("Title").asText());
        assertEquals(2, contar("/_api/web/title"), "se comprueba la sesión otra vez antes de repetir");
    }

    @Test
    void elFormDigestSeReutilizaEntreGuardados() {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("Title", "x");
        for (int i = 0; i < 3; i++) {
            cliente.conSesion(ConexionEdge.PRIORIDAD_GUARDADO, c -> cliente.merge(c, cliente.digest(c),
                    ClienteSharePoint.lista(SITIO, "TestBenchPlanner"), 1, cuerpo, "*"));
        }
        assertEquals(1, contar("/_api/contextinfo"));
        assertEquals(3, contar("/items(1)"));
    }

    @Test
    void desdeUnaConsultaNoSePuedeEscribir() {
        ObjectNode cuerpo = JsonJs.objetoVacio().put("Title", "x");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> cliente.consultar(ConexionEdge.PRIORIDAD_INTERFAZ,
                c -> cliente.merge(c, "d", ClienteSharePoint.lista(SITIO, "TestBenchPlanner"), 1, cuerpo, "*")));
        assertTrue(e.getMessage().contains("solo lectura"));
        assertEquals(0, contar("/items(1)"));
    }

    @Test
    void conLaEscrituraDesactivadaNoSeEnviaNada() {
        ClienteSharePoint soloLectura = new ClienteSharePoint(conexion, SITIO, false, ahora::get);
        ObjectNode cuerpo = JsonJs.objetoVacio().put("Title", "x");
        assertThrows(com.borgwarner.tbp.aplicacion.puertos.EscrituraDesactivada.class, () -> soloLectura.conSesion(
                ConexionEdge.PRIORIDAD_GUARDADO, c -> soloLectura.merge(c, "d", ClienteSharePoint.lista(SITIO, "TestBenchPlanner"), 1, cuerpo, "*")));
        assertEquals(0, contar("/items(1)"));
    }
}
