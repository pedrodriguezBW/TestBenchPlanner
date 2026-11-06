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

---

## Interfaz React (Hito 3)

### Usarla en el PC (sin Node)

1. `cd backend` y `mvnw.cmd clean verify` (como siempre). El jar ya incluye la
   interfaz que viene compilada en `frontend/dist`.
2. Arrancar igual que en H2 (local o SharePoint en solo lectura).
3. Abrir `http://127.0.0.1:5001/`. La clásica sigue en `/clasico`.

Si `frontend/dist` no existe, «/» muestra la interfaz clásica como antes.

### Recompilar la interfaz (solo si se cambia `frontend/src`)

- Sin instalar nada: `mvnw.cmd -Pinterfaz clean verify` (necesita salida a
  nodejs.org y al registro de npm).
- Con Node disponible: `cd frontend`, `npm install`, `npm run build`.
  Para desarrollar con recarga automática: `npm run dev` (puerto 5173, reenvía
  `/api` al backend en 5001).

### Pruebas de la interfaz (laboratorio)

Necesitan Node y Playwright; se han pasado en el entorno de desarrollo contra el
backend simulado (que ejecuta el HTML de la 4.9.4 por detrás):

```
node herramientas/laboratorio/backend-simulado-4.9.4.cjs 5001
TZ=Europe/Madrid node herramientas/laboratorio/comparar-interfaz.cjs     # paridad: 65 casos F, E, S, C, Q, K, R
TZ=Europe/Madrid node herramientas/laboratorio/pruebas-interfaz.cjs      # conflicto, sin conexión, solo lectura, D17
```

`comparar-interfaz.cjs` pulsa, escribe, elige en el calendario y arrastra como
un usuario, y compara planificación, orden, DataJson guardado y ventana de
cierre con las referencias de la 4.9.4. Admite `--url` para lanzarlo contra el
backend Java en modo local (usa `/api/v1/local/reinicio`, que **solo existe con
`planner.almacen=local`**).

### Prueba manual en el PC (bloque A)

Con el backend en modo local y `http://127.0.0.1:5001/`:

1. Cambiar de banco en el menú lateral; la dirección cambia a `#/<banco>`.
2. Editar comentario, duración, fechas (calendario con CW, Today, Clear), casillas.
   El indicador pasa a «Saving…» y vuelve a «● Synced».
3. Arrastrar filas por ⠿ (una y varias seleccionadas); duplicar, mover a otro banco, borrar.
4. 📌 por fila, ↩ deshacer, Confirm all.
5. ✔ cerrar un ensayo; lista «Completed»: Reuse, ✎ Corregir, Restore.
6. Conflicto: abrir dos pestañas, cambiar el mismo comentario en las dos en
   menos de 20 s → aparece «Otra persona ha cambiado este ensayo».
7. Comparar visualmente con `/clasico` (mismos datos, mismos colores y textos).

### Prueba manual en el PC (bloque B)

Con el backend en modo local y `http://127.0.0.1:5001/` (se abre en el Dashboard):

1. Comparar con `/clasico` los dos bloques KPI, el plan anual, la gráfica y la tabla de ocupación.
2. ◀ Prev / Next ▶ / Today y el filtro Org. Prev/Next pasan el plan a la semana; «Full year» vuelve.
3. Zoom −/+/100 % y «Focus proyecto» en el plan anual.
4. Gráfica: % / Horas, Org de la tarjeta, CW desde–hasta, Full year y ocultar una serie desde la leyenda.
5. Tabla de ocupación: % / Horas, Org, rango de CW (acumulado), «Año» y «Semana del dashboard».
6. Arrastrar el tirador inferior de una tarjeta (doble clic lo restablece).
7. «By Responsible»: una persona, Manager View (tabla del equipo), Org y zoom.
8. 📊 Update Yearly Status (en modo local escribe en el histórico local) y mensaje de confirmación.

Paridad automática del bloque B (con Node): `TZ=Europe/Madrid node herramientas/laboratorio/comparar-panel.cjs`.

### Prueba manual en el PC (bloque C)

Con el backend en modo local, menú «📈 KPI History», comparando con `/clasico`:

1. Org ALL / BW / CTAG: tarjetas de la foto semanal, tabla por semana y motivos acumulados.
2. Estadísticas: Año, Año hasta hoy, Trimestre, Mes y un rango con Desde/Hasta.
3. Responsable: una persona y «Todos».
4. «Reload from SharePoint»: el indicador pasa a «Actualizando…» y vuelve a «Actualizado hh:mm».
5. Cerrar un ensayo en un banco y volver: aparece en las estadísticas.
6. 📊 Update Yearly Status en el Dashboard y volver: la semana aparece en la foto semanal.
7. Con SharePoint en solo lectura: mismos números que `/clasico`.

### Prueba manual del ciclo de vida (corrección del 6-nov)

1. Abrir la app, minimizar Edge 2-3 minutos y volver: el backend sigue vivo y no hay aviso «Sin backend».
2. Dejar la pestaña en segundo plano (otra pestaña delante) varios minutos y volver: igual.
3. Abrir dos pestañas y cerrar una: el backend sigue.
4. Recargar (F5): el backend sigue.
5. Cerrar la última pestaña: a los ~8 s el log muestra «Sin pestañas abiertas: se cierra el backend».

### Prueba manual en el PC (bloque D)

En modo local, comparando con `/clasico`:

1. Banco MD2: columna «Resources» al final de la tabla; 🔍 abre el selector («Disponibles» / «En uso», búsqueda).
2. Añadir un equipo libre y uno ocupado: con el ocupado aparece el aviso ámbar y la fila muestra «!».
3. Quitar un equipo con «✓ Quitar».
4. «📋 List of Equipments»: Gantt por equipo, Prev/Next/Today, zoom a la semana, foco de proyecto.
5. Admin: «🔒 Bloquear recurso», guardar, ver la barra rayada y la fila en «Bloqueos manuales»; borrarla.
6. Admin: «🔧 Equipments List»: nuevo tipo, nuevo equipo, código (y código repetido), Org, renombrar
   (el nombre cambia en los ensayos) y borrar.
7. Con SharePoint en solo lectura: mismos datos que `/clasico` y ningún cambio se guarda.

