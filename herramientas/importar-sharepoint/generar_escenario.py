"""
Genera un escenario de datos ANONIMIZADO a partir de una lectura (solo lectura)
de las listas de SharePoint.

Entrada : carpeta con un .jsonl por lista (cabecera + filas), tal y como sale de
          la exportación de solo lectura. Esa carpeta es PRIVADA y no se versiona.
Salida  : carpeta de escenario con las respuestas que daría el backend 4.9.4
          (/api/load, /api/load_config, /api/load_kpis, /api/whoami,
          /api/load_resources, /api/approvals_all, /api/app_version).

Así el mismo escenario sirve para tres cosas:
  1. alimentar a la 4.9.4 original en el laboratorio (oráculo),
  2. arrancar el adaptador local del backend Java,
  3. comparar ambas implementaciones sobre datos representativos.

Anonimización: se sustituyen nombres de personas y correos por alias estables
(«Responsable 01», usuario01@ejemplo.local). Los proyectos, bancos y textos
técnicos se conservan porque las pruebas manuales los usan para localizar filas.
"""
import json
import re
import sys
from pathlib import Path

# Columnas que el backend 4.9.4 pide al leer el histórico de KPI (KPI_COLUMNS).
COLUMNAS_KPI = ["Year", "CW", "SnapDate", "Org", "Benches", "TotalBenches", "Launches",
                "Finishing", "AccruingHours", "NoApprovedID", "Behind", "NotStarted",
                "RiskBehind", "Unplanned", "ReasonResources", "ReasonParts",
                "ReasonBreakdown", "ReasonTestDrop", "DelayedDeclared", "OccHoursBusy",
                "OccHoursCap", "OccPct", "ReasonPriority", "ReasonOther", "KPIJson"]

# Campos del ensayo que contienen nombres de personas.
CAMPOS_PERSONA = ("resp", "finalBy", "finalConfirmedBy", "kpiCallBy")


def leer_lista(carpeta, nombre):
    ruta = Path(carpeta) / f"{nombre}.jsonl"
    if not ruta.exists():
        return []
    lineas = ruta.read_text(encoding="utf-8").splitlines()
    cabecera = json.loads(lineas[0])
    return [dict(zip(cabecera, json.loads(linea))) for linea in lineas[1:] if linea.strip()]


def numero(valor):
    """SharePoint devuelve los números como 16.0; el JSON del backend daría 16."""
    if valor is None or valor == "":
        return None
    try:
        f = float(valor)
    except (TypeError, ValueError):
        return valor
    return int(f) if f.is_integer() else f


def orden_seq(fila):
    seq = numero(fila.get("Seq"))
    return (seq if seq is not None else 1e9, int(fila.get("id") or 0))


class Anonimizador:
    """Asigna alias estables a personas y correos en orden de aparición."""

    def __init__(self):
        self.personas = {}
        self.correos = {}

    def persona(self, nombre):
        if not nombre:
            return nombre
        if nombre not in self.personas:
            self.personas[nombre] = f"Responsable {len(self.personas) + 1:02d}"
        return self.personas[nombre]

    def correo(self, correo):
        if not correo:
            return correo
        clave = correo.strip().lower()
        if clave not in self.correos:
            self.correos[clave] = f"usuario{len(self.correos) + 1:02d}@ejemplo.local"
        return self.correos[clave]

    def texto(self, texto):
        """Sustituye en textos libres los nombres que ya conocemos."""
        if not isinstance(texto, str) or not texto:
            return texto
        for real, alias in sorted(self.personas.items(), key=lambda x: -len(x[0])):
            texto = texto.replace(real, alias)
        return re.sub(r"[\w.+-]+@[\w-]+\.[\w.]+", lambda m: self.correo(m.group(0)), texto)


