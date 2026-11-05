package com.borgwarner.tbp.infraestructura.comun;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilidades compartidas: comparación de versiones «4.10 > 4.9» y marcas de tiempo. */
public final class Versionado {

    private static final DateTimeFormatter SIN_MILISEGUNDOS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Versionado() {
    }

    /** Compara por tramos numéricos: -1, 0 o 1 (_ver_cmp del backend 4.9.4). */
    public static int comparar(String a, String b) {
        List<Integer> pa = tramos(a);
        List<Integer> pb = tramos(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            int x = i < pa.size() ? pa.get(i) : 0;
            int y = i < pb.size() ? pb.get(i) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static List<Integer> tramos(String version) {
        List<Integer> partes = new ArrayList<>();
        Matcher m = Pattern.compile("\\d+").matcher(version == null ? "" : version);
        while (m.find()) {
            partes.add(Integer.parseInt(m.group()));
        }
        return partes;
    }

    /** Instante en UTC sin milisegundos, como time.strftime("%Y-%m-%dT%H:%M:%SZ"). */
    public static String instanteSinMilisegundos(Instant instante) {
        return SIN_MILISEGUNDOS.format(instante);
    }
}
