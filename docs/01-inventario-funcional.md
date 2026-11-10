# Inventario funcional de TestBenchPlanner 4.9.4

Fuente: código de `legacy/BW_TestBench_Planner_31_Local.html` y `legacy/backend_testbench_v4_9_4.py`,
contrastado con el manual técnico y la guía de uso v4.9.4.

Cada funcionalidad tiene un **ID estable** que se usa en los casos de prueba
(`herramientas/laboratorio/casos/*.json`, campo `funcionalidad`) y, más adelante,
en el código nuevo y en sus pruebas. La columna **Casos** indica qué casos de
caracterización la cubren hoy contra la 4.9.4 original.

Estado:
- **Caracterizada**: hay al menos un caso ejecutado en la 4.9.4 con referencia guardada.
- **Hecho (hito)**: migrado en la versión nueva y cubierto por sus pruebas.

**Cierre (H3Z, 9-nov-2026):** ya no queda ninguna entrada pendiente. La
auditoría función a función de la 4.9.4 está en `05-auditoria-cierre.md`.

**Validación (10-nov-2026):** H3F, H3Z y H3R validados en el PC. La escritura
controlada en SharePoint (SP) y el lanzador (APP) quedan para H4.

## 1. Motor de planificación y estados (dominio)

| ID | Funcionalidad | Implementación 4.9.4 | Casos | Estado |
|---|---|---|---|---|
| PLAN | Cálculo de inicio/fin por banco en orden de prioridad (cola, hoy, muestras, forzado, real, asumido; fin real, forzado, 100 %, elástico, plan × CF) | `computeSchedule`, `effStartOf`, `isStarted`, `remDaysOf`, `plannedDays`, `durDays`, `cfOf` | F01–F07, E02–E18, E24 | Caracterizada |
| PLAN-OVL | Aviso de solape en el mismo banco | `computeSchedule` (final) | F01–F07 (campo `solapes`) | Caracterizada |
| CAL | Calendario: fines de semana y festivos de Vigo (por usuario, un año) | `computeHolidays`, `easter`, `isFree`, `addFree`, `nextFree`, `HOLSET` | F04, F05, F07, E13 | Caracterizada (D6) |
| STATE | Estado: queued / confirmed / running / delayed / completed / special; `risk` y `behind` = retraso | `statusOf`, `manualCallOf`, `finalStatusOf` | todos (campo `estado`) | Caracterizada |
| SUG | Sugerencias «¿En retraso?» (sin avance > 7 días, fin > comprometido + 1 laborable, arranque tardío, ritmo lento) | `suggestionsFor`, `suggestionOpen`, `idleDays`, `tolEndOf`, `plannedEndOf` | F01–F07, S02 | Caracterizada |
| KPI | Bloques semanales del dashboard (bancos con carga, corriendo, en retraso, aviso ámbar) | `blockKPIs` | F01–F06, E19, S03 | Caracterizada |
| KPI-WEEK | KPI de la semana (inicia, acaba, corriendo, en cola, retraso por motivo) | `weekKPIs` | F01–F06 | Caracterizada |
| OCU | Ocupación por horas (24 h, 7 días, tope diario, Weekend = No, < 1 día, cerrados con fechas históricas) | `occupancyOf`, `benchBusyHours`, `capHoursOf` | F01–F07, E15 | Caracterizada |
| KPI-SNAP | Foto semanal ALL/BW/CTAG y envío al histórico | `snapshotKPIs`, `updateYearlyStatus`, `/api/save_kpis`, `_kpi_body` | P01, P02 | Caracterizada (D8) |
| HIST | Lectura del histórico semanal (semanas nuevas frente a antiguas) | `kpiHistRows`, `/api/load_kpis` | F01–F05 | Caracterizada |
| EST | Estadísticas de cerrados por fecha de arranque, confirmados / sin confirmar | `statRows`, `statDateOf`, `isConfirmedClose` | F01–F05, K01, K08, K10, K11 | Caracterizada |
| EST-CSV | Exportación CSV de estadísticas | `statExportCSV` | — | Hecho (H3F, UF08–UF11) |

## 2. Operativa diaria de la tabla de un banco

