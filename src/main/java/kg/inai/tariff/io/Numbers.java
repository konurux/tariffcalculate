package kg.inai.tariff.io;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Разбор чисел из «грязных» строк: запятая вместо точки, пробелы, nbsp. */
public final class Numbers {

    private static final Set<String> TRUE = Set.of("1", "true", "yes", "y", "да");

    private Numbers() {
    }

    public static BigDecimal parse(String s) {
        if (s == null) {
            throw new NumberFormatException("null");
        }
        String t = s.trim().replace(" ", "").replace(" ", "").replace(',', '.');
        if (t.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        return new BigDecimal(t);
    }

    public static boolean isTruthy(String s) {
        return s != null && TRUE.contains(s.trim().toLowerCase(Locale.ROOT));
    }
}
