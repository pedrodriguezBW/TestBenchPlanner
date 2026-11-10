package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * Lectura y guardado de las listas compartidas (catálogos, usuarios y
 * recursos) con las protecciones de D11: leer antes de escribir
 * (RegistroLecturas) y no vaciar ni reescribir sobre una lectura vacía
 * (ProteccionListas, aplicada por cada almacén antes de escribir nada).
 */
@Service
public class ServicioListas {

    private final AlmacenCatalogos catalogos;
    private final AlmacenUsuarios usuarios;
    private final AlmacenRecursos recursos;
    private final RegistroLecturas lecturas;

    public ServicioListas(AlmacenCatalogos catalogos, AlmacenUsuarios usuarios, AlmacenRecursos recursos,
                          RegistroLecturas lecturas) {
        this.catalogos = catalogos;
        this.usuarios = usuarios;
        this.recursos = recursos;
        this.lecturas = lecturas;
    }

    public ObjectNode leerCatalogos() {
        try {
            ObjectNode leido = catalogos.leer();
            lecturas.anotar(RegistroLecturas.BANCOS, !leido.has("benches_error"), leido.path("benches").size());
            lecturas.anotar(RegistroLecturas.PROYECTOS, !leido.has("projects_error"), leido.path("projects").size());
            lecturas.anotar(RegistroLecturas.RESPONSABLES, !leido.has("responsibles_error"), leido.path("responsibles").size());
            return leido;
        } catch (PeticionCancelada cancelada) {
            throw cancelada; // la pestaña se ha cerrado: no cuenta como lectura fallida (D11)
        } catch (RuntimeException e) {
            lecturas.anotar(RegistroLecturas.BANCOS, false, 0);
            lecturas.anotar(RegistroLecturas.PROYECTOS, false, 0);
            lecturas.anotar(RegistroLecturas.RESPONSABLES, false, 0);
            throw e;
        }
    }

    public ObjectNode guardarCatalogos(JsonNode entrada) {
        lecturas.exigir(RegistroLecturas.BANCOS);
        lecturas.exigir(RegistroLecturas.PROYECTOS);
        if (entrada.path("responsibles").size() > 0) {
            lecturas.exigir(RegistroLecturas.RESPONSABLES); // solo se toca si llega una lista no vacía
        }
        return catalogos.guardar(entrada);
    }

    /** La lectura de usuarios la anota ServicioSesion al identificar (whoami). */
    public ObjectNode guardarUsuarios(JsonNode entrada) {
        lecturas.exigir(RegistroLecturas.USUARIOS);
        return usuarios.guardar(entrada);
    }

    public ObjectNode leerRecursos() {
        try {
            ObjectNode leido = recursos.leer();
            lecturas.anotar(RegistroLecturas.RECURSOS, !leido.has("resources_error") && !leido.has("error"),
                    leido.path("resources").size());
            return leido;
        } catch (PeticionCancelada cancelada) {
            throw cancelada; // la pestaña se ha cerrado: no cuenta como lectura fallida (D11)
        } catch (RuntimeException e) {
            lecturas.anotar(RegistroLecturas.RECURSOS, false, 0);
            throw e;
        }
    }

    public ObjectNode guardarRecursos(JsonNode entrada) {
        lecturas.exigir(RegistroLecturas.RECURSOS);
        return recursos.guardarCatalogo(entrada);
    }

    public boolean permitirVacias() {
        return lecturas.permitirVacias();
    }
}
