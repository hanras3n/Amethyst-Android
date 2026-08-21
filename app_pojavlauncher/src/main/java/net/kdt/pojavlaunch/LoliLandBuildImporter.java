package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Keep;

import com.google.gson.Gson;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Keep
public final class LoliLandBuildImporter {
    private static final String TAG = "LoliLandImport";
    private static final Gson GSON = new Gson();

    public static class BuildManifest {
        public String systemName = "custom";
        public String displayName = "LoliLand";
        public String versionId;
        public String username = null;
        public String uuid = null;
        public List<String> javaArgs = new ArrayList<>();
    }

    private LoliLandBuildImporter() {}

    public static void scanAndImportAsync(Context context) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                scanAndImport(context.getApplicationContext());
            } catch (Throwable t) {
                Log.e(TAG, "Import pass failed", t);
            }
        });
    }

    private static void scanAndImport(Context ctx) {
        File importDir = new File(Tools.DIR_GAME_HOME, "import");
        if (!importDir.isDirectory()) return;
        File doneDir = new File(importDir, "imported");
        File[] zips = importDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (zips == null || zips.length == 0) return;

        FileUtils.ensureDirectory(importDir);
        FileUtils.ensureDirectory(doneDir);

        for (File zip : zips) {
            try {
                notify(ctx, "LoliLand: installing " + zip.getName() + "...");
                BuildManifest manifest = importZip(ctx, zip, doneDir);
                notify(ctx, "LoliLand: " + manifest.displayName + " installed!");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to import " + zip.getName(), t);
                notify(ctx, "LoliLand import failed: " + zip.getName());
            }
        }
    }

    private static BuildManifest importZip(Context ctx, File zip, File doneDir) throws IOException {
        File stage = new File(zip.getParentFile(), "stage-" + System.currentTimeMillis());
        FileUtils.deleteDirectory(stage);
        FileUtils.ensureDirectory(stage);
        unzip(zip, stage);

        File manifestFile = new File(stage, "loliland-build.json");
        if (!manifestFile.isFile())
            throw new IOException("loliland-build.json is missing in the archive root");

        BuildManifest manifest = GSON.fromJson(Tools.read(manifestFile.getAbsolutePath()), BuildManifest.class);
        if (manifest.versionId == null || manifest.versionId.trim().isEmpty())
            throw new IOException("Manifest has no versionId");
        manifest.versionId = manifest.versionId.trim();

        File versionJson = new File(stage, "versions/" + manifest.versionId + "/" + manifest.versionId + ".json");
        File versionJar = new File(stage, "versions/" + manifest.versionId + "/" + manifest.versionId + ".jar");
        if (!versionJson.isFile()) throw new IOException("Version json missing for " + manifest.versionId);
        if (!versionJar.isFile()) throw new IOException("Version jar missing for " + manifest.versionId);

        mergeDirectory(new File(stage, "versions"), new File(Tools.DIR_HOME_VERSION));
        mergeDirectory(new File(stage, "libraries"), new File(Tools.DIR_HOME_LIBRARY));
        mergeDirectory(new File(stage, "assets"), new File(Tools.ASSETS_PATH));

        File gameTarget = new File(Tools.DIR_GAME_NEW, "games/" + manifest.systemName);
        if (gameTarget.exists()) FileUtils.deleteDirectory(gameTarget);
        File gameSource = new File(stage, "game");
        if (gameSource.isDirectory()) {
            FileUtils.moveDirectory(gameSource, gameTarget);
        } else {
            FileUtils.ensureDirectory(gameTarget);
        }

        copyAssetIfNeeded(ctx, "loliland_tech.json", Tools.CTRLMAP_PATH);

        String gameDirRelative = ".minecraft/games/" + manifest.systemName;

        MinecraftAccount account = installAccount(manifest);

        LauncherProfiles.load();
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = "LoliLand: " + manifest.displayName;
        profile.type = "custom";
        profile.lastVersionId = manifest.versionId;
        profile.gameDir = gameDirRelative;
        profile.javaArgs = joinArgs(manifest.javaArgs);
        profile.icon = "Fabric";
        String profileKey = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.mainProfileJson.profiles.put(profileKey, profile);
        LauncherProfiles.write();

        SharedPreferences.Editor prefs = LauncherPreferences.DEFAULT_PREF.edit();
        prefs.putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey);
        prefs.putString("defaultCtrl", Tools.CTRLMAP_PATH + "/loliland_tech.json");
        int allocation = LauncherPreferences.DEFAULT_PREF.getInt("allocation", 0);
        int target = recommendedAllocation();
        if (allocation < target) prefs.putInt("allocation", target);
        prefs.apply();

        PojavProfile.setCurrentProfile(ctx, account.username);

        File processed = new File(doneDir, zip.getName());
        if (processed.exists()) processed.delete();
        FileUtils.moveFile(zip, processed);
        FileUtils.deleteQuietly(stage);

        return manifest;
    }

    private static MinecraftAccount installAccount(BuildManifest manifest) throws IOException {
        String username = manifest.username == null || manifest.username.trim().isEmpty()
                ? "Player" : manifest.username.trim();
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, username + ".json");
        MinecraftAccount account;
        if (accountFile.isFile()) {
            account = MinecraftAccount.load(username);
            if (account == null) account = new MinecraftAccount();
        } else {
            account = new MinecraftAccount();
        }
        account.username = username;
        account.accessToken = "0";
        account.clientToken = "0";
        account.isMicrosoft = false;
        if (manifest.uuid != null && !manifest.uuid.trim().isEmpty()) {
            account.profileId = manifest.uuid.trim().replace("-", "");
        } else {
            account.profileId = offlineUuid(username).replace("-", "");
        }
        account.save();
        return account;
    }

    private static String joinArgs(List<String> args) {
        StringBuilder sb = new StringBuilder();
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(arg.trim());
            }
        }
        return sb.toString();
    }

    private static int recommendedAllocation() {
        long totalMb = Runtime.getRuntime().maxMemory() >> 20;
        int byRam = (int) (totalMb * 55 / 100);
        return Math.max(3072, Math.min(byRam, 6144));
    }

    private static void copyAssetIfNeeded(Context ctx, String assetName, String outputDir) throws IOException {
        FileUtils.ensureDirectory(new File(outputDir));
        File out = new File(outputDir, assetName);
        if (!out.exists()) {
            Tools.copyAssetFile(ctx, assetName, outputDir, false);
        }
    }

    private static void mergeDirectory(File source, File target) throws IOException {
        if (!source.isDirectory()) return;
        FileUtils.ensureDirectory(target);
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File dest = new File(target, child.getName());
            if (child.isDirectory()) {
                mergeDirectory(child, dest);
            } else {
                if (dest.exists() && dest.length() == child.length()) continue;
                FileUtils.copyFileToDirectory(child, target);
            }
        }
    }

    private static void unzip(File zip, File output) throws IOException {
        FileUtils.ensureDirectory(output);
        String canonicalOutput = output.getCanonicalPath() + File.separator;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zip))) {
            byte[] buffer = new byte[1 << 16];
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(output, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(canonicalOutput))
                    throw new IOException("Blocked zip-slip entry: " + entry.getName());
                if (entry.isDirectory()) {
                    FileUtils.ensureDirectory(outFile);
                    continue;
                }
                FileUtils.ensureDirectory(outFile.getParentFile());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) fos.write(buffer, 0, read);
                }
                zis.closeEntry();
            }
        }
    }

    private static String offlineUuid(String username) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            hash[6] &= 0x0F;
            hash[6] |= 0x30;
            hash[8] &= 0x3F;
            hash[8] |= 0x80;
            StringBuilder sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "00000000-0000-3000-8000-000000000000";
        }
    }

    private static void notify(Context ctx, String message) {
        Log.i(TAG, message);
        Tools.runOnUiThread(() -> Toast.makeText(ctx, message, Toast.LENGTH_LONG).show());
    }
}
