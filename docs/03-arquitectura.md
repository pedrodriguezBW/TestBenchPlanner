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

---

# Interfaz React (Hito 3)

## Bloques

| Bloque | Contenido | Estado |
|---|---|---|
| A | Vista de banco: tabla de ensayos (30 columnas), Gantt, cierre, lista de cerrados, selección, mover, ordenar, aprobaciones | **Hecho** |
| B | Dashboard y «By Responsible» | **Hecho** |
| C | KPI History y estadísticas | **Hecho** (salvo «Exportar CSV», que va con el bloque F) |
| D | Equipos auxiliares (columna y selector en bancos, List of Equipments, Equipments List) | **Hecho** |
| E | Configuración (Test Benches, Projects, Users, Tools, About, Activity log) | **Hecho** (Export/Import JSON con el bloque F) |
| F | Exportaciones (incluida la del Gantt a Excel) | Pendiente |

Mientras un bloque no está migrado, su entrada del menú lo indica y enlaza a la
interfaz clásica (`/clasico`); no se ha inventado nada en su lugar.

## Estructura (`frontend/src`)

```
api/          tipos.ts (contrato /api/v1) · cliente.ts (errores tipados: 409, 422, sin conexión)
estado/       Planificador.tsx: carga, cola de guardado, conflictos, relectura de 20 s, latido
componentes/  Dialogo, SelectorFecha (calendario propio con CW), Avisos (toasts, mensajes, conflicto)
utilidades/   fechas, textos (etiquetas y colores de la 4.9.4), preferencias (localStorage)
bloques/banco TablaEnsayos, columnas, Celdas, Gantt, VentanaCierre, ListaCerrados,
              BuscadorAprobaciones, BarraSeleccion, VistaBanco
bloques/kpi   VistaKpiHistorico, Estadisticas
bloques/recursos CeldaRecursos, SelectorRecursos, VistaRecursos, ListaEquipos
bloques/configuracion ConfigBancos, ConfigProyectos, ConfigUsuarios, ConfigHerramientas
              (+ VentanaCalendario), AcercaDe, RegistroActividad
bloques/panel VistaDashboard, VistaResponsable, BloquesKpi, GanttGeneral,
              GraficaOcupacion, TablaOcupacion, NavegacionSemana, comunes
estilos/      base-4.9.4.css (el CSS original, sin cambios) · aplicacion.css (lo nuevo)
```

## Reparto de responsabilidades

- **Toda la lógica de negocio está en el backend.** La interfaz no calcula
  fechas, estados, sugerencias ni validaciones: pinta `calculo` de cada fila y
  envía cambios (`/campos`, `/operaciones`, `/cierre`). Así hay una sola
  implementación de las reglas, la que ya pasó la paridad en H2.
- La interfaz conserva el comportamiento de sincronización de la 4.9.4:
  - espera de 1,2 s antes de guardar una edición; los cambios van de uno en uno;
  - relectura cada 20 s (sin pisar lo que se está escribiendo);
  - al arrancar: 3 intentos separados 1,5 s y, si fallan, bloqueo en solo lectura con aviso;
  - sin conexión al guardar: la edición se queda en cola y se reintenta cada 5 s.
- **Conflictos (D12):** cada cambio lleva la versión de la fila. Si el backend
  responde 409, se muestra «Otra persona ha cambiado este ensayo» con
  «Ver su versión» (se descarta lo mío y se relee) o «Guardar la mía encima»
  (se reenvía con `forzar`).
- **Reglas (422):** el mensaje del backend se muestra tal cual, como hacía el
  `alert` de la 4.9.4.
- Las preferencias de pantalla (orden y ancho de columnas, zoom del Gantt,
  reparto tabla/Gantt) se guardan en el navegador, como antes.

## Cómo llega al usuario

`npm run build` genera `frontend/dist`; Maven lo mete dentro del jar como
recursos estáticos y el backend lo sirve en `http://127.0.0.1:5001/`. La
interfaz clásica sigue disponible en `/clasico` como respaldo. El zip ya trae
`frontend/dist` compilado, así que en el PC **no hace falta Node**. Para
recompilar la interfaz sin instalar nada: `mvnw.cmd -Pinterfaz clean verify`
(descarga un Node portátil dentro de `backend/target`).

## Bloque B: Dashboard y «By Responsible»

- **Cálculos en el backend** (`/api/v1/indicadores`):
  - `bloques?lunes&org&responsable`: «Esta semana» / «Semana que viene»
    (blockKPIs); con `responsable` solo cuentan sus ensayos.
  - `tabla-ocupacion?desde&hasta&org`: por tipo de banco, bancos, trabajando,
    horas ocupadas/disponibles, % e inicios/fines (utilCardHTML). Dominio:
    `PanelDashboard.tabla`.
  - `tendencia?cwDesde&cwHasta&org`: ocupación semana a semana del año, en % y
    en horas (trendData). Dominio: `PanelDashboard.tendencia`.
  - `foto-semanal`: 📊 Update Yearly Status (sin cambios desde H2; D21 sigue
    pendiente de decidir en el bloque C).
