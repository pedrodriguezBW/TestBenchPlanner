package com.borgwarner.tbp.configuracion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la aplicación (application.yml o argumentos --planner.…).
 * Ningún valor sensible va en el repositorio: el sitio de SharePoint se indica
 * en la configuración de cada instalación.
 */
@ConfigurationProperties(prefix = "planner")
public record PropiedadesPlanner(
        /* local | sharepoint */
        String almacen,
        /* Fecha fija (aaaa-mm-dd) para reproducir un día concreto; vacío = hoy. */
        String fechaFija,
        /* Carpeta de los datos de este PC (calendario, perfil…). */
        String carpetaUsuario,
        Local local,
        SharePoint sharepoint) {

    /** Almacenamiento local en disco (desarrollo y pruebas, sin SharePoint). */
    public record Local(
            String carpetaDatos,
            String escenarioInicial,
            boolean reiniciarAlArrancar,
            String usuarioEmail,
            String usuarioNombre) {
    }

    /** Acceso a las listas de SharePoint a través del Edge del usuario. */
    public record SharePoint(
            String sitio,
            String sitioAprobaciones,
            String listaAprobaciones,
            /* false (por defecto): no se escribe NADA en SharePoint */
            boolean escrituraHabilitada,
            boolean publicarVersion,
            int puertoDepuracion,
            String carpetaPerfilEdge,
            boolean espejoHabilitado,
            /* Conexiones de solo lectura con Edge en paralelo (0 = todo por una sola, como antes). */
            Integer carrilesLectura) {

        public int carrilesLecturaEfectivos() {
            return carrilesLectura == null ? 2 : Math.max(0, Math.min(4, carrilesLectura));
        }
    }
}
