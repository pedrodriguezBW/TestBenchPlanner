# 07 · Facturación (H6, 12-nov-2026)

Esta versión **sustituye por completo** a la facturación de H5–H5d (periodos, revisión,
aprobación, ajustes, auditoría, Controlling, presentación, ficha «Billing Data» y
almacenamiento JSON). Todo eso se ha eliminado. Lo que queda es exactamente lo descrito en
«CambiosTestBenchFacturacion.docx»: reproducir la hoja mensual del Excel
«2026 Ensayos interno.xlsx» (hojas ENERO, FEBRERO…) a partir de la configuración de las
tareas, con los datos relacionales en listas de SharePoint.

## 0. Periodo de facturación (H6c)

El mes de facturación es un **periodo del día 21 del mes anterior al día 20 del mes**, ambos
incluidos: «febrero 2026» = 21/01/2026 – 20/02/2026 (como en el plan de facturación). Todo se
calcula sobre ese periodo, **no** sobre el mes natural (en el Excel las fechas y horas iban por
mes natural; aquí se corrige):
- Horas netas: solo los días de la tarea dentro del periodo (la parada, en proporción).
- Setup (o piezas): entero en el periodo en que empieza la tarea.
- Inicio / Fin de la tabla dinámica y del detalle: el tramo de la tarea dentro del periodo
  (una tarea del 1 de diciembre al 31 de marzo sale en febrero como 21/01 – 20/02). El detalle
  muestra además las fechas completas de la tarea.
- Tarifa: la del año del mes de facturación (enero 2026 usa 2026 aunque empiece el 21/12/2025).
- El periodo se ve junto al selector de mes y en la cabecera del Excel y del HTML.

## 1. De dónde sale cada dato

| Campo del Excel (INPUTS) | En TestBenchPlanner |
|---|---|
| Proyecto / Nº proyecto | Columna **Project** de la tarea (lista de proyectos) |
| Orden | **Billing Order** del proyecto (Configuration › Projects, columna `BillingOrder` de TestBenchProjects) |
| Requester | **Responsible** de la tarea |
| Planta | **Product Line** de la tarea (Vigo, Viana, Heaters, eCoolers, Recuperador, STC) |
| Equipo | **Equipment Type** del banco donde está la tarea (Configuration › Test Benches, columna `EquipmentType` de TestBenchBenches) |
| Tipo | **Group** del Equipment Type (Billing › Configuration) |
| HV | Columna **HV** de la tarea (Yes / No) |
| Inicio / Fin | Arranque Real y Fin Real (si faltan: Test Start; una tarea en curso cuenta hasta hoy), recortados al periodo |
| Horas netas ensayo | Horas de Inicio a Fin dentro del periodo (días completos × 24) menos la **Parada** (días × 24, repartida en proporción a los días de cada periodo) |
| Horas set-up | Columna **Setup (h)** de la tarea, detrás de Parada. Se factura entero en el periodo en que empieza la tarea. En los Equipment Types **por piezas** (RACS) ahí se escriben las piezas, como en el Excel |
| Horas totales | Horas netas + setup |
| Coste/hora | Tarifa **With HV** o **Without HV** del Equipment Type en el **año** del mes |
| Coste total | La **Billing Rule** del Equipment Type |
| Mes facturación | El mes que se consulta (periodo 21 del mes anterior – 20) |

Tareas que no se facturan: tipos especiales (Calibration, Maintenance, Breakdown, Not
available) y tareas abiertas que aún no han arrancado. No hace falta que estén
terminadas: basta con que estén en curso.

## 2. Reglas de cálculo (fijas en el código)

| Regla | Cálculo |
|---|---|
| LINEAR | horas totales × tarifa |
| ASINH_100 | tarifa × ASINH(horas/100) × 100 (R&D en el Excel) |
| ASINH_200 | tarifa × ASINH(horas/200) × 200 (Heating Plates, HVCH Lubu) |
| PER_PIECE | piezas × tarifa (RACS) |
| NOT_BILLABLE | la tarea no aparece |

Reglas del Excel que se mantienen: **Product Line STC → coste 0** (no necesita HV ni
tarifa) y **número de proyecto 0** (p. ej. «00000 - STC General Works») **→ coste 0**.

## 3. Pantallas

