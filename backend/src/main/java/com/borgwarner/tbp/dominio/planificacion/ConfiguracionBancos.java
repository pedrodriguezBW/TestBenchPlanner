package com.borgwarner.tbp.dominio.planificacion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bancos agrupados por tipo y organización de cada banco (BW / CTAG).
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

    public ConfiguracionBancos(List<Grupo> grupos, Map<String, String> organizacionPorBanco) {
        this.grupos = List.copyOf(grupos);
        this.organizacionPorBanco = Map.copyOf(organizacionPorBanco);
    }

    /** applyConfig: ordena por Seq y agrupa por GroupKey en orden de aparición. */
    public static ConfiguracionBancos desdeLista(List<BancoConfigurado> bancos) {
        if (bancos == null || bancos.isEmpty()) {
            return new ConfiguracionBancos(GRUPOS_DE_SERIE, Map.of());
        }
        List<BancoConfigurado> ordenados = new ArrayList<>(bancos);
        // (a.seq||0)-(b.seq||0): ordenación estable, un Seq vacío cuenta como 0.
        ordenados.sort(Comparator.comparingDouble(b -> b.seq() == null || b.seq().isNaN() ? 0 : b.seq()));
        Map<String, String> nombres = new LinkedHashMap<>();
        Map<String, String> colores = new LinkedHashMap<>();
        Map<String, List<String>> bancosPorGrupo = new LinkedHashMap<>();
        Map<String, String> organizaciones = new LinkedHashMap<>();
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
            return new ConfiguracionBancos(GRUPOS_DE_SERIE, Map.of());
        }
        List<Grupo> grupos = new ArrayList<>();
        bancosPorGrupo.forEach((clave, lista) -> grupos.add(new Grupo(clave, nombres.get(clave), colores.get(clave), List.copyOf(lista))));
        return new ConfiguracionBancos(grupos, organizaciones);
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
}
