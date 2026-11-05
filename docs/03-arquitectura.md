# Arquitectura del backend Java (Hito 2)

Monolito modular Spring Boot 3.3 · Java 17. Un único proceso por PC, como el
backend Python, escuchando en `127.0.0.1:5001`.

```
com.borgwarner.tbp
├── dominio/                 Reglas de negocio puras (sin Spring ni E/S)
│   ├── comun/               Semántica de valores de JavaScript, JSON.stringify y json.dumps
│   ├── calendario/          Fechas, semanas ISO, festivos de Vigo, días laborables
│   ├── ensayo/              Ensayo (DataJson ordenado), duraciones, estados, motivos, tipos especiales
│   ├── planificacion/       Motor de fechas, sugerencias, configuración de bancos, estado y cambios a guardar
│   ├── kpi/                 Bloques semanales, KPI de semana, ocupación, foto semanal, histórico, estadísticas
│   ├── operaciones/         Edición de celdas, cola (compromiso, prioridad, selección, altas/bajas), cierre
│   └── usuarios/            Usuario y roles
├── aplicacion/              Casos de uso: vista, ejecutar operación con concurrencia, sesión, indicadores
│   └── puertos/             Interfaces de persistencia e integración (almacenes, reloj, calendario)
├── infraestructura/
│   ├── comun/               Reloj configurable, calendario en fichero, fila de KPI, versiones
│   ├── local/               Almacén en disco (desarrollo, pruebas, laboratorio)
│   └── sharepoint/          Listas de SharePoint a través del Edge del usuario (Playwright por CDP)
├── api/
│   ├── compat/              /api/* idéntica al backend Python + /clasico (HTML 4.9.4 original)
│   ├── v1/                  API para la interfaz React
│   └── comun/               Errores HTTP y ciclo de vida (latido / apagado)
└── configuracion/           Propiedades y elección de adaptadores (planner.almacen)
```

## Correspondencia con la 4.9.4

| 4.9.4 (JavaScript / Python) | Java |
|---|---|
| `computeSchedule`, `effStartOf`, `isStarted`, `remDaysOf`… | `MotorFechas`, `EstadosEnsayo`, `Duraciones` |
| `statusOf`, `manualCallOf`, `finalStatusOf` | `EstadosEnsayo` |
| `suggestionsFor`, `suggestionOpen`, `tolEndOf`, `plannedEndOf`, `idleDays` | `Sugerencias`, `MotorFechas` |
| `computeHolidays`, `easter`, `isFree`, `addFree`, `nextFree` | `Calendario` |
| `blockKPIs`, `weekKPIs` | `IndicadoresSemana` |
| `occupancyOf`, `benchBusyHours`, `capHoursOf` | `Ocupacion` |
| `snapshotKPIs` (+ corrección D8) | `FotoSemanal` |
| `kpiHistRows` · `statRows`, `statDateOf` | `HistoricoKpi` · `Estadisticas` |
| `bindRows` (manejadores de celdas), `setDowntime`, `setManualState` | `EdicionEnsayo` |
| `confirmDatesRow`, `unconfirmDatesRow`, `confirmAll`, arrastrar, `duplicateSel`, `moveSel`, `deleteSel`, `delRow`, `addRow` | `OperacionesBanco` |
| `openCloseModal`, `closeRecalc`, `confirmClose` (+ D1), `restoreDone`, `reuseDone` | `Cierre` |
| `lbApply`, `lbBuild`, `lbPush` | `Planificacion` (`desdeFilas`, `textoParaGuardar`, `cambiosPendientes`) |
| `do_save`, `fetch_all_items`, caché de Id | `AlmacenEnsayosSharePoint` |
| `safe_body`, espejo en segundo plano | `EspejoSharePoint` |
| `resolve_status`, `do_approvals_all` (solo lectura) | `AprobacionesSharePoint` |
| `do_*_config`, `*_kpis`, `whoami`, `save_users`, recursos, presencia, registro, versión | `ListasAuxiliaresSharePoint` |
| `_ensure_edge`, `_sp_worker_loop`, `_ensure_live_session` | `ConexionEdge`, `ClienteSharePoint` |

## Decisiones

- **DataJson como objeto JSON ordenado** (`Ensayo`): conserva campos desconocidos y el orden de las claves; el texto se escribe exactamente como `JSON.stringify` para que los PCs con la 4.9.4 no reescriban filas sin cambios.
- **Concurrencia optimista** por huella del DataJson (`Versiones`): la interfaz envía la versión que vio; si otra persona cambió la fila → 409 con su versión. En SharePoint, además, cada escritura usa `IF-MATCH` con el ETag leído; si el ETag cambió solo por las columnas espejo, se reintenta.
- **Escritura en SharePoint desactivada por defecto** (`planner.sharepoint.escritura-habilitada=false`): toda escritura pasa por `ClienteSharePoint.exigirEscritura`. Presencia, registro y versión publicada tampoco escriben con ella desactivada. `planner.sharepoint.publicar-version=false` hasta la distribución, para no avisar al equipo antes de tiempo.
- **No se crean listas ni columnas** desde Java: ya existen en producción (la 4.9.4 las creaba si faltaban).
- **Calendario por PC** (D6): fichero `calendario.json` en `planner.carpeta-usuario`.
- **API compatible**: permite servir la 4.9.4 original desde Java en `/clasico` como respaldo; su guardado mantiene «el último gana» (contrato de la 4.9.4).
- **Facturación**: fuera de alcance. No hay ningún modelo, campo ni servicio de facturación; «días facturables» de la ventana de cierre es comportamiento actual que se conserva tal cual.