- **Billing › Monthly sheet** (todos): selector de mes con desplegables de mes y año y flechas
  ◀ ▶. Tabla dinámica como la del Excel (TablaDin_varios): filas **Billing Order → Planta
  (Product Line) → Tipo (Group) → Equipo (Equipment Type) → HV → Inicio → Fin → Coste/hora**,
  columnas una por Group y «Total general». Con las casillas «Filas» se eligen los campos que
  salen (el orden es siempre ese; la elección se recuerda en el navegador). Cada fila se pliega
  y despliega (⊟ / ⊞) y «Mostrar hasta» pliega todo a un nivel. Fechas en formato largo
  («jueves, 1 de enero de 2026», tramo dentro del periodo) y Coste/hora con coma («3,72»).
  La exportación usa los mismos campos; en el Excel las filas quedan agrupadas por niveles
  (botones 1…8 y +/− del margen) y con sangría.
  La **vista previa** incluye las tareas a las que les falta Equipment Type, HV o tarifa
  (marcadas con ⚠, importe 0 y listadas aparte con lo que les falta). **La exportación
  (Excel o HTML) solo lleva las completas.** «Ver solo lo que se exporta» enseña la tabla
  tal y como saldrá. Debajo, el detalle de horas y coste de cada tarea.
- **Billing › Configuration** (Admin): una única tabla por año con Equipment Type, Group,
  With HV, Without HV, Units y Billing Rule; «＋ Año» copia el último año; se puede borrar
  un año. Lista de bancos sin Equipment Type y tabla de reglas.
- **Configuration › Test Benches**: columna **Equipment Type** (antes «Billing equipment»).
- **Configuration › Projects**: columna **Billing order** (sin cambios).
- **Tabla de cada banco**: columnas nuevas **Setup (h)** (detrás de Parada), **HV** y
  **Product Line** (al final). Se ha quitado la columna «Billing».

## 4. SharePoint (sin JSON)

La aplicación no crea listas ni columnas: un Admin del sitio debe crearlas.

| Lista | Columna | Tipo |
|---|---|---|
| **TestBenchEquipmentTypes** (nueva) | Title (= Equipment Type) | ya existe |
| | EquipmentGroup | Una línea de texto |
| | Year | Número |
| | WithoutHV | Número |
| | WithHV | Número |
| | Units | Una línea de texto (`HORAS` / `PIEZAS`) |
| | BillingRule | Una línea de texto (`LINEAR`, `ASINH_100`, `ASINH_200`, `PER_PIECE`, `NOT_BILLABLE`) |
| TestBenchBenches | **EquipmentType** (sustituye a BillingCategory) | Una línea de texto |
| TestBenchProjects | BillingOrder | Una línea de texto (ya creada) |
| TestBenchPlanner (tareas) | SetupHours | Número |
| | HV | Una línea de texto |
| | ProductLine | Una línea de texto |

Notas:
- Una fila de TestBenchEquipmentTypes por Equipment Type **y año**. Al guardar solo se
  escriben las filas que cambian; las que sobran van a la papelera.
- Si falta la lista o alguna de sus columnas, Billing › Configuration lo dice y no deja
  guardar; la hoja mensual no se calcula.
- En la lista de tareas, los valores se guardan en el ensayo como el resto de campos; las
  columnas SetupHours, HV y ProductLine son espejo para filtrar en SharePoint y solo se
  escriben si existen.
- La columna antigua BillingCategory ya no se usa: se puede borrar cuando se haya creado
  EquipmentType y reasignado los bancos.
- En modo local los Equipment Types se guardan en `tipos-equipo.json` junto a los datos.

## 5. Pruebas

- `HojaMensualTest` (Java) y `pruebas-facturacion.cjs` (laboratorio): **regresión con
  enero de 2026** del Excel. Con los datos de `backend/src/test/resources/facturacion/
  enero-2026.json` (solo para pruebas; la aplicación arranca sin Equipment Types), los 25
  «Coste total» de INPUTS_2026 y las 34 filas de la hoja ENERO (total 47.210,59 €) salen
  iguales. Además: horas desde las fechas, dos meses con parada y setup, piezas, STC,
  proyecto 0, incompletas fuera de la exportación y validación.
- `ApiFacturacionTest`: API de punta a punta en local.
- `pruebas-facturacion.cjs` también recorre la interfaz (47 comprobaciones en total).

## 6. API de la hoja

`GET /api/v1/facturacion/hoja?mes=aaaa-mm&niveles=orden,planta,tipo,equipo,hv,inicio,fin,tarifa`
y `GET /hoja/exportar?mes=…&formato=excel|html&niveles=…`. `niveles` es opcional (todos);
`-` = ninguno (solo el total). Los ids se aplican siempre en el orden fijo.
