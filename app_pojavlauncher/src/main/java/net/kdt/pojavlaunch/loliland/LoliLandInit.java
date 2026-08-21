package net.kdt.pojavlaunch.loliland;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import net.kdt.pojavlaunch.PojavApplication;

/** Entry point called from LauncherActivity: refreshes auth flags and prompts login once. */
public final class LoliLandInit {
    private static boolean sPromptedThisSession = false;

    private LoliLandInit() {}

    public static void onStart(Context context) {
        Context app = context.getApplicationContext();
        if (LoliLandAuth.isLoggedIn(app)) {
            PojavApplication.sExecutorService.execute(() -> LoliLandAuth.applyToBuilds(app));
            return;
        }
        if (sPromptedThisSession) return;
        if (!(context instanceof Activity)) return;
        sPromptedThisSession = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Activity a = (Activity) context;
            if (a.isFinishing() || a.isDestroyed()) return;
            LoliLandUi.showLogin(a);
        }, 800);
    }
}
