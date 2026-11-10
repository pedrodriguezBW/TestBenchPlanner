"""Genera los maestros iniciales de facturación a partir de «2026 Ensayos interno.xlsx».

Solo se trasladan maestros (categorías, grupos, reglas, tarifas por año, órdenes de
«Cuentas 2026», parámetros de Controlling). No se importan líneas históricas: de
INPUTS_2026 solo se toma, por proyecto, el requester y la línea de producto más
frecuentes para precargar la ficha del proyecto.
"""
import json, sys, re, collections, warnings
import openpyxl
warnings.filterwarnings('ignore')
origen = sys.argv[1]; destino = sys.argv[2]
wb = openpyxl.load_workbook(origen, data_only=True)
eq = wb['Equipos y costes']

def canon(n):
    s = re.sub(r'\D', '', str(n if n is not None else ''))
    s = s.lstrip('0') or ('0' if s else '')
    if len(s) == 8 and s.startswith('8000'):
        s = '8' + s[4:]
    return s

grupos = {}
for r in range(23, 40):
    b, c = eq.cell(r, 2).value, eq.cell(r, 3).value
    if b: grupos[b] = c or ''
REGLA = {'R&D': 'ASINH_100', 'Heating Plates': 'ASINH_200', 'HVCH Lubu': 'ASINH_200', 'RACS': 'PER_PIECE'}
ORDEN_CONTROLLING = ['Shaker 40kN', 'Shaker 74kN', 'R&D', 'HVCH Lubu', 'Heating Plates', 'HVCH L02', 'HVCH L03',
                     'HVCH Lubu CTAG', 'Cámara climática', 'RACS', 'Cámara choques térmicos', 'RACS_porhora']
categorias, tarifas = [], []
for r in range(3, 18):
    nombre = eq.cell(r, 2).value
    if not nombre: continue
    unidad = 'PIEZAS' if (eq.cell(r, 5).value or '') == 'Piezas' else 'H'
    nota = ' '.join(x for x in [eq.cell(r, 1).value and 'Marcado «Depr» en el Excel.', eq.cell(r, 6).value] if x)
    orden = ORDEN_CONTROLLING.index(nombre) + 1 if nombre in ORDEN_CONTROLLING else 50 + r
    categorias.append({'codigo': nombre, 'grupo': grupos.get(nombre, '') or ('RACS' if nombre.startswith('RACS') else ''),
                       'unidad': unidad, 'regla': REGLA.get(nombre, 'LINEAR'), 'ordenControlling': orden,
                       'activa': True, 'nota': nota})
    for anio, cols in ((2025, (15, 16)), (2026, (3, 4))):
        for hv, col in (('no', cols[0]), ('yes', cols[1])):
            v = eq.cell(r, col).value
            if isinstance(v, (int, float)):
                tarifas.append({'id': f'T-{anio}-{len(tarifas)+1:03d}', 'categoria': nombre, 'proyecto': '', 'planta': '',
                                'lineaProducto': '', 'hv': hv, 'importe': round(float(v), 4), 'unidad': unidad,
                                'desde': f'{anio}-01-01', 'hasta': '' if anio == 2026 else f'{anio}-12-31',
                                'nota': f'Excel «Equipos y costes» {anio}'})
tarifas.append({'id': 'T-STC-000', 'categoria': '*', 'proyecto': '', 'planta': '', 'lineaProducto': 'STC', 'hv': '*',
                'importe': 0, 'unidad': 'H', 'desde': '2025-01-01', 'hasta': '',
                'nota': 'Excel: Coste/hora = 0 cuando la planta (línea de producto) es STC'})

# Proyectos y órdenes: hoja «Cuentas 2026».
cu = wb['Cuentas 2026']
proyectos = collections.OrderedDict()
for r in range(2, cu.max_row + 1):
    nombre, num, orden = cu.cell(r, 1).value, cu.cell(r, 4).value, cu.cell(r, 5).value
    if num is None or not orden: continue
    c = canon(num)
    p = proyectos.setdefault(c, {'numero': c, 'nombre': str(nombre or '').strip(), 'requester': '', 'lineaProducto': '',
                                 'activo': True, 'desde': '', 'hasta': '', 'ordenes': []})
    o = str(orden).strip()
    if o not in [x['codigo'] for x in p['ordenes']]:
        p['ordenes'].append({'codigo': o, 'descripcion': '', 'activa': True})

# Requester y línea de producto más frecuentes en INPUTS_2026 (solo para precargar la ficha).
ws = wb['INPUTS_2026']
hdr = [c.value for c in ws[2]]
idx = {h: i for i, h in enumerate(hdr) if h}
req, lin = collections.defaultdict(collections.Counter), collections.defaultdict(collections.Counter)
for row in ws.iter_rows(min_row=3, values_only=True):
    num = row[idx['Número proyecto']]
    if num in (None, ''): continue
    c = canon(num)
    if row[idx['Requester']]: req[c][str(row[idx['Requester']]).strip()] += 1
    if row[idx['Planta']]: lin[c][str(row[idx['Planta']]).strip().capitalize() if str(row[idx['Planta']]).strip().lower() in ('heaters', 'viana') else str(row[idx['Planta']]).strip()] += 1
    orden = row[idx['Orden']]
    if orden and c not in proyectos:
        proyectos[c] = {'numero': c, 'nombre': str(row[idx['Proyecto']] or '').strip(), 'requester': '', 'lineaProducto': '',
                        'activo': True, 'desde': '', 'hasta': '', 'ordenes': [{'codigo': str(orden).strip(), 'descripcion': '', 'activa': True}]}
for c, p in proyectos.items():
    if req[c]: p['requester'] = req[c].most_common(1)[0][0]
    if lin[c]: p['lineaProducto'] = lin[c].most_common(1)[0][0]

maestros = {
    'revision': 0,
    'origen': 'Valores iniciales tomados de «2026 Ensayos interno.xlsx» (Equipos y costes, Cuentas 2026, Controlling).',
    'proyectos': list(proyectos.values()),
    'categorias': categorias,
    'mapeos': [{'id': f'M-{i+1:03d}', 'banco': b, 'tipoEnsayo': '', 'categoria': 'Cámara climática', 'regla': '', 'activo': True,
                'nota': 'Propuesta inicial: cámara climática de BW. Revisar.'}
               for i, b in enumerate(['1500 WC', '1500 Aire', 'KK127', 'KK129', 'KK118'])],
    'tarifas': tarifas,
    'setupRecomendado': [],
    'controlling': {'cctrOld': '6463-41', 'costElement': '420000', 'moneda': 'EUR', 'unidadHoras': 'H', 'unidadPiezas': 'PC',
                    'texto': 'CATEGORIA'},
    'parametros': {'umbralHorasAltas': 720},
}
json.dump(maestros, open(destino, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(maestros['proyectos']), 'proyectos', sum(len(p['ordenes']) for p in maestros['proyectos']), 'ordenes',
      len(categorias), 'categorias', len(tarifas), 'tarifas')
