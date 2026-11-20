package kg.inai.tariff.io;

import kg.inai.tariff.model.UsageRecord;
import kg.inai.tariff.model.UsageType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Чтение usage_&lt;id&gt;.csv (date, type, amount [+ roaming, duration_sec, tariff]).
 * Разделитель определяется автоматически (, ; tab). Битые строки пропускаются и считаются.
 */
public final class UsageCsvParser {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"));

    /** Результат разбора файла. */
    public record Result(List<UsageRecord> records, int skipped, String currentTariff) {
    }

    private UsageCsvParser() {
    }

    public static Result parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Result parse(String content) {
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\\r?\\n");
        int headerIdx = 0;
        while (headerIdx < lines.length && lines[headerIdx].isBlank()) {
            headerIdx++;
        }
        if (headerIdx >= lines.length) {
            return new Result(List.of(), 0, null);
        }
        char sep = detectSeparator(lines[headerIdx]);
        List<String> header = split(lines[headerIdx], sep);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        Integer dateCol = first(col, "date", "datetime", "timestamp", "time");
        Integer typeCol = first(col, "type");
        Integer amountCol = first(col, "amount", "value", "volume");
        Integer roamCol = first(col, "roaming", "is_roaming");
        Integer durCol = first(col, "duration_sec", "duration");
        Integer tariffCol = first(col, "tariff", "current_tariff", "tariff_id");
        if (dateCol == null || typeCol == null || amountCol == null) {
            throw new IllegalArgumentException("CSV должен содержать колонки date, type, amount; найдено: " + header);
        }

        List<UsageRecord> records = new ArrayList<>();
        int skipped = 0;
        String currentTariff = null;
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            List<String> f = split(line, sep);
            try {
                LocalDateTime dt = parseDateTime(get(f, dateCol));
                String rawType = get(f, typeCol).trim().toLowerCase(Locale.ROOT);
                boolean roaming = roamCol != null && Numbers.isTruthy(get(f, roamCol));
                if (rawType.startsWith("roaming_")) {
                    roaming = true;
                    rawType = rawType.substring("roaming_".length());
                }
                UsageType type = switch (rawType) {
                    case "call", "voice" -> UsageType.CALL;
                    case "sms" -> UsageType.SMS;
                    case "data", "internet" -> UsageType.DATA;
                    default -> throw new IllegalArgumentException("unknown type " + rawType);
                };
                BigDecimal amount = Numbers.parse(get(f, amountCol));
                if (amount.signum() < 0) {
                    throw new IllegalArgumentException("negative amount");
                }
                Integer dur = null;
                if (durCol != null && !get(f, durCol).isBlank()) {
                    dur = Numbers.parse(get(f, durCol)).intValue();
                }
                if (currentTariff == null && tariffCol != null && !get(f, tariffCol).isBlank()) {
                    currentTariff = get(f, tariffCol).trim();
                }
                records.add(new UsageRecord(dt, type, amount, roaming, dur));
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        return new Result(records, skipped, currentTariff);
    }

    public static LocalDateTime parseDateTime(String s) {
        String t = s.trim();
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(t, f);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(t, f).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("bad date: " + s);
    }

    private static char detectSeparator(String header) {
        int commas = count(header, ','), semis = count(header, ';'), tabs = count(header, '\t');
        if (semis > commas && semis >= tabs) {
            return ';';
        }
        if (tabs > commas) {
            return '\t';
        }
        return ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Простой split с поддержкой кавычек ("1,5"). */
    static List<String> split(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String get(List<String> f, int idx) {
        if (idx >= f.size()) {
            throw new IllegalArgumentException("short row");
        }
        return f.get(idx);
    }

    private static Integer first(Map<String, Integer> col, String... names) {
        for (String n : names) {
            if (col.containsKey(n)) {
                return col.get(n);
            }
        }
        return null;
    }
}
