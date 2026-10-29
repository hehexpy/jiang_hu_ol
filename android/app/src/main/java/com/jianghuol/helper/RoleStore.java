package com.jianghuol.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 角色配置：哪个客户端当队长、哪些当队员。
 *
 * 设计要点：
 *  1. 真源在 App 内（SharedPreferences），用户在「队伍」页选；
 *  2. 同时导出一份到 /sdcard/Android/data/<pkg>/files/roles.json，
 *     电脑端脚本用 adb 直接读，不需要 root、不需要 run-as；
 *  3. 只认 com.good.* 的客户端，避免把系统应用/助手自己列进来。
 */
public class RoleStore {

    public static final String GAME_PREFIX = "com.good.";

    private static final String PREF = "t046_roles";
    private static final String K_LEADER = "leader_pkg";
    private static final String K_MEMBERS = "member_pkgs";
    private static final String EXPORT_NAME = "roles.json";

    private final Context ctx;

    public RoleStore(Context ctx) {
        this.ctx = ctx;
    }

    private SharedPreferences sp() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String leader() {
        return sp().getString(K_LEADER, "");
    }

    public void setLeader(String pkg) {
        sp().edit().putString(K_LEADER, pkg == null ? "" : pkg).apply();
        export();
    }

    public List<String> members() {
        List<String> out = new ArrayList<>();
        String raw = sp().getString(K_MEMBERS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setMembers(List<String> pkgs) {
        JSONArray arr = new JSONArray();
        if (pkgs != null) for (String p : pkgs) if (p != null && !p.isEmpty()) arr.put(p);
        sp().edit().putString(K_MEMBERS, arr.toString()).apply();
        export();
    }

    /** 应用名，拿不到就退回包名。 */
    public String labelOf(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs == null ? pkg : cs.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** 已安装的游戏客户端（com.good.*），按应用名排序。 */
    public List<String[]> gameApps() {
        List<String[]> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> all = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : all) {
            if (ai.packageName != null && ai.packageName.startsWith(GAME_PREFIX)) {
                CharSequence cs = pm.getApplicationLabel(ai);
                out.add(new String[]{ai.packageName, cs == null ? ai.packageName : cs.toString()});
            }
        }
        Collections.sort(out, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[1].compareTo(b[1]);
            }
        });
        return out;
    }

    /** 导出给电脑端读：/sdcard/Android/data/<pkg>/files/roles.json */
    public File export() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, EXPORT_NAME);
        try {
            JSONObject root = new JSONObject();
            root.put("leader_pkg", leader());
            root.put("leader_label", leader().isEmpty() ? "" : labelOf(leader()));
            JSONArray ms = new JSONArray();
            for (String p : members()) {
                JSONObject m = new JSONObject();
                m.put("pkg", p);
                m.put("label", labelOf(p));
                ms.put(m);
            }
            root.put("members", ms);
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
