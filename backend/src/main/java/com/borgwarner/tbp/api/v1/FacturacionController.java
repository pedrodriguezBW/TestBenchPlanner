package com.borgwarner.tbp.api.v1;

import com.borgwarner.tbp.aplicacion.facturacion.ServicioFacturacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de la facturación (sección «Billing» de la interfaz):
 *   GET  /tipos-equipo            Equipment Types, reglas y Equipment Type de cada banco
 *   PUT  /tipos-equipo            guarda todos los Equipment Types (Admin)
 *   GET  /hoja?mes=aaaa-mm[&niveles=orden,planta,…]   vista previa de la hoja mensual
 *   GET  /hoja/exportar?mes=…&formato=excel|html[&niveles=…]   archivo (solo líneas completas), en base64
 * niveles: campos de las filas (orden, planta, tipo, equipo, hv, inicio, fin, tarifa),
 * siempre en ese orden; sin indicar = todos; «-» = ninguno (solo el total).
 */
@RestController
@RequestMapping("/api/v1/facturacion")
public class FacturacionController {

    private final ServicioFacturacion servicio;

    public FacturacionController(ServicioFacturacion servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/tipos-equipo")
    public ObjectNode tiposEquipo() {
        return servicio.configuracion();
    }

    @PutMapping("/tipos-equipo")
    public ObjectNode guardarTiposEquipo(@RequestBody JsonNode cuerpo) {
        return servicio.guardarTipos(cuerpo);
    }

    @GetMapping("/hoja")
    public ObjectNode hoja(@RequestParam("mes") String mes, @RequestParam(value = "niveles", required = false) String niveles) {
        return servicio.hoja(mes, niveles);
    }

    @GetMapping("/hoja/exportar")
    public ObjectNode exportar(@RequestParam("mes") String mes, @RequestParam(value = "formato", defaultValue = "excel") String formato,
                               @RequestParam(value = "niveles", required = false) String niveles) {
        return servicio.exportar(mes, formato, niveles);
    }
}