def generar(origen, destino):
    anon = Anonimizador()
    destino = Path(destino)
    destino.mkdir(parents=True, exist_ok=True)

    # 1) Usuarios primero: fijan los alias de las personas conocidas.
    usuarios = []
    for fila in sorted(leer_lista(origen, "TestBenchUsers"), key=lambda f: int(f["id"])):
        alias = anon.persona(fila.get("ResponsibleName") or fila.get("title"))
        anon.personas.setdefault(fila.get("title"), alias.replace("Responsable", "Persona"))
        usuarios.append({
            "title": anon.personas[fila.get("title")],
            "email": anon.correo(fila.get("Email")),
            "role": (fila.get("Role") or "").strip(),
            "responsibleName": alias,
        })

    # 2) Ensayos: misma forma que /api/load -> [{uid, data(texto JSON)}]
    ensayos = []
    for fila in sorted(leer_lista(origen, "TestBenchPlanner"), key=lambda f: int(f["id"])):
        datos = json.loads(fila["DataJson"])
        for campo in CAMPOS_PERSONA:
            if datos.get(campo):
                datos[campo] = anon.persona(datos[campo])
        for campo in ("comments", "closeComment", "finalComment", "downtimeNote", "finalDowntimeNote"):
            if campo in datos:
                datos[campo] = anon.texto(datos[campo])
        for edicion in datos.get("finalEdits") or []:
            if edicion.get("by"):
                edicion["by"] = anon.persona(edicion["by"])
        # El texto se vuelve a serializar compacto, igual que JSON.stringify.
        ensayos.append({"uid": fila["title"], "data": json.dumps(datos, ensure_ascii=False, separators=(",", ":"))})

    # 3) Catálogos: misma forma que /api/load_config
    bancos = [{"name": f["title"], "groupKey": f.get("GroupKey"), "groupName": f.get("GroupName"),
               "dot": f.get("Dot"), "org": f.get("Org"), "seq": numero(f.get("Seq"))}
              for f in sorted(leer_lista(origen, "TestBenchBenches"), key=orden_seq) if f.get("title")]
    proyectos = [f["title"] for f in sorted(leer_lista(origen, "TestBenchProjects"), key=orden_seq) if f.get("title")]
    responsables = [anon.persona(f["title"]) for f in sorted(leer_lista(origen, "TestBenchResponsibles"), key=orden_seq) if f.get("title")]
    configuracion = {"responsibles": responsables, "projects": proyectos, "benches": bancos}

    # 4) Histórico de KPI: misma forma que /api/load_kpis (orden Year, CW)
    kpis = []
    for fila in leer_lista(origen, "TestBenchKPIHistory"):
        registro = {c: (fila.get(c) if c in ("SnapDate", "Org", "KPIJson") else numero(fila.get(c)))
                    for c in COLUMNAS_KPI}
        kpis.append(registro)
    kpis.sort(key=lambda r: (r["Year"] if r["Year"] is not None else -1, r["CW"] if r["CW"] is not None else -1))

    # 5) Recursos auxiliares: misma forma que /api/load_resources
    recursos = {"resources": [{"name": f["title"], "groupKey": f.get("GroupKey"), "groupName": f.get("GroupName"),
                               "dot": f.get("Dot"), "code": f.get("EquipmentCode"), "org": f.get("Org"),
                               "seq": numero(f.get("Seq"))}
                              for f in sorted(leer_lista(origen, "TestBenchResources"), key=orden_seq) if f.get("title")],
                "blocks": [{"id": int(f["id"]), "title": f.get("title"), "resource": f.get("Resource"),
                            "start": (f.get("StartDate") or "")[:10], "end": (f.get("EndDate") or "")[:10],
                            "reason": f.get("Reason"), "note": anon.texto(f.get("Note")),
                            "author": anon.persona(f.get("Author"))}
                           for f in leer_lista(origen, "TestBenchResourceBlocks") if f.get("id")]}

    # 6) Aprobaciones (solo lectura): misma forma que /api/approvals_all
    estados = {"approved": ("approved", "green"), "pending": ("pending", "amber"),
               "rejected": ("rejected", "red"), "na": ("na", "red"), "n/a": ("na", "red"), "": ("not_created", "red")}
    aprobaciones = []
    for fila in sorted(leer_lista(origen, "Approvals"), key=lambda f: -int(f["id"])):
        bruto = fila.get("Status")
        clave = (bruto or "").strip().lower()
        estado, color = estados.get(clave, ("other:" + clave, "red"))
        aprobaciones.append({"id": int(fila["id"]), "status": estado, "color": color, "raw": bruto,
                             "requester": anon.persona(fila.get("Requester")) if fila.get("Requester") else None,
                             "service": anon.texto(fila.get("Servicedetails"))})

    version = next((f.get("Value") for f in leer_lista(origen, "TestBenchAppConfig") if f.get("title") == "CurrentVersion"), None)

    ficheros = {
        "planner.json": ensayos,
        "configuracion.json": configuracion,
        "kpis.json": kpis,
        "usuarios.json": usuarios,
        "recursos.json": recursos,
        "aprobaciones.json": aprobaciones,
        "version.json": {"published": version},
    }
    for nombre, contenido in ficheros.items():
        (destino / nombre).write_text(json.dumps(contenido, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Escenario generado en {destino}: {len(ensayos)} ensayos, {len(bancos)} bancos, "
          f"{len(kpis)} semanas KPI, {len(aprobaciones)} aprobaciones, {len(anon.personas)} alias de persona")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Uso: python generar_escenario.py <carpeta_exportada> <carpeta_escenario>")
    generar(sys.argv[1], sys.argv[2])
