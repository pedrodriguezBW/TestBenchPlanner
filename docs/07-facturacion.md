# Facturación en TestBenchPlanner (H5)

Implementación del documento «Plan completo de implementación de la facturación
en TestBenchPlanner» (fases 1 a 7 y la base de la 8). Sustituye el uso operativo
de `2026 Ensayos interno.xlsx`: la facturación sale de los ensayos de la
aplicación, se revisa dentro de ella y se exporta al final.

## 1. Análisis del proyecto (fase 1)

| Pregunta del plan | Resultado del análisis |
|---|---|
| Datos reales del ensayo | Arranque real (`actualStart`) y fin real (`trueEnd`); al cerrar se congelan en `finalTrueStart` / `finalTrueEnd`. Solo fecha, sin hora. |
| Paradas actuales | Un único total `downtimeDays` (decimales) con nota; `downtime[]` guarda un solo tramo sin fechas. El cierre lo copia a `finalDowntimeDays` / `finalDowntime`. No cambian Gantt ni ocupación. |
| Motivos existentes | `MotivosRetraso`: Falta de recursos, Piezas con retraso, Avería, Caída de ensayo, Cambio de prioridad, Otro. Se reutilizan como motivos de parada y se añade «Festivo» (el plan pide marcar los festivos sin trabajo como parada). |
| Fines de semana | Campo `weekend` del ensayo («el banco trabaja en fin de semana»), el mismo que usa la planificación. Se reutiliza tal cual. |
| BW y CTAG | Cada banco tiene `org` (BW o CTAG) en TestBenchBenches: es la **planta** de facturación. La «Planta» del Excel (Heaters, Viana, eCoolers…) es la **línea de producto** del proyecto; se guarda en el maestro de proyectos. |
| CTAG | Los bancos CTAG de la aplicación (Fatiga, FP Aceite 2, 2000 CTAG, 1000 CTAG 1/2, XXL CTAG, Snakes dP) no tienen equivalente directo con los equipos CTAG del Excel (HVCH L02, HVCH L03, HVCH Lubu CTAG). El mapeo lo debe completar un Admin. |
| Requester | No existe en el ensayo. Se toma de la base de aprobaciones (Approval ID → requester) y, si no, del maestro de proyectos. |
| Proyecto | Texto libre «número - nombre». Se usa el número (sin ceros a la izquierda; 8000xxxx → 8xxxx, como en las órdenes 463B…). |
| Cierre | La ventana de cierre valida fechas, estado, motivo y días de parada; no se toca. Su «días facturables» heredado se conserva. |

Lógica económica trasladada del Excel (y comprobada fila a fila, ver §8):

- `Coste/hora` = tabla «Equipos y costes» por equipo y HV; 0 si la planta es STC.
- `Coste total` = 0 si el número de proyecto es 0; R&D: `h·t·ASINH(h/100)·100/h`;
  Heating Plates y HVCH Lubu: `h·t·ASINH(h/200)·200/h`; resto `h·t`.
- Controlling: tabla dinámica por Equipo y Orden (orden de equipos del Excel);
  `Amount = ROUND(Suma de Coste total; 2)`; CCtr (Old) 6463-41; Cost Element
  420000; Crcy EUR; UM H; Order (new) = TRIM(orden); Text = equipo.

## 2. Arquitectura

Módulo desacoplado dentro del backend existente (mismo proceso, mismo puerto),
con separación funcional estricta:

```text
dominio/facturacion          reglas puras: periodos, tramos, horas, tarifas, reglas,
                             incidencias, estados, revisión, aprobación, resúmenes
aplicacion/facturacion       ServicioFacturacion: permisos, versiones, auditoría
aplicacion/puertos           AlmacenFacturacion (documentos JSON + ficheros exportados)
infraestructura/facturacion  almacén en ficheros, Excel (OOXML a mano) y HTML
infraestructura/sharepoint   AlmacenFacturacionSharePoint (biblioteca de documentos)
api/v1/FacturacionController /api/v1/facturacion/*
frontend/src/bloques/facturacion   sección «Billing» de React
```

