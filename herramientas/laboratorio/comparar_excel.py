# Compara el Gantt a Excel de la 4.9.4 (do_export_gantt, openpyxl) con el que
# escribe la versión nueva (excel_espejo.py, copia en Python de ExcelGantt.java)
# celda a celda. Uso: python3 comparar_excel.py  (necesita openpyxl)
import json, re, sys, base64, io, time, os
AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import excel_espejo as espejo, openpyxl
src=open(os.path.join(AQUI,'../../legacy/backend_testbench_v4_9_4.py'),encoding='utf-8').read()
i=src.index('def do_export_gantt('); j=src.index('\n# ---- v4.5', i)
ns={'re':re,'time':time}; exec(src[i:j], ns)
def rgb(x):
    v=getattr(x,'rgb',None)
    return v[-6:].upper() if isinstance(v,str) else None
def foto(b):
    wb=openpyxl.load_workbook(io.BytesIO(b)); ws=wb.active
    celdas={}
    for row in ws.iter_rows():
        for c in row:
            if c.value is None and (c.fill is None or c.fill.fgColor.rgb in (None,'00000000')) and not c.border.left.style: continue
            celdas[c.coordinate]=(c.value, c.fill.fill_type, rgb(c.fill.fgColor) if c.fill.fill_type else None,
               bool(c.font.b), rgb(c.font.color) or '000000', c.font.sz or 11,
               c.alignment.horizontal, bool(c.alignment.wrap_text), c.border.left.style)
    return dict(celdas=celdas, merges=sorted(str(m) for m in ws.merged_cells.ranges), freeze=ws.freeze_panes,
                anchos={k:round(v.width,2) for k,v in ws.column_dimensions.items() if v.width}, alto1=ws.row_dimensions[1].height, titulo=ws.title)
ok=True
for k in ['banco','dash','resp','res','foco']:
    m=json.load(open(os.path.join(AQUI,'referencias-excel',f'{k}.json')))
    ref=ns['do_export_gantt'](m); a=foto(base64.b64decode(ref['b64'])); b=foto(espejo.generar(m,None))
    difs=[]
    for key in ['merges','freeze','anchos','alto1','titulo']:
        if a[key]!=b[key]: difs.append((key, str(a[key])[:200], str(b[key])[:200]))
    for c in sorted(set(a['celdas'])|set(b['celdas'])):
        if a['celdas'].get(c)!=b['celdas'].get(c): difs.append((c,a['celdas'].get(c),b['celdas'].get(c)))
    print(k, 'iguales' if not difs else f'{len(difs)} diferencias', ref['filename'])
    for d in difs[:6]: print('   ',d)
    ok &= not difs
print('TODO IGUAL' if ok else 'HAY DIFERENCIAS')