- **En la interfaz** solo se agrupa lo que ya trae la vista: el Gantt de todos los
  bancos, la carga del equipo (activos / corriendo / en retraso por persona) y el
  aviso de ensayos sin Approval ID aprobado.
- **Estado compartido** (`estado/Panel.tsx`): semana que se mira, zoom, filtro
  Org, ancho de día, responsable elegido y rango de la gráfica, comunes a las dos
  pantallas y vigentes mientras la pestaña está abierta, como las variables
  globales de la 4.9.4. Las preferencias de las tarjetas (`bw_tbp_viewprefs_v1`)
  se guardan en el navegador con la misma clave que la 4.9.4; el alto de las
  tarjetas, en `tbp_alturas_tarjetas`.
- Ajustes hechos en el Gantt del banco al construir el general: la línea de HOY
  empieza al principio del día (antes, medio día más a la derecha), las cabeceras
  quedan fijas al hacer scroll vertical, el proyecto en foco se resalta (`g-foc`)
  y, sin ensayos con fechas, no se muestra la barra de herramientas. Todo como en
  la 4.9.4.

## Bloque C: KPI History

- `GET /api/v1/indicadores/historico?org` devuelve `{filas, semanas}`: las fotos
  semanales de la organización ordenadas por año y CW (kpiHistRows, con la
  lectura antigua de «en retraso» para semanas anteriores a la 4.9.3) y el
  número total de filas leídas, para el texto «Source: SharePoint (N rows)».
- `GET /api/v1/indicadores/estadisticas?desde&hasta&org&responsable` devuelve los
  ensayos cerrados del periodo (statRows) con su estado final, motivo, si el
  cierre está confirmado y su DataJson.
- La pantalla agrupa esos datos para mostrarlos (medias de la foto semanal,
  ensayos por mes y por trimestre, motivos); no recalcula estados ni fechas.
- El histórico se lee al abrir la pantalla, al cambiar Org y con «Reload from
  SharePoint». Solo el bloqueo por falta de conexión impide leerlo; el rol de
  solo lectura sí lo ve, como en la 4.9.4.
- Org y filtros de estadísticas se conservan al cambiar de pantalla mientras la
  pestaña está abierta (KPIHIST_ORG, STAT_FROM/TO/RESP de la 4.9.4).
- Pendiente del bloque F: «⬇️ Exportar CSV».

## Bloque D: equipos auxiliares

- **Catálogo y bloqueos** (`estado/Recursos.tsx`): se leen con `GET /api/v1/recursos`
  al arrancar y después de cada cambio; si la lectura falla se mantiene la última
  copia buena. Cada cambio del catálogo guarda la lista completa (`PUT /recursos`),
  igual que `resSaveCatalog`, protegido por D11 (no se guarda sin una lectura
  correcta previa ni se vacía la lista).
- **Asignar o quitar un equipo** usa la operación `alternarRecurso` del banco, con
  versión de fila y aviso de conflicto como el resto de operaciones.
- **Renombrar un equipo** (`POST /api/v1/recursos/renombrar`, solo Admin): cambia el
  nombre en todos los ensayos que lo usan, activos y cerrados, y después se guarda
  el catálogo, en el mismo orden que la 4.9.4.
- **Ocupación de un equipo** (quién lo usa entre dos fechas, solapes, «!» de la
  fila): se calcula en la interfaz con las fechas que ya da el backend para cada
  ensayo y con los bloqueos; es un cruce de fechas para mostrar, no una regla que
  cambie datos.
- Bloquear un equipo y borrar bloqueos: botones solo para Admin, como en la 4.9.4.

## Bloque E: configuración

- **Test Benches** (`PUT /api/v1/configuracion/bancos`, solo Admin): la interfaz
  envía la lista completa de bancos (como `cfgSnapshot`), los proyectos tal como
  están y los bancos renombrados. `ServicioConfiguracionBancos` calcula con
  `Planificacion.reconfigurar` qué ensayos cambian, guarda primero la lista de
  bancos (con la protección D11: si la rechaza no se escribe nada) y después solo
  los ensayos renombrados o borrados (D28). No se puede renombrar a un banco que
  ya existe (400). Las categorías nuevas sin bancos viven solo en la pantalla
  hasta que se les añade un banco, como en la 4.9.4.
- **Projects** (`PUT /api/v1/catalogos`, Admin y Editor): guarda la lista de
  proyectos con la de bancos tal como se leyó. «Clear all» lo bloquea D11 (412).
- **Users/Rights Management** (`PUT /api/v1/usuarios`, solo Admin). Orden de la
  tabla como en la 4.9.4 (D29) y estado del guardado visible (D30).
- **Tools**: calendario de festivos de este PC (`/api/v1/calendario`), IDs live
  (consulta inmediata de estados de aprobación) y Reset local (D27). Export/Import
  JSON abren `/clasico` hasta el bloque F.
- **About**: versión nueva y el histórico de la 4.9.4 sin cambios (D26).
- **Activity log** (solo Admin): `GET /api/v1/actividad`, solo consulta.
- En solo lectura, búsquedas y consultas de estas pantallas siguen activas; solo
  se desactivan los controles que guardan.

