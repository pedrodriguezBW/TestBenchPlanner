package com.borgwarner.tbp.dominio.planificacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bancos agrupados por tipo (categoría) y organización de cada banco (BW / CTAG).
 * Cada categoría pertenece a un área ({@link Areas}); sin área, Bancos Térmicos.
 *
 * Se construye a partir de la lista compartida de bancos igual que applyConfig
 * de la 4.9.4. Si la lista está vacía se usan los grupos de serie.
 */
public final class ConfiguracionBancos {

    /** Grupos de serie de la 4.9.4 (GROUPS_SEED). */
    public static final List<Grupo> GRUPOS_DE_SERIE = List.of(
            new Grupo("durability", "Durability Thermal Benches", "#4C8DFF", List.of("MD1", "MD2", "MD3", "MD4", "Camiones", "FT Turismos")),
            new Grupo("passenger", "Passenger Car Thermal Benches", "#23BCA3", List.of("Turismos", "Gasolina")),
            new Grupo("corrosion", "Corrosion", "#E0A458", List.of("BC1", "BC2")),
            new Grupo("fatigue", "Fatigue", "#E86A92", List.of("Fatiga", "FP Aceite 1", "FP Aceite 2")),
            new Grupo("climatic", "Climatic / Environment", "#B08BEA", List.of("1500 WC", "1500 Aire", "2000 CTAG", "KK127", "KK129", "KK118")),
            new Grupo("btm", "dP BTM", "#F2A64B", List.of("Snakes dP")));

    private final List<Grupo> grupos;
    private final Map<String, String> organizacionPorBanco;
    private final Map<String, String> areaPorGrupo;
    private final Map<String, String> nombresAreas;

    public ConfiguracionBancos(List<Grupo> grupos, Map<String, String> organizacionPorBanco) {
        this(grupos, organizacionPorBanco, Map.of(), Map.of());
    }

    /**
     * @param areaPorGrupo clave de categoría → código de área (las que falten son Bancos Térmicos)
     * @param nombresAreas código de área → nombre guardado (los que falten, el de serie)
     */
    public ConfiguracionBancos(List<Grupo> grupos, Map<String, String> organizacionPorBanco,
                               Map<String, String> areaPorGrupo, Map<String, String> nombresAreas) {
        this.grupos = List.copyOf(grupos);
        this.organizacionPorBanco = Map.copyOf(organizacionPorBanco);
        Map<String, String> areas = new LinkedHashMap<>();
        areaPorGrupo.forEach((k, v) -> areas.put(k, Areas.normalizar(v)));
        this.areaPorGrupo = Map.copyOf(areas);
        this.nombresAreas = Areas.nombres(nombresAreas);
    }

    /** La misma configuración con otros nombres de área. */
    public ConfiguracionBancos conNombresAreas(Map<String, String> nombres) {
        return new ConfiguracionBancos(grupos, organizacionPorBanco, areaPorGrupo, nombres);
    }

    /** applyConfig: ordena por Seq y agrupa por GroupKey en orden de aparición. */
    public static ConfiguracionBancos desdeLista(List<BancoConfigurado> bancos) {
        return desdeLista(bancos, Map.of());
    }

