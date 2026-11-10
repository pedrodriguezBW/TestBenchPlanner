package com.borgwarner.tbp.configuracion;

import com.borgwarner.tbp.aplicacion.puertos.AlmacenFacturacion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCalendario;
import com.borgwarner.tbp.aplicacion.EstadoConexion;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenCatalogos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenEnsayos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenHistoricoKpi;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenRecursos;
import com.borgwarner.tbp.aplicacion.puertos.AlmacenUsuarios;
import com.borgwarner.tbp.aplicacion.puertos.Colaboracion;
import com.borgwarner.tbp.aplicacion.puertos.ConsultaAprobaciones;
import com.borgwarner.tbp.aplicacion.puertos.Reloj;
import com.borgwarner.tbp.infraestructura.comun.AlmacenCalendarioArchivo;
import com.borgwarner.tbp.infraestructura.comun.RelojConfigurable;
import com.borgwarner.tbp.infraestructura.comun.CopiaSinConexion;
import com.borgwarner.tbp.infraestructura.local.AlmacenEnsayosLocal;
import com.borgwarner.tbp.infraestructura.local.AlmacenesAuxiliaresLocales;
import com.borgwarner.tbp.infraestructura.local.DatosLocales;
import com.borgwarner.tbp.infraestructura.facturacion.AlmacenFacturacionArchivos;
import com.borgwarner.tbp.infraestructura.sharepoint.AlmacenFacturacionSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.AlmacenEnsayosSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.AprobacionesSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ClienteSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ConexionEdge;
import com.borgwarner.tbp.infraestructura.sharepoint.EspejoSharePoint;
import com.borgwarner.tbp.infraestructura.sharepoint.ListasAuxiliaresSharePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Elige los adaptadores de persistencia según planner.almacen:
 *   - local: ficheros en disco (desarrollo, pruebas, laboratorio).
 *   - sharepoint: listas del sitio configurado, a través del Edge del usuario.
 * Una futura base de datos SQL sería otra opción más aquí, sin tocar el resto.
 */
