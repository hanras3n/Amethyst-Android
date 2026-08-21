package net.kdt.pojavlaunch.loliland;

import android.content.Context;
import android.widget.TextView;

import net.kdt.pojavlaunch.LoliLandBuildImporter;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LoliLandClients {
    public static final class ClientEntry {
        public String uuid;
        public String systemName;
        public String displayName;
        public String size;
        public boolean installed;
        @Override public String toString() {
            return displayName + " (" + size + ")" + (installed ? " — установлена" : "");
        }
    }

    private LoliLandClients() {}

    /* ==================== CATALOG ==================== */

    public static List<ClientEntry> fetchClients(Context ctx) throws Exception {
        LoliLandApi.AuthResult creds = LoliLandAuth.loadCreds(ctx);
        if (creds == null) throw new IllegalStateException("Not logged in");
        JSONObject gw = LoliLandApi.gatewayWithClients(creds);
        JSONObject clientsObj = gw.optJSONObject("clients");
        if (clientsObj == null) throw new Exception("Server sent no clients section");
        JSONArray arr = clientsObj.optJSONArray("clients");
        List<ClientEntry> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                ClientEntry e = new ClientEntry();
                e.uuid = c.optString("uuid", null);
                e.systemName = c.optString("systemName", null);
                e.displayName = c.optString("displayName",
                        c.optString("title", c.optString("systemName", "?")));
                e.size = c.optString("size", "?");
                if ("BIG".equals(e.size)) e.size = "крупная";
                else if ("SMALL".equals(e.size)) e.size = "лёгкая";
                if (e.uuid == null || e.uuid.isEmpty()) continue;
                if (e.systemName == null || e.systemName.isEmpty()) e.systemName = e.uuid.substring(0, 8);
                e.installed = new File(Tools.DIR_HOME_VERSION,
                        "loliland-" + sanitize(e.systemName)).isDirectory();
                out.add(e);
            }
        }
        return out;
    }

    /* ==================== DOWNLOAD ==================== */

    public interface DownloadListener {
        /** Called once before downloading starts, on the worker thread. */
        void onTotals(long totalBytes, int totalFiles);
        /**
         * Called as data arrives, on the worker thread.
         * Implementations must throttle UI updates themselves.
         */
        void onProgress(long bytesDone, int filesDone);
        void onDone();
        void onError();
    }

    private static volatile Progress currentProgress;

    /** Requests cancellation of the currently running download, if any. */
    public static void cancelCurrentDownload() {
        Progress p = currentProgress;
        if (p != null) p.cancel();
    }

    public static void downloadAsync(Context ctx, ClientEntry entry) {
        downloadAsync(ctx, entry, null);
    }

    public static void downloadAsync(Context ctx, ClientEntry entry, DownloadListener listener) {
        android.app.Activity activity = ctx instanceof android.app.Activity ? (android.app.Activity) ctx : null;
        Context app = ctx.getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                downloadSync(app, entry, listener);
                if (listener != null) listener.onDone();
                LoliLandBuildImporter.notify(app, "LoliLand: \"" + entry.displayName + "\" установлена!");
            } catch (Throwable t) {
                android.util.Log.e("LoliLandDL", "Download failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : "");
                appendErrorLog(app, entry, t);
                if (listener != null) listener.onError();
                showError(activity, "Ошибка скачивания \"" + entry.displayName + "\"", msg);
            }
        });
    }

    /** Incremented on every protocol change; shown in dialogs so stale installs are detectable. */
    public static final String BUILD_TAG = "ll-dl3";

    private static void showError(android.app.Activity activity, String title, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (activity != null) LoliLandBuildImporter.notify(activity, title + ": " + message);
            return;
        }
        activity.runOnUiThread(() -> {
            TextView tv = new TextView(activity);
            tv.setText(message + "\n\n—\nсборка: " + BUILD_TAG
                    + "\nполный лог: " + new File(Tools.DIR_GAME_HOME, "loliland-error.log").getAbsolutePath());
            tv.setTextIsSelectable(true);
            int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad / 2, pad, 0);
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(tv)
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    private static void appendErrorLog(Context ctx, ClientEntry entry, Throwable t) {
        try {
            File log = new File(Tools.DIR_GAME_HOME, "loliland-error.log");
            FileOutputStream fos = new FileOutputStream(log, true);
            String ts = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
            String head = "\n==== " + ts + " | client=" + entry.systemName
                    + " uuid=" + entry.uuid + " ====\n";
            fos.write(head.getBytes(StandardCharsets.UTF_8));
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {}
    }

    private static void downloadSync(Context ctx, ClientEntry entry, DownloadListener listener) throws Exception {
        LoliLandApi.AuthResult creds = LoliLandAuth.loadCreds(ctx);
        if (creds == null) throw new IllegalStateException("Не выполнен вход в аккаунт LoliLand");

        JSONObject info = LoliLandApi.clientInfo(creds, entry.uuid);
        JSONObject launch = info.getJSONObject("launch");

        String systemName = sanitize(entry.systemName);
        String versionId = "loliland-" + systemName;
        File gameDir = new File(Tools.DIR_GAME_NEW, "games/" + systemName);
        ensureDir(gameDir);

        JSONArray clientMapping = info.optJSONArray("clientFileMapping");
        JSONArray assetMapping = info.optJSONArray("assetsFileMapping");

        Progress progress = new Progress(listener);
        progress.addTotals(clientMapping);
        progress.addTotals(assetMapping);
        progress.emitTotals();
        currentProgress = progress;
        try {
            runDownload(ctx, entry, creds, info, launch, systemName, versionId, gameDir,
                    clientMapping, assetMapping, progress);
        } finally {
            currentProgress = null;
        }
    }

    private static void runDownload(Context ctx, ClientEntry entry, LoliLandApi.AuthResult creds,
            JSONObject info, JSONObject launch, String systemName, String versionId, File gameDir,
            JSONArray clientMapping, JSONArray assetMapping, Progress progress) throws Exception {

        // 1. Client files -> game dir
        List<String[]> done = new ArrayList<>(); // [original, absolute path]
        int total = clientMapping == null ? 0 : clientMapping.length();
        for (int i = 0; i < total; i++) {
            JSONObject f = clientMapping.getJSONObject(i);
            String original = f.getString("original");
            long size = f.optLong("size", -1);
            String sha256 = f.optString("sha256", "");
            File dest = new File(gameDir, original);
            progress.checkCancelled();
            fetchIfNeeded(creds, progress, entry.uuid, original, dest, size, sha256);
            done.add(new String[]{original, dest.getAbsolutePath()});
            if (i % 200 == 0) progress(ctx, entry.displayName, i + 1, total);
        }
        progress(ctx, entry.displayName, total, total);

        // 2. Assets -> shared assets root (objects are content-addressed, safe to merge)
        String assetsRaw = launch.optString("assets", "");
        String assetsUrlPart = assetsRaw.replace(':', '/').replace('\\', '/');
        String assetIndexId = null;
        int atotal = assetMapping == null ? 0 : assetMapping.length();
        for (int i = 0; i < atotal; i++) {
            JSONObject f = assetMapping.getJSONObject(i);
            String original = f.getString("original");
            String rel = original.replace('\\', '/');
            File dest = new File(Tools.ASSETS_PATH, rel);
            progress.checkCancelled();
            fetchAsset(creds, progress, assetsUrlPart, dest,
                    f.optLong("size", -1), f.optString("sha256", ""));
            if (rel.startsWith("indexes/") && rel.endsWith(".json") && assetIndexId == null) {
                assetIndexId = rel.substring("indexes/".length(), rel.length() - ".json".length());
            }
            if (i % 400 == 0) progressAssets(ctx, entry.displayName, i + 1, atotal);
        }
        if (assetIndexId == null) assetIndexId = guessAssetIndex(launch);

        // 3. Resolve the version jar + libraries from the server classPath
        JSONArray classPathArr = launch.optJSONArray("classPath");
        Map<String, String> byBasename = new HashMap<>();
        for (String[] pair : done) {
            String base = pair[0];
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);
            if (!byBasename.containsKey(base)) byBasename.put(base, pair[1]);
        }

        File libTargetBase = new File(Tools.DIR_HOME_LIBRARY, "loliland/" + systemName);
        ensureDir(libTargetBase);
        JSONArray libraries = new JSONArray();
        File versionJar = null;

        if (classPathArr != null && classPathArr.length() > 0) {
            long bestSize = -1;
            for (int i = 0; i < classPathArr.length(); i++) {
                String cp = classPathArr.optString(i, "");
                String base = cp.replace('\\', '/');
                int slash = base.lastIndexOf('/');
                if (slash >= 0) base = base.substring(slash + 1);
                String abs = byBasename.get(base);
                if (abs == null || !base.toLowerCase().endsWith(".jar")) continue;
                File src = new File(abs);

                boolean looksLikeLibrary = isUnderDir(cp.replace('\\', '/'), "libraries/");
                if (looksLikeLibrary || !isVersionJarCandidate(src, gameDir)) {
                    File libDest = new File(libTargetBase, i + "_" + base);
                    if (!libDest.isFile() || libDest.length() != src.length()) {
                        ensureDir(libDest.getParentFile());
                        copyFile(src, libDest);
                    }
                    JSONObject lib = new JSONObject();
                    lib.put("name", "loliland:" + systemName + ":" + i);
                    JSONObject artifact = new JSONObject();
                    artifact.put("path", "loliland/" + systemName + "/" + i + "_" + base);
                    JSONObject downloads = new JSONObject();
                    downloads.put("artifact", artifact);
                    lib.put("downloads", downloads);
                    libraries.put(lib);
                } else {
                    long len = src.length();
                    if (versionJar == null || len > bestSize) {
                        versionJar = src;
                        bestSize = len;
                    }
                }
            }
        }

        // 3b. Safety net: Tools.generateLibClasspath throws unless some library is named
        // org.lwjgl(.lwjgl)?:lwjgl:<version>. If the server classPath had no lwjgl jar,
        // promote one from the downloaded client files.
        boolean hasLwjgl = false;
        for (int i = 0; i < libraries.length(); i++) {
            String n = libraries.getJSONObject(i).optString("name", "");
            if (n.startsWith("org.lwjgl")) { hasLwjgl = true; break; }
        }
        if (!hasLwjgl) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?i)^lwjgl-(\\d[\\w.]*?)\\.jar$");
            File best = null; String bestVer = null; long bestLen = -1;
            for (int i = 0; i < done.size(); i++) {
                String orig = done.get(i)[0];
                int slash = Math.max(orig.lastIndexOf('/'), orig.lastIndexOf('\\'));
                String base = slash >= 0 ? orig.substring(slash + 1) : orig;
                String lower = base.toLowerCase();
                if (lower.contains("natives") || lower.contains("sources")
                        || lower.contains("javadoc")) continue;
                java.util.regex.Matcher m = p.matcher(base);
                if (!m.matches()) continue;
                long len = new File(done.get(i)[1]).length();
                if (len > bestLen) { bestLen = len; best = new File(done.get(i)[1]); bestVer = m.group(1); }
            }
            if (best != null && bestVer != null) {
                int idx = libraries.length();
                String stored = idx + "_lwjgl-" + bestVer + ".jar";
                File libDest = new File(libTargetBase, stored);
                if (!libDest.isFile() || libDest.length() != best.length()) {
                    ensureDir(libDest.getParentFile());
                    copyFile(best, libDest);
                }
                JSONObject lib = new JSONObject();
                lib.put("name", (bestVer.startsWith("3") ? "org.lwjgl:lwjgl:" : "org.lwjgl.lwjgl:lwjgl:") + bestVer);
                JSONObject artifact = new JSONObject();
                artifact.put("path", "loliland/" + systemName + "/" + stored);
                JSONObject downloads = new JSONObject();
                downloads.put("artifact", artifact);
                lib.put("downloads", downloads);
                libraries.put(lib);
            }
        }

        // 4. Install version json + jar
        File versionDir = new File(Tools.DIR_HOME_VERSION, versionId);
        ensureDir(versionDir);
        if (versionJar != null) {
            copyFile(versionJar, new File(versionDir, versionId + ".jar"));
        }

        JSONObject verJson = buildVersionJson(versionId, launch, assetIndexId, libraries);
        LoliLandAuth.writeText(new File(versionDir, versionId + ".json"), verJson.toString());

        // 5. Profile javaArgs from server jvmArguments (+compat +auth placeholders)
        List<String> javaArgs = new ArrayList<>();
        JSONArray jvmArgs = launch.optJSONArray("jvmArguments");
        if (jvmArgs != null) {
            for (int i = 0; i < jvmArgs.length(); i++) {
                String a = jvmArgs.optString(i, "").trim();
                if (a.isEmpty() || isBadAndroidArg(a)) continue;
                javaArgs.add(a);
            }
        }
        String mainClass = launch.optString("mainClass", "");
        if (mainClass.contains("launchwrapper.compat")) {
            javaArgs.add("-Djava.system.class.loader=net.minecraft.launchwrapper.compat.LaunchSystemClassLoader");
            javaArgs.add("-Dlaunchwrapper.proxiedMain=net.minecraft.launchwrapper.Launch");
        }
        javaArgs.add("-Dloliland.auth.poolType=dg");
        javaArgs.add("-Dloliland.auth.uuid=0");
        javaArgs.add("-Dloliland.auth.token=0");
        javaArgs.add("-Dloliland.client.name=" + entry.displayName);
        javaArgs.add("-Dloliland.client.systemName=" + entry.systemName);
        javaArgs.add("-Dloliland.client.displayName=" + entry.displayName);

        net.kdt.pojavlaunch.LoliLandBuildImporter.installBuildProfile(
                ctx, systemName, entry.displayName, versionId, javaArgs, creds.login);
        LoliLandAuth.applyToBuilds(ctx);
    }

    /* ==================== FILE FETCH ==================== */

    private static void fetchIfNeeded(LoliLandApi.AuthResult creds, Progress progress, String uuid,
            String original, File dest, long size, String sha256) throws Exception {
        if (dest.isFile() && (size <= 0 || dest.length() == size)
                && (sha256.isEmpty() || sha256Matches(dest, sha256))) {
            progress.fileSkipped(Math.max(size, 0));
            return;
        }
        if (sha256 == null || sha256.isEmpty()) {
            throw new Exception("Server sent no SHA-256 for " + original);
        }
        ensureDir(dest.getParentFile());
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        InputStream in = LoliLandApi.downloadClientFile(creds, uuid, sha256);
        transfer(in, tmp, progress);
        if (sha256 != null && !sha256.isEmpty() && !sha256Matches(tmp, sha256)) {
            tmp.delete();
            throw new Exception("Checksum mismatch: " + original);
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) copyFile(tmp, dest);
        progress.fileDone();
    }

    private static void fetchAsset(LoliLandApi.AuthResult creds, Progress progress, String assetsUrlPart,
            File dest, long size, String sha256) throws Exception {
        if (dest.isFile() && (size <= 0 || dest.length() == size)
                && (sha256.isEmpty() || sha256Matches(dest, sha256))) {
            progress.fileSkipped(Math.max(size, 0));
            return;
        }
        if (sha256 == null || sha256.isEmpty()) {
            throw new Exception("Server sent no SHA-256 for asset " + dest.getName());
        }
        if (assetsUrlPart.isEmpty()) {
            throw new Exception("Server sent no assets id for " + dest.getName());
        }
        ensureDir(dest.getParentFile());
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        InputStream in = LoliLandApi.downloadAssetFile(creds, assetsUrlPart, sha256);
        transfer(in, tmp, progress);
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) copyFile(tmp, dest);
        progress.fileDone();
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        fos.close();
        fis.close();
    }

    private static void transfer(InputStream in, File dest, Progress progress) throws Exception {
        FileOutputStream fos = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                progress.checkCancelled();
                fos.write(buf, 0, n);
                progress.addBytes(n);
            }
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** Aggregates byte/file counters and forwards them to the UI listener. */
    private static final class Progress {
        private final DownloadListener listener;
        private long totalBytes;
        private int totalFiles;
        private volatile long doneBytes;
        private volatile int doneFiles;
        private volatile boolean cancelled;
        private long lastEmit;

        Progress(DownloadListener listener) { this.listener = listener; }

        void addTotals(JSONArray mapping) {
            if (mapping == null) return;
            for (int i = 0; i < mapping.length(); i++) {
                JSONObject f = mapping.optJSONObject(i);
                if (f == null) continue;
                totalFiles++;
                long s = f.optLong("size", -1);
                if (s > 0) totalBytes += s;
            }
        }

        void emitTotals() {
            if (listener != null) listener.onTotals(totalBytes, totalFiles);
        }

        void addBytes(int n) {
            doneBytes += n;
            maybeEmit(false);
        }

        void fileSkipped(long bytes) {
            doneBytes += bytes;
            doneFiles++;
            maybeEmit(true);
        }

        void fileDone() {
            doneFiles++;
            maybeEmit(true);
        }

        private void maybeEmit(boolean force) {
            if (listener == null) return;
            long now = System.currentTimeMillis();
            if (force || now - lastEmit >= 250) {
                lastEmit = now;
                listener.onProgress(doneBytes, doneFiles);
            }
        }

        void checkCancelled() throws Exception {
            if (cancelled) throw new Exception("Скачивание отменено");
        }

        void cancel() { cancelled = true; }
    }

    private static boolean sha256Matches(File f, String expected) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
        fis.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString().equalsIgnoreCase(expected);
    }

    /* ==================== HELPERS ==================== */

    private static void progress(Context ctx, String name, int done, int total) {
        LoliLandBuildImporter.notify(ctx, "LoliLand: downloading " + name + " — "
                + done + "/" + Math.max(total, 1) + " files");
    }

    private static void progressAssets(Context ctx, String name, int done, int total) {
        LoliLandBuildImporter.notify(ctx, "LoliLand: downloading " + name + " assets — "
                + done + "/" + Math.max(total, 1) + " files");
    }

    private static String guessAssetIndex(JSONObject launch) {
        String uv = launch.optString("updateVersion", "");
        if (uv.isEmpty()) return "legacy";
        try {
            String[] parts = uv.split("\\.");
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*", "")) : 0;
            return minor < 8 ? "legacy" : "5";
        } catch (Exception e) {
            return "legacy";
        }
    }

    private static boolean isVersionJarCandidate(File f, File gameDir) {
        if (!f.isFile()) return false;
        String parent = f.getParentFile() == null ? "" : f.getParentFile().getName();
        // Root-level or bin/ jars are typical merged-client jars; mods/ jars are NOT.
        if ("mods".equals(parent)) return false;
        return true;
    }

    private static boolean isUnderDir(String path, String dir) {
        return path.contains(dir);
    }

    private static boolean isBadAndroidArg(String a) {
        String lower = a.toLowerCase();
        if (lower.contains(":\\") || lower.contains("/users/") || lower.contains("appdata")) return true;
        String[] bad = {
            "-dorg.lwjgl.librarypath", "-dnet.java.games.input.librarypath",
            "-djava.library.path", "-djava.home=", "-dos.name=", "-dos.version=",
            "-dloliland.auth.uuid=", "-dloliland.auth.token=", "-dloliland.token=",
            "-dloliland.resources=", "-dloliland.servers=",
            "-xmx", "-xms", "-xincgc", "-cp", "${classpath}"
        };
        for (String b : bad) if (lower.startsWith(b)) return true;
        return false;
    }

    private static String sanitize(String s) {
        return s == null ? "build" : s.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    private static void ensureDir(File d) {
        if (d != null && !d.isDirectory()) d.mkdirs();
    }

    private static JSONObject buildVersionJson(String versionId, JSONObject launch,
            String assetIndexId, JSONArray libraries) throws Exception {
        String mcver = launch.optString("updateVersion", "1.7.10");
        boolean legacy = mcver.matches("1\\.[0-7](\\..*)?");
        String assetsKey = legacy ? "legacy" : (assetIndexId != null ? assetIndexId : "5");
        String indexId = assetIndexId != null ? assetIndexId : assetsKey;

        StringBuilder margs = new StringBuilder();
        margs.append("--username ${auth_player_name} ")
             .append("--version ${version_name} ")
             .append("--gameDir ${game_directory} ")
             .append("--assetsDir ${assets_root} ")
             .append("--assetIndex ").append(indexId).append(" ")
             .append("--uuid ${auth_uuid} ")
             .append("--accessToken ${auth_access_token} ")
             .append("--userType legacy --userProperties {}");
        JSONArray gameArgs = launch.optJSONArray("gameArguments");
        if (gameArgs != null) {
            for (int i = 0; i < gameArgs.length(); i++) {
                String a = gameArgs.optString(i, "").trim();
                if (a.isEmpty() || !a.startsWith("--")) continue;
                if (a.equals("--username") || a.equals("--gameDir") || a.equals("--gameDirectory")
                        || a.equals("--assetsDir") || a.equals("--assetIndex")
                        || a.equals("--uuid") || a.equals("--accessToken")
                        || a.equals("--version") || a.equals("--userType")) continue;
                margs.append(' ').append(a);
            }
        }

        JSONObject json = new JSONObject();
        json.put("id", versionId);
        json.put("type", "release");
        json.put("time", "2025-01-01T00:00:00+0000");
        json.put("releaseTime", "2025-01-01T00:00:00+0000");
        json.put("assets", assetsKey);
        JSONObject assetIndex = new JSONObject();
        assetIndex.put("id", indexId);
        assetIndex.put("totalSize", 1);
        json.put("assetIndex", assetIndex);
        JSONObject javaVersion = new JSONObject();
        javaVersion.put("component", "jre-legacy");
        javaVersion.put("majorVersion", 8);
        json.put("javaVersion", javaVersion);
        json.put("minimumLauncherVersion", 21);
        json.put("mainClass", launch.optString("mainClass",
                "net.minecraft.launchwrapper.compat.LaunchProxy"));
        json.put("minecraftArguments", margs.toString());
        json.put("libraries", libraries);
        return json;
    }
}
