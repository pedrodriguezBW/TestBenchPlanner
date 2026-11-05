# Ejecución y pruebas (Hito 2)

## Requisitos en el PC de desarrollo

- **JDK 17 o superior.** No hace falta instalarlo: basta con descomprimir un JDK
  portable (p. ej. Eclipse Temurin 17 en .zip) y apuntar `JAVA_HOME` a esa carpeta
  en la ventana de comandos:
  ```bat
  set JAVA_HOME=C:\ruta\jdk-17
  set PATH=%JAVA_HOME%\bin;%PATH%
  ```
- Conexión a Internet la primera vez (descarga Maven y las dependencias).
  Si hay proxy corporativo, configúralo en `%USERPROFILE%\.m2\settings.xml`.
- Para la versión distribuida al equipo, Java irá **empaquetado** (Hito 4): el
  equipo no necesitará instalar nada.

## Compilar y pasar todas las pruebas

```bat
cd backend
mvnw.cmd verify > ..\resultado_mvn.txt 2>&1
```

Guarda `resultado_mvn.txt` en la carpeta compartida de OneDrive para revisarlo.

Pruebas incluidas:

| Prueba | Qué comprueba |
|---|---|
| `ParidadLaboratorioTest` | Los 67 casos del laboratorio: el dominio Java debe dar lo mismo que la 4.9.4 (planificación, DataJson guardado, orden, KPI, ocupación, estadísticas, histórico, ventana de cierre). Solo se admiten las diferencias de `cambios-aprobados.json` (D1, D8, D14). Deja cada foto en `target/paridad/`. |
| `ValoresYJsonTest` | Conversión de valores y formato JSON idénticos a JavaScript y Python; festivos; semanas ISO. |
| `ApiLocalTest` | Backend completo con el almacén local: API compatible, API nueva, conflicto 409 y «guardar la mía encima», 422 por regla de negocio, /clasico. |
| `RelojTest` | D7: «hoy» cambia a medianoche de Madrid, también con fecha fija, y cada cálculo usa la fecha del momento. |
| `ProteccionListasTest` | D11: reglas de lectura vacía y de lista vaciada. |
| `ListasProtegidasTest` | D11 de punta a punta: sin lectura previa, lectura vacía, lectura fallida, vaciar proyectos, usuarios y recursos → 412 y el fichero queda idéntico. |

Las fotos de Java también se pueden comparar con el comparador del laboratorio:
`node herramientas/laboratorio/comparar.cjs backend/target/paridad`.

## Arrancar en modo local (sin SharePoint)

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--planner.fecha-fija=2026-11-05 --planner.local.reiniciar-al-arrancar=true"
```

Abre `http://127.0.0.1:5001/clasico`: es la 4.9.4 original funcionando sobre el
backend Java con los datos del escenario anonimizado. Los cambios se guardan en
`%USERPROFILE%\.testbenchplanner\datos-locales`.

## Arrancar contra SharePoint en SOLO LECTURA (en la oficina)

1. Cierra el Planner 4.9.4 si está abierto (usa el mismo puerto 5001 y el 9222).
2. Crea `backend\config\application-sharepoint.yml` (no se versiona) con:
   ```yaml
   planner:
     almacen: sharepoint
     sharepoint:
       sitio: https://<tenant>.sharepoint.com/sites/<sitio>
       sitio-aprobaciones: https://<tenant>.sharepoint.com/sites/<sitio>/processautomation
       escritura-habilitada: false
   ```
3. Arranca: `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=sharepoint`
4. Abre `http://127.0.0.1:5001/clasico` y compara con la 4.9.4.

Con `escritura-habilitada: false` cualquier intento de guardar devuelve el aviso
«La escritura en el almacén está desactivada» y **no se envía nada** a
SharePoint (tampoco presencia, registro ni versión).

El perfil de Edge se crea en `%USERPROFILE%\.testbenchplanner\.edge_profile`
(distinto del de la 4.9.4): la primera vez puede pedir iniciar sesión.

## Variables de configuración

| Propiedad | Por defecto | Para qué |
|---|---|---|
| `planner.almacen` | `local` | `local` o `sharepoint` |
| `planner.fecha-fija` | vacío | Simular otro día (aaaa-mm-dd) |
| `planner.carpeta-usuario` | `~/.testbenchplanner` | Calendario, datos locales y perfil de Edge |
| `planner.local.escenario-inicial` | `../datos/escenarios/sharepoint-2026-11-05` | Datos de partida del modo local |
| `planner.local.reiniciar-al-arrancar` | `false` | Volver al escenario al arrancar |
| `planner.local.usuario-email` | `laboratorio@ejemplo.local` | Identidad simulada (su rol sale de usuarios.json) |
| `planner.sharepoint.sitio` | vacío (obligatorio) | Sitio de las listas |
| `planner.sharepoint.sitio-aprobaciones` | vacío | Sitio de la base de aprobaciones |
| `planner.sharepoint.escritura-habilitada` | `false` | Permitir escribir en las listas |
| `planner.sharepoint.publicar-version` | `false` | Publicar la versión en TestBenchAppConfig |
| `planner.sharepoint.espejo-habilitado` | `true` | Escribir las columnas espejo (solo con escritura habilitada) |
| `planner.sharepoint.puerto-depuracion` | `9222` | Puerto CDP de Edge |
| `planner.listas.permitir-vacias` | `false` | D11: permitir guardar sobre listas compartidas vacías (solo instalación nueva) |

## Limitación conocida de /clasico (D12)

`/clasico` es la interfaz 4.9.4 sin cambios y guarda con su contrato original:
el último que guarda gana. El aviso de conflicto (409) y la comprobación de
ETag solo existen en `/api/v1` (interfaz React). Usa `/clasico` solo como
respaldo, no a la vez que la interfaz nueva sobre los mismos ensayos.
