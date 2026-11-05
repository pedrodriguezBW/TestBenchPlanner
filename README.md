# TestBenchPlanner · migración a React + Java

Repositorio de la migración de TestBenchPlanner 4.9.4 (HTML monolítico +
backend Python/Flask) a **React** (interfaz) y **Java / Spring Boot** (backend),
conservando el comportamiento funcional y el uso de las listas de SharePoint.

## Estado

| Hito | Contenido | Estado |
|---|---|---|
| H1 | Caracterización de la 4.9.4 y laboratorio de paridad | **Terminado** |
| H2 | Backend Java: dominio, casos de uso, persistencia local y SharePoint | Pendiente |
| H3 | Interfaz React por bloques (A operativa · B dashboard · C KPI · D recursos · E configuración · F exportaciones) | Pendiente |
| H4 | Validación integral en la oficina, empaquetado con Java incluido, retirada de la 4.9.4 | Pendiente |

## Estructura

```
legacy/                    Versión 4.9.4 original, sin modificar (oráculo de paridad)
datos/escenarios/          Escenarios de datos ANONIMIZADOS (formato de respuesta del backend 4.9.4)
datos/privado/             Lecturas de SharePoint sin anonimizar (NO se versiona)
herramientas/
  importar-sharepoint/     Genera un escenario anonimizado a partir de una lectura de SharePoint
  laboratorio/             Ejecuta la 4.9.4 sin SharePoint, casos de prueba, referencias y comparador
docs/                      Inventario, discrepancias y decisiones
```

## Reglas de trabajo

- Ningún desarrollo ni prueba escribe en las listas de SharePoint. Las lecturas
  de SharePoint se hacen en solo lectura y se anonimizan antes de usarlas.
- La 4.9.4 de `legacy/` no se modifica: es la referencia.
- Una funcionalidad solo se da por migrada cuando sus casos dan 0 diferencias
  sin clasificar en `herramientas/laboratorio/comparar.cjs`.
- Facturación: fuera de alcance.
