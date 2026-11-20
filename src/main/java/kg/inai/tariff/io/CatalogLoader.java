package kg.inai.tariff.io;

import com.fasterxml.jackson.databind.JsonNode;
import kg.inai.tariff.model.Addon;
import kg.inai.tariff.model.Tariff;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static kg.inai.tariff.io.Json.bool;
import static kg.inai.tariff.io.Json.decimal;
import static kg.inai.tariff.io.Json.pick;
import static kg.inai.tariff.io.Json.text;

/**
 * Чтение tariffs.json и addons.json. Формат организаторов заранее неизвестен,
 * поэтому поддерживаются синонимы полей и вложенные блоки overage/roaming.
 */
public final class CatalogLoader {

    private CatalogLoader() {
    }

    public static List<Tariff> loadTariffs(Path file) throws IOException {
        JsonNode tree = Json.MAPPER.readTree(Files.readString(file));
        JsonNode sharedRoaming = tree.isObject() ? pick(tree, "roaming_rates", "roaming") : null;
        JsonNode root = unwrap(tree, "tariffs");
        List<Tariff> out = new ArrayList<>();
        for (JsonNode t : root) {
            out.add(parseTariff(t, sharedRoaming));
        }
        if (out.isEmpty()) {
            throw new IOException("В " + file + " нет тарифов");
        }
        return out;
    }

    public static List<Addon> loadAddons(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        JsonNode root = unwrap(Json.MAPPER.readTree(Files.readString(file)), "addons");
        List<Addon> out = new ArrayList<>();
        for (JsonNode a : root) {
            String id = text(a, null, "id", "code", "name");
            out.add(new Addon(
                    id,
                    text(a, id, "name", "title"),
                    decimal(a, BigDecimal.ZERO, "price", "fee", "cost"),
                    decimal(a, BigDecimal.ZERO, "minutes", "min").intValue(),
                    decimal(a, BigDecimal.ZERO, "gb", "data_gb"),
                    decimal(a, BigDecimal.ZERO, "sms").intValue()));
        }
        return out;
    }

    static Tariff parseTariff(JsonNode t, JsonNode sharedRoaming) {
        String id = text(t, null, "id", "code", "name");
        JsonNode over = pick(t, "overage", "over", "extra");
        JsonNode roam = pick(t, "roaming");
        if ((roam == null || !roam.isObject()) && sharedRoaming != null && sharedRoaming.isObject()) {
            roam = sharedRoaming;
        }
        JsonNode pkg = pick(t, "package", "included");
        JsonNode src = pkg != null ? pkg : t;
        return new Tariff(
                id,
                text(t, id, "name", "title"),
                decimal(t, BigDecimal.ZERO, "fee", "monthly_fee", "subscription_fee", "price"),
                limitInt(pick(src, "minutes", "min")),
                limitDecimal(pick(src, "gb", "data_gb")),
                limitInt(pick(src, "sms")),
                decimal(t, decimal(over, BigDecimal.ZERO, "call", "min", "minute", "minutes"), "price_min", "over_min", "extra_min"),
                decimal(t, decimal(over, BigDecimal.ZERO, "data", "gb", "data_gb"), "price_gb", "over_gb", "extra_gb"),
                decimal(t, decimal(over, BigDecimal.ZERO, "sms"), "price_sms", "over_sms", "extra_sms"),
                bool(t, false, "night_unlimited", "night_unlim", "nightUnlimited"),
                roam != null && roam.isObject()
                        ? decimal(roam, BigDecimal.ZERO, "call", "min", "minute", "minutes")
                        : decimal(t, BigDecimal.ZERO, "roam_min", "roaming_min"),
                roam != null && roam.isObject()
                        ? decimal(roam, BigDecimal.ZERO, "data", "gb", "data_gb")
                        : decimal(t, BigDecimal.ZERO, "roam_gb", "roaming_gb"),
                roam != null && roam.isObject()
                        ? decimal(roam, BigDecimal.ZERO, "sms")
                        : decimal(t, BigDecimal.ZERO, "roam_sms", "roaming_sms"));
    }

    /** null / -1 / "unlimited" → безлимит (null). Отсутствует поле → 0 (пакета нет). */
    private static Integer limitInt(JsonNode v) {
        BigDecimal d = limitDecimal(v);
        return d == null ? null : d.intValue();
    }

    private static BigDecimal limitDecimal(JsonNode v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v.isTextual()) {
            String s = v.asText().trim().toLowerCase(Locale.ROOT);
            if (s.equals("unlimited") || s.equals("inf") || s.equals("безлимит")) {
                return null;
            }
        }
        BigDecimal d = Json.toDecimal(v);
        return d.signum() < 0 ? null : d;
    }

    private static JsonNode unwrap(JsonNode root, String key) {
        if (root.isObject() && root.has(key)) {
            return root.get(key);
        }
        return root;
    }
}
