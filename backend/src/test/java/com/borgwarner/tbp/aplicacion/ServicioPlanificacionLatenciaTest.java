package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.borgwarner.tbp.dominio.operaciones.OperacionesBanco;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Evita que las altas añadan una segunda lectura completa tras guardar. */
class ServicioPlanificacionLatenciaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 10);
    private AlmacenEnsayos ensayos;
    private AlmacenCatalogos catalogos;
    private ServicioPlanificacion servicio;
    private Usuario usuario;

    @BeforeEach
    void preparar() {
        ensayos = mock(AlmacenEnsayos.class);
        catalogos = mock(AlmacenCatalogos.class);
        AlmacenCalendario calendarios = mock(AlmacenCalendario.class);
        ServicioSesion sesion = mock(ServicioSesion.class);
        Reloj reloj = mock(Reloj.class);

        when(ensayos.leer()).thenReturn(List.of(new FilaGuardada("r-original", textoOriginal())));
        when(ensayos.guardar(any(), anyBoolean(), any())).thenReturn(new AlmacenEnsayos.ResumenGuardado(1, 0, 0));
        when(catalogos.leer()).thenReturn(catalogos());
        when(calendarios.leer(HOY)).thenReturn(Calendario.porDefecto(HOY));
        when(reloj.hoy()).thenReturn(HOY);
        when(reloj.ahora()).thenReturn(Instant.parse("2026-11-10T08:30:00Z"));

        servicio = new ServicioPlanificacion(ensayos, catalogos, calendarios, reloj, sesion);
        usuario = new Usuario("ana@ejemplo.local", "Ana", "Ana Responsable", Usuario.EDITOR);
    }

    @Test
    void crearConfirmaConLaVistaCalculadaSinReleerSharePoint() {
        var resultado = servicio.ejecutar(usuario, Map.of(), false,
                (planificacion, plan) -> OperacionesBanco.anadir(planificacion, "Banco 1", usuario, () -> "r-nuevo"));

        assertEquals("r-nuevo", resultado.valor());
        assertTrue(resultado.vista().planificacion().buscar("Banco 1", "r-nuevo").isPresent());
        assertEquals(2, resultado.vista().planificacion().activos("Banco 1").size());
        verify(ensayos, times(1)).leer();
        verify(catalogos, times(1)).leer();
        verify(ensayos, times(1)).guardar(any(), anyBoolean(), any());
    }

    @Test
    void duplicarConfirmaConLaVistaCalculadaSinReleerSharePoint() {
        var resultado = servicio.ejecutar(usuario, Map.of("r-original", Versiones.de(textoOriginal())), false,
                (planificacion, plan) -> OperacionesBanco.duplicar(planificacion, "Banco 1", List.of("r-original"), () -> "r-copia"));

        assertEquals(List.of("r-copia"), resultado.valor());
        assertTrue(resultado.vista().planificacion().buscar("Banco 1", "r-copia").isPresent());
        assertEquals(2, resultado.vista().planificacion().activos("Banco 1").size());
        verify(ensayos, times(1)).leer();
        verify(catalogos, times(1)).leer();
        verify(ensayos, times(1)).guardar(any(), anyBoolean(), any());
    }

    private static ObjectNode catalogos() {
        ObjectNode salida = JsonJs.objetoVacio();
        salida.putArray("benches").addObject()
                .put("name", "Banco 1").put("groupKey", "g").put("groupName", "Grupo")
                .put("dot", "#000000").put("org", "BW").put("seq", 1);
        return salida;
    }

    private static String textoOriginal() {
        ObjectNode datos = JsonJs.objetoVacio();
        datos.put("_bench", "Banco 1").put("_seq", 1).put("_benchOrg", "BW")
                .put("id", "r-original").put("done", false).put("project", "P1")
                .put("durMode", "manual").put("manualUnit", "days").put("durManual", 1)
                .put("cf", 1.2).put("weekend", false).put("inputs", true);
        return JsonJs.escribir(datos);
    }
}
