package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Consultas de una pestaña que se recarga: se pueden descartar; las escrituras nunca. */
class FiltroPestanaTest {

    private final PestanasActivas pestanas = new PestanasActivas();
    private final FiltroPestana filtro = new FiltroPestana(pestanas);

    private Cancelacion.Testigo testigoDurante(String metodo, String ruta, String pestana) throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest(metodo, ruta);
        if (pestana != null) {
            peticion.addHeader(FiltroPestana.CABECERA, pestana);
        }
        AtomicReference<Cancelacion.Testigo> visto = new AtomicReference<>();
        filtro.doFilter(peticion, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest p, jakarta.servlet.ServletResponse r) {
                visto.set(Cancelacion.actual());
            }
        });
        assertNull(Cancelacion.actual(), "el hilo queda limpio para la siguiente petición");
        return visto.get();
    }

    @Test
    void lasConsultasLlevanElTestigoDeSuPestana() throws Exception {
        Cancelacion.Testigo a = testigoDurante("GET", "/api/v1/planificacion", "pestana-1");
        Cancelacion.Testigo b = testigoDurante("POST", "/api/v1/aprobaciones/estados", "pestana-1");
        assertNotNull(a);
        assertSame(a, b);
        assertFalse(a.cancelado());
    }

    @Test
    void lasEscriturasYLaInterfazClasicaNoSeCancelan() throws Exception {
        assertNull(testigoDurante("POST", "/api/v1/bancos/MD2/operaciones", "pestana-1"));
        assertNull(testigoDurante("POST", "/api/v1/bancos/MD2/ensayos/r1/campos", "pestana-1"));
        assertNull(testigoDurante("PUT", "/api/v1/recursos", "pestana-1"));
        assertNull(testigoDurante("GET", "/api/v1/planificacion", null), "sin cabecera (clásica): nada cambia");
    }

    @Test
    void alCerrarLaPestanaSeCancelanSusConsultasYSiVuelveEmpiezaDeCero() throws Exception {
        Cancelacion.Testigo antes = testigoDurante("GET", "/api/v1/recursos", "pestana-2");
        Cancelacion.Testigo otra = testigoDurante("GET", "/api/v1/recursos", "pestana-3");
        pestanas.cancelar("pestana-2");
        assertTrue(antes.cancelado());
        assertFalse(otra.cancelado(), "las demás pestañas siguen igual");
        Cancelacion.Testigo despues = testigoDurante("GET", "/api/v1/recursos", "pestana-2");
        assertNotSame(antes, despues);
        assertFalse(despues.cancelado());
    }
}
