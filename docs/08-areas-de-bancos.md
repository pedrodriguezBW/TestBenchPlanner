# Áreas de bancos (H7)

Petición de Pedro: «CambiosEstructuraTestBenchPlanner.docx» (16-nov-2026).
Las respuestas a las 14 preguntas del análisis están al final.

## Qué cambia

Se añade un nivel **por encima de las categorías** de bancos:

| Código | Nombre de serie | Organización |
|---|---|---|
| `THERMAL` | Thermal Benches | Se elige banco a banco: BW o CTAG (como hasta ahora). |
| `HVCH-CTAG` | HVCH - CTAG | Fija: los bancos guardan `Org = CTAG`. No se muestra columna Org. |
| `HVCH-STC` | HVCH - STC | Fija: los bancos guardan `Org = STC`. No se muestra columna Org. |

- Las áreas son **fijas** y siempre van en ese orden. Un Admin puede **renombrarlas**
  (Test Benches), pero no crear áreas nuevas.
- El área es de la **categoría**: todos los bancos de una categoría están en la
  misma área. Mover una categoría de área mueve todos sus bancos.
- Todos los bancos actuales son **Thermal Benches**: no hace falta migrar datos.

## Dónde se guarda

| Dato | Dónde | Notas |
|---|---|---|
| Área de cada categoría | Columna nueva **`Area`** (una línea de texto) de `TestBenchBenches`, el mismo valor en todos los bancos de la categoría | Vacía o desconocida = Thermal Benches. Thermal Benches se guarda **vacío**, así que mientras no haya bancos HVCH la columna no es obligatoria. Si falta la columna y se intenta poner una categoría en HVCH, el guardado se para con un mensaje claro antes de escribir nada. **La columna la crea un Admin del sitio** (la aplicación no cambia el esquema de las listas). |
| Nombre de un área (solo si se renombra) | `TestBenchAppConfig`, fila con Title `AreaName:THERMAL` / `AreaName:HVCH-CTAG` / `AreaName:HVCH-STC` y el nombre en `Value` | Sin fila = nombre de serie. Solo se escribe si cambia. |
| Organización de los bancos HVCH | Columna `Org` de siempre (`CTAG` o `STC`) | Así la interfaz clásica, que trata un banco sin Org como BW, no los suma a BW. |
| Local | `configuracion.json`: `benches[].area` y `areaNames` | |

La interfaz clásica (`/clasico`) no conoce las áreas: guarda la lista de bancos
sin `area` y se conserva la guardada (igual que `EquipmentType`).

## Filtro de bancos

Dashboard, «By Responsible», gráfica por CW, tabla de ocupación, KPI History y
estadísticas tienen **dos desplegables**: **Area** (ALL, Thermal Benches,
HVCH - CTAG, HVCH - STC) y, **solo con Thermal Benches**, **Org** (ALL, BW, CTAG).

Internamente es un solo código, el que viaja en `?org=` a la API:

| Area | Org | Código | Bancos |
|---|---|---|---|
| ALL | — | `ALL` | Todos |
| Thermal Benches | ALL | `THERMAL` | Todos los térmicos |
| Thermal Benches | BW | `BW` | Térmicos de BW |
| Thermal Benches | CTAG | `CTAG` | Térmicos de CTAG |
| HVCH - CTAG | — | `HVCH-CTAG` | Los del área |
| HVCH - STC | — | `HVCH-STC` | Los del área |

`BW` y `CTAG` son los códigos de la 4.9.4: mientras todos los bancos eran térmicos
significaban lo mismo, por eso el histórico guardado sigue valiendo. Ya no existe
«todo lo BW de todas las áreas» (acordado).

## Pantallas

- **Menú lateral:** los bancos van agrupados por área y, dentro, por categoría.
  Arriba hay un desplegable («All areas» o un área) y cada área se pliega con un
  clic en su título. Las dos cosas se recuerdan en el navegador y no dependen del
  filtro del Dashboard. Un área sin categorías no aparece en el menú.
