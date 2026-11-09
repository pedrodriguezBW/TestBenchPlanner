# Auditoría de cierre de la migración TestBenchPlanner 4.9.4 → React + Java

Fecha: 9-nov-2026 · Fuente: `legacy/BW_TestBench_Planner_31_Local.html` (script analizado con acorn: 452 definiciones de función, 373 nombres de nivel superior; las funciones internas de otras funciones se cubren con su contenedora), `legacy/backend_testbench_v4_9_4.py` (31 rutas /api + raíz), `frontend/src`, `backend/src/main/java`, docs/01–03 y README.
Alcanzabilidad: grafo de llamadas desde el código de nivel superior, los manejadores del HTML estático y los `onclick/onchange/oninput` dentro de cadenas HTML (también setTimeout/addEventListener/window.x), revisado a mano. Las líneas son las del HTML 4.9.4.

## Resumen

**Veredicto: la migración NO se puede declarar cerrada todavía.** Quedan 10 huecos (0 de gravedad alta, 3 media, 7 baja). Todo lo demás está migrado, es código muerto, está decidido o lo cubre la arquitectura nueva.

| Categoría | función | método | ruta | semilla | temporizador | manejador | vista | Total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HUECO | 11 | 0 | 6 | 1 | 8 | 0 | 0 | 26 |
| NO_MIGRADA_DECIDIDO | 7 | 0 | 3 | 2 | 2 | 0 | 0 | 14 |
| CODIGO_MUERTO | 25 | 0 | 0 | 2 | 0 | 0 | 2 | 29 |
| INFRAESTRUCTURA | 13 | 0 | 0 | 7 | 3 | 0 | 1 | 24 |
| MIGRADA | 317 | 31 | 23 | 20 | 16 | 4 | 6 | 417 |
| **Total** | 373 | 31 | 32 | 32 | 29 | 4 | 9 | 510 |

### Huecos

| ID | Hueco | Gravedad | Dónde se ve en la 4.9.4 | Elementos |
|---|---|---|---|---|
| H-01 | Presencia: quién está conectado | **media** | Barra superior, todas las pantallas (cualquier rol; no en la foto READONLY). | `pollPresence`, `renderPresence`, `v40Init setTimeout 2 s + setInterval 15 s`, `PRESENCE_ONLINE` |
| H-02 | Aviso de versión antigua y publicación de la versión | **media** | Banner #ver-banner bajo la barra superior, todas las pantallas. | `checkVersion`, `verParts`, `cmpVer`, `isStaleVersion`, `setInterval(checkVersion,120 s)`, `Python publish_version_bg (Timer 3 s)` |
| H-03 | Diagnósticos /api/diag* | **baja** | Ninguna (URL directa, uso de soporte/Admin). | `/api/diag (parcial)`, `/api/diag_kpi`, `/api/diag_config`, `/api/diag_users`, `/api/diag_activity`, `/api/diag_resources` |
| H-04 | Modo offline de consulta (última copia local) | **media** | Arranque sin red/SSO (todas las pantallas en solo consulta). | `load() (copia bw_bench_planner_v10 mostrada sin conexión)`, `saveUsersSnap`, `loadUsersSnap`, `showOfflineSnapBanner`, `OFFLINE_USERS_KEY` |
| H-05 | Tostada de bienvenida | **baja** | Arranque (cualquier pantalla). | `showWelcome`, `setTimeout 3 s` |
| H-06 | Relectura periódica de catálogos (proyectos y usuarios) | **baja** | Sugerencias de las columnas Project/Responsible, desplegables de By Responsible, Projects. | `pollConfig`, `setInterval(pollConfig, 60 s)` |
| H-07 | Cierre del calendario con Escape y al redimensionar | **baja** | Celdas de fecha de la tabla del banco, ventana de cierre y bloqueos de equipos. | `listener keydown Escape → calClose`, `listener resize → calClose` |
| H-08 | Tiradores: soporte táctil y doble clic en la columna de etiquetas del Gantt de banco | **baja** | Banco, Dashboard, By Responsible, List of Equipments. | `touchstart/touchmove/touchend en bindCardResizers, bindBenchSplit, bindColHeaders, enhanceGantts`, `dblclick en .g-lwgrip del banco` |
| H-09 | Desplegables con sugerencias muestran la lista completa al entrar | **baja** | Tabla del banco (columnas con datalist). | `document focusin/focusout (v3.6, ítem 6)` |
| H-10 | Abrir el navegador al arrancar el backend | **baja** | Arranque de la aplicación (.bat). | `Python threading.Timer(1,5 s, _open_browser_once)` |

#### H-01 · Presencia: quién está conectado (media)
- **Qué hace:** Cada 15 s (tras 2 s) envía el latido de presencia (/api/presence) y pinta en la barra superior hasta 6 burbujas con iniciales y color de cada persona conectada (la propia resaltada, «+N» y «N en línea»). Sirve para saber quién más está editando antes de tocar un banco.
- **Por qué es hueco:** D9 decidió «imitar por ahora» la presencia; el backend Java la tiene (/api/v1/presencia, /api/presence, TTL 75 s), pero React nunca la llama ni la pinta. Inventario PRES sigue «Pendiente».
- **Propuesta:** React: añadir presencia(): POST /api/v1/presencia en api/cliente.ts; hook usePresencia en estado/ (setTimeout 2 s + setInterval 15 s, no si la sesión no cargó) y componente Presencia en App.tsx dentro de .presence-wrap (clases .pres-av/.me/.pres-more/.pres-lbl ya están en base-4.9.4.css; MAX 6, title con nombre y «(tú)»). Java: nada (ya devuelve {me, online:[{email,name,initials,color,me}]}); añadir prueba en pruebas-interfaz.cjs con el backend simulado.

#### H-02 · Aviso de versión antigua y publicación de la versión (media)
- **Qué hace:** Al arrancar, el backend Python publica su versión en TestBenchAppConfig/CurrentVersion (sin rebajarla nunca). Cada 2 min la interfaz consulta /api/app_version y, si la publicada es mayor que la suya (comparación numérica), muestra el banner naranja «Estás usando una versión antigua (vX). Hay disponible vY. Cierra y vuelve a abrir».
- **Por qué es hueco:** Java tiene Colaboracion.publicarVersion() y planner.sharepoint.publicar-version, pero ningún código la llama; React no consulta /api/app_version ni muestra banner. Sin esto, en la retirada (H4) ni los PCs con 4.9.4 ni los que tengan un jar antiguo se enteran de la versión nueva. Inventario VER «Pendiente».
- **Propuesta:** Java: un @EventListener(ApplicationReadyEvent) asíncrono que llame a colaboracion.publicarVersion(TestBenchPlannerApplication.VERSION) (respeta publicar-version y escritura-habilitada, nunca rebaja); GET /api/v1/version {backend, published}. React: hook que lo consulte al arrancar y cada 120 s, compare por tramos numéricos (portar verParts/cmpVer a utilidades/) y pinte #ver-banner en App.tsx con el mismo texto. Decidir con Pedro cuándo activar publicar-version (publicar 5.0.0 hará saltar el aviso en todos los PCs con 4.9.4).

#### H-03 · Diagnósticos /api/diag* (baja)
- **Qué hace:** Rutas de soporte (navegador o Postman) que dicen qué columnas esperadas existen o faltan en cada lista de SharePoint (planner, KPI, catálogos, usuarios, actividad, recursos) y su tipo. El changelog de About las cita para diagnosticar el registro de actividad y los recursos.
- **Por qué es hueco:** Java solo tiene GET /api/diag con versión y número de filas; las otras cinco dan 404. Como Java ya no crea listas ni columnas, es justo la herramienta para validar las listas de producción en H4. Inventario APP «Pendiente (H2/H4)».
- **Propuesta:** Java: DiagnosticoController en api/compat con GET y POST para las 6 rutas; cada una lee {lista}/fields?$select=InternalName,TypeAsString con ClienteSharePoint (solo lectura) y compara con la lista de columnas esperadas que ya usan AlmacenEnsayosSharePoint/ListasAuxiliaresSharePoint (extraerlas a constantes). Mismo formato JSON que Python (list, present, missing, types). En modo local devolver {almacen:"local"}.

