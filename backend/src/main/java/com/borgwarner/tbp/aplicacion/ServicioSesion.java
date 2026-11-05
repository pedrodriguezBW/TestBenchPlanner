package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.dominio.usuarios.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

/**
 * Quién usa la aplicación y con qué rol (whoami de la 4.9.4).
 *
 * El rol sale de la lista de usuarios buscando por email; quien no esté es de
 * solo lectura. Como el backend corre en el PC de cada usuario, la identidad se
 * resuelve una vez y se reutiliza.
 */
@Service
public class ServicioSesion {

    private final AlmacenUsuarios usuarios;
    private final Colaboracion colaboracion;
    private final RegistroLecturas lecturas;
    private volatile Usuario actual;
    private volatile ArrayNode ultimaLista;

    public ServicioSesion(AlmacenUsuarios usuarios, Colaboracion colaboracion, RegistroLecturas lecturas) {
        this.usuarios = usuarios;
        this.colaboracion = colaboracion;
        this.lecturas = lecturas;
    }

    /** Usuario actual; la primera vez lo identifica y anota la conexión en el registro. */
    public Usuario usuarioActual() {
        Usuario usuario = actual;
        if (usuario == null) {
            usuario = identificar();
        }
        return usuario;
    }

    /** Vuelve a leer identidad y roles (whoami). */
    public synchronized Usuario identificar() {
        AlmacenUsuarios.Identidad identidad = usuarios.identidadActual();
        String email = identidad.email() == null ? "" : identidad.email().toLowerCase();
        ArrayNode lista;
        try {
            lista = usuarios.leer();
        } catch (RuntimeException e) {
            lecturas.anotar(RegistroLecturas.USUARIOS, false, 0);
            throw e;
        }
        // Una lista vacía (lectura fallida en SharePoint) no habilita guardar usuarios (D11).
        lecturas.anotar(RegistroLecturas.USUARIOS, true, lista.size());
        JsonNode encontrado = null;
        for (JsonNode u : lista) {
            String correo = u.path("email").asText("").trim().toLowerCase();
            if (!correo.isEmpty() && correo.equals(email)) {
                encontrado = u;
                break;
            }
        }
        String rol = encontrado != null ? Usuario.normalizarRol(encontrado.path("role").asText("")) : Usuario.SOLO_LECTURA;
        String responsable = encontrado != null ? encontrado.path("responsibleName").asText("") : "";
        String nombre = identidad.nombre() == null ? "" : identidad.nombre().trim();
        if (nombre.isEmpty()) {
            String titulo = encontrado != null ? encontrado.path("title").asText("") : "";
            nombre = !titulo.isEmpty() ? titulo : (!responsable.isEmpty() ? responsable : email);
        }
        Usuario usuario = new Usuario(email, nombre, responsable, rol);
        if (actual == null) {
            colaboracion.anotarActividad(email, nombre, "connect", "role=" + rol);
        }
        actual = usuario;
        ultimaLista = lista;
        return usuario;
    }

    /** Olvida al usuario identificado (reinicio del modo local para pruebas). */
    public synchronized void olvidar() {
        actual = null;
        ultimaLista = null;
    }

    /** Lista de usuarios leída en la última identificación. */
    public ArrayNode usuarios() {
        if (ultimaLista == null) {
            identificar();
        }
        return ultimaLista;
    }

    public void exigirEscritura(Usuario usuario) {
        if (usuario.soloLectura()) {
            throw new SinPermiso("Tu usuario es de solo lectura: no se guardan cambios.");
        }
    }

    public void exigirAdmin(Usuario usuario) {
        if (!usuario.esAdmin()) {
            throw new SinPermiso("Solo un Admin puede hacer esta operación.");
        }
    }
}
