package com.borgwarner.tbp.api.comun;

import com.borgwarner.tbp.aplicacion.Cancelacion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Asocia cada CONSULTA de la interfaz React al testigo de su pestaña
 * (cabecera X-TBP-Pestana), para poder descartarla si la pestaña se cierra o
 * se recarga antes de que SharePoint la atienda.
 *
 * Solo las lecturas: GET y los POST que solo consultan. Una escritura enviada
 * no se cancela nunca. La interfaz clásica no envía la cabecera: nada cambia.
 */
@Component
public class FiltroPestana extends OncePerRequestFilter {

    public static final String CABECERA = "X-TBP-Pestana";

    /** POST que solo leen (la API los define como POST por compatibilidad o por el tamaño del cuerpo). */
    static final Set<String> POST_DE_CONSULTA = Set.of("/api/v1/aprobaciones/estados");

    private final PestanasActivas pestanas;

    public FiltroPestana(PestanasActivas pestanas) {
        this.pestanas = pestanas;
    }

    static boolean esConsulta(HttpServletRequest peticion) {
        String metodo = peticion.getMethod();
        if ("GET".equalsIgnoreCase(metodo)) {
            return true;
        }
        return "POST".equalsIgnoreCase(metodo) && POST_DE_CONSULTA.contains(peticion.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        Cancelacion.Testigo testigo = esConsulta(peticion) ? pestanas.testigoDe(peticion.getHeader(CABECERA)) : null;
        Cancelacion.Testigo anterior = Cancelacion.vincular(testigo);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            Cancelacion.vincular(anterior);
        }
    }
}
