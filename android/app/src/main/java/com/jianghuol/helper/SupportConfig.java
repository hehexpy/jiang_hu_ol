package com.jianghuol.helper;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

final class SupportConfig {
    final SharedPreferences prefs;
    private JSONObject defaults = new JSONObject();
    SupportConfig(Context context) {
        prefs=context.getSharedPreferences("remote_support",Context.MODE_PRIVATE);
        try (java.io.InputStream in=context.getAssets().open("support_config.json")) {
            defaults=new JSONObject(new String(SupportTransport.read(in,32768),StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
    }
    String get(String name) { return prefs.getString(name,"upload_token".equals(name)?BuildConfig.SUPPORT_UPLOAD_TOKEN:defaults.optString(name,"")); }
    void save(String endpoint,String token,String repo) {
        if(!endpoint.isEmpty()) {
            if(SupportPolicy.https(endpoint).getRawQuery()!=null)throw new IllegalArgumentException("上传地址不能携带查询参数");
        }
        if(token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException("上传凭证格式错误");
        if(!repo.isEmpty())SupportPolicy.repo(repo);
        prefs.edit().putString("endpoint",endpoint).putString("upload_token",token).putString("github_repo",repo).apply();
    }
}
