# Gantt general rediseñado (H8)

Petición de Pedro (16-nov-2026, chat «Propuestas Visuales para Gantt Moderno»):
cuando hay muchos bancos y ensayos apilados, el Gantt general es confuso y
cuesta saber qué es de cada proyecto. De las cinco maquetas (A–E) decidió:

- **Base: propuesta A** (carriles apilados + color por proyecto).
- **Trazabilidad de la propuesta D**, como mejora del «Focus proyecto» que ya existía.
- **Tarjetas al pasar el ratón** (de D y E).
- La agrupación de ensayos cortos de E **no** entra (no la pidió).

Afecta al Gantt general: **Dashboard › Annual plan by bench** y **By Responsible**
(Manager View y por persona). El Gantt de cada banco, el de equipos, el Excel,
Export read-only y `/clasico` no cambian.

## Qué se ve

| Elemento | Comportamiento |
|---|---|
| **Color = proyecto** | Cada proyecto tiene siempre el mismo color (el mismo que ya usaba el Excel del Gantt). Leyenda con los 14 proyectos con más barras en el periodo y «+N más»; clic en un proyecto de la leyenda = enfocarlo. El selector **Color: Proyecto / Estado** permite volver a los colores por estado de la 4.9.4 (se recuerda en el navegador). Con «Color: Proyecto» se ocultan los chips Queued/Confirmed/Running/En retraso de la tarjeta, que ya no aplicarían. |
| **Forma = estado** | Completado: translúcido con ✓ · En curso: marca blanca a la izquierda · Confirmado: relleno · En cola: discontinuo y casi transparente · En retraso: borde rojo · Bloqueo (Calibration, Maintenance…): rayado, como antes. Leyenda de formas en la barra de herramientas. |
| **Carriles** | Los ensayos que coinciden en el tiempo en un banco van en filas separadas en vez de pintarse unos encima de otros. El banco crece lo necesario y muestra el aviso **«N en paralelo»** (máximo de ensayos a la vez). |
| **Ocupación** | Mini barra y % junto al nombre del banco: días del periodo visible con algún ensayo (sin bloqueos), sin contar dos veces los solapes. Verde, ámbar desde 70 % y rojo desde 90 %. Es una referencia visual rápida; la ocupación oficial (horas 7×24, fines de semana) sigue siendo la de la tabla del Dashboard. |
| **Categorías plegables** | Cabecera por categoría (con su color y nº de bancos) debajo de la de área (la de área solo sale si se ven varias). Clic = plegar o desplegar; se recuerda en el navegador. |
| **Pasado atenuado** | Sombreado antes de HOY y líneas suaves por semana. |
| **Tarjeta al pasar el ratón** | Sustituye al tooltip: proyecto, estado, ensayo (tipo · comentario), banco y categoría, fechas y días, responsable, avance, fechas comprometidas, ID de aprobación y, con el foco en ese proyecto, **de dónde viene y adónde va**. Atajos: **clic** = abrir el ensayo en su banco (se centra y se resalta un momento en la tabla; los cerrados abren la lista de completados) y **F** = enfocar ese proyecto. |

### Modo foco (Focus proyecto)

- **Ficha resumen** del proyecto: ensayos, bancos, % completado (cerrados = 100 %, activos según su avance), cerrados, fin previsto (el fin más tardío de los activos), cuántos están en retraso, responsables y pasos entre bancos. ✕ quita el foco. Cuenta todo el proyecto, no solo lo visible, con los mismos filtros de área/Org y responsable.
- **Bancos del proyecto resaltados** (franja morada y fondo); el resto de bancos en gris claro.
- **El resto de barras se atenúan pero conservan el color de su proyecto** (se sigue viendo qué está ocupado).
- **Las categorías sin ensayos del proyecto se pliegan solas** (se pueden abrir con un clic; al cambiar de proyecto vuelven a plegarse).
- **Trazabilidad (botón ☑/☐, recordado)**: flechas entre ensayos consecutivos del proyecto en bancos distintos.

### Cómo se deduce la trazabilidad

La aplicación **no guarda la muestra** de cada ensayo, así que el recorrido se
deduce de las fechas: el origen de un ensayo es el ensayo del mismo proyecto,
**en otro banco**, que terminó más tarde sin pasar de su inicio y como mucho
**21 días antes**. Cada ensayo tiene como mucho un origen; un origen puede
alimentar a varios (muestras que se reparten). Los bloqueos no cuentan.

Si hay **12 flechas o menos** se dibujan todas. Si hay más (proyectos grandes:
el proyecto BMW 80000885 tiene 58 pasos), solo se dibujan las del ensayo bajo el
ratón, y la ficha lo indica, para que el Gantt no se llene de líneas.

Si en el futuro se quiere una trazabilidad exacta por muestra (S1, S2…), haría
falta un campo «Muestra» en el ensayo (DataJson y, si se quiere ver en
SharePoint, una columna espejo). Las flechas pasarían a unir los ensayos que
comparten muestra sin cambiar nada más.

## Dónde está