#### H-04 · Modo offline de consulta (última copia local) (media)
- **Qué hace:** Si no se puede leer SharePoint, la 4.9.4 sigue mostrando la última planificación guardada en el navegador y los menús según la última copia de usuarios, con el banner «✈ Modo offline · solo consulta. Mostrando la última copia local (dd/mm hh:mm)» y todo bloqueado para escribir.
- **Por qué es hueco:** React solo muestra el candado rojo y la pantalla vacía: no hay copia local (D27 lo menciona como consecuencia, pero no hay decisión explícita de renunciar a la consulta offline; inventario apartado 5, prueba 28 «Sin conexión: H2»).
- **Propuesta:** O bien documentar la renuncia como D33, o bien: Java guarda la última lectura buena (filas + catálogos + usuarios, con fecha) en planner.carpeta-usuario/cache/; si SharePoint falla, GET /api/v1/planificacion y /sesion devuelven esa copia con {offline:true, copiaDe} y soloLectura=true, sin contarla como lectura válida en RegistroLecturas (D11). React: si offline, pintar la vista en solo lectura con el banner azul de la 4.9.4 en lugar de la pantalla vacía.

#### H-05 · Tostada de bienvenida (baja)
- **Qué hace:** Al identificar al usuario muestra 3 s abajo a la derecha «👋 Welcome back · <nombre> · Administrator/Editor/Read-only access».
- **Por qué es hueco:** No existe en React; el CSS #welcome-toast sí está en base-4.9.4.css.
- **Propuesta:** React: en App.tsx, un useEffect sobre sesion que active un estado visible durante 3 s y pinte <div id="welcome-toast" className="show"> con .wt-hi/.wt-name/.wt-role (displayName || responsable || email; texto según rol).

#### H-06 · Relectura periódica de catálogos (proyectos y usuarios) (baja)
- **Qué hace:** Cada 60 s (si no se está escribiendo) relee bancos, proyectos y responsables compartidos y actualiza menús y sugerencias con lo que hayan cambiado otros.
- **Por qué es hueco:** En React los bancos llegan con la relectura de 20 s, pero proyectos y usuarios (recargarListas) solo se leen al arrancar o tras cambiarlos uno mismo: un proyecto dado de alta por otra persona no aparece hasta recargar.
- **Propuesta:** React: en estado/Planificador.tsx, setInterval(recargarListas, 60000) con las mismas guardas que la relectura (no si hay foco en INPUT/SELECT/TEXTAREA, modal abierto o envío en curso).

#### H-07 · Cierre del calendario con Escape y al redimensionar (baja)
- **Qué hace:** El calendario emergente de las celdas de fecha se cierra con la tecla Escape o al cambiar el tamaño de la ventana. Es el único atajo de teclado de la 4.9.4.
- **Por qué es hueco:** SelectorFecha.tsx solo cierra al pulsar fuera.
- **Propuesta:** React: en el useEffect de Calendario (componentes/SelectorFecha.tsx) añadir document keydown (Escape → onCerrar) y window resize → onCerrar, con su limpieza.

#### H-08 · Tiradores: soporte táctil y doble clic en la columna de etiquetas del Gantt de banco (baja)
- **Qué hace:** Los divisores (alto de tarjetas, reparto tabla/Gantt, ancho de columnas, ancho de etiquetas del Gantt) también se arrastran con el dedo; en el Gantt del banco, doble clic en el tirador vuelve al ancho por defecto.
- **Por qué es hueco:** React solo usa onMouseDown; Gantt.tsx no tiene onDoubleClick en .g-lwgrip (GanttGeneral y VistaRecursos sí).
- **Propuesta:** React: cambiar onMouseDown/mousemove/mouseup por Pointer Events (onPointerDown + setPointerCapture, pointermove/pointerup) en VistaBanco.tsx, TablaEnsayos.tsx, panel/comunes.tsx, Gantt.tsx, GanttGeneral.tsx y VistaRecursos.tsx; añadir onDoubleClick={() => setAnchoEtiqueta(null)} al .g-lwgrip de Gantt.tsx.

#### H-09 · Desplegables con sugerencias muestran la lista completa al entrar (baja)
- **Qué hace:** Al entrar en un campo con lista de sugerencias (Type, Responsible, Project, Approval ID) con valor, la 4.9.4 vacía el campo temporalmente (dejando el valor como placeholder) para que Edge muestre todas las opciones; si sales sin escribir, restaura el valor.
- **Por qué es hueco:** Celdas.tsx (Entrada) no lo hace: con valor, Edge filtra por el texto actual y solo enseña coincidencias.
- **Propuesta:** React: en Entrada (bloques/banco/Celdas.tsx), si hay lista: en onFocus guardar el valor, ponerlo como placeholder y mostrar '' en local; en onBlur, si sigue vacío, restaurar sin llamar a onCambio. Cubrir con un caso en comparar-interfaz.cjs.

#### H-10 · Abrir el navegador al arrancar el backend (baja)
- **Qué hace:** Al lanzar el backend se abre solo http://127.0.0.1:5001 en el navegador.
- **Por qué es hueco:** Spring Boot no lo hace; el plan lo deja para el «lanzador» de H4, pero no hay implementación ni decisión de prescindir.
- **Propuesta:** Java: ApplicationReadyEvent → abrir msedge/Desktop.browse("http://127.0.0.1:5001/") salvo propiedad planner.abrir-navegador=false (desactivada en pruebas), o hacerlo en el lanzador .bat de H4.

### Rutas /api compatibles (backend/src/main/java/com/borgwarner/tbp/api/compat)
- Existen 26 de las 31: 22 completas, 3 como no-op deliberado (`/api/update_info`, `/api/self_update`, `/api/release_file`: equivalentes a la 4.9.4 con RELEASE_FOLDER vacío; inventario UPD) y 1 parcial (`/api/diag`). Ninguna responde 501.
- **No implementadas (404): `/api/diag_kpi`, `/api/diag_config`, `/api/diag_users`, `/api/diag_activity`, `/api/diag_resources`.** `/api/diag` existe pero con otro contenido (H-03).
- Diferencias de método: `/api/presence` y `/api/load_resources` solo POST (Python también GET); `/api/diag` solo GET (Python GET y POST). `/api/activity_log` ignora `top`. Ninguna afecta al HTML 4.9.4 (siempre usa POST), solo a llamadas manuales.

### Otras diferencias detectadas sin documentar (no son huecos, conviene registrarlas en 02-discrepancias)
1. El foco de proyecto del Gantt ahora se recuerda en el navegador (`tbp_gantt_foco`); en la 4.9.4 duraba lo que la pestaña.
2. El buscador de Approval IDs carga la lista viva una vez por pantalla de banco (la 4.9.4 la recargaba en cada apertura) y ya no mezcla la base local/semilla de IDs: si la lectura falla la lista sale vacía.
3. El orden y ancho de columnas se guardan con otra clave (`tbp_columnas_v1`): las preferencias personalizadas de la 4.9.4 no se heredan.
4. `/api/health` lee los ensayos (Python solo comprobaba la sesión) y `/api/diag` cambia de contenido.
5. `refreshRunning()` se sigue llamando en la 4.9.4 pero está vacía; `renderMonday` no existe (la vista «__monday__» del inventario no se puede migrar porque nunca existió en la 4.9.4).

## Matriz completa

