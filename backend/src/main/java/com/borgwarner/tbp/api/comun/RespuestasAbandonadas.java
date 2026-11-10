package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.PeticionCancelada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;


/**
 * Peticiones que el navegador abandonó (recargar o cerrar la página): la
 * conexión ya está cerrada y no hay nada que contestar.
 *
 * Spring las resolvía con su manejador por defecto, que deja un aviso por cada
 * una («AsyncRequestNotUsableException: ServletOutputStream failed to write:
 * java.io.IOException: Se ha anulado una conexión establecida por el software
 * en su equipo host»). Este resolutor va el primero, las da por atendidas sin
 * escribir nada y solo lo anota en el nivel DEBUG. Los errores de verdad
 * siguen su curso (ManejadorErrores).
 */
@Component
public class RespuestasAbandonadas implements HandlerExceptionResolver, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(RespuestasAbandonadas.class);

    @Override
    public ModelAndView resolveException(HttpServletRequest peticion, HttpServletResponse respuesta, Object manejador,
                                         Exception excepcion) {
        if (!esAbandono(excepcion)) {
            return null;
        }
        LOG.debug("Petición abandonada por el navegador: {} {}", peticion.getMethod(), peticion.getRequestURI());
        return new ModelAndView(); // atendida: no se escribe nada en una conexión cerrada
    }

    /** ¿La excepción (o su causa) indica que el cliente ya no está? */
    static boolean esAbandono(Throwable excepcion) {
        for (Throwable t = excepcion; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AsyncRequestNotUsableException || t instanceof PeticionCancelada) {
                return true;
            }
            if (t.getClass().getName().equals("org.apache.catalina.connector.ClientAbortException")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
