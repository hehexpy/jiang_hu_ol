package com.jianghuol.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 账号与密码：每个游戏客户端（com.good.*）对应一个游戏账号 + 密码。
 *
 * 设计要点（与 RoleStore 同一套思路）：
 *  1. 真源在 App 内（SharedPreferences），用户在「队伍」页直接输入，改完即存；
 *  2. 同时导出一份到 /sdcard/Android/data/<pkg>/files/accounts.json，
 *     电脑端 team_up.py 用 adb 直接读 —— 这样「一键上号」的账号密码来源
 *     就是这个 App，不需要再去改电脑上的 accounts.json；
 *  3. 密码只落在本机（App 私有目录 + 应用专属外部目录），不进聊天记录、不上传。
 */
public class AccountStore {

    private static final String PREF = "t046_accounts";
    private static final String EXPORT_NAME = "accounts.json";

    private final Context ctx;

    public AccountStore(Context ctx) {
        this.ctx = ctx;
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读某个客户端的账号密码，返回 [account, password]；没填过返回 ["",""]。 */
    public String[] get(String pkg) {
        String raw = sp().getString(pkg, "{}");
        try {
            JSONObject o = new JSONObject(raw);
            return new String[]{o.optString("account", ""), o.optString("password", "")};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    /** 保存并立即导出。account/password 传 null 表示不改动那一项。 */
    public void set(String pkg, String account, String password) {
        try {
            JSONObject o = new JSONObject(sp().getString(pkg, "{}"));
            if (account != null) o.put("account", account.trim());
            if (password != null) o.put("password", password);
            sp().edit().putString(pkg, o.toString()).apply();
            export();
        } catch (Exception ignored) {
        }
    }

    /** 导出给电脑端读：/sdcard/Android/data/<pkg>/files/accounts.json */
    public File export() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, EXPORT_NAME);
        try {
            JSONObject root = new JSONObject();
            Map<String, ?> all = sp().getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                try {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                    String a = o.optString("account", "");
                    String p = o.optString("password", "");
                    if (a.isEmpty() && p.isEmpty()) continue;
                    JSONObject row = new JSONObject();
                    row.put("account", a);
                    row.put("password", p);
                    root.put(e.getKey(), row);
                } catch (Exception ignored) {
                }
            }
            root.put("updated_at", System.currentTimeMillis());
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
