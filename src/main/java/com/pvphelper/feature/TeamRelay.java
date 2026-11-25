package com.pvphelper.feature;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvphelper.PvpConfig;
import com.pvphelper.PvpHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Синхронизация координат команды через собственный сервер-релей.
 * Раз в секунду шлём свою позицию и получаем позиции остальных — так координаты
 * своих видны НА ЛЮБОЙ ДИСТАНЦИИ (ванильный сервер этого не даёт).
 * Сеть идёт в фоновом потоке-демоне, чтобы не тормозить игру.
 */
public final class TeamRelay {

    public record Remote(String name, double x, double y, double z, String dim) {}

    private static final Map<String, Remote> remote = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shtrafostan-relay");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).build();

    private static int tickCounter = 0;
    private static volatile boolean inFlight = false;
    private static boolean warned = false;

    private TeamRelay() {}

    public static Collection<Remote> players() {
        return remote.values();
    }

    /** true, если этот ник сейчас шлёт координаты в релей — значит, у него есть мод. */
    public static boolean knows(String name) {
        if (name == null) return false;
        for (String n : remote.keySet()) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static void tick(Minecraft mc) {
        if (!PvpConfig.RELAY_ENABLED.get()) {
            if (!remote.isEmpty()) remote.clear();
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null) return;

        String url = PvpConfig.RELAY_URL.get();
        if (url == null || url.isBlank()) return;

        if (++tickCounter < 20) return; // ~1 секунда
        tickCounter = 0;
        if (inFlight) return;
        inFlight = true;

        String name = p.getScoreboardName();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        String dim = p.level().dimension().identifier().toString();
        String token = PvpConfig.RELAY_TOKEN.get();
        String base = url.replaceAll("/+$", "");

        EXEC.submit(() -> {
            try {
                sync(base, token, name, x, y, z, dim);
                warned = false;
            } catch (Exception e) {
                remote.clear();
                if (!warned) {
                    warned = true;
                    PvpHelper.LOGGER.warn("ШТРАФОСТАН relay: нет связи с {} ({})", base, e.getMessage());
                }
            } finally {
                inFlight = false;
            }
        });
    }

    private static void sync(String base, String token, String name,
                             double x, double y, double z, String dim) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("token", token);
        req.addProperty("name", name);
        req.addProperty("x", x);
        req.addProperty("y", y);
        req.addProperty("z", z);
        req.addProperty("dim", dim);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(base + "/sync"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(req), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            remote.clear();
            if (!warned) {
                warned = true;
                PvpHelper.LOGGER.warn("ШТРАФОСТАН relay: сервер ответил {} (проверь токен/URL)", resp.statusCode());
            }
            return;
        }

        JsonObject body = GSON.fromJson(resp.body(), JsonObject.class);
        JsonArray arr = body.getAsJsonArray("players");
        Map<String, Remote> fresh = new HashMap<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String n = o.get("name").getAsString();
                if (n.equalsIgnoreCase(name)) continue; // себя не показываем
                fresh.put(n, new Remote(n,
                    o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                    o.has("dim") ? o.get("dim").getAsString() : ""));
            }
        }
        remote.clear();
        remote.putAll(fresh);
    }
}
