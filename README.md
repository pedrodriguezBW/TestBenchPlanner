# TestBenchPlanner · migración a React + Java

Migración de **TestBenchPlanner 4.9.4** —una interfaz HTML monolítica con backend
Python/Flask— a una aplicación local con **React** en el frontend y **Java /
Spring Boot** en el backend.

El objetivo es conservar el comportamiento funcional y la compatibilidad con las
listas actuales de SharePoint mientras la interfaz se sustituye por bloques. La
versión 4.9.4 se mantiene sin modificar como oráculo de paridad y como respaldo
durante la transición.

> **Estado actual: H3E.** H1 y H2 están terminados. En H3 están migrados los
> bloques A (operativa diaria de bancos), B (Dashboard y «By Responsible»), C
> (KPI History y estadísticas), D (equipos auxiliares) y E (configuración); las
> exportaciones e importaciones (bloque F) siguen disponibles mediante la
> interfaz clásica en `/clasico`.

## Índice

- [Estado de la migración](#estado-de-la-migración)
- [Arquitectura](#arquitectura)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Tecnologías](#tecnologías)
- [Puesta en marcha](#puesta-en-marcha)
- [Pruebas y paridad](#pruebas-y-paridad)
- [Configuración](#configuración)
- [APIs e interfaces](#apis-e-interfaces)
- [Reglas de seguridad y trabajo](#reglas-de-seguridad-y-trabajo)
- [Documentación](#documentación)

## Estado de la migración

| Hito | Resultado incorporado al repositorio | Estado |
|---|---|---|
| **H1 · Caracterización** | Inventario funcional de la 4.9.4, escenario anonimizado, laboratorio de paridad, 67 casos de referencia y discrepancias documentadas. | **Terminado** |
| **H2 · Backend Java** | Monolito modular Spring Boot, dominio y casos de uso, persistencia local y SharePoint, API nueva y API compatible con la 4.9.4. | **Terminado**; pruebas verdes y SharePoint validado en solo lectura |
| **H2b · Protección de datos** | Protección frente a lecturas ausentes, fallidas o vacías; reconciliación bloqueada para evitar sobrescrituras destructivas de listas compartidas. | **Integrado en H2** |
| **H3A · React, bloque A** | Vista de banco: tabla de 30 columnas, Gantt, edición, selección, operaciones de cola, aprobaciones, cierre y lista de ensayos cerrados. | **Terminado** |
| **H3B · React, bloque B** | Dashboard (bloques KPI, plan anual por banco, gráfica de ocupación por CW, tabla de ocupación por tipo de banco, 📊 Update Yearly Status) y «By Responsible» (Manager View, carga del equipo, aviso de IDs sin aprobar). | **Terminado** |
| **H3C · React, bloque C** | KPI History: estadísticas de ensayos cerrados (periodo, responsable, por mes, trimestre y motivo, detalle) y foto semanal (medias, tabla por semana, motivos acumulados) por organización. Incluye la corrección del ciclo de vida (minimizar ya no cierra el backend). | **Terminado** |
| **H3D · React, bloque D** | Equipos auxiliares: columna «Resources» y selector de equipos en los bancos, «List of Equipments» (Gantt por equipo, bloqueos manuales) y «Equipments List» (catálogo, solo Admin). Incluye la separación entre controles de consulta y de escritura en solo lectura. | **Terminado** |
| **H3E · React, bloque E** | Configuración: Test Benches (categorías y bancos; renombrar conserva ensayos, borrar los borra), Projects, Users/Rights Management, Tools (calendario de festivos, IDs live, Reset local), About y Activity log. | **Entregado**; pendiente de validar en el PC |
| **H3F · React** | Exportaciones e importaciones (Gantt a Excel, CSV de estadísticas, copia JSON). Hasta su migración se hacen desde `/clasico`. | Pendiente |
| **H4 · Entrega** | Validación integral en oficina, escritura controlada en SharePoint, runtime de Java incluido, lanzador y retirada controlada de la 4.9.4. | Pendiente |

### Funcionalidad disponible en H3E

- Planificación y edición diaria por banco.
- Cálculo de fechas, estados, sugerencias, cierres, KPI, ocupación y calendario
en el backend; React no duplica reglas de negocio.
- Operaciones sobre la cola: comprometer y deshacer, confirmar todas, arrastrar,
duplicar, mover, borrar y añadir ensayos.
- Cierre, corrección, restauración y reutilización de ensayos.
- Concurrencia optimista en la API React: ante un cambio ajeno se puede ver la
versión remota o forzar la versión local.
- Relectura periódica, cola de guardado y recuperación ante pérdida de conexión.
- Ciclo de vida por pestaña: el backend se cierra solo cuando se cierra la última
pestaña; minimizar el navegador o dejar una pestaña oculta no lo cierra.
- Dashboard: navegación por semanas, filtro Org, bloques «Esta semana» / «Semana
que viene», Gantt de todos los bancos (año o semana, zoom y foco de proyecto),
gráfica de ocupación por CW (% u horas, rango de CW, series ocultables), tabla de
ocupación por tipo de banco (semana o acumulado) y foto semanal del histórico.
- «By Responsible»: planificación de una persona o del equipo (Manager View),
carga por responsable y aviso de ensayos sin Approval ID aprobado.
- KPI History: estadísticas de ensayos cerrados con periodo (año, año hasta hoy,
trimestre, mes o fechas), responsable y Org, y foto semanal del histórico con
medias, tabla por semana y motivos acumulados. «Exportar CSV» llega con el
bloque F.
- Equipos auxiliares: columna «Resources» en cada banco con aviso «!» si un
equipo asignado está ocupado en esas fechas, selector «Disponibles / En uso»
con búsqueda, Gantt de ocupación por equipo con bloqueos y solapes, bloqueos
manuales (Admin) y catálogo «Equipments List» (Admin; renombrar un equipo lo
cambia en todos los ensayos).
- Configuración: Test Benches (solo Admin; cada cambio se guarda en el acto y
renombrar o borrar un banco actualiza sus ensayos en el mismo paso), Projects
(Admin y Editor; alta, pegar, importar CSV/TXT, borrar), Users/Rights
Management, Tools (calendario de festivos de este PC, IDs live, Reset local),
About y Activity log.
- Solo lectura: se bloquean únicamente las acciones que modifican datos; filtros,
búsquedas, navegación y consultas siguen disponibles en todas las pantallas.
- La aplicación se abre en el Dashboard, como la 4.9.4.
- Interfaz clásica servida en `/clasico` para los bloques todavía no migrados.

## Arquitectura

La aplicación se ejecuta como un **monolito modular local**. Spring Boot escucha
en `127.0.0.1:5001`, sirve la API y publica la compilación de React. El frontend
de desarrollo usa Vite en el puerto `5173` y reenvía `/api` y `/clasico` al
backend.

```text
Navegador
├── /                    React (frontend/dist)
└── /clasico             HTML 4.9.4 sin modificar
          │
          ▼
Spring Boot · 127.0.0.1:5001
├── /api/v1/*            API de la interfaz React
├── /api/*               API compatible con la 4.9.4
├── dominio              Reglas puras de negocio
├── aplicacion           Casos de uso y puertos
└── infraestructura
    ├── local            Escenarios en disco para desarrollo y pruebas
    └── sharepoint       Listas de SharePoint mediante Edge + Playwright/CDP
```

### Principios de diseño

- **Una única implementación de las reglas:** fechas, estados, validaciones,
sugerencias, cierres e indicadores viven en el backend.
- **Arquitectura por puertos y adaptadores:** el dominio no depende de Spring,
SharePoint ni del sistema de ficheros.
- **Compatibilidad durante la migración:** `/api/*` y `/clasico` permiten ejecutar
la 4.9.4 sobre el backend Java.
- **Persistencia intercambiable:** `planner.almacen` selecciona el adaptador
`local` o `sharepoint`.
- **Concurrencia optimista:** `/api/v1` usa la huella de `DataJson`; SharePoint
añade comprobación de ETag.
- **Conservación de datos:** `DataJson` mantiene campos desconocidos y el orden de
las claves para no reescribir filas sin cambios.

## Estructura del repositorio

```text
TestBenchPlanner/
├── backend/                              Backend Java / Spring Boot
│   ├── .mvn/                             Configuración de Maven Wrapper
│   ├── mvnw · mvnw.cmd                   Maven Wrapper para Linux/macOS y Windows
│   ├── pom.xml                           Dependencias, build y perfil «interfaz»
│   └── src/
│       ├── main/
│       │   ├── java/com/borgwarner/tbp/
│       │   │   ├── api/
│       │   │   │   ├── compat/           API /api/* y servicio de /clasico
│       │   │   │   ├── local/            Reinicio del escenario local para pruebas
│       │   │   │   ├── v1/               Planificación, configuración e indicadores
│       │   │   │   └── comun/            Errores HTTP y ciclo de vida
│       │   │   ├── aplicacion/            Casos de uso, sesión, listas e indicadores
│       │   │   │   └── puertos/           Contratos de persistencia e integración
│       │   │   ├── configuracion/         Selección y propiedades de adaptadores
│       │   │   ├── dominio/
│       │   │   │   ├── calendario/        Festivos, semanas ISO y días laborables
│       │   │   │   ├── comun/             Semántica y serialización JSON compatible
│       │   │   │   ├── ensayo/            Ensayos, duración, estados y motivos
│       │   │   │   ├── kpi/               KPI, ocupación, fotos e histórico
│       │   │   │   ├── listas/            Protección de listas compartidas
│       │   │   │   ├── operaciones/       Edición, cola y cierre
│       │   │   │   ├── planificacion/     Motor de fechas y sugerencias
│       │   │   │   └── usuarios/          Usuario y roles
│       │   │   └── infraestructura/
│       │   │       ├── comun/             Reloj, calendario, versiones y KPI
│       │   │       ├── local/             Almacenes basados en ficheros
│       │   │       └── sharepoint/         Edge/CDP, listas, aprobaciones y espejo
│       │   └── resources/application.yml  Configuración por defecto
│       └── test/java/com/borgwarner/tbp/
│           ├── dominio/                   Pruebas unitarias del dominio
│           ├── integracion/               API, almacenes y protecciones
│           └── paridad/                   Comparación Java ↔ referencias 4.9.4
│
├── frontend/                             Interfaz React de H3
│   ├── public/                            Recursos estáticos
│   ├── dist/                              Build generado; el JAR lo sirve en / si existe
│   ├── package.json                       Scripts y dependencias
│   ├── vite.config.ts                     Puerto 5173 y proxy al backend
│   └── src/
│       ├── api/                           Cliente y tipos de /api/v1
│       ├── bloques/banco/                 Bloque A: tabla, Gantt, cierre y operaciones
│       ├── bloques/panel/                 Bloque B: Dashboard y «By Responsible»
│       ├── bloques/kpi/                   Bloque C: KPI History y estadísticas
│       ├── bloques/recursos/              Bloque D: equipos auxiliares
│       ├── bloques/configuracion/         Bloque E: Test Benches, Projects, Users, Tools, About y Activity log
│       ├── componentes/                   Diálogos, avisos y selector de fecha
│       ├── estado/                        Carga, guardado, conflictos, relectura, Dashboard, equipos y ciclo de vida
│       ├── estilos/                       CSS original y estilos de la nueva interfaz
│       └── utilidades/                    Fechas, textos y preferencias locales
│
├── legacy/                               Versión 4.9.4 original: no modificar
│   ├── BW_TestBench_Planner_31_Local.html
│   └── backend_testbench_v4_9_4.py
│
├── datos/
│   ├── escenarios/sharepoint-2026-11-05/ Escenario anonimizado versionado
│   └── privado/                          Lecturas reales; excluidas de Git
│
├── herramientas/
│   ├── importar-sharepoint/              Generación de escenarios anonimizados
│   └── laboratorio/
│       ├── casos/                         Entradas del laboratorio
│       ├── casos-interfaz/                Casos de pantalla (Dashboard, «By Responsible», KPI History y equipos)
│       ├── referencias/                   Resultados esperados de la 4.9.4
│       ├── comparar.cjs                   Comparador backend/paridad
│       ├── comparar-interfaz.cjs          Paridad funcional de React (bloque A)
│       ├── comparar-panel.cjs             Paridad visual de pantallas (bloques B, C, D y E)
│       ├── pruebas-ciclo-de-vida.cjs      Minimizar u ocultar no da de baja la pestaña
│       ├── pruebas-interfaz.cjs           Conflicto, conexión y solo lectura
│       └── backend-simulado-4.9.4.cjs     Backend de apoyo para pruebas React
│
├── docs/
│   ├── 00-plan-aprobado.md               Alcance e hitos
│   ├── 01-inventario-funcional.md        Inventario de comportamiento 4.9.4
│   ├── 02-discrepancias.md                Diferencias conocidas y decisiones
│   ├── 03-arquitectura.md                 Arquitectura Java y React
│   └── 04-ejecucion-y-pruebas.md          Guía operativa y de validación
│
├── .gitignore
└── README.md
```

## Tecnologías

| Área | Tecnología |
|---|---|
| Backend | Java 17, Spring Boot 3.3.5, Maven Wrapper |
| Frontend | React 19.2.5, TypeScript 5.8.3, Vite 7.3.5 |
| Integración SharePoint | Playwright Java mediante Edge y CDP |
| Laboratorio web | Node.js y Playwright |
| Persistencia de desarrollo | Ficheros JSON bajo `~/.testbenchplanner` |

## Puesta en marcha

### Requisitos de desarrollo

- **JDK 17 o superior.** Puede usarse un JDK portable; no es obligatorio
instalarlo en el sistema.
- Conexión a Internet en la primera ejecución de Maven para descargar el wrapper
y las dependencias.
- **Node.js solo es necesario si se modifica el frontend** o se ejecutan las
pruebas web. Las entregas de H3 incluyen `frontend/dist` compilado; si falta en un
clon limpio, se puede generar con npm o con el perfil Maven `interfaz`.

En Windows, si se usa un JDK portable:

```bat
set JAVA_HOME=C:\ruta\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
```

### Compilar y ejecutar las pruebas

```bat
cd backend
mvnw.cmd clean verify
```

En Linux o macOS:

```bash
cd backend
./mvnw clean verify
```

### Ejecutar en modo local

El modo local arranca con el escenario anonimizado y escribe únicamente en la
carpeta del usuario.

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--planner.fecha-fija=2026-11-05 --planner.local.reiniciar-al-arrancar=true"
```

Abrir:

- Nueva interfaz: <http://127.0.0.1:5001/>
- Interfaz clásica: <http://127.0.0.1:5001/clasico>

Los datos locales se guardan en `%USERPROFILE%\.testbenchplanner\datos-locales`.

### Desarrollar el frontend

Con el backend Java arrancado en `5001`:

```bash
cd frontend
npm install
npm run dev
```

Vite abre <http://127.0.0.1:5173> y reenvía las rutas del backend.

Para generar `frontend/dist`:

```bash
npm run build
```

También se puede recompilar React desde Maven sin instalar Node globalmente:

```bat
cd backend
mvnw.cmd -Pinterfaz clean verify
```

Este perfil descarga un Node portable en `backend/target`.

### Ejecutar contra SharePoint en solo lectura

1. Cerrar TestBenchPlanner 4.9.4 si está abierto; utiliza los mismos puertos
`5001` y `9222`.
2. Crear `backend/config/application-sharepoint.yml` —la carpeta está excluida
de Git—:

```yaml
planner:
  almacen: sharepoint
  sharepoint:
    sitio: https://<tenant>.sharepoint.com/sites/<sitio>
    sitio-aprobaciones: https://<tenant>.sharepoint.com/sites/<sitio>/processautomation
    escritura-habilitada: false
```

3. Arrancar el perfil:

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=sharepoint
```

Con `escritura-habilitada: false` no se escribe en SharePoint: también quedan
desactivados presencia, registro de actividad y publicación de versión.

## Pruebas y paridad

### Backend

`mvnw.cmd verify` ejecuta, entre otras, estas suites:

| Suite | Cobertura principal |
|---|---|
| `ParidadLaboratorioTest` | 80 casos de planificación, `DataJson`, orden, KPI, ocupación, histórico, cierre, Dashboard (tabla de ocupación, gráfica por CW y KPI por responsable), KPI History (fotos por organización y estadísticas con filtros) y equipos auxiliares (asignar, quitar, renombrar) frente a la 4.9.4. |
| `ValoresYJsonTest` | Valores, JSON, festivos y semanas ISO compatibles con JavaScript/Python. |
| `ApiLocalTest` | API compatible y API v1, conflicto 409, forzado, reglas 422 y `/clasico`. |
| `RelojTest` | Cambio de día en la zona `Europe/Madrid` y fecha fija. |
| `ProteccionListasTest` | Protección ante lecturas vacías y listas vaciadas. |
| `ListasProtegidasTest` | Protección de punta a punta sin alterar los ficheros locales. |
| `ReinicioLocalTest` | Reinicio controlado del escenario local usado por las pruebas web. |
| `CicloDeVidaTest` | Apagado solo al cerrar la última pestaña: minimizar u ocultar no la da de baja; recargar no apaga. |
| `SoloLecturaIntegracionTest` | Como usuario de solo lectura: las consultas de todas las pantallas responden y los filtros cambian el resultado; las escrituras devuelven 403 sin tocar datos. |
| `ConfiguracionBancosTest` | Test Benches: renombrar conserva ensayos, borrar los borra, mover u Org no tocan ensayos, solo Admin, no se renombra a un banco existente ni se vacía la lista. |

Las salidas de paridad quedan en `backend/target/paridad` y pueden volver a
compararse con:

```bash
node herramientas/laboratorio/comparar.cjs backend/target/paridad
```

### Interfaz React

Las pruebas web requieren Node.js y Playwright:

```bash
node herramientas/laboratorio/backend-simulado-4.9.4.cjs 5001
TZ=Europe/Madrid node herramientas/laboratorio/comparar-interfaz.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-interfaz.cjs
TZ=Europe/Madrid node herramientas/laboratorio/comparar-panel.cjs
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-ciclo-de-vida.cjs
```

El comparador de interfaz recorre 65 casos de edición, fechas, estados, cola,
cierre y roles. `pruebas-interfaz.cjs` cubre además conflicto, desconexión, modo
solo lectura, filtros de consulta en todas las pantallas migradas y el flujo de reinicio local. `comparar-panel.cjs` ejecuta 78 casos
de Dashboard, «By Responsible», KPI History, equipos auxiliares y configuración en la 4.9.4 y
en React con los mismos gestos y compara lo que se ve: KPI, barras del Gantt
(posición, color, texto), puntos de la gráfica, tablas, selector y ventanas,
avisos y mensajes. `pruebas-ciclo-de-vida.cjs` comprueba que ocultar o
minimizar no da de baja la pestaña y que cerrarla sí.

## Configuración

Propiedades principales de Spring Boot:

| Propiedad | Valor por defecto | Uso |
|---|---|---|
| `planner.almacen` | `local` | Adaptador `local` o `sharepoint`. |
| `planner.fecha-fija` | vacío | Simula la fecha actual con formato `aaaa-mm-dd`. |
| `planner.carpeta-usuario` | `~/.testbenchplanner` | Datos locales, calendario y perfil de Edge. |
| `planner.local.escenario-inicial` | `../datos/escenarios/sharepoint-2026-11-05` | Escenario inicial del modo local. |
| `planner.local.reiniciar-al-arrancar` | `false` | Restaura el escenario al iniciar. |
| `planner.local.usuario-email` | `laboratorio@ejemplo.local` | Identidad simulada del modo local. |
| `planner.sharepoint.sitio` | vacío | Sitio principal; obligatorio con SharePoint. |
| `planner.sharepoint.sitio-aprobaciones` | vacío | Sitio de la base de aprobaciones. |
| `planner.sharepoint.escritura-habilitada` | `false` | Habilita las escrituras en las listas. |
| `planner.sharepoint.publicar-version` | `false` | Publica la versión en `TestBenchAppConfig`. |
| `planner.sharepoint.espejo-habilitado` | `true` | Actualiza columnas espejo cuando se permite escribir. |
| `planner.sharepoint.puerto-depuracion` | `9222` | Puerto CDP de Edge. |
| `planner.listas.permitir-vacias` | `false` | Permite guardar sobre listas compartidas vacías solo en una instalación nueva. |

## APIs e interfaces

- **`/`**: frontend React compilado. Si `frontend/dist` no está disponible, se
sirve la interfaz clásica.
- **`/clasico`**: HTML 4.9.4 original sobre el backend Java.
- **`/api/v1/*`**: API usada por React para sesión, planificación, campos,
operaciones, cierre, configuración, aprobaciones, recursos e indicadores
(`/api/v1/indicadores/bloques`, `tabla-ocupacion`, `tendencia`, `foto-semanal`,
`historico`, `estadisticas`) y equipos (`/api/v1/recursos`, `recursos/bloqueos`,
`recursos/renombrar`).
- **`/api/*`**: contrato compatible con el backend Python para mantener operativa
la interfaz 4.9.4.
- **`/api/v1/local/reinicio`**: reinicio del escenario para el laboratorio;
solo existe con `planner.almacen=local`.

> `/clasico` conserva el modelo de concurrencia de la 4.9.4: el último guardado
gana. No debe utilizarse al mismo tiempo que la interfaz React sobre los mismos
ensayos. El aviso de conflicto y la comprobación de versión pertenecen a
`/api/v1`.

## Reglas de seguridad y trabajo

- En desarrollo y pruebas, SharePoint se usa **solo para lectura**.
- La escritura está desactivada por defecto y toda operación pasa por la
protección `planner.sharepoint.escritura-habilitada`.
- No se crean listas ni columnas desde Java.
- `datos/privado/`, `backend/config/` y los perfiles de Edge no se versionan.
- Los escenarios versionados deben estar anonimizados.
- `legacy/` no se modifica: es la referencia funcional.
- Una funcionalidad solo se considera migrada cuando no presenta diferencias
sin clasificar frente al laboratorio.
- Las diferencias aprobadas se registran en
`herramientas/laboratorio/cambios-aprobados.json` (datos) y
`herramientas/laboratorio/cambios-aprobados-interfaz.json` (pantallas).
- **Solo lectura en la interfaz:** `soloLectura` se aplica solo a las acciones
que modifican datos (editar, ordenar, añadir, borrar, cerrar, asignar, guardar
catálogos…). Nunca se deshabilitan contenedores completos ni controles de
consulta (filtros, búsquedas, semanas, rangos, zoom, foco, recargar). Toda
escritura se comprueba también en el backend (403).
- Facturación queda fuera del alcance de esta migración.

## Documentación

- [`docs/00-plan-aprobado.md`](docs/00-plan-aprobado.md): alcance, decisiones e
hitos aprobados.
- [`docs/01-inventario-funcional.md`](docs/01-inventario-funcional.md): inventario
de la versión 4.9.4.
- [`docs/02-discrepancias.md`](docs/02-discrepancias.md): discrepancias conocidas
y resolución adoptada.
- [`docs/03-arquitectura.md`](docs/03-arquitectura.md): detalle de módulos,
correspondencia con la 4.9.4 y diseño de React.
- [`docs/04-ejecucion-y-pruebas.md`](docs/04-ejecucion-y-pruebas.md): comandos de
ejecución, configuración y validación manual.
- [`herramientas/laboratorio/README.md`](herramientas/laboratorio/README.md): uso
del laboratorio de paridad.