- **Configuration › Test Benches:** un bloque por área (también las vacías) con
  su nombre editable, su «＋ Add category» y sus categorías. Cada categoría tiene
  un selector de área para moverla (pide confirmación y avisa de la organización
  que tomarán sus bancos). ▲/▼ de categoría mueve dentro de su área. La columna
  Org solo aparece en Thermal Benches. «Move to category» indica el área de las
  categorías de otra área; al mover un banco a otra área toma su organización.
- **Gantt del Dashboard y de «By Responsible»:** si se ven bancos de más de un
  área, una fila de título por área. El Excel del Gantt lleva esa misma fila.
- **Additional Equipment › List of Equipments:** filtro **Org** (ALL, BW, CTAG)
  según la organización de cada equipo en Equipments List (sin Org = BW). Se
  recuerda en el navegador. No cambia nada más de los equipos.

## KPI History (foto semanal)

«📊 Update Yearly Status» guarda ahora **seis filas** por semana en
`TestBenchKPIHistory` (misma lista, misma columna `Org`, de texto):
`ALL`, `BW`, `CTAG`, `THERMAL`, `HVCH-CTAG`, `HVCH-STC`. Mismo cálculo que hasta
ahora, con el nivel superior. Título de fila: `2026-CW47-HVCH-STC`.

Lectura de las semanas anteriores: `BW` y `CTAG` se leen como Thermal Benches de
esa organización y, para Thermal Benches (sin organización), se usa la fila `ALL`
de las semanas que no tienen fila `THERMAL` (entonces todo era térmico).

Si alguien usa la interfaz clásica para la foto semanal cuando ya hay bancos
HVCH, la foto la calcula el servidor (las seis filas), porque el «CTAG» de la
clásica sumaría los bancos de HVCH - CTAG a los térmicos.

## Copia JSON

La exportación añade `groups[].area` y `areaNames`; la importación los aplica si
vienen (una copia antigua conserva las áreas guardadas). El «Export read-only» y
`/clasico` se quedan **sin áreas** (acordado).

## Lo que NO cambia

- Permisos y calendario (festivos y fines de semana) son los mismos para todas las áreas.
- Facturación: sin cambios hasta el documento de facturación. Pedro querrá
  facturar **cada área por separado** (en los térmicos la facturación no suele
  correr a nuestra cuenta). La API de la hoja ya devuelve el área de cada banco.

## Pruebas

- Java: `AreasTest` (lectura de la columna, normalización, filtros, histórico) y
  `AreasIntegracionTest` (mover categoría a HVCH, Org fija, nombres de área,
  KPI que cuadran ALL = THERMAL + HVCH y THERMAL = BW + CTAG, la clásica conserva
  el área, foto semanal con 6 filas).
- `ParidadLaboratorioTest` compara solo las fotos ALL/BW/CTAG con la 4.9.4 (D39).
- `herramientas/laboratorio/pruebas-areas.cjs`: 7 pruebas de interfaz (Test
  Benches por áreas, mover categoría, menú, Dashboard, KPI History, renombrar
  área, filtro de equipos).
- `comparar-panel.cjs`: con todos los bancos térmicos la versión nueva debe verse
  igual que la 4.9.4; el selector de área se trata como el Org de la 4.9.4 (D39).

## Respuestas de Pedro al análisis (16-nov-2026)

1. Área por categoría, columna nueva `Area` (vacío = Thermal). 2. Áreas fijas,
renombrables. 3. Todo en inglés, guion corto. 4. Org solo en Thermal; HVCH con Org
fija CTAG/STC. 5. Los bancos actuales son todos Thermal. 6. Dos desplegables.
7. También en las tarjetas con filtro propio, By Responsible y Gantt general.
8. Menú: desplegable de área + secciones plegables, independiente del Dashboard.
9. Test Benches por áreas, áreas vacías visibles. 10. Seis fotos, misma lógica.
11. Facturación: esperar al documento. 12. Equipos: solo un filtro BW/CTAG.
13. Excel del Gantt y copia JSON con área; read-only y /clasico planos.
14. Permisos y calendario sin cambios.
