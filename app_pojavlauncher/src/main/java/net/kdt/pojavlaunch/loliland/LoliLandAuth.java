package net.kdt.pojavlaunch.loliland;

import android.content.Context;
import android.content.SharedPreferences;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class LoliLandAuth {
    private static final String PREF_FILE = "loliland_auth";
    public static final String KEY_ACCESS_ID = "access_id";
    public static final String KEY_ACCESS_TOKEN = "access_token";
    public static final String KEY_LOGIN = "login";
    public static final String KEY_PASSWORD = "password";

    private LoliLandAuth() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn(Context ctx) {
        return prefs(ctx).contains(KEY_ACCESS_TOKEN)
            && prefs(ctx).contains(KEY_ACCESS_ID);
    }

    public static LoliLandApi.AuthResult loadCreds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(KEY_ACCESS_TOKEN)) return null;
        LoliLandApi.AuthResult r = new LoliLandApi.AuthResult();
        r.accessId = p.getString(KEY_ACCESS_ID, null);
        r.accessToken = p.getString(KEY_ACCESS_TOKEN, null);
        r.login = p.getString(KEY_LOGIN, null);
        return r;
    }

    public static void save(Context ctx, LoliLandApi.AuthResult creds, String password) {
        prefs(ctx).edit()
            .putString(KEY_ACCESS_ID, creds.accessId)
            .putString(KEY_ACCESS_TOKEN, creds.accessToken)
            .putString(KEY_LOGIN, creds.login)
            .putString(KEY_PASSWORD, password == null ? "" : password)
            .apply();
    }

    /** Rewrites -Dloliland.auth.* flags in every loliland-* build profile and updates the game account. */
    public static void applyToBuilds(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String accessId = p.getString(KEY_ACCESS_ID, "");
        String accessToken = p.getString(KEY_ACCESS_TOKEN, "");
        String login = p.getString(KEY_LOGIN, "");
        String password = p.getString(KEY_PASSWORD, "").replace(" ", "");
        if (accessId.isEmpty() || accessToken.isEmpty() || login.isEmpty()) return;

        try {
            net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.load();
            java.util.Map<String, net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile> profiles =
                net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.mainProfileJson.profiles;
            boolean changed = false;
            for (net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile mp : profiles.values()) {
                if (mp.lastVersionId == null || !mp.lastVersionId.startsWith("loliland-")) continue;
                String ja = mp.javaArgs == null ? "" : mp.javaArgs;
                String patched = patchArgs(ja, accessId, accessToken, password);
                if (!patched.equals(ja)) {
                    mp.javaArgs = patched;
                    changed = true;
                }
            }
            if (changed) net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.write();
        } catch (Exception ignored) {}

        try {
            MinecraftAccount acc = new MinecraftAccount();
            acc.username = login;
            acc.profileId = accessId.replace("-", "");
            acc.accessToken = "0";
            acc.save();
            PojavProfile.setCurrentProfile(ctx, login);
        } catch (Exception ignored) {}
    }

    private static String patchArgs(String ja, String accessId, String accessToken, String password) {
        StringBuilder sb = new StringBuilder();
        boolean hasPwdFlag = false;
        for (String tok : ja.split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.startsWith("-Dloliland.auth.uuid=")) {
                sb.append("-Dloliland.auth.uuid=").append(accessId).append(' ');
            } else if (tok.startsWith("-Dloliland.auth.token=")) {
                sb.append("-Dloliland.auth.token=").append(accessToken).append(' ');
            } else if (tok.startsWith("-Dloliland.token=")) {
                hasPwdFlag = true;
                sb.append("-Dloliland.token=").append(password).append(' ');
            } else {
                sb.append(tok.replace(" ", "")).append(' ');
            }
        }
        if (!password.isEmpty() && !hasPwdFlag) {
            sb.append("-Dloliland.token=").append(password);
        }
        return sb.toString().trim();
    }

    public static String readText(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void writeText(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes(StandardCharsets.UTF_8));
        fos.close();
    }
}
