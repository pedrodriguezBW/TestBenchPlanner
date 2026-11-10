# Laboratorio de paridad con la 4.9.4

Ejecuta la versión **original** (`legacy/BW_TestBench_Planner_31_Local.html`, sin
modificar) en un Chromium sin ventana, con un backend simulado que sirve un
escenario de datos y registra todo lo que la aplicación intenta guardar. Ninguna
petición sale del navegador: no se toca SharePoint.

Es el «modo laboratorio» del apartado 10.3 del manual técnico, ampliado: en vez
de inyectar el estado, se deja arrancar la aplicación entera (lectura
autoritativa, catálogos, usuario, KPI) contra datos conocidos y con el reloj
parado en la fecha del caso.

## Piezas

| Fichero | Para qué |
|---|---|
| `oraculo-4.9.4.cjs` | Abre la 4.9.4 con un escenario, una fecha y un rol. Simula todas las rutas `/api/*` del backend Python. |
| `controlador-4.9.4.cjs` | Traduce los pasos neutros de un caso (editar, comprometer, cerrar…) a acciones sobre la 4.9.4 y saca la **foto** del estado observable. |
| `normalizar.cjs` | Renombra los ids creados durante el caso (`nuevo-1`…) y calcula las diferencias de DataJson. |
| `generar-referencias.cjs` | Ejecuta los casos y guarda el resultado en `referencias/`. |
| `casos/*.json` | Casos de caracterización, independientes de la implementación. |
| `referencias/*.json` | Resultado de la 4.9.4 para cada caso: la verdad contra la que se compara la versión nueva. |
| `backend-simulado-4.9.4.cjs` | Backend `/api/v1` que calcula con la 4.9.4 por detrás, para probar la interfaz React sin Java. |
| `controlador-react.cjs` · `comparar-interfaz.cjs` | Ejecutan los casos sobre la interfaz React como un usuario y comparan con las referencias (bloque A). |
| `casos-interfaz/*.json` · `comparar-panel.cjs` | Dashboard, «By Responsible» (bloque B), KPI History (bloque C), equipos auxiliares (bloque D) y configuración (bloque E): mismos gestos en las dos interfaces y comparación de lo que se ve. |
| `pruebas-ciclo-de-vida.cjs` | Presencia de la pestaña: ocultar o minimizar no avisa de cierre; salir de la página sí. |
| `pruebas-rendimiento.cjs` | Latencia con SharePoint: altas y copias visibles al momento con un guardado lento simulado, cola de operaciones, fallo y código de pestaña en cada petición. |
| `cambios-aprobados.json` · `cambios-aprobados-interfaz.json` | Diferencias admitidas, cada una ligada a una discrepancia o a un bloque pendiente. |

## Qué contiene una foto

- `planificacion`: por banco y en orden, inicio, fin, criterio (R/A/F/C/H/M), estado, sugerencias, solapes, días planificados, fin tolerado y fin planificado.
- `cambiosPersistidos`: los ensayos cuyo **DataJson** ha cambiado, campo a campo y con el texto exacto que se enviaría a SharePoint.
- `guardados`: los envíos a `/api/save` (uids actualizados y borrados).
- `otrasEscrituras`: cualquier otra escritura (fotos KPI, configuración…), con su cuerpo.
- `dialogos`: avisos y confirmaciones mostrados.
- `kpi`, `estadisticas`, `historicoKpi`, `cierre`, `persistido`: según pida el caso.
- `kpiHistorico` (casos C10–C12): fotos semanales por organización y estadísticas con varios filtros (Org, responsable, periodo).
- `panel` (casos B01–B05): tabla de ocupación (semana, año y CW10–CW14), gráfica por CW (% y horas) y bloques KPI por responsable, leídos de las tarjetas del Dashboard de la 4.9.4.
- `erroresJs`: errores de JavaScript (deben ser cero).

## Requisitos y ejecución

Node 18 o superior, Playwright y un Chromium. Hay que fijar la zona horaria:
la 4.9.4 trabaja con fechas locales y el equipo del laboratorio está en Madrid.

```bash
npm install                    # instala playwright (ver package.json)
npx playwright install chromium
TZ=Europe/Madrid node generar-referencias.cjs          # todos los casos
TZ=Europe/Madrid node generar-referencias.cjs K1       # solo los que contienen «K1»
```

En Windows (PowerShell): `$env:TZ="Europe/Madrid"; node generar-referencias.cjs`.

Variables opcionales: `PLAYWRIGHT_MODULE` (ruta al módulo playwright) y
`CHROMIUM_PATH` (ejecutable de Chromium) si no se usan los instalados por npm.

## Regenerar las referencias

Las referencias solo se regeneran si cambia el **escenario** o se añaden casos.
Nunca para «hacer pasar» una comparación: si la versión nueva difiere, primero
se clasifica la diferencia (regresión, ambigüedad, error de la prueba,
discrepancia documentada o mejora aprobada). Ver `docs/02-discrepancias.md`.
