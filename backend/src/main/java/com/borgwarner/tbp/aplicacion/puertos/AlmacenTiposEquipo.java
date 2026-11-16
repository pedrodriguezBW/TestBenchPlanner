package com.borgwarner.tbp.aplicacion.puertos;

import com.borgwarner.tbp.dominio.facturacion.TipoEquipo;

import java.util.List;

/**
 * Equipment Types de facturación: una fila por Equipment Type y año (lista
 * TestBenchEquipmentTypes de SharePoint; en modo local, tipos-equipo.json).
 */
public interface AlmacenTiposEquipo {

    /** Todas las filas. Si la lista no se puede leer, lanza la excepción con el motivo. */
    List<TipoEquipo> leer();

    /** Deja la lista exactamente con estas filas (crea, actualiza y manda a la papelera lo que sobra). */
    void guardar(List<TipoEquipo> tipos);
}
