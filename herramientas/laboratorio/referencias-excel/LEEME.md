# Referencias del Gantt a Excel (bloque F)

- `<vista>.json`: la matriz que construye la 4.9.4 (`buildGanttMatrix`) en el
  escenario anonimizado el 5-nov-2026: banco MD2, Dashboard (plan anual),
  By Responsible, List of Equipments y banco MD2 con foco de proyecto.
- `<vista>.sheet1.xml` y `<vista>.styles.xml`: la hoja y los estilos que debe
  escribir `ExcelGantt.java` para esa matriz. `ExcelGanttTest` los compara
  carácter a carácter.

Cómo se han obtenido: `excel_espejo.py` es una copia en Python de
`ExcelGantt.java`; `comparar_excel.py` abre con openpyxl el Excel de la 4.9.4
(`do_export_gantt` del backend Python original) y el del espejo y comprueba
que son iguales celda a celda (valor, relleno, fuente, alineación, bordes,
combinaciones, anchos, inmovilización). Resultado: iguales en las 5 vistas.
