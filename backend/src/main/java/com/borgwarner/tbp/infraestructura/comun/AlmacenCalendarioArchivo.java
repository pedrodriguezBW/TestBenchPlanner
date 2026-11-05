package com.borgwarner.tbp.infraestructura.comun;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.dominio.calendario.Calendario;
import com.borgwarner.tbp.dominio.calendario.Festivo;
import com.borgwarner.tbp.dominio.comun.JsonJs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendario de festivos de este PC en un fichero (calendario.json), igual que
 * la 4.9.4 lo guardaba en el navegador de cada usuario. La primera vez se crea
 * con los festivos de Vigo del año en curso.
 */
public class AlmacenCalendarioArchivo implements AlmacenCalendario {

    private final Path fichero;

    public AlmacenCalendarioArchivo(Path carpetaUsuario) {
        this.fichero = carpetaUsuario.resolve("calendario.json");
    }

    @Override
    public synchronized Calendario leer(LocalDate hoy) {
        if (!Files.exists(fichero)) {
            Calendario nuevo = Calendario.porDefecto(hoy);
            guardar(nuevo);
            return nuevo;
        }
        try {
            JsonNode raiz = JsonJs.mapper().readTree(fichero.toFile());
            List<Festivo> festivos = new ArrayList<>();
            for (JsonNode f : raiz.path("holidays")) {
                festivos.add(new Festivo(f.path("date").asText(), f.path("name").asText("")));
            }
            return new Calendario(raiz.path("year").asInt(hoy.getYear()), raiz.path("skipHolidays").asBoolean(true), festivos);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede leer " + fichero, e);
        }
    }

    @Override
    public synchronized void guardar(Calendario calendario) {
        ObjectNode raiz = JsonJs.objetoVacio();
        raiz.put("year", calendario.anio());
        raiz.put("skipHolidays", calendario.saltarFestivos());
        ArrayNode lista = raiz.putArray("holidays");
        for (Festivo f : calendario.festivos()) {
            lista.addObject().put("date", f.date()).put("name", f.name());
        }
        try {
            Files.createDirectories(fichero.getParent());
            JsonJs.mapper().writerWithDefaultPrettyPrinter().writeValue(fichero.toFile(), raiz);
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede guardar " + fichero, e);
        }
    }
}