Lo único que la facturación escribe en la planificación es el objeto
`DataJson.billing` del ensayo (ficha «Billing Data»), con la misma cola, versión
de fila y aviso de conflicto que el resto de ediciones. La planificación,
reservas, CF, Gantt, Dashboard, KPI, días facturables heredados y cierre
operativo no cambian.

### Datos del ensayo (`DataJson.billing`)

```json
{ "startTime": "08:00", "endTime": "16:30",
  "stops": [{ "id": "…", "from": "2026-02-12T10:15", "to": "2026-02-12T14:45",
              "reason": "Averia", "billable": false, "note": "" }],
  "setupHours": 2, "setupConfirmed": true, "hv": "no",
  "billable": true, "billableBy": "", "billableAt": "",
  "order": "463B11364MD4", "orderException": null,
  "pieces": "", "note": "", "updatedBy": "…", "updatedAt": "…" }
```

Las fechas siguen siendo las del arranque y fin real de siempre (una sola fuente
de verdad); solo se añade la hora. Los ensayos antiguos no tienen `billing`: se
leen con valores por defecto (facturable, sin hora, sin setup confirmado).

### Documentos propios

| Documento | Contenido |
|---|---|
| `maestros` | Proyectos y órdenes, categorías, mapeos banco/tipo → categoría, tarifas versionadas, setup recomendado, parámetros de Controlling. Se crea con los valores del Excel (`resources/facturacion/maestros-iniciales.json`) la primera vez que un Admin guarda. |
| `periodo-aaaa-mm` | Periodo, líneas (valor calculado, aplicado, ajustes, exclusión, aplazamiento, propuesta de tarifa, historial), fotos aprobadas (líneas + tarifas + categorías + Controlling), reaperturas, exportaciones. |
| `auditoria-aaaa` | Eventos: cambios de importe, aprobaciones, reaperturas, maestros, exportaciones, datos de ensayo. |
| `exportaciones/` | Copia de cada fichero exportado (nunca se borran). |

`planner.facturacion.almacen`: `local` (por defecto; ficheros de este PC) o
`sharepoint` (carpeta compartida de una biblioteca del sitio, indicada en
`planner.facturacion.carpeta-sharepoint`, que hay que crear antes; necesita
`escritura-habilitada`). Para que todo el equipo comparta periodos, la entrega
debe usar `sharepoint`. Concurrencia optimista: huella del fichero (local) o
ETag (SharePoint) → 409 «conflicto-facturacion».

## 3. Motor (fases 3 y 4)

- **Periodos**: del 21 del mes anterior al 20 del mes, ambos incluidos. Un
  ensayo que atraviesa periodos se divide en tramos (línea `ensayo@aaaa-mm`).
- **Horas netas** del tramo = tiempo real dentro del periodo − fines de semana
  (si el banco no trabaja en fin de semana) − paradas no facturables (sin restar
  dos veces la parte de una parada que cae en fin de semana). Se redondea el
  resultado del tramo a 15 min; las paradas no se redondean.
- Hora que falta: el tramo se calcula desde 00:00 / hasta 24:00, pero la línea
  queda **bloqueada** (FALTA_HORA_INICIO / FIN) hasta indicarla.
- **Abiertos**: con arranque real y sin fin se facturan hasta el corte del
  periodo (o hasta ahora si el periodo no ha terminado: advertencia).
- **Setup**: se suma en el tramo que contiene el arranque. Obligatorio confirmado
  antes de facturar; se precarga el recomendado (categoría, banco, tipo).
