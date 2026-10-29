package com.jianghuol.helper;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 角色 ID 档案：**永远从 assets/char_ids.json 读**。
 *
 * 这是电脑端 analysis/t046/char_ids.json 的手机副本（2026-10-20 逐个点头像采集）。
 * 手机闭环不给用户改 ID —— 猜错 ID 会把陌生人加成好友、把邀请发给错的人
 * （WORKLOG 17:45「发错人」那次就是这个坑）。所以 App 只提供查询，不提供编辑。
 */
public final class Archive {

    private Archive() {
    }

    /** account → charId；文件缺失/损坏时返回空表（调用方据此中止，而不是猜）。 */
    public static Map<String, String> idsByAccount(Context ctx) {
        Map<String, String> out = new HashMap<>();
        try {
            InputStream is = ctx.getAssets().open("char_ids.json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject root = new JSONObject(sb.toString());
            JSONObject chars = root.optJSONObject("chars");
            if (chars == null) return out;
            for (java.util.Iterator<String> it = chars.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject row = chars.optJSONObject(pkg);
                if (row == null) continue;
                String account = row.optString("account", "");
                String id = row.optString("char_id", "");
                if (!account.isEmpty() && !id.isEmpty()) out.put(account, id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