    /** applyConfig con los nombres de área guardados. */
    public static ConfiguracionBancos desdeLista(List<BancoConfigurado> bancos, Map<String, String> nombresAreas) {
        if (bancos == null || bancos.isEmpty()) {
            return new ConfiguracionBancos(GRUPOS_DE_SERIE, Map.of(), Map.of(), nombresAreas);
        }
        List<BancoConfigurado> ordenados = new ArrayList<>(bancos);
        // (a.seq||0)-(b.seq||0): ordenación estable, un Seq vacío cuenta como 0.
        ordenados.sort(Comparator.comparingDouble(b -> b.seq() == null || b.seq().isNaN() ? 0 : b.seq()));
        Map<String, String> nombres = new LinkedHashMap<>();
        Map<String, String> colores = new LinkedHashMap<>();
        Map<String, List<String>> bancosPorGrupo = new LinkedHashMap<>();
        Map<String, String> organizaciones = new LinkedHashMap<>();
        Map<String, String> areas = new LinkedHashMap<>();
        for (BancoConfigurado banco : ordenados) {
            if (banco == null || banco.name() == null || banco.name().isEmpty()) {
                continue;
            }
            String clave = (banco.groupKey() != null && !banco.groupKey().isEmpty())
                    ? banco.groupKey() : claveDeNombre(banco.groupName());
            if (!bancosPorGrupo.containsKey(clave)) {
                nombres.put(clave, (banco.groupName() != null && !banco.groupName().isEmpty()) ? banco.groupName() : clave);
                colores.put(clave, (banco.dot() != null && !banco.dot().isEmpty()) ? banco.dot() : "#23BCA3");
                bancosPorGrupo.put(clave, new ArrayList<>());
                // El área es de la categoría: manda la primera fila que la trae.
            }
            if (!areas.containsKey(clave) && banco.area() != null && !banco.area().trim().isEmpty()) {
                areas.put(clave, Areas.normalizar(banco.area()));
            }
            List<String> lista = bancosPorGrupo.get(clave);
            if (!lista.contains(banco.name())) {
                lista.add(banco.name());
            }
            if (banco.org() != null && !banco.org().isEmpty()) {
                organizaciones.put(banco.name(), banco.org());
            }
        }
        if (bancosPorGrupo.isEmpty()) {
            return new ConfiguracionBancos(GRUPOS_DE_SERIE, Map.of(), Map.of(), nombresAreas);
        }
        List<Grupo> grupos = new ArrayList<>();
        bancosPorGrupo.forEach((clave, lista) -> grupos.add(new Grupo(clave, nombres.get(clave), colores.get(clave), List.copyOf(lista))));
        // Las categorías de cada área van juntas, en el orden de las áreas (ordenación estable:
        // dentro de un área se respeta el orden guardado). Sin áreas no cambia nada.
        grupos.sort(Comparator.comparingInt(g -> Areas.CODIGOS.indexOf(areas.getOrDefault(g.key(), Areas.TERMICOS))));
        return new ConfiguracionBancos(grupos, organizaciones, areas, nombresAreas);
    }

    /**
     * Clave para un grupo sin GroupKey. La 4.9.4 añade un sufijo aleatorio; aquí
     * se usa uno estable para que la misma configuración dé siempre la misma clave.
     */
    private static String claveDeNombre(String nombre) {
        String base = (nombre == null || nombre.isEmpty() ? "General" : nombre).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return base + "_" + Integer.toString(Math.abs(base.hashCode()) % 46656, 36);
    }

    public List<Grupo> grupos() {
        return grupos;
    }

    /** Todos los bancos en el orden del menú. */
    public List<String> bancos() {
        List<String> todos = new ArrayList<>();
        grupos.forEach(g -> todos.addAll(g.benches()));
        return todos;
    }

    public boolean existe(String banco) {
        return grupos.stream().anyMatch(g -> g.benches().contains(banco));
    }

    /** Organización del banco (benchOrg): la configurada o BW. */
    public String organizacion(String banco) {
        String org = organizacionPorBanco.get(banco);
        return (org == null || org.isEmpty()) ? "BW" : org;
    }

    /** Área (código) de una categoría: la guardada o Bancos Térmicos. */
    public String areaDeGrupo(String claveGrupo) {
        String area = areaPorGrupo.get(claveGrupo);
        return area == null ? Areas.TERMICOS : area;
    }

    /** Área (código) del banco: la de su categoría. */
    public String area(String banco) {
        for (Grupo g : grupos) {
            if (g.benches().contains(banco)) {
                return areaDeGrupo(g.key());
            }
        }
        return Areas.TERMICOS;
    }

    /** Código de área → nombre, en el orden fijo. */
    public Map<String, String> nombresAreas() {
        return nombresAreas;
    }

    /** Nombre visible de un área. */
    public String nombreArea(String area) {
        return nombresAreas.getOrDefault(Areas.normalizar(area), Areas.NOMBRES_DE_SERIE.get(Areas.TERMICOS));
    }
}
