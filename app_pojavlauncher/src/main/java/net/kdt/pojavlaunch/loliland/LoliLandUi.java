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
        if (!LoliLandAuth.isLoggedIn(ctx)) {
            toast(ctx, "Сначала войдите в аккаунт LoliLand");
            showLogin(activity);
            return;
        }
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
                            .setPositiveButton("Скачать", (d2, w2) ->
                                    LoliLandClients.downloadAsync(activity, picked))
                            .setNegativeButton("Отмена", null)
                            .show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    /* ==================== MISC ==================== */

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static void postToast(Context ctx, String msg) {
        LoliLandBuildImporter.notify(ctx, msg);
    }
}