| Nombre | Línea 4.9.4 | Tipo | Categoría | Dónde / motivo |
|---|---|---|---|---|
| `checkVersion()` | 1969 | función | HUECO | H-02: banner «Estás usando una versión antigua» |
| `cmpVer()` | 1966 | función | HUECO | H-02 |
| `isStaleVersion()` | 1968 | función | HUECO | H-02 |
| `loadUsersSnap()` | 1931 | función | HUECO | H-04 |
| `pollConfig()` | 1633 | función | HUECO | H-06: los bancos se releen con la vista cada 20 s, pero proyectos y responsables (dl-proj, dl-resp) solo al arrancar o tras cambiarlos uno mismo |
| `pollPresence()` | 2012 | función | HUECO | H-01: latido de presencia cada 15 s (backend /api/v1/presencia existe, la interfaz no lo llama) |
| `renderPresence()` | 1998 | función | HUECO | H-01: avatares «N en línea» en la barra superior |
| `saveUsersSnap()` | 1930 | función | HUECO | H-04: copia local de usuarios para el modo offline |
| `showOfflineSnapBanner()` | 1932 | función | HUECO | H-04: banner «Modo offline · solo consulta» |
| `showWelcome()` | 1779 | función | HUECO | H-05: tostada «Welcome back» con nombre y rol al arrancar |
| `verParts()` | 1965 | función | HUECO | H-02 (comparación de versiones) |
| `/api/diag` |  | ruta | HUECO | H-03: existe solo GET y devuelve versión/filas; Python (GET/POST) informaba columnas presentes/faltantes de la lista, lastEnsure y totalFields |
| `/api/diag_activity` |  | ruta | HUECO | H-03: no existe en Java (404) |
| `/api/diag_config` |  | ruta | HUECO | H-03: no existe en Java (404) |
| `/api/diag_kpi` |  | ruta | HUECO | H-03: no existe en Java (404) |
| `/api/diag_resources` |  | ruta | HUECO | H-03: no existe en Java (404) |
| `/api/diag_users` |  | ruta | HUECO | H-03: no existe en Java (404) |
| `OFFLINE_USERS_KEY bw_offline_users_v1` |  | semilla | HUECO | H-04 (modo offline) |
| `focusin/focusout en inputs con datalist` |  | temporizador | HUECO | H-09 |
| `Python: Timer(1,5 s) _open_browser_once` |  | temporizador | HUECO | H-10 (previsto como «lanzador» en H4) |
| `Python: Timer(3 s) publish_version_bg` |  | temporizador | HUECO | H-02: Colaboracion.publicarVersion existe pero nadie la llama |
| `resize / keydown Escape → cerrar calendario` |  | temporizador | HUECO | H-07 |
| `setInterval(checkVersion, 120 s)` |  | temporizador | HUECO | H-02 |
| `setInterval(pollConfig, 60 s)` |  | temporizador | HUECO | H-06 |
| `showWelcome setTimeout 3 s` |  | temporizador | HUECO | H-05 |
| `v40Init: setTimeout(pollPresence, 2 s) + setInterval 15 s` |  | temporizador | HUECO | H-01 |
| `closeUpd()` | 2087 | función | NO_MIGRADA_DECIDIDO | UPD |
| `openUpd()` | 2086 | función | NO_MIGRADA_DECIDIDO | Inventario UPD «se mantiene desactivada» (RELEASE_FOLDER vacío); /api/update_info no-op |
| `runManualUpdate()` | 2100 | función | NO_MIGRADA_DECIDIDO | UPD (/api/release_file responde ok:false) |
| `runSelfUpdate()` | 2089 | función | NO_MIGRADA_DECIDIDO | UPD (/api/self_update responde ok:false) |
| `scheduleCfgSync()` | 1603 | función | NO_MIGRADA_DECIDIDO | D28: cada cambio se guarda en el acto, sin espera de 1,2 s |
| `upLog()` | 2088 | función | NO_MIGRADA_DECIDIDO | UPD |
| `v4CheckUpdate()` | 2121 | función | NO_MIGRADA_DECIDIDO | UPD: en React HTML y backend salen del mismo jar, no hay desajuste que avisar |
| `/api/release_file` |  | ruta | NO_MIGRADA_DECIDIDO | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java: no-op deliberado (ok:false), UPD |
| `/api/self_update` |  | ruta | NO_MIGRADA_DECIDIDO | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java: no-op deliberado (ok:false «RELEASE_FOLDER no configurado»), UPD |
| `/api/update_info` |  | ruta | NO_MIGRADA_DECIDIDO | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java: no-op deliberado (release_version vacío, canServerUpdate=false), inventario UPD |
| `RESPS_SEED` |  | semilla | NO_MIGRADA_DECIDIDO | D24 (sin la lista fija de 10 nombres) |
| `TODAY (congelado al abrir)` |  | semilla | NO_MIGRADA_DECIDIDO | D7: «hoy» se recalcula en cada petición (RelojConfigurable) |
| `scheduleCfgSync (espera 1,2 s) + envoltura window[n] de mutadores` |  | temporizador | NO_MIGRADA_DECIDIDO | D28 (guardado inmediato de configuración) |
| `v40Init: setTimeout(v4CheckUpdate, 4 s) + setInterval 120 s` |  | temporizador | NO_MIGRADA_DECIDIDO | UPD desactivada |
| `altColor()` | 471 | función | CODIGO_MUERTO | sin llamadas |
| `benchWorking()` | 716 | función | CODIGO_MUERTO | sin llamadas |
| `canReportDelay()` | 628 | función | CODIGO_MUERTO | sin llamadas |
| `checkId()` | 1272 | función | CODIGO_MUERTO | sin llamadas (comprobación antigua de ID) |
| `importIDDB()` | 1327 | función | CODIGO_MUERTO | sin llamadas (D32: no se migra) |
| `isBehind()` | 629 | función | CODIGO_MUERTO | sin llamadas |
| `isDelayed()` | 630 | función | CODIGO_MUERTO | sin llamadas |
| `isStale()` | 631 | función | CODIGO_MUERTO | sin llamadas (devuelve false) |
| `kpiCardsHTML()` | 801 | función | CODIGO_MUERTO | sin llamadas |
| `kpiStatusOf()` | 557 | función | CODIGO_MUERTO | sin llamadas |
| `launchNoProgress()` | 1108 | función | CODIGO_MUERTO | sin llamadas |
| `projEndFromProgress()` | 526 | función | CODIGO_MUERTO | sin llamadas |
| `reasonsLineHTML()` | 800 | función | CODIGO_MUERTO | solo la llama kpiCardsHTML (muerta) |
| `refreshRunning()` | 641 | función | CODIGO_MUERTO | se llama pero su cuerpo está vacío (no-op desde v3.x) |
| `reportedDelay()` | 624 | función | CODIGO_MUERTO | sin llamadas |
| `reportedStart()` | 621 | función | CODIGO_MUERTO | sin llamadas |
| `resTypes()` | 2385 | función | CODIGO_MUERTO | sin llamadas (solo citada en un comentario de frontend/src/estado/Recursos.tsx) |
| `riskLate()` | 529 | función | CODIGO_MUERTO | sin llamadas |
| `shade()` | 470 | función | CODIGO_MUERTO | solo la llama altColor, que tampoco se llama |
| `spSyncNow()` | 1499 | función | CODIGO_MUERTO | window.spSyncNow: sin llamadas (gancho de consola) |
| `statBarHTML()` | 2664 | función | CODIGO_MUERTO | sin llamadas |
| `statSetOrg()` | 2629 | función | CODIGO_MUERTO | sin llamadas (el filtro Org de estadísticas usa setKpiHistOrg) |
| `statusBadge()` | 632 | función | CODIGO_MUERTO | sin llamadas |
| `toggleClientView()` | 2250 | función | CODIGO_MUERTO | sin llamadas (modo cliente: solo queda el CSS .client-view) |
| `workDaysBetween()` | 490 | función | CODIGO_MUERTO | sin llamadas |
| `RISK_HORIZON_WEEKS` |  | semilla | CODIGO_MUERTO | declarada y nunca usada |
| `STTOL` |  | semilla | CODIGO_MUERTO | declarada y nunca usada |
| `modo cliente (toggleClientView / .client-view)` |  | vista | CODIGO_MUERTO | toggleClientView no se llama nunca |
| `vista __monday__ / renderMonday` |  | vista | CODIGO_MUERTO | renderMonday no está definida en ningún sitio y nada asigna current='__monday__': la rama de setManualState es inalcanzable (si se alcanzase daría ReferenceError) |
| `addResp()` | 1321 | función | INFRAESTRUCTURA | ídem renderResps (solo alcanzable en la foto READONLY, que en la versión nueva es el mismo HTML 4.9.4 incrustado (frontend/src/bloques/exportaciones/descargas.ts exportarSoloLectura, D31)) |
| `esc()` | 457 | función | INFRAESTRUCTURA | React escapa el texto al pintar (JSX) |
| `focusSearch()` | 1535 | función | INFRAESTRUCTURA | inputs controlados de React conservan foco/caret |
| `gRerender()` | 2197 | función | INFRAESTRUCTURA | React repinta solo |
| `load()` | 452 | función | INFRAESTRUCTURA | estado local bw_bench_planner_v10 sustituido por GET /api/v1/planificacion (frontend/src/estado/Planificador.tsx) |
| `loadIDDB()` | 409 | función | INFRAESTRUCTURA | caché local bw_id_db_v1 sustituida por consulta al backend (backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/AprobacionesSharePoint.java, D31) |
| `opt()` | 1004 | función | INFRAESTRUCTURA | helper de <option>; JSX |
| `renderResps()` | 1320 | función | INFRAESTRUCTURA | pantalla «Responsibles»: la v3.7 la quitó del menú; solo alcanzable en la foto READONLY, que en la versión nueva es el mismo HTML 4.9.4 incrustado (frontend/src/bloques/exportaciones/descargas.ts exportarSoloLectura, D31). Los responsables salen de usuarios+catálogo (D24) |
| `resLoadLocal()` | 2371 | función | INFRAESTRUCTURA | ídem (frontend/src/estado/Recursos.tsx) |
| `resSaveLocal()` | 2370 | función | INFRAESTRUCTURA | caché bw_resources_cache_v1 sustituida por la última copia buena en memoria (frontend/src/estado/Recursos.tsx) |
| `save()` | 453, 1498, 1777 | función | INFRAESTRUCTURA | guardado local + scheduleSync sustituido por cola de guardado de frontend/src/estado/Planificador.tsx (3 definiciones: original, v3.4 con scheduleSync y v3.7 con bloqueo ReadOnly) |
| `saveCache()` | 2040 | función | INFRAESTRUCTURA | cachés bw_kpihist_cache_v1 / bw_activity_cache_v1 sustituidas por lectura al abrir la pantalla |
| `saveIDDB()` | 410 | función | INFRAESTRUCTURA | ídem loadIDDB: sin caché local de IDs; estados vivos desde backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/AprobacionesSharePoint.java |
| `EMB / EMB_IDDB / EMB_IDROWS / READONLY` |  | semilla | INFRAESTRUCTURA | modo foto de solo lectura: se genera con el HTML 4.9.4 original (descargas.ts exportarSoloLectura, D31) |
| `ID_DB_SEED` |  | semilla | INFRAESTRUCTURA | estados vivos de la base de aprobaciones (backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/AprobacionesSharePoint.java, frontend/src/estado/Planificador.tsx); en la foto READONLY se incrusta el estado leído en ese momento (D31) |
| `KPI_CACHE_KEY / ACT_CACHE_KEY / RES_LS / bw_id_db_v1` |  | semilla | INFRAESTRUCTURA | cachés locales sustituidas por lecturas al backend |
| `LS bw_bench_planner_v10` |  | semilla | INFRAESTRUCTURA | sin copia local del estado: todo viene del backend (D27) |
| `PROJECTS_SEED` |  | semilla | INFRAESTRUCTURA | el catálogo de proyectos se lee siempre del backend (TestBenchProjects); la carga conjunta (D17) elimina la ventana en que se veían los de serie y D11 impide enviarlos |
| `SEED` |  | semilla | INFRAESTRUCTURA | objeto vacío: ensayos de ejemplo inexistentes; sin efecto |
| `TEST_BUILD` |  | semilla | INFRAESTRUCTURA | etiqueta de pruebas del HTML; la versión la fija el build |
| `Python: hilo _sp_worker_loop (cola SharePoint con prioridad)` |  | temporizador | INFRAESTRUCTURA | backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/ConexionEdge.java + ClienteSharePoint.java |
| `setTimeout(URL.revokeObjectURL)` |  | temporizador | INFRAESTRUCTURA | frontend/src/bloques/exportaciones/descargas.ts (descargar) |
| `v41Init (cachés KPI/actividad + setTimeout 6 s)` |  | temporizador | INFRAESTRUCTURA | las pantallas leen al abrirse (frontend/src/bloques/kpi/VistaKpiHistorico.tsx, RegistroActividad.tsx) |
| `vista __resps__ (Responsibles)` |  | vista | INFRAESTRUCTURA | solo en la foto READONLY (menú original); allí sigue siendo el HTML 4.9.4 (D31). En la app normal la v3.7 la quitó del menú |
| `__spSaveKPIs()` | 1500 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/v1/IndicadoresController.java (foto-semanal) |
| `_dayIndex()` | 2311 | función | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts |
| `_findUser()` | 1875 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx |
| `_resTypeMembers()` | 2571 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `actionLabel()` | 2041 | función | MIGRADA | frontend/src/bloques/configuracion/RegistroActividad.tsx (ACCIONES) |
| `activeRows()` | 642 | función | MIGRADA | frontend/src/bloques/banco/VistaBanco.tsx (filtro !cerrado) |
| `actualStartOf()` | 506 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `addBench()` | 1313 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `addCategory()` | 1308 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `addDays()` | 460 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java / frontend/src/utilidades/fechas.ts (sumarDias) |
| `addFree()` | 465 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `addHol()` | 1294 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `addProject()` | 1319 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx |
| `addResource()` | 2575 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `addResType()` | 2570 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `addRow()` | 1104, 1795 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (anadir; 2 capas: original + v3.7 responsable por defecto) |
| `addUser()` | 1877 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx |
| `applyConfig()` | 1574 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/ConfiguracionBancos.java |
| `applyFocus()` | 2223 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx / frontend/src/bloques/recursos/VistaRecursos.tsx |
| `applyGanttLW()` | 2203 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx |
| `applyRoleUI()` | 1764 | función | MIGRADA | frontend/src/App.tsx + frontend/src/estado/Planificador.tsx |
| `applySuggestion()` | 602 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (aplicarSugerencia) |
| `benchBusyHours()` | 741 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Ocupacion.java |
| `benchOrg()` | 782 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/ConfiguracionBancos.java |
| `bgRefreshActivity()` | 2060 | función | MIGRADA | frontend/src/bloques/configuracion/RegistroActividad.tsx (Recargar) |
| `bgRefreshKpi()` | 2073 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx (Reload from SharePoint) |
| `bindBenchSplit()` | 999 | función | MIGRADA | frontend/src/bloques/banco/VistaBanco.tsx (tbp_reparto_banco; sin táctil) |
| `bindCardResizers()` | 879 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx (sin soporte táctil, ver HUECO H-08) |
| `bindColHeaders()` | 1084 | función | MIGRADA | frontend/src/bloques/banco/TablaEnsayos.tsx (arrastrar columnas y redimensionar) |
| `bindReasonSelects()` | 1103 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx (columna reason) |
| `bindRows()` | 1090 | función | MIGRADA | frontend/src/bloques/banco/TablaEnsayos.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/EdicionEnsayo.java |
| `blockKPIs()` | 809 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/IndicadoresSemana.java |
| `buildActivityHTML()` | 2042 | función | MIGRADA | frontend/src/bloques/configuracion/RegistroActividad.tsx |
| `buildGanttMatrix()` | 2318 | función | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts (matrizGantt) + backend/src/main/java/com/borgwarner/tbp/infraestructura/excel/ExcelGantt.java |
| `calClear()` | 1011 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `calClose()` | 1008 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `calCloseEl()` | 1016 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `calMove()` | 1009 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `calPick()` | 1010 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `capHoursOf()` | 738 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Ocupacion.java |
| `cardHeights()` | 876 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx (tbp_alturas_tarjetas) |
| `cfgSnapshot()` | 1566 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx + frontend/src/api/tipos.ts |
| `cfOf()` | 478 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `clearList()` | 1323 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx (Clear all, bloqueado por D11) |
| `clearSel()` | 1097 | función | MIGRADA | frontend/src/bloques/banco/BarraSeleccion.tsx (onLimpiar) |
| `closeCfg()` | 1290 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `closeCloseModal()` | 1183 | función | MIGRADA | frontend/src/bloques/banco/VentanaCierre.tsx |
| `closeDone()` | 1280 | función | MIGRADA | frontend/src/bloques/banco/ListaCerrados.tsx |
| `closeIdPicker()` | 1285 | función | MIGRADA | frontend/src/bloques/banco/BuscadorAprobaciones.tsx |
| `closeRead()` | 1206 | función | MIGRADA | frontend/src/bloques/banco/VentanaCierre.tsx |
| `closeRecalc()` | 1212 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/Cierre.java (cierre/validacion) |
| `closeResPicker()` | 2437 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx |
| `collect()` | 692 | función | MIGRADA | frontend/src/App.tsx (datalists dl-proj/dl-ids) |
| `collectApprovalIds()` | 1706 | función | MIGRADA | frontend/src/estado/Planificador.tsx (idsAprobacion) |
| `colOrder()` | 1080 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx (ordenColumnas) / frontend/src/bloques/banco/TablaEnsayos.tsx (tbp_columnas_v1) |
| `colWidths()` | 1081 | función | MIGRADA | frontend/src/bloques/banco/TablaEnsayos.tsx |
| `computeHolidays()` | 416 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `computeSchedule()` | 644 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/MotorFechas.java |
| `confirmAll()` | 1118 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `confirmClose()` | 1240 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/Cierre.java (D1) |
| `confirmDatesRow()` | 1105 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `cw()` | 469 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java / frontend/src/utilidades/fechas.ts (semanaIso) |
| `d0()` | 459 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java / frontend/src/utilidades/fechas.ts |
| `dateInput()` | 1005 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `daysBetween()` | 468 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java |
| `defaultResp()` | 1790 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (anadir con usuario) |
| `delBench()` | 1315 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java |
| `delCategory()` | 1312 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java |
| `deleteBlock()` | 2506 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `deleteSel()` | 1096 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `delListVal()` | 1322 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx (rama responsibles: solo alcanzable en la foto READONLY, que en la versión nueva es el mismo HTML 4.9.4 incrustado (frontend/src/bloques/exportaciones/descargas.ts exportarSoloLectura, D31)) |
| `delResource()` | 2579 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `delResType()` | 2574 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `delRow()` | 1137 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `delUser()` | 1889 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx |
| `doneRows()` | 643 | función | MIGRADA | frontend/src/bloques/banco/ListaCerrados.tsx |
| `duplicateSel()` | 1095 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `durDays()` | 472 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `durLabelOf()` | 1277 | función | MIGRADA | frontend/src/utilidades/textos.ts |
| `easter()` | 413 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `editingNow()` | 1434 | función | MIGRADA | frontend/src/estado/Planificador.tsx (relectura sin pisar la edición) |
| `editUser()` | 1876 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx |
| `effStartOf()` | 511 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `endCellHTML()` | 1039 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx |
| `enhanceGantts()` | 2268 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx |
| `ensureCloseModal()` | 1162 | función | MIGRADA | frontend/src/bloques/banco/VentanaCierre.tsx |
| `ensureResModals()` | 2405 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx / frontend/src/bloques/recursos/VistaRecursos.tsx |
| `exportGanttExcel()` | 2352 | función | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts + POST /api/v1/exportaciones/gantt |
| `exportJSON()` | 1328 | función | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts + backend/src/main/java/com/borgwarner/tbp/api/v1/ExportacionesController.java |
| `exportReadOnly()` | 1330 | función | MIGRADA | frontend/src/App.tsx + frontend/src/bloques/exportaciones/descargas.ts (D31) |
| `fillDL()` | 693 | función | MIGRADA | frontend/src/App.tsx (datalists) |
| `finalStatusOf()` | 1269 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `fitBenchSplit()` | 997 | función | MIGRADA | frontend/src/estilos/base-4.9.4.css (.bench-split height calc) |
| `fmt()` | 467 | función | MIGRADA | frontend/src/utilidades/fechas.ts |
| `fmtH()` | 870 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `fmtStamp()` | 2025 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx / frontend/src/bloques/configuracion/RegistroActividad.tsx (sello) |
| `focusOptionsHTML()` | 2254 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (g-focsel) |
| `ganttHTML()` | 1273 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx |
| `ganttLW()` | 2202 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (bw_gantt_lw) |
| `gBaseW()` | 2195 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx / frontend/src/bloques/recursos/VistaRecursos.tsx |
| `gCurW()` | 2196 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/estado/Panel.tsx |
| `gKind()` | 2194 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx / frontend/src/bloques/recursos/VistaRecursos.tsx |
| `go()` | 709 | función | MIGRADA | frontend/src/App.tsx (ir/hash) |
| `gZoom()` | 2198 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx / frontend/src/bloques/panel/GanttGeneral.tsx / frontend/src/bloques/recursos/VistaRecursos.tsx (zoom −/+) |
| `gZoomReset()` | 2200 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (100%) |
| `headHTML()` | 1082 | función | MIGRADA | frontend/src/bloques/banco/TablaEnsayos.tsx |
| `idClass()` | 408, 1741 | función | MIGRADA | frontend/src/utilidades/textos.ts (claseAprobacion) |
| `idleDays()` | 523 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/MotorFechas.java |
| `idPickerEntries()` | 1283 | función | MIGRADA | frontend/src/bloques/banco/BuscadorAprobaciones.tsx (solo la lista viva; ya no mezcla ID_DB local, D31) |
| `idPillStyle()` | 1282 | función | MIGRADA | frontend/src/utilidades/textos.ts (estiloPildoraAprobacion) |
| `idStatus()` | 407, 1740 | función | MIGRADA | frontend/src/utilidades/textos.ts (claseAprobacion) + frontend/src/estado/Planificador.tsx (estados de /api/v1/aprobaciones/estados); 1.ª definición sustituida en la 4.9.4 por la de v3.7 |
| `importJSON()` | 1342 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx + backend/src/main/java/com/borgwarner/tbp/aplicacion/ServicioCopiaSeguridad.java (D10) |
| `importListFile()` | 1325 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx |
| `inWeek()` | 712 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/IndicadoresSemana.java |
| `isAdmin()` | 1758 | función | MIGRADA | frontend/src/App.tsx (sesion.esAdmin) + backend/src/main/java/com/borgwarner/tbp/dominio/usuarios/Usuario.java |
| `isConfirmedClose()` | 1268 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Estadisticas.java |
| `isEditor()` | 1759 | función | MIGRADA | frontend/src/App.tsx |
| `isFree()` | 463 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `isHol()` | 462 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `iso()` | 415 | función | MIGRADA | frontend/src/utilidades/fechas.ts + backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java |
| `isReadOnlyUser()` | 1760 | función | MIGRADA | frontend/src/estado/Planificador.tsx (soloLectura) |
| `isSpecialType()` | 373 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/TiposEspeciales.java |
| `isStarted()` | 519 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `isWE()` | 461 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java / frontend/src/utilidades/fechas.ts (esFinDeSemana) |
| `kpiBlockHTML()` | 831 | función | MIGRADA | frontend/src/bloques/panel/BloquesKpi.tsx |
| `kpiHistRows()` | 1345 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/HistoricoKpi.java |
| `kpiTwoBlocksHTML()` | 841 | función | MIGRADA | frontend/src/bloques/panel/BloquesKpi.tsx |
| `lbApply()` | 1460 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java (desdeFilas) |
| `lbBuild()` | 1444 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java (textoParaGuardar) |
| `lbHeartbeat()` | 1517 | función | MIGRADA | frontend/src/estado/cicloDeVida.ts |
| `lbLoad()` | 1454 | función | MIGRADA | frontend/src/estado/Planificador.tsx + backend/src/main/java/com/borgwarner/tbp/api/v1/PlanificacionController.java |
| `lbPoll()` | 1502 | función | MIGRADA | frontend/src/estado/Planificador.tsx (RELECTURA_MS 20000) |
| `lbPush()` | 1475 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java (cambiosPendientes) + frontend/src/estado/Planificador.tsx |
| `lbShutdown()` | 1522 | función | MIGRADA | frontend/src/estado/cicloDeVida.ts (pagehide, D20) |
| `loadConfig()` | 1616 | función | MIGRADA | frontend/src/estado/Planificador.tsx (recargarListas) + vista.grupos |
| `loadKpiHistory()` | 1501, 2135 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx (api.historico; 2 capas) |
| `loadWhoami()` | 1936 | función | MIGRADA | frontend/src/estado/Planificador.tsx (GET /api/v1/sesion) + backend/src/main/java/com/borgwarner/tbp/aplicacion/ServicioSesion.java |
| `manualCallOf()` | 544 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `manualLabel()` | 545 | función | MIGRADA | frontend/src/utilidades/textos.ts (ESTADOS_DECLARABLES) |
| `manualStateSelectHTML()` | 604 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx (columna status) |
| `markDone()` | 1184 | función | MIGRADA | frontend/src/bloques/banco/VentanaCierre.tsx |
| `mergeInto()` | 1297 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx (anadirVarios) |
| `mergeRespsFromUsers()` | 1901 | función | MIGRADA | frontend/src/estado/Planificador.tsx (personas) |
| `moveBench()` | 1316 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `moveBenchToCat()` | 1317 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `moveCategory()` | 1311 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `moveSel()` | 1099 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `nextFree()` | 464 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `occupancyOf()` | 757 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Ocupacion.java |
| `onEdit()` | 1100 | función | MIGRADA | frontend/src/estado/Planificador.tsx (editarCampo) |
| `onMoveTargetChange()` | 1098 | función | MIGRADA | frontend/src/bloques/banco/BarraSeleccion.tsx |
| `openBlockModal()` | 2499 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `openCfg()` | 1289 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx (VentanaCalendario) |
| `openCloseModal()` | 1185 | función | MIGRADA | frontend/src/bloques/banco/VentanaCierre.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/Cierre.java |
| `openDateCal()` | 1007 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `openDone()` | 1275 | función | MIGRADA | frontend/src/bloques/banco/ListaCerrados.tsx |
| `openIdPicker()` | 1284, 1806 | función | MIGRADA | frontend/src/bloques/banco/BuscadorAprobaciones.tsx (2 capas: original + v3.7 búsqueda con el nombre) |
| `openResPicker()` | 2429 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx |
| `orgBenchFF()` | 765 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/FiltroOrganizacion.java |
| `orgFF()` | 864 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/FiltroOrganizacion.java |
| `orgSelectHTML()` | 766 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `orgSelectHTMLKpi()` | 1409 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx |
| `overlaps()` | 711 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/IndicadoresSemana.java |
| `overlapWarnHTML()` | 1033 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx |
| `overviewGantt()` | 769 | función | MIGRADA | frontend/src/bloques/panel/GanttGeneral.tsx |
| `parseLines()` | 1296 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx |
| `pasteList()` | 1324 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx |
| `pct()` | 713 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/PanelDashboard.java |
| `persistGroups()` | 1307 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/aplicacion/ServicioConfiguracionBancos.java (D28) |
| `pickId()` | 1288 | función | MIGRADA | frontend/src/bloques/banco/VistaBanco.tsx (onElegir) |
| `pickRow()` | 2439 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx |
| `plannedDays()` | 487 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `plannedEndOf()` | 566 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/MotorFechas.java |
| `positionTodayLine()` | 2205 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx |
| `progCellInner()` | 1110 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx |
| `progFrac()` | 488 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `progPct()` | 489 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java / frontend/src/bloques/banco/columnas.tsx |
| `projColor()` | 2315 | función | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts (colorProyecto) |
| `pushConfig()` | 1604 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx / frontend/src/bloques/configuracion/ConfigProyectos.tsx (PUT /api/v1/configuracion/bancos, /catalogos; D11, D28) |
| `pushUsers()` | 1890 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx (PUT /api/v1/usuarios, D11) |
| `reasonKey()` | 784 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/MotivosRetraso.java |
| `reasonLabel()` | 381 | función | MIGRADA | frontend/src/utilidades/textos.ts (etiquetaMotivo) |
| `rebuildDotOf()` | 411 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/ConfiguracionBancos.java (vista.grupos) |
| `recomputeAll()` | 691 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/ResultadoPlan.java / backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java |
| `refreshApprovals()` | 1711 | función | MIGRADA | frontend/src/estado/Planificador.tsx (60 s + IDs live) |
| `refreshDL()` | 707 | función | MIGRADA | frontend/src/App.tsx (datalists) |
| `regenYear()` | 1292 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx + /api/v1/calendario/anio |
| `reloadIdList()` | 1286 | función | MIGRADA | frontend/src/bloques/banco/BuscadorAprobaciones.tsx (Reload live) |
| `remDaysOf()` | 483 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `renameBench()` | 1314 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java |
| `renameCategory()` | 1309 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `renameResource()` | 2576 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (recursos/renombrar) |
| `renameResType()` | 2572 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `render()` | 1410, 1910, 2147, 2602 | función | MIGRADA | frontend/src/App.tsx (5 definiciones encadenadas: original, v3.7 users/tools, v4.0 activity, v4.5 recursos) |
| `renderAbout()` | 1326 | función | MIGRADA | frontend/src/bloques/configuracion/AcercaDe.tsx (D26) |
| `renderActivity()` | 2053 | función | MIGRADA | frontend/src/bloques/configuracion/RegistroActividad.tsx |
| `renderBench()` | 995 | función | MIGRADA | frontend/src/bloques/banco/VistaBanco.tsx |
| `renderBenchesCfg()` | 1299 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `renderCal()` | 1012 | función | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `renderDashboard()` | 974 | función | MIGRADA | frontend/src/bloques/panel/VistaDashboard.tsx |
| `renderDoneList()` | 1278 | función | MIGRADA | frontend/src/bloques/banco/ListaCerrados.tsx |
| `renderHolList()` | 1291 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `renderIdList()` | 1287 | función | MIGRADA | frontend/src/bloques/banco/BuscadorAprobaciones.tsx |
| `renderKpiHistory()` | 1351, 2141 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx (2 capas: original + v4.1 chip de frescura) |
| `renderProjects()` | 1318 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigProyectos.tsx |
| `renderResources()` | 2534 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `renderResourcesCfg()` | 2549 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `renderRespDash()` | 977 | función | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx |
| `renderResPick()` | 2440 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx |
| `renderSide()` | 708, 1816, 2154, 2581 | función | MIGRADA | frontend/src/App.tsx (nav lateral; 4 definiciones: original, v3.7 por rol, v4.0 Activity log, v4.5 equipos). La original (con «Responsibles») solo alcanzable en la foto READONLY, que en la versión nueva es el mismo HTML 4.9.4 incrustado (frontend/src/bloques/exportaciones/descargas.ts exportarSoloLectura, D31) |
| `renderTools()` | 1834 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `renderUsers()` | 1849 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigUsuarios.tsx (D29, D30) |
| `resActor()` | 2384 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/aplicacion/ServicioPlanificacion.java (actor) |
| `resByType()` | 2386 | función | MIGRADA | frontend/src/estado/Recursos.tsx |
| `resCellInner()` | 2490 | función | MIGRADA | frontend/src/bloques/recursos/CeldaRecursos.tsx |
| `resConflictsForRow()` | 2477 | función | MIGRADA | frontend/src/estado/Recursos.tsx |
| `resetUtilRange()` | 863 | función | MIGRADA | frontend/src/bloques/panel/TablaOcupacion.tsx |
| `resFind()` | 2387 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `resLoad()` | 2372 | función | MIGRADA | frontend/src/estado/Recursos.tsx |
| `resourceGanttHTML()` | 2509 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `resOverlap()` | 2388 | función | MIGRADA | frontend/src/estado/Recursos.tsx |
| `resPickTab()` | 2438 | función | MIGRADA | frontend/src/bloques/recursos/SelectorRecursos.tsx |
| `respPool()` | 698 | función | MIGRADA | frontend/src/estado/Planificador.tsx (personas, D24) |
| `resSaveCatalog()` | 2379 | función | MIGRADA | frontend/src/estado/Recursos.tsx (PUT /api/v1/recursos, D11) |
| `resSlug()` | 2569 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `resToast()` | 2475 | función | MIGRADA | frontend/src/componentes/Avisos.tsx (avisar) |
| `restoreDone()` | 1271 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/Cierre.java (D2) |
| `resUsageOn()` | 2389 | función | MIGRADA | frontend/src/estado/Recursos.tsx |
| `reuseDone()` | 1279 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/Cierre.java |
| `rmHol()` | 1295 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `roleClass()` | 1757 | función | MIGRADA | frontend/src/App.tsx (role-pill) |
| `rowHTML()` | 1083 | función | MIGRADA | frontend/src/bloques/banco/TablaEnsayos.tsx |
| `runSync()` | 1490 | función | MIGRADA | frontend/src/estado/Planificador.tsx (enviarPendientes) |
| `rzHandle()` | 878 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `rzOpen()` | 877 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `safeJson()` | 1433 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/comun/JsonJs.java |
| `saveBlock()` | 2500 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `saveViewPref()` | 856 | función | MIGRADA | frontend/src/estado/Panel.tsx (bw_tbp_viewprefs_v1) |
| `schedDays()` | 479 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `scheduleSync()` | 1489 | función | MIGRADA | frontend/src/estado/Planificador.tsx (ESPERA_GUARDADO_MS 1200) |
| `scrollGantt()` | 1274, 2307 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (2 capas: original + v4.5 enhanceGantts) |
| `setBenchOrg()` | 783 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx (orgBanco) |
| `setCatColor()` | 1310 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx |
| `setDashOrg()` | 764 | función | MIGRADA | frontend/src/estado/Panel.tsx / frontend/src/bloques/panel/comunes.tsx |
| `setDashZoom()` | 767 | función | MIGRADA | frontend/src/estado/Panel.tsx / frontend/src/bloques/panel/VistaDashboard.tsx |
| `setDowntime()` | 1149 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/EdicionEnsayo.java |
| `setFocusProject()` | 2242 | función | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (tbp_gantt_foco; ahora se recuerda, la 4.9.4 no) |
| `setKpiHistOrg()` | 1344 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx |
| `setManualState()` | 593 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (declararEstado) / frontend/src/bloques/banco/VistaBanco.tsx |
| `setResourceCode()` | 2577 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `setResourceOrg()` | 2578 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `setRespSel()` | 976 | función | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx |
| `setResTypeColor()` | 2573 | función | MIGRADA | frontend/src/bloques/recursos/ListaEquipos.tsx |
| `setResZoom()` | 2508 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx / frontend/src/estado/Panel.tsx |
| `setSpStatus()` | 1436 | función | MIGRADA | frontend/src/App.tsx (SINCRONIZACION) |
| `setTrendRange()` | 875 | función | MIGRADA | frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `setUtilRange()` | 858 | función | MIGRADA | frontend/src/bloques/panel/TablaOcupacion.tsx |
| `setViewPref()` | 857 | función | MIGRADA | frontend/src/estado/Panel.tsx |
| `shiftWeek()` | 714 | función | MIGRADA | frontend/src/bloques/panel/NavegacionSemana.tsx |
| `shiftWeekRes()` | 2548 | función | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx |
| `shiftWeekResp()` | 993 | función | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx / NavegacionSemana.tsx |
| `showWriteLock()` | 1427 | función | MIGRADA | frontend/src/App.tsx (lock-banner) |
| `slugKey()` | 1298 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigBancos.tsx (claveDe) |
| `snapshotKPIs()` | 1125 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/FotoSemanal.java (D8) |
| `spanDays()` | 1154 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `startAssumed()` | 521 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java (calculo.arranqueAsumido) / frontend/src/bloques/banco/columnas.tsx |
| `startCellHTML()` | 1038 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx |
| `startWhyHTML()` | 1021 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx + frontend/src/utilidades/textos.ts (CRITERIO_INICIO) |
| `statBucket()` | 2656 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statDateOf()` | 2640 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Estadisticas.java |
| `statDefaults()` | 2623 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx / frontend/src/estado/Panel.tsx |
| `statExportCSV()` | 2673 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statPct()` | 2663 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statQuick()` | 2631 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statRows()` | 2642 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Estadisticas.java + /api/v1/indicadores/estadisticas |
| `statSetFrom()` | 2627 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statSetResp()` | 2630 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statSetTo()` | 2628 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx |
| `statsSectionHTML()` | 2695 | función | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx (D25) |
| `statusCellInner()` | 634 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx |
| `statusColor()` | 369 | función | MIGRADA | frontend/src/utilidades/textos.ts (colorEstado) |
| `statusLabel()` | 370 | función | MIGRADA | frontend/src/utilidades/textos.ts (etiquetaEstado) |
| `statusOf()` | 546 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/EstadosEnsayo.java |
| `statusOfId()` | 1730 | función | MIGRADA | frontend/src/estado/Planificador.tsx (estadosAprobacion) + frontend/src/utilidades/textos.ts |
| `stHex()` | 367, 2310 | función | MIGRADA | frontend/src/utilidades/textos.ts (colorEstado) |
| `stickyHeaders()` | 2214 | función | MIGRADA | frontend/src/bloques/panel/GanttGeneral.tsx |
| `stRGBA()` | 368 | función | MIGRADA | frontend/src/utilidades/textos.ts / frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `stripRT()` | 1432 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Planificacion.java (el backend nunca guarda campos de cálculo) |
| `stripState()` | 1329 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/aplicacion/ServicioCopiaSeguridad.java (D32) |
| `suggestedState()` | 588 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Sugerencias.java |
| `suggestionBadgeHTML()` | 612 | función | MIGRADA | frontend/src/bloques/banco/columnas.tsx (sug-badge) |
| `suggestionOpen()` | 591 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Sugerencias.java |
| `suggestionsFor()` | 571 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Sugerencias.java / frontend/src/utilidades/textos.ts (textoSugerencia) |
| `targetOf()` | 473 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/Duraciones.java |
| `thisWeek()` | 715 | función | MIGRADA | frontend/src/bloques/panel/NavegacionSemana.tsx |
| `thisWeekResp()` | 994 | función | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx / NavegacionSemana.tsx |
| `toggleDoneScope()` | 1276 | función | MIGRADA | frontend/src/bloques/banco/ListaCerrados.tsx |
| `toggleResource()` | 2480 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java (alternarRecurso) |
| `toggleSkip()` | 1293 | función | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `toggleTrend()` | 874 | función | MIGRADA | frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `tolEndOf()` | 525 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/MotorFechas.java |
| `trendChartHTML()` | 916 | función | MIGRADA | frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `trendData()` | 885 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/PanelDashboard.java |
| `trendSVG()` | 895 | función | MIGRADA | frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `uid()` | 423 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/GeneradorIds.java |
| `unconfirmDatesRow()` | 1119 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/operaciones/OperacionesBanco.java |
| `unitLabel()` | 474 | función | MIGRADA | frontend/src/utilidades/textos.ts |
| `updateFreshChip()` | 2033 | función | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx / frontend/src/bloques/configuracion/RegistroActividad.tsx |
| `updateSelBar()` | 1094 | función | MIGRADA | frontend/src/bloques/banco/BarraSeleccion.tsx |
| `updateYearlyStatus()` | 1136 | función | MIGRADA | frontend/src/bloques/panel/VistaDashboard.tsx + backend/src/main/java/com/borgwarner/tbp/api/v1/IndicadoresController.java (foto-semanal, D21) |
| `utilCardHTML()` | 925 | función | MIGRADA | frontend/src/bloques/panel/TablaOcupacion.tsx + backend/src/main/java/com/borgwarner/tbp/dominio/kpi/PanelDashboard.java |
| `vpModeToggle()` | 868 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `vpOrgSelect()` | 866 | función | MIGRADA | frontend/src/bloques/panel/comunes.tsx |
| `weekKPIs()` | 790 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/IndicadoresSemana.java |
| `weekKPIsBench()` | 799 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/IndicadoresSemana.java |
| `weekRange()` | 710 | función | MIGRADA | frontend/src/estado/Panel.tsx |
| `weekStart()` | 466 | función | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Fechas.java / frontend/src/utilidades/fechas.ts (lunesDe) |
| `onclick #imp.click() (Import JSON)` |  | manejador | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx |
| `onclick Reset (local)` |  | manejador | MIGRADA | frontend/src/bloques/configuracion/ConfigHerramientas.tsx (D27) |
| `onclick window.open(TICKETS_URL)` |  | manejador | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx |
| `onclick window.print() (🖨 Print)` |  | manejador | MIGRADA | frontend/src/App.tsx + @media print en base-4.9.4.css |
| `COLDEFS.actions.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.actions) |
| `COLDEFS.actualStart.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.actualStart) |
| `COLDEFS.approvalId.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.approvalId) |
| `COLDEFS.avail.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.avail) |
| `COLDEFS.cf.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.cf) |
| `COLDEFS.comments.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.comments) |
| `COLDEFS.confirmedEnd.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.confirmedEnd) |
| `COLDEFS.confirmedStart.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.confirmedStart) |
| `COLDEFS.cycles.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.cycles) |
| `COLDEFS.cycleUnit.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.cycleUnit) |
| `COLDEFS.cycleVal.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.cycleVal) |
| `COLDEFS.downtime.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.downtime) |
| `COLDEFS.durManual.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.durManual) |
| `COLDEFS.durMode.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.durMode) |
| `COLDEFS.end.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.end) |
| `COLDEFS.inputs.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.inputs) |
| `COLDEFS.lastUpdate.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.lastUpdate) |
| `COLDEFS.manualUnit.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.manualUnit) |
| `COLDEFS.planned.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.planned) |
| `COLDEFS.prio.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.prio) |
| `COLDEFS.progPct.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.progPct) |
| `COLDEFS.progressActual.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.progressActual) |
| `COLDEFS.project.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.project) |
| `COLDEFS.reason.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.reason) |
| `COLDEFS.resources.cell` | 2496 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.resources) |
| `COLDEFS.responsible.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.responsible) |
| `COLDEFS.start.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.start) |
| `COLDEFS.status.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.status) |
| `COLDEFS.trueEnd.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.trueEnd) |
| `COLDEFS.type.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.type) |
| `COLDEFS.weekend.cell` | 1041–1073 | método | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS.weekend) |
| `/ (index)` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/InterfazClasicaController.java (React si hay dist; si no, /clasico) |
| `/api/activity_log` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (ignora el parámetro top; siempre 200) |
| `/api/app_version` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET y POST). Ojo: Java nunca publica su versión (ver H-02) |
| `/api/approvals` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java |
| `/api/approvals_all` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET y POST) |
| `/api/delete_resource_block` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java |
| `/api/export_gantt` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java → backend/src/main/java/com/borgwarner/tbp/infraestructura/excel/ExcelGantt.java |
| `/api/health` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET; comprueba leyendo ensayos, Python solo miraba la sesión) |
| `/api/heartbeat` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java + backend/src/main/java/com/borgwarner/tbp/api/comun/CicloDeVida.java (D20) |
| `/api/load` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java |
| `/api/load_config` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET y POST) |
| `/api/load_kpis` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET y POST) |
| `/api/load_resources` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (solo POST; Python aceptaba GET) |
| `/api/log_event` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (ni la 4.9.4 ni React lo llaman) |
| `/api/presence` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (solo POST; Python aceptaba también GET) + /api/v1/presencia. La interfaz React no lo usa (H-01) |
| `/api/save` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (contrato «el último gana», D12) |
| `/api/save_config` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (protección D11) |
| `/api/save_kpis` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java |
| `/api/save_resource_block` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java |
| `/api/save_resources` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (D11) |
| `/api/save_users` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (D11) |
| `/api/shutdown` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java + CicloDeVida.java (D20) |
| `/api/whoami` |  | ruta | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/compat/ApiCompatibleController.java (GET y POST) |
| `bw_bench_gw / bw_gantt_lw` |  | semilla | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (mismas claves) |
| `CITY_IMG` |  | semilla | MIGRADA | frontend/public/ciudad.jpg |
| `COLDEFS (objeto)` |  | semilla | MIGRADA | frontend/src/bloques/banco/columnas.tsx (COLUMNAS, 31 columnas) |
| `DEFAULT_COLS / COLORDER_VER` |  | semilla | MIGRADA | frontend/src/bloques/banco/columnas.tsx + TablaEnsayos.tsx (VERSION_COLUMNAS = v4.9.3c). La preferencia nueva usa la clave tbp_columnas_v1: el orden personalizado de la 4.9.4 no se hereda |
| `DELAY_REASONS / REASON_LABEL` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/MotivosRetraso.java + textos.ts |
| `GROUPS_SEED` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/ConfiguracionBancos.java (GRUPOS_DE_SERIE) |
| `HOURS_PER_DAY` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/kpi/Ocupacion.java |
| `LB {base, poll, beat}` |  | semilla | MIGRADA | frontend/src/estado/Planificador.tsx (RELECTURA_MS) + estado/cicloDeVida.ts (INTERVALO_LATIDO_MS); base relativa |
| `MANUAL_STATES` |  | semilla | MIGRADA | frontend/src/utilidades/textos.ts (ESTADOS_DECLARABLES) |
| `MONTHS_ES` |  | semilla | MIGRADA | frontend/src/bloques/kpi/Estadisticas.tsx (MESES) |
| `NO_REPORT_DAYS / _SEV` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/planificacion/Sugerencias.java |
| `PROJ_PALETTE` |  | semilla | MIGRADA | frontend/src/bloques/exportaciones/descargas.ts |
| `RES_BLOCK_REASONS` |  | semilla | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx (MOTIVOS_BLOQUEO) |
| `SPECIAL_TYPES` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/ensayo/TiposEspeciales.java |
| `STHEX` |  | semilla | MIGRADA | frontend/src/utilidades/textos.ts |
| `TEST_TYPES` |  | semilla | MIGRADA | frontend/src/utilidades/textos.ts (TIPOS_DE_ENSAYO) |
| `TICKETS_URL` |  | semilla | MIGRADA | frontend/src/utilidades/textos.ts (URL_TICKETS_APROBACION) |
| `VERSION / BUILD / BUILD_DATE` |  | semilla | MIGRADA | frontend/src/App.tsx + backend/src/main/java/com/borgwarner/tbp/TestBenchPlannerApplication.java (5.0.0, D26) |
| `VIEWPREF_KEY bw_tbp_viewprefs_v1` |  | semilla | MIGRADA | frontend/src/estado/Panel.tsx (misma clave) |
| `VIGO_LOCAL` |  | semilla | MIGRADA | backend/src/main/java/com/borgwarner/tbp/dominio/calendario/Calendario.java |
| `lbBootstrap (3 intentos × 1,5 s, candado)` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx (arranque) |
| `mousedown fuera → cerrar calendario` |  | temporizador | MIGRADA | frontend/src/componentes/SelectorFecha.tsx |
| `pagehide / beforeunload → lbShutdown` |  | temporizador | MIGRADA | frontend/src/estado/cicloDeVida.ts (solo pagehide, D20) |
| `Python: _watchdog (sin latido → apagar)` |  | temporizador | MIGRADA | backend/src/main/java/com/borgwarner/tbp/api/comun/CicloDeVida.java (D20) |
| `Python: hilo _mirror_worker_loop (columnas espejo)` |  | temporizador | MIGRADA | backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/EspejoSharePoint.java (D13, D19) |
| `Python: presencia TTL 75 s` |  | temporizador | MIGRADA | backend/src/main/java/com/borgwarner/tbp/infraestructura/sharepoint/ListasAuxiliaresSharePoint.java + local/Presencia.java (D9) |
| `resize → fitBenchSplit + scrollGantt` |  | temporizador | MIGRADA | CSS .bench-split + Gantt.tsx |
| `resize → redibujar gráfica (150 ms)` |  | temporizador | MIGRADA | frontend/src/bloques/panel/GraficaOcupacion.tsx |
| `resToast setTimeout 5,5 s` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx (avisar, 4,5 s / 7 s) |
| `scheduleSync (espera 1,2 s)` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx (ESPERA_GUARDADO_MS) |
| `setInterval(lbHeartbeat, 5 s)` |  | temporizador | MIGRADA | frontend/src/estado/cicloDeVida.ts |
| `setInterval(lbPoll, 20 s)` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx (RELECTURA_MS) |
| `setTimeout(refreshApprovals, 2,5 s) + setInterval 60 s` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx |
| `setTimeout(scrollGantt, 0) tras pintar` |  | temporizador | MIGRADA | frontend/src/bloques/banco/Gantt.tsx (useEffect) |
| `v37Init (whoami, roles, bienvenida, versión)` |  | temporizador | MIGRADA | frontend/src/estado/Planificador.tsx (sesión y roles); sus partes bienvenida y versión → H-05, H-02 |
| `v45Init (resLoadLocal + setTimeout(resLoad, 6,5 s))` |  | temporizador | MIGRADA | frontend/src/estado/Recursos.tsx (lectura al arrancar) |
| `vista __benches__ / __projects__ / __users__ / __tools__ / __about__ / __activity__` |  | vista | MIGRADA | frontend/src/bloques/configuracion/* |
| `vista __dash__` |  | vista | MIGRADA | frontend/src/bloques/panel/VistaDashboard.tsx |
| `vista __kpihist__` |  | vista | MIGRADA | frontend/src/bloques/kpi/VistaKpiHistorico.tsx |
| `vista __resources__ / __rescfg__` |  | vista | MIGRADA | frontend/src/bloques/recursos/VistaRecursos.tsx / ListaEquipos.tsx |
| `vista __resp__ / __manager__` |  | vista | MIGRADA | frontend/src/bloques/panel/VistaResponsable.tsx |
| `vista <banco>` |  | vista | MIGRADA | frontend/src/bloques/banco/VistaBanco.tsx |

## Estado propuesto para docs/01-inventario-funcional.md

Pasan a **Hecho**: EST-CSV, A7-ID (sin caso de paridad: conviene añadir uno), A9-GANTT, A9-XLSX, A10-COLS, RES-ROW, V-DASH, V-RESP, V-KPIHIST, V-RES, V-CFG, V-ACT, SYNC-CFG (salvo H-06), USERS, LOG (connect/edit/res_block en Java), MIRROR y SP (validación de escritura en H4), UPD (no-op deliberado, «se mantiene desactivada»), OFFLINE en su parte de ID_DB_SEED/exportReadOnly (D31).
**V-MONDAY**: cerrar como «no existe en la 4.9.4» (renderMonday nunca se definió; código muerto).
**Siguen abiertas**: PRES (H-01), VER (H-02), APP (H-03 diagnósticos y H-10 arranque/lanzador, H4), OFFLINE en su parte de consulta sin conexión (H-04) y SYNC-CFG solo por H-06. Apartado 5: la prueba 19 (A7-ID) pasa a hecha; la 28 (sin conexión) depende de H-04; 23–26, 27 y 30–32 siguen como estaban (H4).

---

## Resolución (H3Z, 9-nov-2026)

| Hueco | Resultado | Dónde | Prueba |
|---|---|---|---|
| H-01 Presencia | Migrado | `componentes/Colaboracion.tsx` (`Presencia`), `/api/v1/presencia` | `pruebas-cierre.cjs` |
| H-02 Aviso de versión | Migrado; publicar la versión al arrancar existe pero sigue desactivado (`planner.sharepoint.publicar-version=false`) | `Colaboracion.tsx` (`AvisoVersion`), `GET /api/v1/version`, `aplicacion/PublicacionVersion.java` | `pruebas-cierre.cjs` |
| H-03 Diagnóstico `/api/diag*` | Se deja para el lanzador (D36) | — | — |
| H-04 Consulta sin conexión | Migrado: última copia buena en `~/.testbenchplanner/copia-sin-conexion`, solo consulta, banner «Modo offline» | `infraestructura/comun/CopiaSinConexion.java`, `aplicacion/EstadoConexion.java`, `Colaboracion.tsx` (`AvisoSinConexion`) | `CopiaSinConexionTest`, `pruebas-cierre.cjs` |
| H-05 Bienvenida | Migrado | `Colaboracion.tsx` (`Bienvenida`) | `pruebas-cierre.cjs` |
| H-06 Relectura de catálogos | Migrado (cada 60 s, mismas guardas que la relectura) | `estado/Planificador.tsx` | Revisión de código |
| H-07 Calendario con Escape / redimensionar | Migrado | `componentes/SelectorFecha.tsx` | `pruebas-cierre.cjs` |
| H-08 Tiradores táctiles y doble clic | Migrado (Pointer Events y `touch-action: none`) | seis componentes con tiradores, `estilos/aplicacion.css` | `pruebas-cierre.cjs` (ratón) |
| H-09 Sugerencias completas al entrar | Migrado | `bloques/banco/Celdas.tsx` (`Entrada`) | `pruebas-cierre.cjs` |
| H-10 Abrir el navegador al arrancar | Se deja para el lanzador (D36) | — | — |

Diferencias sin documentar que encontró la auditoría: documentadas como D33–D35 (y D36).
Con esto, todas las entradas de la 4.9.4 están migradas, son código muerto o tienen una decisión documentada.
