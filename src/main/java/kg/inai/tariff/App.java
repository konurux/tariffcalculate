package kg.inai.tariff;

import kg.inai.tariff.core.Config;
import kg.inai.tariff.core.TariffService;
import kg.inai.tariff.io.Json;
import kg.inai.tariff.llm.Explainer;
import kg.inai.tariff.web.WebServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CLI.
 * <pre>
 *   java -jar target/tariff-calc.jar data/usage_1.csv          # один абонент → JSON
 *   java -jar target/tariff-calc.jar data                      # все usage_*.csv в папке
 *   java -jar target/tariff-calc.jar data/usage_1.csv --current T2 --out result.json
 * </pre>
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String path = null;
        String dataDir = null;
        String current = null;
        String outFile = null;
        Integer servePort = null;
        boolean explain = false;
        boolean jury = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--explain" -> explain = true;
                case "--jury" -> jury = true;
                case "--serve" -> servePort = (i + 1 < args.length && args[i + 1].matches("\\d+"))
                        ? Integer.parseInt(args[++i]) : 8080;
                case "--data-dir" -> dataDir = args[++i];
                case "--current" -> current = args[++i];
                case "--out" -> outFile = args[++i];
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                default -> path = args[i];
            }
        }
        if (servePort != null) {
            return serve(servePort, dataDir != null ? dataDir : (path != null ? path : "data"));
        }
        if (path == null) {
            printUsage();
            return 1;
        }

        try {
            Path p = Path.of(path);
            boolean dir = Files.isDirectory(p);
            Path catalogDir = dataDir != null ? Path.of(dataDir) : (dir ? p : parentOf(p));
            TariffService service = TariffService.fromDir(catalogDir, Config.defaults());

            Object result;
            int code = 0;
            if (dir) {
                List<Object> all = new ArrayList<>();
                for (Path f : usageFiles(p, service)) {
                    try {
                        all.add(service.recommend(f, current));
                    } catch (RuntimeException | IOException e) {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("subscriber", TariffService.subscriberId(f));
                        err.put("error", e.getMessage());
                        all.add(err);
                        code = 2;
                    }
                }
                result = all;
            } else {
                Map<String, Object> single = service.recommend(p, current);
                if (explain) {
                    Map<String, Object> ex = Explainer.fromEnv().explain(single);
                    single.put("explanation", ex.get("explanation"));
                    single.put("explanation_source", ex.get("source"));
                }
                result = single;
            }

            if (jury) {
                result = result instanceof List<?> l ? l.stream().map(App::juryOnly).toList() : juryOnly(result);
            }
            String json = Json.MAPPER.writeValueAsString(result);
            if (outFile != null) {
                Files.writeString(Path.of(outFile), json);
            }
            System.out.println(json);
            return code;
        } catch (IOException | RuntimeException e) {
            System.err.println("Ошибка: " + e.getMessage());
            return 2;
        }
    }

    private static int serve(int port, String dataDir) {
        try {
            Path dir = Path.of(dataDir);
            Explainer explainer = Explainer.fromEnv();
            new WebServer(TariffService.fromDir(dir, Config.defaults()), dir, explainer).start(port);
            System.err.println("Веб-форма: http://localhost:" + port + "  (данные: " + dir.toAbsolutePath() + ")");
            System.err.println("Объяснение: " + (explainer.llmEnabled()
                    ? "Claude (" + Explainer.MODEL + ")" : "шаблон (задайте ANTHROPIC_API_KEY для LLM)"));
            Thread.currentThread().join();
            return 0;
        } catch (IOException e) {
            System.err.println("Ошибка запуска сервера: " + e.getMessage());
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static Path parentOf(Path p) {
        Path parent = p.toAbsolutePath().getParent();
        return parent != null ? parent : Path.of(".");
    }

    /** Поля в формате expected.json организаторов. */
    private static final List<String> JURY_FIELDS = List.of("id", "action", "tariff_id", "addon_ids",
            "current_monthly_cost", "new_monthly_cost", "monthly_savings", "switch_fee", "net_savings_3m",
            "monthly_costs", "error");

    @SuppressWarnings("unchecked")
    private static Object juryOnly(Object r) {
        if (!(r instanceof Map<?, ?> m)) {
            return r;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : JURY_FIELDS) {
            if (m.containsKey(k)) {
                out.put(k, ((Map<String, Object>) m).get(k));
            }
        }
        if (!out.containsKey("id") && m.containsKey("subscriber")) {
            out.put("id", m.get("subscriber"));
        }
        return out;
    }

    /** Абоненты в порядке subscribers.json; если его нет — все usage_*.csv в папке. */
    private static List<Path> usageFiles(Path dir, TariffService service) throws IOException {
        List<TariffService.SubscriberInfo> subs = service.subscribers();
        if (!subs.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (TariffService.SubscriberInfo s : subs) {
                out.add(dir.resolve(s.usageFile() != null ? s.usageFile() : "usage_" + s.id() + ".csv"));
            }
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().matches("usage_.*\\.csv"))
                    .sorted(Comparator.comparing(App::naturalKey))
                    .toList();
        }
    }

    private static String naturalKey(Path f) {
        String id = TariffService.subscriberId(f);
        return id.matches("\\d+") ? String.format("%010d", Long.parseLong(id)) : "~" + id;
    }

    private static void printUsage() {
        System.err.println("""
                Использование:
                  java -jar target/tariff-calc.jar <usage_<id>.csv | папка> [--data-dir DIR] [--current TARIFF_ID] [--out FILE] [--explain] [--jury]
                  --jury — только поля эталона (id, action, tariff_id, addon_ids, *_monthly_cost, monthly_savings, switch_fee, net_savings_3m, monthly_costs)
                  java -jar target/tariff-calc.jar --serve [PORT] [--data-dir DIR]   веб-форма (по умолчанию 8080, data/)
                tariffs.json, addons.json (и опционально subscribers.json) берутся из папки с CSV или из --data-dir.""");
    }
}
