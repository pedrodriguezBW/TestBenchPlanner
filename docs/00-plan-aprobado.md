# Plan de migración aprobado (5-nov-2026)

Decisiones del usuario:
- Se empieza de cero; no se reutiliza trabajo previo.
- Java irá **empaquetado** con su propio runtime (no se puede instalar en los PCs).
- Festivos, importar JSON y presencia (D6, D10, D9): igual que la 4.9.4.
- D1 se corrige; D2 se conserva y documenta; D8 y D12 a criterio técnico.
- Conflictos de edición: **aviso al usuario** («Otra persona ha cambiado este ensayo», con «Ver su versión» / «Guardar la mía encima»).
- SharePoint: el desarrollo solo lee; la versión entregada escribirá en las listas
  igual que la 4.9.4. La escritura es una opción de configuración: apagada en
  desarrollo y pruebas, encendida en la entrega.

## H1 · Caracterización y protección del comportamiento
Objetivo: un oráculo ejecutable de la 4.9.4. Entregables: inventario con IDs,
laboratorio, escenario real anonimizado, casos y referencias, discrepancias.

## H2 · Backend Java (monolito modular Spring Boot)
Dominio sin dependencias (planificación, estados, sugerencias, cierre, KPI,
ocupación, calendario) · casos de uso · puertos de persistencia · adaptador
local (escenario en disco) · adaptador SharePoint (mismas listas, mismo DataJson,
espejo y KPI byte a byte; escritura desactivable) · API para React y API
compatible `/api/*` para servir la 4.9.4 como respaldo · concurrencia optimista
con aviso. Aceptación: 0 diferencias sin clasificar con las referencias.

## H3 · Interfaz React por bloques
A operativa diaria · B dashboard · C KPI/estadísticas · D recursos · E
configuración · F exportaciones. Sin reglas de negocio en los componentes.
Aceptación por bloque: casos del laboratorio iguales a la 4.9.4 y pruebas
manuales del bloque.

## H4 · Validación integral y retirada controlada
Validación en la oficina (SharePoint primero en solo lectura y después
escritura controlada), paquete con Java incluido y lanzador, documentación de
instalación, trazabilidad final. La 4.9.4 sigue disponible como respaldo hasta
que se valide la nueva.