- **Selección**: cualquier ensayo que ocupe el periodo (real o planificado).
  Planificado sin arrancar → PENDING sin importe; arrancado según la
  planificación pero sin arranque real → BLOCKED; no facturable → EXCLUDED;
  tipos especiales (calibración, avería…) no se facturan; tramo ya incluido en
  un periodo aprobado → BLOCKED (YA_FACTURADO).
- **Resolución económica**: proyecto (maestro) → orden (única: automática;
  varias: elegir; otra: solo Admin con justificación) → categoría por mapeo
  banco + tipo de ensayo → regla (del mapeo o de la categoría) → HV (manual) →
  tarifa vigente el día 20 del periodo con la jerarquía proyecto → planta o
  línea de producto → general; sin tarifa → BLOCKED.
- **Reglas**: LINEAR, ASINH_100, ASINH_200, PER_PIECE, NOT_BILLABLE. Las
  reductoras se aplican por línea antes de agrupar.
- **Incidencias** bloqueantes y advertencias del §21 del plan, con texto e
  icono. Estados: READY, WARNING, BLOCKED, EXCLUDED, PENDING, ADJUSTED y
  DEFERRED (aplazada).

## 4. Revisión, aprobación y trazabilidad (fases 5 y 6)

- **Recalcular** (Borrador / En revisión) conserva ajustes, exclusiones,
  aplazamientos y propuestas; si el valor de origen de un campo ajustado cambia,
  se avisa (CAMBIO_POSTERIOR) y se elige «Conservar» o «Usar recalculado».
- **Ajustes**: horas netas, setup, piezas, proyecto, orden, HV, categoría,
  coste final y texto de Controlling. Justificación obligatoria si cambia el
  importe. Se guardan valor calculado, aplicado, usuario, rol, fecha y
  justificación. Un ajuste económico de Usuario/Editor queda pendiente hasta que
  un Admin lo valida o rechaza.
- **Tarifa excepcional**: cualquiera puede proponerla (PENDING_RATE_APPROVAL,
  con tarifa calculada, propuesta, diferencia, usuario y justificación); el Admin
  la aprueba, rechaza o sustituye, para esa línea o como nueva tarifa maestra
  (se cierra la anterior y se crea una versión desde el inicio del periodo).
- **Excluir** (sin motivo obligatorio) y **aplazar** la línea completa al periodo
  siguiente (allí aparece como «Pendiente del periodo anterior», conservando el
  periodo de ejecución).
- **Enviar a revisión**: sin bloqueos, con al menos una línea facturable,
  setups, órdenes, tarifas y reglas completos (las líneas excluidas o aplazadas no
  cuentan).
- **Aprobar** (Admin, con confirmación): foto inmutable de líneas, indicadores,
  tarifas, categorías y Controlling. Un periodo aprobado no se recalcula; si un
  ensayo cambia después, aparece una alerta.
- **Reabrir** (Admin, justificación obligatoria): vuelve a En revisión, conserva
  la foto anterior y marca las exportaciones como obsoletas.

Permisos (backend y pantalla): READONLY consulta; Usuario/Editor completan datos,
registran paradas, marcan no facturable, preparan y proponen; Admin mantiene
maestros, valida ajustes, aprueba, reabre y exporta.

## 5. Pantallas

Menú lateral → **Billing** (separado de la planificación y de Configuration):

- **Periods**: lista (periodo, fechas, estado, ensayos, incidencias, importe,
  creado por) y «Crear y calcular» eligiendo solo el mes.
- **Review**: cabecera, acciones, 12 filtros, tabla de líneas y panel de detalle
  (origen, desglose temporal, datos económicos, ajustes e historial, acciones).
- **Overview / Reports**: indicadores; pestañas Overview (desglose por proyecto,
  orden, banco, categoría, tipo, planta, línea, requester o HV, con barras),
  Projects, Equipment (marca las reglas no lineales), Plants (BW/CTAG y tabla por
  línea de producto como las hojas mensuales del Excel), Incidents (abre la
  línea), Controlling (vista previa con filtros y desglose por ensayo) y
  Exports & audit.
