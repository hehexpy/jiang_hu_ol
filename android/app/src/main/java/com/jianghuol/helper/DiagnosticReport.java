package com.jianghuol.helper;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 仅收集排障白名单文本，不扫描账号文件、偏好目录或截图。 */
final class DiagnosticReport {
    static File pending(Context ctx){return new File(ctx.getCacheDir(),"support/pending.zip");}
    static synchronized File create(Context ctx,String note)throws Exception {
        File target=pending(ctx);File dir=target.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建日志包目录");
        List<String> secrets=new ArrayList<>();
        for(Object raw:ctx.getSharedPreferences("t046_accounts",0).getAll().values()){
            JSONObject account=new JSONObject(String.valueOf(raw));
            secrets.add(account.optString("account"));secrets.add(account.optString("password"));
        }
        secrets.add(new SupportConfig(ctx).get("upload_token"));
        secrets.add(ctx.getSharedPreferences("MainActivity",0).getString("bridge_token",""));
        secrets.sort((a,b)->Integer.compare(b.length(),a.length()));
        android.content.pm.PackageInfo pi=ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),0);
        ScriptStatus.Snapshot status=ScriptStatus.snapshot();
        JSONObject meta=new JSONObject().put("format",1).put("created_at",System.currentTimeMillis())
            .put("version_name",pi.versionName).put("version_code",pi.versionCode)
            .put("android_api",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("task",status.task).put("state",status.state.name()).put("detail",status.detail)
            .put("feedback",note).put("screenshots_included",false);
        File partial=new File(dir,"pending.tmp");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(partial))){
            put(zip,"report.json",meta.toString(2),secrets);
            put(zip,"logs/memory.log",LogBus.get().dumpText(),secrets);
            File base=ctx.getExternalFilesDir(null);if(base==null)base=ctx.getFilesDir();
            addFile(zip,"logs/app.log",new File(base,"logs/app.log"),512*1024,secrets);
            File[] runs=new File(base,"farm").listFiles(File::isDirectory);
            if(runs!=null){
                Arrays.sort(runs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
                for(int i=0;i<Math.min(3,runs.length);i++){
                    File run=runs[i];if(!run.getName().matches("[A-Za-z0-9_-]+"))continue;
                    for(String name:new String[]{"state.json","timing.jsonl","actions.jsonl"})
                        addFile(zip,"farm/"+run.getName()+"/"+name,new File(run,name),128*1024,secrets);
                }
            }
        }catch(Exception e){partial.delete();throw e;}
        if(partial.length()>SupportPolicy.REPORT_LIMIT){partial.delete();throw new IOException("诊断包超过大小限制");}
        // 不删除上一份未发送报告，直到新包完整生成。
        if(!partial.renameTo(target))throw new IOException("无法保存诊断包");
        return target;
    }
    private static void addFile(ZipOutputStream zip,String name,File file,int limit,List<String> secrets)throws Exception {
        if(!file.isFile())return;
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            long length=in.length(),start=Math.max(0,length-limit);in.seek(start);
            byte[] bytes=new byte[(int)(length-start)];in.readFully(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(start>0){int newline=text.indexOf('\n');text=newline>=0?text.substring(newline+1):"[日志长行已截断]";}
            put(zip,name,text,secrets);
        }
    }
    private static void put(ZipOutputStream zip,String name,String text,List<String> secrets)throws Exception {
        if(!SupportPolicy.reportEntry(name))throw new IOException("诊断条目不在白名单");
        zip.putNextEntry(new ZipEntry(name));
        String clean;
        if(name.endsWith(".json")){
            try{clean=scrub(new JSONObject(text),secrets).toString(2);}
            catch(org.json.JSONException e){if(name.equals("report.json"))throw e;clean=SupportPolicy.redact(text,secrets);}
        }else clean=SupportPolicy.redact(text,secrets);
        byte[] bytes=clean.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>256*1024){
            if(name.equals("report.json"))throw new IOException("问题描述过长");
            clean=new String(bytes,bytes.length-256*1024,256*1024,StandardCharsets.UTF_8);
            int line=clean.indexOf('\n');clean="[较早内容已截断]\n"+(line>=0?clean.substring(line+1):"");
            bytes=clean.getBytes(StandardCharsets.UTF_8);
        }
        zip.write(bytes);zip.closeEntry();
    }
    private static JSONObject scrub(JSONObject object,List<String> secrets)throws Exception{
        for(java.util.Iterator<String> keys=object.keys();keys.hasNext();){
            String key=keys.next();Object value=object.get(key);
            if(key.matches("(?i).*(password|passwd|token|secret|密码).*$"))object.put(key,"[已脱敏]");
            else if(value instanceof String)object.put(key,SupportPolicy.redact((String)value,secrets));
            else if(value instanceof JSONObject)scrub((JSONObject)value,secrets);
            else if(value instanceof org.json.JSONArray)scrubArray((org.json.JSONArray)value,secrets);
        }return object;
    }
    private static void scrubArray(org.json.JSONArray array,List<String> secrets)throws Exception{
        for(int i=0;i<array.length();i++){
            Object value=array.get(i);
            if(value instanceof String)array.put(i,SupportPolicy.redact((String)value,secrets));
            else if(value instanceof JSONObject)scrub((JSONObject)value,secrets);
            else if(value instanceof org.json.JSONArray)scrubArray((org.json.JSONArray)value,secrets);
        }
    }
}
