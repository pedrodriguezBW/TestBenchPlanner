# Rendimiento con SharePoint (H3R)

Corrección de los retrasos detectados al trabajar contra SharePoint con la
versión React + Java, que en la 4.9.4 no eran perceptibles. No cambia ninguna
regla funcional, rol, permiso, el modo solo lectura, la protección de listas
(D11), las ETag, el aviso de conflicto ni el guardado automático de 1,2 s.

> **Estado: validado en el PC (10-nov-2026).** `mvnw.cmd clean verify` pasa y la
> aplicación funciona correctamente contra SharePoint. La primera compilación
> falló en `ListasAuxiliaresSharePoint.copiaLista`: `map(JsonNode::deepCopy)`
> deducía `List<Object>` porque `JsonNode.deepCopy()` es genérico. Se corrigió
> indicando el tipo (`.<JsonNode>map(JsonNode::deepCopy)`), sin cambio de
> comportamiento.

## Síntomas y causas

| # | Síntoma | Causa en el código | Corrección |
|---|---|---|---|
| 1 | Recargar la página tardaba unos 20 s en mostrar datos. | Todo el tráfico con SharePoint pasaba por **un único hilo** (`ConexionEdge`) en serie. Al arrancar, la interfaz pide a la vez sesión, planificación, catálogos (dos veces: una dentro de la planificación y otra para las listas), usuarios (dos veces), recursos, versión y estados de aprobación. Además, **cada** llamada comprobaba antes la sesión SSO con una petición extra, cada lectura de lista volvía a pedir sus columnas y cada guardado pedía un FormDigest nuevo. | Lecturas compartidas (`LecturaCompartida`): si llegan varias a la vez, solo una va a SharePoint; catálogos y usuarios se reutilizan 15 s, recursos 10 s (siempre por debajo de los sondeos de la interfaz) y lo que escribe este PC las invalida en el acto. La lista de ensayos **no** se reutiliza: cada lectura es autoritativa, solo se comparte la que está en curso. La sesión se comprueba como mucho una vez por minuto (con recuperación automática si caduca), las columnas se recuerdan y el FormDigest se reutiliza mientras es válido. |
| 2 | Aviso `AsyncRequestNotUsableException … Se ha anulado una conexión establecida…` al recargar. | Al recargar, el navegador abandona las peticiones de la página anterior; Spring intentaba escribir la respuesta en una conexión cerrada y lo registraba como aviso. | `RespuestasAbandonadas` (primer resolutor de excepciones) da esas peticiones por atendidas sin escribir nada y solo lo anota en DEBUG. Los errores reales siguen igual. |
| 3 | Recursos y estados de aprobación especialmente lentos. | Los estados se pedían **un Approval ID por petición**, en serie, en el mismo hilo que la planificación (cientos de peticiones al abrir). Los recursos iban con la prioridad más baja y esperaban detrás de todo; además la interfaz no los pedía hasta tener la planificación. | Estados por lotes de 50 (`$filter=Id eq … or …`), recordados 20 s y con prioridad secundaria; si un lote falla, se resuelve ID a ID como antes (mismo resultado por ID). Recursos con prioridad normal y pedidos desde el arranque. Consultas en **conexiones de solo lectura en paralelo** (`planner.sharepoint.carriles-lectura`, 2 por defecto). |
| 4 | Crear o duplicar tardaba ~5 s en verse. | La interfaz esperaba la respuesta del backend, que a su vez esperaba a SharePoint: comprobar cada fila con una consulta propia, escribir, anotar el registro de actividad (otra escritura) y obtener un FormDigest nuevo. | La interfaz enseña la fila **al momento** (`operacionesOptimistas.ts`) con el id definitivo, que el backend usa si es válido y está libre (`idsPropuestos` → `GeneradorIds.conPropuestos`). Lo que se escriba en la fila espera a su alta y luego sigue la regla de 1,2 s. Si el guardado falla, la fila desaparece y se avisa. En el backend: comprobación previa de versiones en una sola consulta (lotes de 20), registro de actividad en segundo plano y FormDigest reutilizado. |
| 5 | Varias peticiones a la vez sobre `catalogos.json.tmp` («el archivo está siendo utilizado por otro proceso»). | `CopiaSinConexion` escribía siempre el **mismo temporal** sin exclusión: dos lecturas que terminaban a la vez chocaban en Windows. | Un cerrojo por copia, un temporal propio por escritura (siempre borrado), reintento breve si Windows tiene el fichero abierto y no se reescribe si el contenido no ha cambiado (la fecha se renueva una vez por minuto). |