- **Configuration** (solo Admin): Projects and orders (con pegado masivo),
  Equipment mappings y categorías, Rates (versiones), Calculation rules,
  Controlling y setup recomendado, Audit.
- En cada banco: columna **Billing** (estado de los datos: ✔ completo, !
  BILLING_DATA_INCOMPLETE con lo que falta, ⊘ no facturable) que abre la ficha
  «Billing Data»; botón «💶 Billing» en Completed; sección plegable opcional en
  la ventana de cierre (el cierre se guarda aunque falten datos económicos).

## 6. Exportaciones (fase 7)

Solo Admin y solo un periodo aprobado: Excel completo (Resumen, Detalle, Proyecto
y orden, Equipos, Tipo de ensayo, Planta y línea, Controlling - Internal Testing,
Incidencias y ajustes), Excel reducido de Controlling, HTML de presentación
(portada, resumen, planta, equipo, proyecto, línea de producto, incidencias y
ajustes, Controlling; navegación anterior/siguiente y teclado) o todos. Cada
exportación queda registrada (identificador, versión, usuario, fecha, tipo,
estado válida/obsoleta) y se puede volver a descargar.

## 7. RACS (fase 8)

Incluido lo básico: categoría RACS con unidad «piezas» y regla PER_PIECE, campo
«Piezas» en la ficha y en los ajustes, Controlling con su UM (configurable, «PC»
por defecto). Queda pendiente de validar con un caso real de RACS y decidir la UM
que espera Controlling (el Excel solo tiene filas por horas en la hoja).

## 8. Verificación

| Prueba | Qué comprueba |
|---|---|
| `herramientas/laboratorio/facturacion/generar-referencias.cjs` | 6 casos con expectativas calculadas a mano (división de tramos, fines de semana, paradas solapadas con fin de semana, redondeo, abiertos, todas las incidencias, flujo completo con ajustes, propuestas, aprobación, alerta, reapertura, aplazamiento y tarifa maestra). Genera `referencias-facturacion.json`. |
| `ParidadFacturacionTest` (Maven) | El dominio Java repite los mismos pasos y debe dar exactamente lo mismo que la referencia JavaScript. |
| `ExcelFacturacionTest` (Maven) | Las **522 filas** de 2026 del Excel salen con la misma tarifa y el mismo coste; la hoja Controlling de octubre (43 filas) coincide en filas, orden, sumas, Amount y constantes; el .xlsx exportado tiene esa hoja con sus columnas. |
| `ApiFacturacionTest` (Maven) | Flujo de punta a punta con el almacén local: maestros, ficha (422 con paradas solapadas), periodo, 409, exclusión, envío, aprobación, exportación (3 ficheros y copia en disco), reapertura, auditoría. |
| `herramientas/laboratorio/pruebas-facturacion.cjs` | 28 comprobaciones de la interfaz React (ficha, cierre, periodos, revisión, ajustes, aprobación, descargas, overview, Controlling, reapertura, configuración y permisos). |

## 9. Pendiente de decidir o completar

1. **Mapeo de bancos**: los maestros iniciales solo proponen las cámaras
   climáticas de BW (1500 WC, 1500 Aire, KK127, KK129, KK118 → Cámara
   climática). El resto de bancos (incluidos los de CTAG) debe mapearlo un Admin
   en Configuration → Equipment mappings; mientras tanto sus líneas salen
   bloqueadas con «El banco no tiene categoría».
2. **Almacén compartido**: para el equipo, `planner.facturacion.almacen=sharepoint`
   con una carpeta de biblioteca creada a mano. No se ha probado contra
   SharePoint real (solo lectura en desarrollo).
3. **Setup recomendado**: vacío; el Excel no tiene una tabla de setups por equipo.
4. **Requester** en los maestros de proyectos: precargado con el más frecuente
   de INPUTS_2026 cuando existe.
