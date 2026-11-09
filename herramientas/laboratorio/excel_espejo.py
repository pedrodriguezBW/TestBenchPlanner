# Copia en Python de ExcelGantt.java. Sirve para comprobar, sin JDK, que el Excel
# que escribe la versión nueva es igual al de la 4.9.4 (ver comparar_excel.py).
# Si cambia ExcelGantt.java hay que cambiar esto igual y regenerar referencias-excel/.
import json, re, zipfile, io, sys, datetime
BORDE='D9D9D9'; CAB='0C1D2C'
AL={'N':'','C':'<alignment horizontal="center" vertical="center" wrapText="1"/>','I':'<alignment horizontal="left" vertical="center"/>','IS':'<alignment horizontal="left" vertical="center" wrapText="0"/>'}
class Est:
    def __init__(s):
        s.f=['<font><sz val="11"/><color rgb="FF000000"/><name val="Calibri"/><family val="2"/></font>']
        s.r=['<fill><patternFill patternType="none"/></fill>','<fill><patternFill patternType="gray125"/></fill>']
        s.x=['<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>']
    def idx(s,l,v):
        if v in l: return l.index(v)
        l.append(v); return len(l)-1
    def hex(s,c):
        h=(c or '').strip().lstrip('#')
        if len(h)==3: h=''.join(ch*2 for ch in h)
        return h.upper() if re.fullmatch('[0-9A-Fa-f]{6}',h) else '8CA0AD'
    def fuente(s,b,c,t): return s.idx(s.f,'<font>'+('<b/>' if b else '')+'<sz val="%d"/><color rgb="FF%s"/><name val="Calibri"/><family val="2"/></font>'%(t,s.hex(c)))
    def rel(s,c): return s.idx(s.r,'<fill><patternFill patternType="solid"><fgColor rgb="FF%s"/><bgColor indexed="64"/></patternFill></fill>'%s.hex(c))
    def xf(s,f,r,b,a):
        x='<xf numFmtId="0" fontId="%d" fillId="%d" borderId="%d" xfId="0"'%(f,r,b)
        if f: x+=' applyFont="1"'
        if r: x+=' applyFill="1"'
        if b: x+=' applyBorder="1"'
        x+= (' applyAlignment="1">'+AL[a]+'</xf>') if a!='N' else '/>'
        return s.idx(s.x,x)
    def xml(s):
        bd=''.join('<%s style="thin"><color rgb="FF%s"/></%s>'%(k,BORDE,k) for k in ['left','right','top','bottom'])+'<diagonal/>'
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
          +'<fonts count="%d">%s</fonts>'%(len(s.f),''.join(s.f))+'<fills count="%d">%s</fills>'%(len(s.r),''.join(s.r))
          +'<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border>'+bd+'</border></borders>'
          +'<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
          +'<cellXfs count="%d">%s</cellXfs>'%(len(s.x),''.join(s.x))
          +'<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
def col(c):
    s=''
    while c>0: s=chr(65+(c-1)%26)+s; c=(c-1)//26
    return s
