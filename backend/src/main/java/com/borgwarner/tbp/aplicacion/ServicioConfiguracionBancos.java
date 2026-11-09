package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Configuration → Test Benches (persistGroups de la 4.9.4).
 *
 * En la 4.9.4 cambiar la configuración de bancos guardaba a la vez la lista de
 * bancos y los ensayos: renombrar un banco reescribe sus ensayos con el nombre
 * nuevo y borrar un banco (o una categoría con bancos) borra sus ensayos.
 * Aquí se hace en un solo paso y en este orden:
 *
 *  1. Se calcula qué ensayos cambian con la configuración nueva.
 *  2. Se guarda la lista de bancos y proyectos. La protección de listas (D11)
 *     se comprueba aquí: si la rechaza no se escribe nada más.
 *  3. Se guardan los ensayos que cambian (renombrados o borrados).
 *
 * Mover bancos o categorías, cambiar colores u organización no toca ningún
 * ensayo (la organización guardada en cada ensayo no se actualiza, D19).
 */
@Service
public class ServicioConfiguracionBancos {

    /** Resultado: filas de ensayos guardadas y vista nueva. */
    public record Resultado(int ensayosGuardados, int ensayosBorrados, Vista vista) {
    }

    private final ServicioPlanificacion planificacion;
    private final ServicioListas listas;
    private final ServicioSesion sesion;
    private final AlmacenEnsayos ensayos;

    public ServicioConfiguracionBancos(ServicioPlanificacion planificacion, ServicioListas listas, ServicioSesion sesion,
                                       AlmacenEnsayos ensayos) {
        this.planificacion = planificacion;
        this.listas = listas;
        this.sesion = sesion;
        this.ensayos = ensayos;
    }

    /**
     * @param catalogo    {"projects": [...], "benches": [{name, groupKey, groupName, dot, org, seq}]}
     * @param renombrados nombre anterior → nombre nuevo de los bancos renombrados
     */
    public Resultado guardar(Usuario usuario, JsonNode catalogo, Map<String, String> renombrados) {
        sesion.exigirAdmin(usuario);
        Vista vista = planificacion.vista();
        // Renombrar a un nombre que ya usa otro banco que sigue existiendo mezclaría (o perdería) ensayos.
        renombrados.forEach((anterior, nuevo) -> {
            if (vista.planificacion().existeBanco(nuevo) && !renombrados.containsKey(nuevo)) {
                throw new IllegalArgumentException("Ya existe un banco llamado «" + nuevo + "»");
            }
        });
        ConfiguracionBancos nueva = ServicioPlanificacion.configuracionDe(catalogo.path("benches"));
        Planificacion reconfigurada = vista.planificacion().reconfigurar(nueva, renombrados);
        List<CambioFila> cambios = reconfigurada.cambiosPendientes();
        listas.guardarCatalogos(catalogo);
        if (!cambios.isEmpty()) {
            ensayos.guardar(cambios, true, ServicioPlanificacion.actor(usuario));
        }
        int borrados = (int) cambios.stream().filter(CambioFila::esBorrado).count();
        return new Resultado(cambios.size() - borrados, borrados, planificacion.vista());
    }
}