## Peticiones canceladas al recargar

Cada carga de la página tiene un código de pestaña que ya se usaba para el ciclo
de vida; ahora viaja también en todas sus peticiones (`X-TBP-Pestana`). Cuando la
pestaña avisa de que se cierra o se recarga (`/api/shutdown`), sus **consultas**
que aún esperan turno se descartan (`FiltroPestana`, `PestanasActivas`,
`Cancelacion`) y la página nueva no espera detrás de ellas. Nunca se cancela:

- una escritura (operación, edición de campos, cierre, configuración…);
- una lectura compartida que también esperan otras peticiones;
- nada de la interfaz clásica (no envía el código).

Una consulta descartada no cuenta como lectura fallida para D11 ni activa el
modo sin conexión.

## Garantías que se mantienen

- **Escrituras**: todas siguen por una sola conexión, en orden y con la misma
  prioridad que antes; desde una conexión de lectura es imposible escribir
  (`ClienteSharePoint` lo impide). Con `escritura-habilitada: false` no se envía
  nada, igual que antes.
- **ETag y conflictos**: la comprobación previa de versiones y el `IF-MATCH` con
  ETag no cambian; solo se leen las filas en una consulta en lugar de en varias.
- **D11**: la lectura de catálogos, usuarios y recursos se sigue anotando en
  `RegistroLecturas`; una lectura con `*_error` no se reutiliza nunca.
- **Operaciones**: en el backend se ejecutan de una en una; en la interfaz, en
  el orden en que se pidieron (cola), así que una copia de una fila recién
  añadida espera a su alta.
- **Relectura**: no se relee la planificación mientras hay un alta o una copia
  a medio guardar, para no mostrarla dos veces.

## Configuración

| Propiedad | Por defecto | Uso |
|---|---|---|
| `planner.sharepoint.carriles-lectura` | `2` | Conexiones de solo lectura con Edge. `0` = comportamiento anterior (una sola conexión). Máximo 4. |

## Pruebas de regresión

Java (`mvnw.cmd verify`): `LecturaCompartidaTest`, `ConexionEdgeTest`,
`ClienteSharePointTest`, `AprobacionesSharePointTest`, `CatalogosSharePointTest`,
`CopiaSinConexionConcurrenciaTest`, `ManejadorErroresTest`, `FiltroPestanaTest`,
`GeneradorIdsTest`, `OperacionesInmediatasTest`, `ServicioPlanificacionLatenciaTest`.

Interfaz: `frontend/test/operacionesOptimistas.test.mjs` (`npm test`, también en
`-Pinterfaz`) y `herramientas/laboratorio/pruebas-rendimiento.cjs` contra el
backend simulado con un guardado de 2,5 s.

## Cómo volver a comprobarlo en el PC

1. `mvnw.cmd clean verify` (o `-Pinterfaz` para recompilar React y pasar `npm test`).
2. Arrancar con SharePoint en solo lectura y recargar varias veces (F5): los
   datos deben aparecer en pocos segundos y no debe salir el aviso
   `AsyncRequestNotUsableException` ni ningún aviso sobre `catalogos.json.tmp`.
3. En `~/.testbenchplanner/copia-sin-conexion` no deben quedar ficheros `.tmp`.
4. Con escritura controlada: añadir y duplicar deben verse al instante (fila
   atenuada mientras se guarda) y escribir enseguida en la fila nueva no debe
   perder nada.
5. Si algo no va bien con varias conexiones, `--planner.sharepoint.carriles-lectura=0`
   vuelve a una sola conexión sin tocar nada más.