@Configuration
public class ConfiguracionAlmacenes {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionAlmacenes.class);

    @Bean
    public RelojConfigurable reloj(PropiedadesPlanner propiedades) {
        return new RelojConfigurable(propiedades.fechaFija());
    }

    /** El calendario de festivos es siempre de este PC, sea cual sea el almacén (D6). */
    @Bean
    public AlmacenCalendario almacenCalendario(PropiedadesPlanner propiedades) {
        return new AlmacenCalendarioArchivo(Path.of(propiedades.carpetaUsuario()));
    }

    /** Adaptadores del modo local. */
    @Configuration
    @ConditionalOnProperty(name = "planner.almacen", havingValue = "local", matchIfMissing = true)
    public static class Local {

        @Bean
        public DatosLocales datosLocales(PropiedadesPlanner p) {
            LOG.info("Almacén LOCAL en {} (escenario inicial {})", p.local().carpetaDatos(), p.local().escenarioInicial());
            return new DatosLocales(Path.of(p.local().carpetaDatos()), Path.of(p.local().escenarioInicial()), p.local().reiniciarAlArrancar());
        }

        @Bean
        public Colaboracion colaboracion(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.ColaboracionLocal(datos);
        }

        @Bean
        public AlmacenEnsayos almacenEnsayos(DatosLocales datos, Colaboracion colaboracion) {
            return new AlmacenEnsayosLocal(datos, colaboracion);
        }

        @Bean
        public AlmacenCatalogos almacenCatalogos(DatosLocales datos, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Catalogos(datos, permitirVacias);
        }

        @Bean
        public AlmacenHistoricoKpi almacenHistoricoKpi(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.HistoricoKpi(datos);
        }

        @Bean
        public AlmacenesAuxiliaresLocales.Usuarios almacenUsuarios(DatosLocales datos, PropiedadesPlanner p,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Usuarios(datos,
                    new AlmacenUsuarios.Identidad(p.local().usuarioEmail(), p.local().usuarioNombre()), permitirVacias);
        }

        @Bean
        public AlmacenRecursos almacenRecursos(DatosLocales datos, Colaboracion colaboracion,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new AlmacenesAuxiliaresLocales.Recursos(datos, colaboracion, permitirVacias);
        }

        /** Facturación del modo local: junto a los datos locales (se reinicia con ellos solo si se borra la carpeta). */
        @Bean
        public AlmacenFacturacion almacenFacturacion(PropiedadesPlanner p) {
            return new AlmacenFacturacionArchivos(Path.of(p.local().carpetaDatos()).resolve("facturacion"));
        }

        @Bean
        public ConsultaAprobaciones consultaAprobaciones(DatosLocales datos) {
            return new AlmacenesAuxiliaresLocales.Aprobaciones(datos);
        }
    }

    /** Adaptadores de SharePoint. */
    @Configuration
    @ConditionalOnProperty(name = "planner.almacen", havingValue = "sharepoint")
    public static class SharePoint {

        @Bean(destroyMethod = "close")
        public ConexionEdge conexionEdge(PropiedadesPlanner p) {
            return new ConexionEdge(p.sharepoint().puertoDepuracion(), Path.of(p.sharepoint().carpetaPerfilEdge()),
                    p.sharepoint().carrilesLecturaEfectivos());
        }

        @Bean
        public ClienteSharePoint clienteSharePoint(ConexionEdge conexion, PropiedadesPlanner p) {
            if (p.sharepoint().sitio() == null || p.sharepoint().sitio().isBlank()) {
                throw new IllegalStateException("Falta planner.sharepoint.sitio (URL del sitio de SharePoint)");
            }
            LOG.warn("Almacén SHAREPOINT {} · escritura {}", p.sharepoint().sitio(),
                    p.sharepoint().escrituraHabilitada() ? "HABILITADA" : "DESACTIVADA (solo lectura)");
            return new ClienteSharePoint(conexion, p.sharepoint().sitio(), p.sharepoint().escrituraHabilitada());
        }

        /**
         * Es también el bean de ConsultaAprobaciones: no se declara otro bean que
         * devuelva la misma instancia, porque habría dos candidatos del tipo
         * ConsultaAprobaciones (y de AprobacionesSharePoint) y la inyección fallaría.
         */
        @Bean
        public AprobacionesSharePoint aprobacionesSharePoint(ClienteSharePoint cliente, PropiedadesPlanner p) {
            if (p.sharepoint().sitioAprobaciones() == null || p.sharepoint().sitioAprobaciones().isBlank()) {
                throw new IllegalStateException("Falta planner.sharepoint.sitio-aprobaciones (sitio de la base de aprobaciones)");
            }
            return new AprobacionesSharePoint(cliente, p.sharepoint().sitioAprobaciones(), p.sharepoint().listaAprobaciones());
        }

        @Bean
        public EspejoSharePoint espejoSharePoint(ClienteSharePoint cliente, AprobacionesSharePoint aprobaciones, PropiedadesPlanner p) {
            return new EspejoSharePoint(cliente, aprobaciones, p.sharepoint().espejoHabilitado());
        }

        @Bean
        public Colaboracion colaboracion(ClienteSharePoint cliente, PropiedadesPlanner p) {
            return new ListasAuxiliaresSharePoint.ColaboracionSharePoint(cliente, p.sharepoint().publicarVersion());
        }

        /** Última copia buena en este PC para consultar sin conexión (H-04). */
        @Bean
        public CopiaSinConexion copiaSinConexion(PropiedadesPlanner p, EstadoConexion estado) {
            return new CopiaSinConexion(Path.of(p.carpetaUsuario()), estado);
        }

        @Bean
        public AlmacenEnsayos almacenEnsayos(ClienteSharePoint cliente, Colaboracion colaboracion, EspejoSharePoint espejo, CopiaSinConexion copia) {
            return copia.ensayos(new AlmacenEnsayosSharePoint(cliente, colaboracion, espejo));
        }

        @Bean
        public AlmacenCatalogos almacenCatalogos(ClienteSharePoint cliente, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias,
                                                 CopiaSinConexion copia) {
            return copia.catalogos(new ListasAuxiliaresSharePoint.Catalogos(cliente, permitirVacias));
        }

        @Bean
        public AlmacenHistoricoKpi almacenHistoricoKpi(ClienteSharePoint cliente) {
            return new ListasAuxiliaresSharePoint.HistoricoKpi(cliente);
        }

        @Bean
        public AlmacenUsuarios almacenUsuarios(ClienteSharePoint cliente, @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias,
                                               CopiaSinConexion copia) {
            return copia.usuarios(new ListasAuxiliaresSharePoint.Usuarios(cliente, permitirVacias));
        }

        /**
         * Facturación con SharePoint: por defecto en ficheros de este PC
         * (planner.facturacion.almacen=local); con «sharepoint» se comparte en una
         * carpeta de una biblioteca del sitio (planner.facturacion.carpeta-sharepoint),
         * que hay que crear antes. Nunca se escribe en las listas de la planificación.
         */
        @Bean
        public AlmacenFacturacion almacenFacturacion(ClienteSharePoint cliente, PropiedadesPlanner p,
                                                     @Value("${planner.facturacion.almacen:local}") String modo,
                                                     @Value("${planner.facturacion.carpeta-sharepoint:}") String carpeta) {
            if ("sharepoint".equalsIgnoreCase(modo)) {
                if (carpeta == null || carpeta.isBlank()) {
                    throw new IllegalStateException("Falta planner.facturacion.carpeta-sharepoint (carpeta de la biblioteca para la facturación)");
                }
                LOG.warn("Facturación en SharePoint: {}", carpeta);
                return new AlmacenFacturacionSharePoint(cliente, carpeta);
            }
            return new AlmacenFacturacionArchivos(Path.of(p.carpetaUsuario()).resolve("facturacion"));
        }

        @Bean
        public AlmacenRecursos almacenRecursos(ClienteSharePoint cliente, Colaboracion colaboracion,
                                               @Value("${planner.listas.permitir-vacias:false}") boolean permitirVacias) {
            return new ListasAuxiliaresSharePoint.Recursos(cliente, colaboracion, permitirVacias);
        }
    }
}
