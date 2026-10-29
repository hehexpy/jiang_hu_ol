package com.jianghuol.helper;

import android.content.Context;
import android.content.pm.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

final class GitHubUpdater {
    static final class Release {
        final int code;final String version,notes,url,sha;final long size;
        Release(JSONObject m,String url,String notes)throws Exception{
            code=m.getInt("versionCode");version=m.getString("versionName");sha=m.getString("sha256");size=m.getLong("size");
            this.url=url;this.notes=notes;
            if(code<1||size<1||size>SupportPolicy.APK_LIMIT||!sha.matches("[a-fA-F0-9]{64}"))throw new IOException("更新清单不合法");
        }
    }
    static File ready(Context context){return new File(context.getCacheDir(),"updates/update.apk");}
    static Release check(String repository)throws Exception {
        String repo=SupportPolicy.repo(repository);
        JSONObject release=new JSONObject(SupportTransport.githubText("https://api.github.com/repos/"+repo+"/releases/latest",1024*1024));
        if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IOException("尚无正式版本");
        JSONArray assets=release.getJSONArray("assets");JSONObject manifest=find(assets,"update.json");
        if(manifest==null)throw new IOException("Release 缺少 update.json 更新清单");
        JSONObject m=new JSONObject(SupportTransport.githubText(assetUrl(repo,manifest),32768));
        JSONObject apk=find(assets,m.getString("apk"));
        if(apk==null||!m.getString("apk").endsWith(".apk"))throw new IOException("Release 缺少安装包");
        Release result=new Release(m,assetUrl(repo,apk),release.optString("body",""));
        if(apk.getLong("size")!=result.size)throw new IOException("安装包大小与更新清单不一致");
        return result;
    }
    private static JSONObject find(JSONArray assets,String name)throws Exception{
        JSONObject found=null;
        for(int i=0;i<assets.length();i++)if(name.equals(assets.getJSONObject(i).optString("name"))){
            if(found!=null)throw new IOException("Release 附件重名");found=assets.getJSONObject(i);
        }
        return found;
    }
    private static String assetUrl(String repo,JSONObject asset)throws Exception{
        String url=asset.getString("browser_download_url");SupportPolicy.https(url);
        if(!url.startsWith("https://github.com/"+repo+"/releases/download/"))throw new IOException("更新附件不属于此仓库");
        return url;
    }
    static File download(Context context,Release release)throws Exception{
        File target=ready(context),dir=target.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建更新目录");
        File part=new File(dir,"update.part");HttpURLConnection c=SupportTransport.github(release.url);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)){
                byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1){size+=n;if(size>release.size)throw new IOException("安装包超过声明大小");out.write(b,0,n);digest.update(b,0,n);}
            }
            if(size!=release.size||!hex(digest.digest()).equalsIgnoreCase(release.sha))throw new IOException("安装包校验失败，请重试");
            verify(context,part,release.code);
            if(!part.renameTo(target))throw new IOException("更新保存失败");
            return target;
        }finally{c.disconnect();part.delete();}
    }
    @SuppressWarnings("deprecation")
    static void verify(Context context,File apk,int expectedCode)throws Exception{
        PackageManager pm=context.getPackageManager();
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
        PackageInfo incoming=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName))throw new IOException("安装包包名不符");
        if(incoming.versionCode<=installed.versionCode||incoming.versionCode!=expectedCode)throw new IOException("更新版本号不符或不是新版");
        if(installed.signatures==null||incoming.signatures==null||installed.signatures.length==0
            ||!new HashSet<>(Arrays.asList(installed.signatures)).equals(new HashSet<>(Arrays.asList(incoming.signatures))))
            throw new IOException("安装包签名与当前应用不一致，拒绝安装");
    }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte value:b)s.append(String.format(java.util.Locale.US,"%02x",value&255));return s.toString();}
}