| Pieza | Fichero |
|---|---|
| Gantt general | `frontend/src/bloques/panel/GanttGeneral.tsx` |
| Tarjeta | `frontend/src/bloques/panel/TarjetaEnsayo.tsx` |
| Cálculos puros (carriles, simultáneos, ocupación, trazabilidad) | `frontend/src/utilidades/ganttModerno.ts` |
| Estilos (todo bajo `.gm`) | `frontend/src/estilos/gantt-moderno.css` |
| Abrir el ensayo desde el Gantt | `frontend/src/bloques/banco/VistaBanco.tsx` (lee `sessionStorage.tbp_abrir_ensayo`) |
| Altura libre: cabecera y barra horizontal flotantes (H9) | `frontend/src/bloques/panel/desplazamientoGantt.tsx` |

Preferencias nuevas del navegador: `tbp_gantt_color`, `tbp_gantt_traza`,
`tbp_gantt_cat_plegadas`. El foco sigue en `tbp_gantt_foco` (compartido con el
Gantt del banco, como antes).

Sin cambios en el backend Java, en SharePoint ni en los datos.

## Altura libre y divisor del banco (H9)

Petición de Pedro tras ver el H8: el tirador para ampliar la tarjeta del Gantt
no dejaba que el diagrama ocupase todo el alto (el Gantt seguía limitado al 70 %
de la pantalla con su propio scroll), y en cada banco, al bajar el divisor de
la tabla, el Gantt de abajo se comprimía.

- **Dashboard y By Responsible.** Se quita el tirador de la tarjeta del Gantt
  («Annual plan by bench», «My/Team plan by bench»). El Gantt ya no tiene
  scroll vertical propio: mide lo que necesitan sus bancos y es la página la
  que se desplaza. Las demás tarjetas (gráfica y tabla de ocupación, tabla del
  Manager View) conservan su tirador.
- **Cabecera flotante.** Mientras el Gantt está a la vista, la cabecera (meses,
  CW y días) acompaña al scroll de la página y se queda arriba; al salir del
  Gantt vuelve a su sitio.
- **Barra horizontal flotante.** Si la barra de scroll horizontal del Gantt
  queda por debajo de la pantalla, aparece una copia pegada al borde inferior
  que mueve el diagrama (y se mueve con él). Desaparece en cuanto se ve la del
  propio Gantt.
- **Banco.** El divisor se mantiene, pero ahora fija el **alto de la tabla** y
  no un reparto: el Gantt de abajo mide siempre lo que necesita, así que
  agrandar la tabla lo **desplaza hacia abajo** (la página hace scroll) en vez
  de comprimirlo. La tabla no se puede abrir más de lo que mide entera. Por
  defecto la tabla mide lo que necesita, hasta media pantalla. Doble clic en el
  divisor vuelve al alto por defecto. El alto se recuerda en
  `tbp_alto_tabla_banco` (deja de usarse `tbp_reparto_banco`).
- El Gantt del banco usa también la cabecera y la barra horizontal flotantes.
- Al imprimir no cambia nada (los elementos flotantes llevan `no-print`).

Para la paridad, la tarjeta sigue siendo `.card.rzcard`, el Gantt general
conserva la clase `g-mini` (la altura libre la da `gm-alto-libre`) y la barra
flotante lleva `data-solo-nueva`.

## Paridad con la 4.9.4 (D40)

`comparar-panel.cjs` sigue comparando el Gantt general con la 4.9.4: posición y
ancho de cada barra, texto, clases (`solid`, `striped`, `haloq`, `g-dim`,
`g-foc`), círculos del compromiso, cabeceras y línea de HOY. Lo nuevo se ha
dejado fuera de la comparación así:

- El texto del tooltip de la 4.9.4 (estado y fechas) se conserva en `data-titulo`
  y se compara igual; ya no se compara el color de fondo, la opacidad ni la
  sombra (ahora el color es el del proyecto y el estado va por la forma).
- De la etiqueta del banco se compara solo el nombre (`.g-bname`).
- Los controles y elementos nuevos llevan `data-solo-nueva`.
- Las filas de categorías plegadas siguen en la página (ocultas), así que el
  número y orden de bancos se sigue comparando.

## Pruebas

| Prueba | Resultado |
|---|---|
| `cd frontend && npm test` (carriles, ocupación, trazabilidad, flechas visibles) | 20/20 (8 nuevas) |
| `pruebas-gantt.cjs` (9 pruebas en el navegador; 2 nuevas en H9: altura libre y divisor del banco) | 9/9 |
| `comparar-panel.cjs` (Dashboard, By Responsible, KPI, equipos, configuración, exportaciones) | 94/94 (UE08/UE19/UE20 con el texto de ayuda aprobado como D40) |
| `comparar-interfaz.cjs` · `pruebas-interfaz.cjs` · `pruebas-areas.cjs` · `pruebas-cierre.cjs` | 68/68 · 7/7 · 7/7 · 8/8 |

Las mismas suites se volvieron a pasar tras el H9 con el mismo resultado (94/94, 68/68, 7/7, 7/7, 8/8, 20/20).

Prueba manual sugerida: Dashboard › año completo; comprobar los carriles en un
banco con muchos ensayos (Fatiga, MD1); elegir un proyecto grande en «Focus
proyecto» y pasar el ratón por sus ensayos; pulsar F sobre otra barra; clic en
una barra para abrir el banco; plegar una categoría y recargar; probar «Color:
Estado»; Zoom to week (círculos del compromiso en su carril). H9: bajar por el
Dashboard con zoom (cabecera arriba y barra horizontal abajo); en un banco con
muchos ensayos, bajar el divisor y comprobar que el Gantt baja sin encogerse.
