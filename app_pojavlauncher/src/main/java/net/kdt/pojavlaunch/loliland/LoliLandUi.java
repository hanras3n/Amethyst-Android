package net.kdt.pojavlaunch.loliland;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.kdt.pojavlaunch.LoliLandBuildImporter;
import net.kdt.pojavlaunch.PojavApplication;

public final class LoliLandUi {
    private LoliLandUi() {}

    /* ==================== LOGIN ==================== */

    public static void showLogin(android.app.Activity activity) {
        Context ctx = activity;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText loginField = new EditText(ctx);
        loginField.setHint("Логин");
        loginField.setSingleLine(true);
        box.addView(loginField);

        final EditText passwordField = new EditText(ctx);
        passwordField.setHint("Пароль");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setSingleLine(true);
        box.addView(passwordField);

        final EditText emailField = new EditText(ctx);
        emailField.setHint("Email (только для регистрации)");
        emailField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setSingleLine(true);
        emailField.setVisibility(View.GONE);
        box.addView(emailField);

        final CheckBox registerBox = new CheckBox(ctx);
        registerBox.setText("Создать новый аккаунт");
        registerBox.setOnCheckedChangeListener((b, checked) ->
                emailField.setVisibility(checked ? View.VISIBLE : View.GONE));
        box.addView(registerBox);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("LoliLand — вход")
                .setView(box)
                .setPositiveButton("Войти", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setOnClickListener(v -> {
                String login = loginField.getText().toString().trim();
                String password = passwordField.getText().toString();
                String email = emailField.getText().toString().trim();
                boolean register = registerBox.isChecked();
                if (login.isEmpty() || password.isEmpty()) {
                    toast(ctx, "Введите логин и пароль");
                    return;
                }
                if (register && email.isEmpty()) {
                    toast(ctx, "Введите email для регистрации");
                    return;
                }
                ok.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                toast(ctx, "LoliLand: подключение...");
                PojavApplication.sExecutorService.execute(() -> {
                    try {
                        LoliLandApi.AuthResult result = register
                                ? LoliLandApi.registerOrLogin(login, email, password)
                                : LoliLandApi.login(login, password);
                        LoliLandAuth.save(ctx, result, password);
                        LoliLandAuth.applyToBuilds(ctx);
                        postToast(ctx, "LoliLand: вы вошли как " + result.login);
                        activity.runOnUiThread(dialog::dismiss);
                    } catch (LoliLandApi.ApiException e) {
                        if (e.errorCode == LoliLandApi.ERROR_2FA) {
                            postToast(ctx, "Требуется двухфакторная аутентификация. " +
                                    "Войдите на сайте и отключите 2FA либо используйте код входа лаунчера ПК.");
                        } else {
                            postToast(ctx, "Ошибка входа: " + e.getMessage());
                        }
                        activity.runOnUiThread(() -> ok.setEnabled(true));
                        activity.runOnUiThread(() ->
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true));
                    } catch (Exception e) {
                        postToast(ctx, "Нет связи с сервером: " + e.getMessage());
                        activity.runOnUiThread(() -> ok.setEnabled(true));
                        activity.runOnUiThread(() ->
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true));
                    }
                });
            });
        });
        dialog.show();
    }

    /* ==================== CLIENTS ==================== */

    public static void showClients(android.app.Activity activity) {
        Context ctx = activity;
        toast(ctx, "LoliLand: загрузка списка сборок...");
        PojavApplication.sExecutorService.execute(() -> {
            try {
                java.util.List<LoliLandClients.ClientEntry> clients =
                        LoliLandClients.fetchClients(ctx);
                if (clients.isEmpty()) {
                    postToast(ctx, "Список сборок пуст");
                    return;
                }
                activity.runOnUiThread(() -> pickClient(activity, clients));
            } catch (Throwable t) {
                postToast(ctx, "Не удалось получить сборки: " + t.getMessage());
            }
        });
    }

    private static void pickClient(android.app.Activity activity,
            java.util.List<LoliLandClients.ClientEntry> clients) {
        String[] items = new String[clients.size()];
        for (int i = 0; i < clients.size(); i++) items[i] = clients.get(i).toString();
        new AlertDialog.Builder(activity)
                .setTitle("Сборки LoliLand")
                .setItems(items, (d, which) -> {
                    LoliLandClients.ClientEntry picked = clients.get(which);
                    new AlertDialog.Builder(activity)
                            .setTitle(picked.displayName)
                            .setMessage("Скачать сборку (" + picked.size + ")?\n" +
                                    "Файлы проверяются по SHA-256, докачка поддерживается.")
                            .setPositiveButton("Скачать", (d2, w2) -> {
                                if (!LoliLandAuth.isLoggedIn(activity)) {
                                    toast(activity, "Для скачивания войдите в аккаунт");
                                    showLogin(activity);
                                    return;
                                }
                                startDownloadWithProgress(activity, picked);
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void startDownloadWithProgress(android.app.Activity activity,
            LoliLandClients.ClientEntry entry) {
        android.content.Context ctx = activity;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.TextView status = new android.widget.TextView(ctx);
        status.setText("Подготовка...");
        box.addView(status);

        final android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(10000);
        bar.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        bar.setLayoutParams(lp);
        box.addView(bar);

        final long[] totals = {0, 0}; // bytes, files
        final long[] lastUi = {0};

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Скачивание: " + entry.displayName)
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setCancelable(false)
                .create();

        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

        LoliLandClients.DownloadListener listener = new LoliLandClients.DownloadListener() {
            @Override public void onTotals(long totalBytes, int totalFiles) {
                totals[0] = totalBytes;
                totals[1] = totalFiles;
            }
            @Override public void onProgress(long bytesDone, int filesDone) {
                long now = System.currentTimeMillis();
                synchronized (lastUi) {
                    if (now - lastUi[0] < 200) return;
                    lastUi[0] = now;
                }
                activity.runOnUiThread(() -> {
                    long tb = totals[0];
                    int pct = tb > 0 ? (int) Math.min(10000L, bytesDone * 10000L / tb) : 0;
                    bar.setProgress(pct);
                    status.setText(formatMb(bytesDone) + " из " + formatMb(tb)
                            + "  ·  файлы: " + filesDone + "/" + totals[1]
                            + "  ·  " + (pct / 100) + "%");
                });
            }
            @Override public void onDone() {
                if (!finished.compareAndSet(false, true)) return;
                activity.runOnUiThread(() -> {
                    try { dialog.dismiss(); } catch (Exception ignored) {}
                    toast(activity, "LoliLand: \"" + entry.displayName + "\" установлена!");
                });
            }
            @Override public void onError() {
                finished.set(true);
                activity.runOnUiThread(() -> {
                    try { dialog.dismiss(); } catch (Exception ignored) {}
                });
            }
        };

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    v.setEnabled(false);
                    toast(activity, "Отмена...");
                    LoliLandClients.cancelCurrentDownload();
                }));

        dialog.show();
        LoliLandClients.downloadAsync(activity, entry, listener);
    }

    private static String formatMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f МБ", bytes / 1048576.0);
    }

    /* ==================== MISC ==================== */

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static void postToast(Context ctx, String msg) {
        LoliLandBuildImporter.notify(ctx, msg);
    }
}
