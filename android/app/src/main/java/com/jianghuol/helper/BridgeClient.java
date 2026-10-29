package com.jianghuol.helper;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** 仅连接固定回环调试服务，接口不接受任意命令。 */
public final class BridgeClient {
    private final String token;
    public BridgeClient(String token) { this.token = token; }
    public JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:18739" + path).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(8000);
        c.setRequestMethod(method); c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream raw = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (raw == null) throw new java.io.IOException("服务没有返回内容");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = raw) { byte[] b = new byte[4096]; int n; while ((n=in.read(b))!=-1) bytes.write(b,0,n); }
            JSONObject result = new JSONObject(bytes.toString("UTF-8"));
            if (code >= 400) throw new java.io.IOException(result.optString("message", "请求失败"));
            return result;
        } finally { c.disconnect(); }
    }
}
