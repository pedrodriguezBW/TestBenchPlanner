"""Referencia de paridad con «2026 Ensayos interno.xlsx»: filas de INPUTS_2026 (solo
los campos que intervienen en tarifa y coste) y la hoja Controlling de octubre."""
import json, sys, datetime, warnings, openpyxl
warnings.filterwarnings('ignore')
wb = openpyxl.load_workbook(sys.argv[1], data_only=True)
ws = wb['INPUTS_2026']
hdr = [c.value for c in ws[2]]; I = {h: i for i, h in enumerate(hdr) if h}
filas = []
for r, row in enumerate(ws.iter_rows(min_row=3, values_only=True), start=3):
    eq, ct, mes = row[I['Equipo']], row[I['Coste total']], row[I['Mes facturación']]
    if not eq or not isinstance(ct, (int, float)) or not isinstance(mes, datetime.datetime): continue
    filas.append({'fila': r, 'mes': mes.strftime('%Y-%m'), 'equipo': eq, 'orden': row[I['Orden']], 'planta': row[I['Planta']],
                  'hv': 'yes' if row[I['HV']] == 'Sí' else 'no', 'numeroProyecto': str(row[I['Número proyecto']]),
                  'horasTotales': row[I['Horas totales']], 'costeHora': row[I['Coste/hora']], 'costeTotal': ct})
c = wb['Controlling - Internal Testing ']
ctrl = []
for r in range(4, c.max_row + 1):
    if not c.cell(r, 1).value: continue
    ctrl.append({'equipo': c.cell(r, 1).value, 'orden': c.cell(r, 2).value, 'sumaCoste': c.cell(r, 3).value, 'sumaHoras': c.cell(r, 4).value,
                 'cctrOld': c.cell(r, 7).value, 'costElement': c.cell(r, 10).value})
json.dump({'origen': '2026 Ensayos interno.xlsx', 'mesControlling': c.cell(1, 2).value.strftime('%Y-%m'), 'filas': filas, 'controlling': ctrl},
          open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(filas), 'filas', len(ctrl), 'filas de Controlling')
