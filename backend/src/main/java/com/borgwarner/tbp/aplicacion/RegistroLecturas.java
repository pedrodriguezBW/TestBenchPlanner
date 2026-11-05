package com.borgwarner.tbp.aplicacion;

import com.borgwarner.tbp.dominio.listas.ReconciliacionBloqueada;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Leer antes de escribir» para las listas compartidas (D11).
 *
 * Igual que la 4.9.4 no guardaba ensayos hasta haber hecho una lectura
 * correcta (LOADED_OK), el backend no deja guardar bancos, proyectos,
 * usuarios o recursos si en esta sesión no ha habido antes una lectura
 * correcta y NO VACÍA de esa lista. Una lectura fallida anula la anterior.
 */
@Component
public class RegistroLecturas {

    public static final String BANCOS = "bancos";
    public static final String PROYECTOS = "proyectos";
    public static final String RESPONSABLES = "responsables";
    public static final String USUARIOS = "usuarios";
    public static final String RECURSOS = "recursos";

    private final Map<String, Boolean> correctas = new ConcurrentHashMap<>();
    private final boolean permitirVacias;

    public RegistroLecturas(@Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
        this.permitirVacias = permitirVacias;
    }

    /** Anota el resultado de una lectura: correcta y con elementos, o no. */
    public void anotar(String lista, boolean correcta, int elementos) {
        correctas.put(lista, correcta && (elementos > 0 || permitirVacias));
    }

    public void exigir(String lista) {
        if (!Boolean.TRUE.equals(correctas.get(lista))) {
            throw new ReconciliacionBloqueada("No se ha guardado «" + lista + "»: no hay una lectura correcta de la lista "
                    + "en esta sesión. Vuelve a abrir la aplicación e inténtalo de nuevo.");
        }
    }

    public boolean permitirVacias() {
        return permitirVacias;
    }

    /** Olvida todas las lecturas (pruebas). */
    public void olvidarTodo() {
        correctas.clear();
    }
}
