package net.kdt.pojavlaunch.loliland;

import android.os.Build;

import net.kdt.pojavlaunch.PojavApplication;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LoliLandApi {
    public static final String GATEWAY_DG = "https://launcher-dg.loliland.pro";
    public static final String GATEWAY_CF = "https://launcher-new.loliland.pro";

    public static final int ERROR_2FA = -7;

    private static String[] gateways() {
        return new String[]{GATEWAY_DG, GATEWAY_CF};
    }

    public static final class AuthResult {
        public String accessId;
        public String accessToken;
        public String login;
    }

    public static final class ApiException extends IOException {
        public final int errorCode;
        public ApiException(int code, String message) {
            super(message);
            this.errorCode = code;
        }
    }

    private LoliLandApi() {}

    /* ===================== AUTH ===================== */

    public static AuthResult login(String login, String password) throws IOException {
        return authCall("/gateway/login", buildAuthBody(login, null, password));
    }

    public static AuthResult registerOrLogin(String login, String email, String password) throws IOException {
        return authCall("/gateway/register-or-login", buildAuthBody(login, email, password));
    }

    private static JSONObject buildAuthBody(String login, String email, String password) throws IOException {
        try {
            JSONObject body = baseBody();
            body.put("login", login);
            if (email != null) body.put("email", email);
            body.put("password", password);
            return body;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static AuthResult authCall(String path, JSONObject body) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        IOException last = null;
        for (String gw : gateways()) {
            try {
                HttpURLConnection c = open(gw + path, "POST");
                setCommonHeaders(c, null);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                writeAll(c, payload);
                int status = c.getResponseCode();
                String text = readAll(c);
                if (status < 200 || status >= 300) continue;
                return parseGateway(text);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No gateway reachable");
    }

    private static AuthResult parseGateway(String text) throws IOException {
        try {
            JSONObject resp = new JSONObject(text);
            JSONObject err = resp.optJSONObject("authError");
            if (err != null && err.length() > 0) {
                throw new ApiException(
                    err.optInt("error_code", 0),
                    err.optString("error_message", "Unknown error"));
            }
            JSONObject auth = resp.optJSONObject("auth");
            if (auth == null) throw new IOException("Response has no auth section");
            JSONObject data = auth.optJSONObject("data");
            if (data == null) throw new IOException("Response has no user data");
            AuthResult r = new AuthResult();
            r.accessToken = auth.getString("accessToken");
            r.accessId = data.getString("id");
            r.login = data.optString("login", "");
            if (r.login.isEmpty()) throw new IOException("Empty login in response");
            return r;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /* ===================== CATALOG / DOWNLOADS ===================== */

    /** POST /gateway/token (auth via Access headers); returns the raw LauncherGatewayData JSON. */
    public static JSONObject gatewayWithClients(AuthResult creds) throws IOException {
        IOException last = null;
        String[] paths = {"/gateway/token", "/gateway"};
        for (String gw : gateways()) {
            for (String path : paths) {
                try {
                    HttpURLConnection c = open(gw + path, "POST");
                    setCommonHeaders(c, creds);
                    c.setRequestProperty("Content-Type", "application/json");
                    writeAll(c, baseBody().toString().getBytes(StandardCharsets.UTF_8));
                    int status = c.getResponseCode();
                    String text = readAll(c);
                    if (status == 401 || status == 403) {
                        throw new ApiException(status,
                            "Требуется повторный вход (сервер ответил " + status + ")");
                    }
                    if (status < 200 || status >= 300) continue;
                    JSONObject resp = new JSONObject(text);
                    JSONObject err = resp.optJSONObject("authError");
                    if (err != null && err.length() > 0) {
                        throw new ApiException(err.optInt("error_code", 0),
                            err.optString("error_message", "Ошибка авторизации"));
                    }
                    return resp;
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    if (e instanceof IOException) last = (IOException) e; else last = new IOException(e);
                }
            }
        }
        throw last != null ? last : new IOException("No gateway reachable");
    }

    /** GET /client/<uuid>/info -> ClientResources JSON. */
    public static JSONObject clientInfo(AuthResult creds, String clientUuid) throws IOException {
        IOException last = null;
        for (String gw : gateways()) {
            try {
                HttpURLConnection c = open(gw + "/client/" + clientUuid + "/info", "GET");
                setCommonHeaders(c, creds);
                int status = c.getResponseCode();
                String text = readAll(c);
                if (status < 200 || status >= 300) {
                    last = describeHttpError(status, text);
                    continue;
                }
                return new JSONObject(text);
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        throw last != null ? last : new IOException("No gateway reachable");
    }

    private static IOException describeHttpError(int status, String body) {
        try {
            JSONObject err = new JSONObject(body);
            String msg = err.optString("error_message", "");
            if (!msg.isEmpty()) return new ApiException(err.optInt("error_code", status), msg + " (HTTP " + status + ")");
        } catch (Exception ignored) {}
        return new IOException("HTTP " + status);
    }

    /**
     * Opens a download stream for a client file addressed by its SHA-256 hash
     * ("/download/client/&lt;uuid&gt;/&lt;sha256&gt;.zip" — despite the extension the payload is the raw file).
     * Caller must close.
     */
    public static InputStream downloadClientFile(AuthResult creds, String clientUuid, String sha256) throws IOException {
        return downloadByHash(creds, "/download/client/" + clientUuid + "/", sha256);
    }

    /**
     * Opens a download stream for an asset file addressed by its SHA-256 hash
     * ("/download/assets/&lt;assetsUrlPart&gt;/&lt;sha256&gt;.zip"). Caller must close.
     */
    public static InputStream downloadAssetFile(AuthResult creds, String assetsUrlPart, String sha256) throws IOException {
        return downloadByHash(creds, "/download/assets/" + assetsUrlPart + "/", sha256);
    }

    private static InputStream downloadByHash(AuthResult creds, String base, String sha256) throws IOException {
        IOException last = null;
        for (String gw : gateways()) {
            try {
                HttpURLConnection c = open(gw + base + sha256.toLowerCase() + ".zip", "GET");
                setCommonHeaders(c, creds);
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) {
                    IOException err = describeHttpError(status, readAll(c));
                    c.disconnect();
                    last = err;
                    continue;
                }
                return c.getInputStream();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Cannot download " + base + sha256);
    }

    /* ===================== HELPERS ===================== */

    private static JSONObject baseBody() throws Exception {
        JSONObject sys = new JSONObject();
        sys.put("processorName", safe(Build.HARDWARE));
        sys.put("processorId", "0");
        sys.put("processorPhysical", Runtime.getRuntime().availableProcessors());
        sys.put("processorLogical", Runtime.getRuntime().availableProcessors());
        sys.put("baseboardManufacturer", "unknown");
        sys.put("baseboardName", safe(Build.BOARD));
        sys.put("baseboardSerial", "0");
        sys.put("hardwareUUID", deviceUuid());
        sys.put("freq", 0);
        sys.put("graphics", new JSONArray());
        sys.put("ramAmount", totalRamMb());
        sys.put("osName", "Linux");
        sys.put("arch", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "aarch64");
        sys.put("isX64", false);
        sys.put("displays", new JSONArray());

        JSONObject body = new JSONObject();
        body.put("main", true);
        // "monitoing" typo preserved intentionally: the official client sends it
        body.put("monitoing", true);
        body.put("clients", "true");
        body.put("systemData", sys);
        return body;
    }

    private static String safe(String s) { return s == null || s.isEmpty() ? "unknown" : s; }

    private static String deviceUuid() {
        try {
            String id = android.provider.Settings.Secure.getString(
                PojavApplication.sAppContext.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
            if (id != null && !id.isEmpty()) return id;
        } catch (Exception ignored) {}
        return "00000000-0000-0000-0000-000000000000";
    }

    @SuppressWarnings("deprecation")
    private static long totalRamMb() {
        try (RandomAccessFile f = new RandomAccessFile("/proc/meminfo", "r")) {
            String line = f.readLine();
            if (line != null && line.contains("MemTotal")) {
                String digits = line.replaceAll("[^0-9]", "");
                return Long.parseLong(digits) / 1024L;
            }
        } catch (Exception ignored) {}
        return 4096;
    }

    static void setCommonHeaders(HttpURLConnection c, AuthResult creds) {
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept-Language", "ru");
        c.setRequestProperty("User-Agent", "loli-launcher-client");
        c.setRequestProperty("X-Idempotency-Key", java.util.UUID.randomUUID().toString());
        if (creds != null) {
            c.setRequestProperty("Access-Id", creds.accessId);
            c.setRequestProperty("Access-Token", creds.accessToken);
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setDoInput(true);
        if ("POST".equals(method)) c.setDoOutput(true);
        return c;
    }

    private static void writeAll(HttpURLConnection c, byte[] data) throws IOException {
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        InputStream is;
        try {
            is = c.getInputStream();
        } catch (IOException e) {
            is = c.getErrorStream();
        }
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
