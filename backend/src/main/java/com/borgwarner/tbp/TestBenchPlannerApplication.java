package com.borgwarner.tbp;

import com.borgwarner.tbp.configuracion.PropiedadesPlanner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Punto de entrada del backend de TestBenchPlanner. */
@SpringBootApplication
@EnableConfigurationProperties(PropiedadesPlanner.class)
public class TestBenchPlannerApplication {

    /** Versión de esta aplicación (la que se publica en TestBenchAppConfig al distribuirla). */
    public static final String VERSION = "5.0.0";

    public static void main(String[] args) {
        SpringApplication.run(TestBenchPlannerApplication.class, args);
    }
}