def esc(t): return t.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;')
def generar(p, hoy):
    e=Est(); celdas={}; comb=[]; anchos={}
    def cel(f,c): return celdas.setdefault(f,{}).setdefault(c,[None,None,0])
    def poner(f,c,t,s):
        x=cel(f,c); x[2]=s
        if t!='': x[0]=t; x[1]='s'
    def val(f,c,v,s):
        if v is None: cel(f,c)[2]=s
        elif isinstance(v,(int,float)) and not isinstance(v,bool): x=cel(f,c); x[0]=str(v); x[1]='n'; x[2]=s
        else: poner(f,c,str(v),s)
    def est(f,c,s): cel(f,c)[2]=s
    def ref(f,c): return col(c)+str(f)
    def merge(f0,c0,f1,c1): comb.append(ref(f0,c0)+':'+ref(f1,c1))
    tit=p.get('title') or 'Gantt'; dias=p.get('days') or []; diario=bool(dias); cols=dias if diario else (p.get('weeks') or [])
    filas=p.get('rows') or []; ley=p.get('legend') or []; n=len(cols)
    merge(1,1,1,max(2,1+n)); poner(1,1,tit,e.xf(e.fuente(True,CAB,13),0,0,'I'))
    poner(2,1,'',e.xf(e.fuente(False,'000000',11),e.rel(CAB),0,'N')); em=e.xf(e.fuente(True,CAB,10),e.rel('E9EEF2'),0,'C')
    i=0
    while i<n:
        m=str(cols[i].get('month','')); t=0
        while i+t<n and str(cols[i+t].get('month',''))==m: t+=1
        if t>1: merge(2,2+i,2,2+i+t-1)
        poner(2,2+i,m,em); i+=t
    ec=e.xf(e.fuente(True,'FFFFFF',10),e.rel(CAB),1,'C'); poner(3,1,'Bench / Test',ec); i=0
    while i<n:
        w=str(cols[i].get('cw','')); t=0
        while i+t<n and str(cols[i+t].get('cw',''))==w: t+=1
        if t>1: merge(3,2+i,3,2+i+t-1)
        poner(3,2+i,'CW'+w,ec); i+=t
    hr=3
    if diario:
        hr=4; poner(4,1,'',e.xf(e.fuente(False,'000000',11),e.rel(CAB),0,'N')); fd=e.fuente(False,'FFFFFF',8)
        for d,dd in enumerate(cols):
            if dd.get('today'): s=e.xf(e.fuente(True,'06231F',8),e.rel('23BCA3'),1,'C')
            elif dd.get('hol'): s=e.xf(fd,e.rel('5A2530'),1,'C')
            elif dd.get('we'): s=e.xf(fd,e.rel('24384A'),1,'C')
            else: s=e.xf(fd,e.rel(CAB),1,'C')
            val(4,2+d,dd.get('day'),s)
    r0=hr+1; el=e.xf(e.fuente(True,'1D2D3B',10),e.rel('F4F7F9'),1,'I'); eg=e.xf(0,0,1,'N'); ew=e.xf(0,e.rel('EFF3F6'),1,'N')
    for ri,f in enumerate(filas):
        rr=r0+ri; val(rr,1,f.get('label'),el)
        for d in range(n): est(rr,2+d, ew if diario and (cols[d].get('we') or cols[d].get('hol')) else eg)
        occ=set()
        for sg in f.get('cells') or []:
            c0=max(0,int(sg.get('c0',0))); c1=min(n-1,max(c0,int(sg.get('c1',c0))))
            while c0<=c1 and c0 in occ: c0+=1
            if c0>c1: continue
            end=c0
            while end+1<=c1 and end+1 not in occ: end+=1
            if end>c0: merge(rr,2+c0,rr,2+end)
            dim=bool(sg.get('dim')); s=e.xf(e.fuente(not dim,'111111' if dim else '0B1620',9),e.rel(sg.get('color') or '#8CA0AD'),1,'IS')
            poner(rr,2+c0,sg.get('text') or '',s)
            for c in range(c0,end+1): occ.add(c)
    if ley:
        lr=r0+len(filas)+1; poner(lr,1,'Leyenda de proyectos',e.xf(e.fuente(True,CAB,11),0,0,'N')); en=e.xf(e.fuente(False,'1D2D3B',10),0,0,'I')
        for i,l in enumerate(ley): est(lr+1+i,1,e.xf(0,e.rel(l.get('color') or '#8CA0AD'),1,'N')); val(lr+1+i,2,l.get('name'),en)
    anchos[1]=34.0
    for d in range(n): anchos[2+d]=2.9 if diario else 5.4
    x=['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">']
    x.append('<sheetViews><sheetView workbookViewId="0"><pane xSplit="1" ySplit="%d" topLeftCell="%s" activePane="bottomRight" state="frozen"/></sheetView></sheetViews>'%(hr,ref(hr+1,2)))
    x.append('<sheetFormatPr defaultRowHeight="15"/><cols>'+''.join('<col min="%d" max="%d" width="%s" customWidth="1"/>'%(c,c,w) for c,w in anchos.items())+'</cols><sheetData>')
    for f in sorted(celdas):
        x.append('<row r="%d"%s>'%(f,' ht="22" customHeight="1"' if f==1 else ''))
        for c in sorted(celdas[f]):
            v,t,s=celdas[f][c]
            if v is None: x.append('<c r="%s" s="%d"/>'%(ref(f,c),s))
            elif t=='n': x.append('<c r="%s" s="%d"><v>%s</v></c>'%(ref(f,c),s,v))
            else: x.append('<c r="%s" s="%d" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'%(ref(f,c),s,esc(v)))
        x.append('</row>')
    x.append('</sheetData>')
    if comb: x.append('<mergeCells count="%d">'%len(comb)+''.join('<mergeCell ref="%s"/>'%m for m in comb)+'</mergeCells>')
    x.append('<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>')
    hoja=''.join(x)
    bio=io.BytesIO(); z=zipfile.ZipFile(bio,'w')
    z.writestr('[Content_Types].xml','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>')
    z.writestr('_rels/.rels','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
    z.writestr('xl/workbook.xml','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Gantt" sheetId="1" r:id="rId1"/></sheets></workbook>')
    z.writestr('xl/_rels/workbook.xml.rels','<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
    z.writestr('xl/worksheets/sheet1.xml',hoja); z.writestr('xl/styles.xml',e.xml()); z.close()
    return bio.getvalue()
if __name__=='__main__':
    p=json.load(open(sys.argv[1])); open(sys.argv[2],'wb').write(generar(p,None))