| ID | Funcionalidad | Implementación 4.9.4 | Casos | Estado |
|---|---|---|---|---|
| A1-EDIT | Edición de celdas con guardado diferido (1,2 s) y validación del fin real | `bindRows`, `onEdit`, `save`, `scheduleSync` | E01–E24 | Caracterizada |
| A2-STATE | Estado En plazo / En retraso; En plazo borra el motivo; aplicar sugerencia | `setManualState`, `applySuggestion` | S01–S03 | Caracterizada |
| A3-PIN | 📌 comprometer, ↩ deshacer (con confirmación), Confirmar todas | `confirmDatesRow`, `unconfirmDatesRow`, `confirmAll` | C01–C05 | Caracterizada |
| A4-ORDER | Cambiar prioridad arrastrando (una o varias filas seleccionadas) | `bindRows` (drop) | Q01, Q02 | Caracterizada |
| A5-SEL | Selección múltiple: duplicar, mover a otro banco y posición, borrar | `duplicateSel`, `moveSel`, `deleteSel`, `delRow` | Q03–Q08 | Caracterizada (D3) |
| A6-ADD | Añadir ensayo al final de los activos con responsable por defecto | `addRow` (2 capas), `defaultResp` | Q09 | Caracterizada |
| A7-ID | Approval ID: color por estado y buscador de la base de aprobaciones | `idStatus`, `idClass`, `openIdPicker`, `/api/approvals*` | — | Hecho (H3A; buscador de IDs sin caso de paridad, validado a mano) |
| A8-DONE | Lista de cerrados: restaurar, reutilizar, ✎ corregir (solo Admin) | `renderDoneList`, `restoreDone`, `reuseDone`, `openCloseModal(…, true)` | K10–K15 | Caracterizada (D1, D2) |
| A9-GANTT | Gantt diario, zoom, foco por proyecto | `renderGantt*`, `gZoom` | — | Hecho (H3A; foco y tirador en H3Z) |
| A9-XLSX | Exportar el Gantt a Excel (lo genera el backend) | `exportGanttExcel`, `/api/export_gantt` | — | Hecho (H3F, `ExcelGanttTest`, UF01–UF07) |
| A10-COLS | Orden y ancho de columnas por usuario | `COLDEFS`, `COLORDER_VER`, localStorage | — | Hecho (H3A; las preferencias de la 4.9.4 no se heredan, D35) |
| A11-CLOSE | Ventana de cierre: valores propuestos, 8 validaciones en orden, desviación, sugerencia, días facturables, foto final | `openCloseModal`, `closeRecalc`, `confirmClose`, `setDowntime` | K01–K09 | Caracterizada |
| RES-ROW | Recursos auxiliares asignados al ensayo y aviso de conflicto | `resConflictsForRow`, `openResPicker` | — | Hecho (H3D) |

## 3. Vistas

| ID | Vista | Implementación 4.9.4 | Estado |
|---|---|---|---|
| V-DASH | Dashboard: bloques semanales, plan anual, gráfica de ocupación por CW, tabla de ocupación por tipo, preferencias locales | `renderDash`, `trendData`, `utilCardHTML` | Hecho (H3B) |
| V-RESP | By Responsible y Manager View | `current='__resp__'`, `shiftWeekResp` | Hecho (H3B) |
| V-MONDAY | Vista de la reunión del lunes | `renderMonday` | No existe en la 4.9.4 (`renderMonday` no está definida ni se llega a la vista): código muerto |
| V-KPIHIST | KPI History: estadísticas, filtros, foto semanal guardada | `renderKpiHistory`, `statQuick` | Hecho (H3C) |
| V-RES | Equipos auxiliares: catálogo, bloqueos, Gantt de recursos | `__resources__`, `__rescfg__`, `/api/*resource*` | Hecho (H3D) |
| V-CFG | Configuración: bancos y categorías, proyectos, usuarios y roles, calendario, importar/exportar JSON, exportar HTML de solo lectura, About | `__benches__`, `__projects__`, `__users__`, `__tools__`, `__about__` | Hecho (H3E y H3F) |
| V-ACT | Registro de actividad | `__activity__`, `/api/activity_log` | Hecho (H3E) |

## 4. Transversal, sincronización e integración

