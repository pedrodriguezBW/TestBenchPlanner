package com.borgwarner.tbp.api.compat;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sirve la interfaz 4.9.4 original, sin modificar, en /clasico. Es el
 * respaldo acordado hasta que la interfaz React esté validada con SharePoint.
 * Mientras la interfaz React no exista, la raíz redirige aquí.
 */
@Controller
public class InterfazClasicaController {

    private static final String HTML = "clasico/BW_TestBench_Planner_31_Local.html";

    @GetMapping("/clasico")
    public ResponseEntity<String> clasico() throws IOException {
        ClassPathResource recurso = new ClassPathResource(HTML);
        if (!recurso.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream entrada = recurso.getInputStream()) {
            return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                    .body(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/")
    public String inicio() {
        return new ClassPathResource("static/index.html").exists() ? "forward:/index.html" : "redirect:/clasico";
    }
}
