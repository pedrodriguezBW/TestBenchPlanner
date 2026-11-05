package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.ConflictoEdicion;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.planificacion.BancoConfigurado;
import com.borgwarner.tbp.dominio.planificacion.CambioFila;
import com.borgwarner.tbp.dominio.planificacion.ConfiguracionBancos;
import com.borgwarner.tbp.dominio.planificacion.ContextoPlan;
import com.borgwarner.tbp.dominio.planificacion.FilaGuardada;
import com.borgwarner.tbp.dominio.planificacion.Planificacion;
import com.borgwarner.tbp.dominio.planificacion.ResultadoPlan;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Casos de uso de la planificación: leer el estado y ejecutar operaciones con
 * control de concurrencia.
 *
 * Cada operación se aplica sobre una lectura RECIENTE del almacén. Antes de
 * guardar se comprueba que las filas que se van a escribir siguen como las vio
 * el usuario (versiones); si otra persona las ha cambiado, se lanza
 * ConflictoEdicion y no se escribe nada. Con «forzar» se omite la comprobación
 * de lo que vio el usuario (opción «Guardar la mía encima»), pero el almacén
 * sigue protegiendo contra cambios ocurridos durante la propia escritura.
 */
@Service
public class ServicioPlanificacion {

    /** Una operación del dominio sobre el estado leído. */
    @FunctionalInterface
    public interface Operacion<T> {
        T aplicar(Planificacion planificacion, ResultadoPlan plan);
    }

    /** Resultado: lo que devuelve la operación, los cambios guardados y la vista nueva. */
    public record Resultado<T>(T valor, List<CambioFila> cambios, Vista vista) {
    }

    private final AlmacenEnsayos ensayos;
    private final AlmacenCatalogos catalogos;
    private final AlmacenCalendario calendarios;
    private final Reloj reloj;
    private final ServicioSesion sesion;

    public ServicioPlanificacion(AlmacenEnsayos ensayos, AlmacenCatalogos catalogos, AlmacenCalendario calendarios,
                                 Reloj reloj, ServicioSesion sesion) {
        this.ensayos = ensayos;
        this.catalogos = catalogos;
        this.calendarios = calendarios;
        this.reloj = reloj;
        this.sesion = sesion;
    }

    /** Lee catálogos y ensayos a la vez y calcula el plan (sin el hueco de 20 s de la 4.9.4, D17). */
    public Vista vista() {
        ConfiguracionBancos configuracion = configuracion();
        List<FilaGuardada> filas = ensayos.leer();
        Planificacion planificacion = Planificacion.desdeFilas(filas, configuracion);
        ContextoPlan contexto = contexto();
        return new Vista(planificacion, ResultadoPlan.calcular(planificacion, contexto), contexto);
    }

    public ContextoPlan contexto() {
        Calendario calendario = calendarios.leer(reloj.hoy());
        return new ContextoPlan(reloj.hoy(), calendario);
    }

    public ConfiguracionBancos configuracion() {
        JsonNode lista = catalogos.leer().path("benches");
        List<BancoConfigurado> bancos = new ArrayList<>();
        for (JsonNode b : lista) {
            JsonNode seq = b.get("seq");
            bancos.add(new BancoConfigurado(b.path("name").asText(""), texto(b, "groupKey"), texto(b, "groupName"),
                    texto(b, "dot"), texto(b, "org"), seq == null || seq.isNull() ? null : seq.asDouble()));
        }
        return ConfiguracionBancos.desdeLista(bancos);
    }

    /** Quién firma en el registro de actividad: nombre visible, responsable o correo. */
    public static AlmacenEnsayos.Actor actor(Usuario usuario) {
        String nombre = usuario.displayName();
        if (nombre == null || nombre.isEmpty()) {
            nombre = usuario.responsibleName();
        }
        if (nombre == null || nombre.isEmpty()) {
            nombre = usuario.email();
        }
        return new AlmacenEnsayos.Actor(usuario.email(), nombre);
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }

    /** Versión (huella) de cada fila de la vista, para que la interfaz la devuelva al operar. */
    public static Map<String, String> versiones(Vista vista) {
        Map<String, String> versiones = new HashMap<>();
        for (String banco : vista.planificacion().bancos()) {
            vista.planificacion().ensayos(banco).forEach(e -> versiones.put(e.id(), Versiones.de(vista.planificacion().textoLeido(e.id()))));
        }
        return versiones;
    }

    /**
     * Ejecuta una operación y guarda lo que cambie.
     *
     * @param versionesVistas uid -> versión que tenía el usuario en pantalla
     * @param forzar          true = «Guardar la mía encima»
     */
    public <T> Resultado<T> ejecutar(Usuario usuario, Map<String, String> versionesVistas, boolean forzar, Operacion<T> operacion) {
        sesion.exigirEscritura(usuario);
        Vista vista = vista();
        T valor = operacion.aplicar(vista.planificacion(), vista.plan());
        List<CambioFila> cambios = vista.planificacion().cambiosPendientes();
        if (!forzar && versionesVistas != null) {
            List<ConflictoEdicion.FilaEnConflicto> conflictos = new ArrayList<>();
            for (CambioFila cambio : cambios) {
                String vistaUsuario = versionesVistas.get(cambio.uid());
                if (vistaUsuario != null && cambio.textoAnterior() != null && !vistaUsuario.equals(Versiones.de(cambio.textoAnterior()))) {
                    conflictos.add(new ConflictoEdicion.FilaEnConflicto(cambio.uid(), cambio.textoAnterior()));
                }
            }
            if (!conflictos.isEmpty()) {
                throw new ConflictoEdicion(conflictos);
            }
        }
        if (!cambios.isEmpty()) {
            ensayos.guardar(cambios, true, actor(usuario));
            vista.planificacion().confirmarGuardado(cambios);
        }
        Vista nueva = new Vista(vista.planificacion(), ResultadoPlan.calcular(vista.planificacion(), vista.contexto()), vista.contexto());
        return new Resultado<>(valor, cambios, nueva);
    }
}