| ID | Funcionalidad | Implementación 4.9.4 | Estado |
|---|---|---|---|
| SYNC-LOAD | Lectura autoritativa al arrancar (3 intentos), candado de solo lectura si falla | `lbBootstrap`, `LOADED_OK` | Caracterizada (arranque del laboratorio) |
| SYNC-PUSH | Envío por diferencias (`upserts`/`deletes`), serializado, debounce 1,2 s | `lbPush`, `runSync`, `lbBuild` | Caracterizada (campo `guardados` de cada caso) |
| SYNC-POLL | Relectura cada 20 s salvo foco en un campo | `lbPoll`, `editingNow` | Caracterizada parcialmente (D17); concurrencia en H2 |
| SYNC-CFG | Catálogos compartidos (bancos, proyectos) y su reconciliación | `loadConfig`, `pushConfig`, `/api/*_config` | Hecho (H3E; relectura cada 60 s en H3Z) |
| ROL | Roles Admin / Editor / ReadOnly aplicados en el navegador | `applyRoleUI`, `isAdmin`, `isReadOnlyUser` | Caracterizada (R01, R02, K12) (D14) |
| USERS | Identificación SSO, siembra de usuarios, gestión de usuarios | `/api/whoami`, `/api/save_users` | Hecho (H2 y H3E; orden de Admin corregido, D29) |
| PRES | Presencia (latido 15 s, TTL 75 s) | `/api/presence` | Hecho (H3Z, `pruebas-cierre.cjs`) (D9) |
| LOG | Registro de actividad (connect, edit, res_block…) | `_append_activity` | Hecho (H2) |
| VER | Versión publicada y aviso de versión antigua | `/api/app_version`, `_publish_version` | Hecho (H3Z; publicar la versión queda desactivado hasta la distribución) |
| MIRROR | Columnas espejo de SharePoint en segundo plano | `safe_body`, `_mirror_*` | Hecho (H2) (D13) |
| SP | Acceso a SharePoint: sesión SSO por Edge + CDP, FormDigest, MERGE, papelera, paginación, columnas que faltan | backend Python | Hecho (H2; escritura a validar en la oficina) |
| APP | Arranque, apagado al cerrar la pestaña, diagnóstico `/api/diag*` | `.bat`, `/api/shutdown`, `/api/diag*` | Hecho el apagado (H2/H3C1); diagnóstico `/api/diag*` y abrir el navegador al arrancar se dejan para el lanzador (D36) |
| UPD | Autoactualización (desactivada: `RELEASE_FOLDER` vacío) | `/api/update_info`, `/api/self_update` | Se mantiene desactivada |
| OFFLINE | Base de IDs importada y modo HTML de solo lectura exportado | `ID_DB_SEED`, `exportReadOnly` | Hecho: foto de solo lectura (H3F, D31) y consulta sin conexión con la última copia (H3Z); la semilla de IDs la sustituye la consulta en vivo |

## 5. Pruebas manuales del bloque A (Pruebas_manuales_bloque_A.xlsx)

El Excel se escribió para un escenario anterior (fecha 26-oct-2026 y filas
concretas que ya no existen con esos datos). Sus 32 pruebas se han trasladado a
casos automáticos sobre el escenario real anonimizado:

| Excel | Caso(s) | Excel | Caso(s) |
|---|---|---|---|
| 1 | E01 | 17 | Q06 |
| 2 | E02 | 18 | Q09 |
| 3 | E03 | 19 | A7-ID (hecho en H3A) |
| 4 | E06 | 20 | K14, K15 |
| 5 | S01 | 21 | K13 |
| 6 | S02 | 22 | K10, K11 |
| 7 | E11 | 23–26 | Solo React: validadas en H3A |
| 8 | K01 | 27 | Concurrencia: H2 (aviso de conflicto aprobado) |
| 9 | C01 | 28 | Sin conexión: H3Z (`pruebas-cierre.cjs`, `CopiaSinConexionTest`) |
| 10 | C02 | 29 | R01 |
| 11 | C04 | 30–32 | SharePoint en solo lectura: H4 (validación en la oficina) |
| 12 | C05 | | |
| 13 | Q01 | | |
| 14 | Q02 | | |
| 15 | Q03 | | |
| 16 | Q04 | | |
