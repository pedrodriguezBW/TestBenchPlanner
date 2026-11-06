# Registro de defectos y mejoras posmigración

> **Proyecto:** TestBenchPlanner  
> **Versión de referencia:** 4.9.4  
> **Objetivo:** conservar durante la migración la paridad funcional con la versión 4.9.4 y registrar por separado los defectos heredados o mejoras no bloqueantes para tratarlos después de completar la migración.

## Criterio de clasificación

- **Heredado:** el comportamiento ya existe en la versión 4.9.4. No bloquea la migración mientras React y Java mantengan la paridad acordada.
- **Diferencia aprobada:** existe una diferencia respecto a la 4.9.4, pero está documentada y aceptada.
- **Mejora técnica:** no es un error funcional inmediato, pero conviene corregirla antes de distribuir o mantener el producto a largo plazo.
- **Bloqueante de migración:** defecto nuevo introducido por Java o React. Debe corregirse antes de cerrar el hito correspondiente.

## Defectos y mejoras registrados

| ID | Área | Tipo | Descripción | Riesgo o impacto | Momento recomendado |
|---|---|---|---|---|---|
| PM-001 | Dashboard | Heredado | Si falla una consulta, el Dashboard puede conservar cifras anteriores y continuar mostrando `Synced`, sin avisar claramente de que los datos no se han actualizado. | El usuario podría interpretar datos antiguos como actuales. | Después de completar H3 y antes de la distribución final. |
| PM-002 | Dashboard | Heredado / robustez | Una doble pulsación en **Update Yearly Status** podría lanzar dos actualizaciones concurrentes. | Posibles operaciones duplicadas o estados inconsistentes. | Añadir bloqueo del botón mientras la operación está en curso. |
| PM-003 | Frontend | Mejora técnica | La instalación de dependencias no es totalmente reproducible si no se conserva `package-lock.json` y no se usa `npm ci`. | Distintas instalaciones podrían resolver versiones diferentes. | Antes de preparar la distribución final. |
| PM-004 | Frontend | Mejora técnica | Pueden quedar bundles antiguos dentro de `frontend/dist`. | Aumenta el tamaño del paquete y dificulta saber qué artefactos están activos. | Limpiar `dist` antes de cada compilación de producción. |
| PM-005 | Interfaz | Mejora técnica | Algunas leyendas, tiradores y operaciones dependen del ratón y pueden resultar poco accesibles con teclado o pantalla táctil. | Menor accesibilidad y peor uso en dispositivos táctiles. | Fase de mejora UX posterior a la migración. |
| PM-006 | Gantt | Diferencia documentada, D22 | La documentación antigua describía un relleno de avance dentro de la barra del Gantt, pero la 4.9.4 no lo dibuja. React sigue el comportamiento visible de la 4.9.4. | Ninguno para la paridad; queda pendiente decidir si se desea incorporar el indicador. | Después de la migración, como mejora visual opcional. |
| PM-007 | Ayuda | Diferencia documentada, D23 | El texto de ayuda de la 4.9.4 menciona `Launched` y un estado automático que ya no existen. React muestra un texto actualizado. | Diferencia textual intencionada, sin cambio funcional. | Mantener documentado; revisar textos al cerrar H3. |
| PM-008 | Validación | Diferencia aprobada | El aviso de validación de **Fin real** aparece en React después de la espera de guardado automático de 1,2 segundos, no inmediatamente como en la 4.9.4. | Retraso breve en la información mostrada al usuario. | Valorar validación inmediata en cliente tras completar la migración. |
| PM-009 | Interfaz clásica | Heredado / D12 | `/clasico` no dispone del control moderno de conflictos. Mantiene el criterio de “último guardado gana” y puede recrear un ensayo que otra persona haya eliminado. | Pérdida o sobrescritura de cambios concurrentes. | Retirar `/clasico` tras validar React; no usar ambas interfaces simultáneamente sobre los mismos ensayos. |
| PM-010 | Datos de prueba | Mejora técnica | El escenario de aprobaciones puede no estar suficientemente anonimizado para salir de un entorno corporativo controlado. | Posible exposición de información interna si el repositorio o los paquetes se distribuyen fuera del entorno autorizado. | Revisar y anonimizar antes de cualquier distribución externa. |
| PM-011 | Fecha operativa | Diferencia aprobada, D7 | Java calcula la fecha actual de Madrid en cada petición y cambia de día a medianoche; `/clasico` congela la fecha al abrirse. | Durante una sesión cruzando medianoche, ambas interfaces pueden mostrar fechas operativas distintas. | Aceptado para Java/React; retirar la discrepancia al retirar `/clasico`. |
| PM-012 | Configuración de listas | Restricción de seguridad, D11 | Con la protección de listas activada no se puede vaciar de golpe una lista compartida. Las instalaciones nuevas con listas vacías requieren `planner.listas.permitir-vacias=true`. | Una operación administrativa legítima puede quedar bloqueada si no se usa la configuración específica. | Mantener como protección; documentar el procedimiento de instalación inicial. |

## Seguimiento por hito

### H1

- Caracterización de la versión 4.9.4 y creación del laboratorio de paridad.
- Se estableció que los defectos heredados no bloquean la migración si se reproducen de forma equivalente.

### H2 / H2b

- **D7:** se decidió que Java y React cambien de fecha a medianoche.
- **D11:** se añadió protección para evitar escrituras o reconciliaciones tras lecturas incompletas, fallidas o vacías de SharePoint.
- **D12:** se documentó que la protección mediante ETag y `409 Conflict` solo está completa en `/api/v1`.
- Validación completada con 92 pruebas y SharePoint en modo solo lectura.

### H3A

- Vista de banco React validada contra el backend Java.
- Validación Maven completada con 94 pruebas.
- Edición, guardado automático, movimiento, duplicado, borrado, cierre, restauración y conflicto entre pestañas probados.
- **D22**, **D23** y el retraso de validación de **Fin real** registrados como no bloqueantes.
- Comparación con SharePoint en solo lectura correcta respecto a `/clasico`.

### H3B

- Dashboard y By Responsible validados contra el backend Java.
- Foto semanal validada en almacenamiento local.
- Comparación con SharePoint en solo lectura correcta respecto a `/clasico`.
- Registrados los riesgos de consulta silenciosamente fallida y doble pulsación en **Update Yearly Status**.

## Plantilla para nuevos hallazgos

Añadir una fila a la tabla principal con estos datos:

- **ID:** siguiente identificador `PM-XXX`.
- **Área:** pantalla, módulo o componente afectado.
- **Tipo:** heredado, diferencia aprobada, mejora técnica o bloqueante de migración.
- **Descripción:** comportamiento observado y, si aplica, comparación con la 4.9.4.
- **Riesgo o impacto:** consecuencia práctica.
- **Momento recomendado:** hito o fase en la que debe resolverse.

## Regla de cierre

Un defecto de la 4.9.4 reproducido por la aplicación migrada se registra aquí y **no bloquea el hito actual**, salvo que implique pérdida de datos, escritura insegura en SharePoint o un riesgo nuevo introducido por Java o React. Los defectos nuevos de la migración sí deben corregirse antes de cerrar el hito correspondiente.
