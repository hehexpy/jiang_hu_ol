package com.jianghuol.helper;

import java.io.*;
import java.net.*;

final class SupportTransport {
    static byte[] read(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("响应超过大小限制");out.write(buf,0,n);}
        return out.toByteArray();
    }
    static HttpURLConnection open(String url)throws IOException {
        SupportPolicy.https(url);
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","JiangHuHelper-Android");
        return c;
    }
    static HttpURLConnection github(String url)throws IOException {
        for(int i=0;i<6;i++){
            URI uri=SupportPolicy.https(url);
            if(!SupportPolicy.githubHost(uri.getHost()))throw new IOException("更新下载跳转到未授权站点");
            HttpURLConnection c=open(url);int status=c.getResponseCode();
            if(status==301||status==302||status==303||status==307||status==308){
                String location=c.getHeaderField("Location");c.disconnect();
                if(location==null)throw new IOException("更新跳转缺少地址");
                url=uri.resolve(location).toString();continue;
            }
            if(status!=200){c.disconnect();throw new IOException(status==404?"未找到公开正式版本，请检查仓库或发布 Release":"GitHub 请求失败（HTTP "+status+"）");}
            return c;
        }
        throw new IOException("更新跳转次数过多");
    }
    static String githubText(String url,int limit)throws IOException {
        HttpURLConnection c=github(url);
        try(InputStream in=c.getInputStream()){return new String(read(in,limit),java.nio.charset.StandardCharsets.UTF_8);}
        finally{c.disconnect();}
    }
    static String upload(File report,SupportConfig config)throws Exception {
        String endpoint=config.get("endpoint"),token=config.get("upload_token");
        if(endpoint.isEmpty()||token.isEmpty())throw new IOException("请先配置日志服务器和上传凭证");
        if(report.length()>SupportPolicy.REPORT_LIMIT)throw new IOException("日志包过大");
        HttpURLConnection c=open(endpoint);
        try{
            c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(report.length());
            c.setRequestProperty("Content-Type","application/zip");
            c.setRequestProperty("Authorization","Bearer "+token);
            try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(report)){
                byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            int status=c.getResponseCode();
            if(status!=201)throw new IOException("日志上传失败（HTTP "+status+"），可重试");
            try(InputStream in=c.getInputStream()){
                org.json.JSONObject result=new org.json.JSONObject(new String(read(in,32768),java.nio.charset.StandardCharsets.UTF_8));
                String id=result.getString("report_id");
                if(!id.matches("[A-Za-z0-9_-]{8,80}"))throw new IOException("服务器未返回有效报告编号");
                return id;
            }
        }finally{c.disconnect();}
    }
}
