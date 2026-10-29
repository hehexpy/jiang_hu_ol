package com.jianghuol.helper;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.io.File;

/** 诊断与更新独立入口；所有网络/打包工作均在后台，不占用游戏引擎线程。 */
final class SupportCenter {
    private final Activity activity;
    private final SupportConfig config;
    private static final java.util.concurrent.atomic.AtomicBoolean WORKING=new java.util.concurrent.atomic.AtomicBoolean();
    private static final UpdateNotice AUTO=new UpdateNotice();
    private static final class AvailableUpdate {
        final String repository;final GitHubUpdater.Release release;
        AvailableUpdate(String repository,GitHubUpdater.Release release){this.repository=repository;this.release=release;}
    }
    private static volatile AvailableUpdate automaticUpdate;
    private final android.os.Handler foreground=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean resumed;
    private AlertDialog automaticDialog;
    private final Runnable automaticTick=new Runnable(){public void run(){
        if(!resumed)return;
        if(canNotify()){
            showAutomaticRelease();
            if(automaticDialog==null)checkAutomatically();
        }
        foreground.postDelayed(this,30000);
    }};
    SupportCenter(Activity activity){this.activity=activity;config=new SupportConfig(activity);}
    void onResume(){resumed=true;foreground.removeCallbacks(automaticTick);foreground.postDelayed(automaticTick,1200);}
    void onPause(){
        resumed=false;foreground.removeCallbacks(automaticTick);
        if(automaticDialog!=null){automaticDialog.dismiss();automaticDialog=null;}
    }
    private boolean canNotify(){return resumed&&!activity.isDestroyed()&&!activity.isFinishing()
        &&activity.hasWindowFocus()&&!RunGate.busy()&&!WORKING.get();}
    private void checkAutomatically(){
        String repo=config.get("github_repo");long now=android.os.SystemClock.elapsedRealtime();
        if(!AUTO.due(repo,now)||!WORKING.compareAndSet(false,true))return;
        AUTO.attempted(repo,now);
        new Thread(()->{
            try{
                GitHubUpdater.Release release=GitHubUpdater.check(repo);
                if(repo.equals(config.get("github_repo")))automaticUpdate=new AvailableUpdate(repo,release);
                AUTO.completed(repo,true);
            }catch(Exception ignored){AUTO.completed(repo,false);}
            finally{
                WORKING.set(false);
                foreground.post(()->{if(canNotify())showAutomaticRelease();});
            }
        },"automatic-update-check").start();
    }
    private void showAutomaticRelease(){
        AvailableUpdate available=automaticUpdate;String repo=config.get("github_repo");
        if(!canNotify()||automaticDialog!=null||available==null||!repo.equals(available.repository))return;
        GitHubUpdater.Release release=available.release;
        try{
            int installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
            if(!AUTO.shouldPrompt(repo,release.code,installed))return;
            automaticDialog=new AlertDialog.Builder(UiStyle.dialog(activity)).setTitle("发现新版 "+release.version)
                .setMessage(releaseMessage(release)).setPositiveButton("下载更新",(d,w)->download(release))
                .setNegativeButton("稍后",null).create();
            automaticDialog.setOnDismissListener(d->automaticDialog=null);
            automaticDialog.show();UiStyle.polish(automaticDialog);AUTO.shown(release.code);
        }catch(Exception ignored){if(automaticDialog!=null){automaticDialog.dismiss();automaticDialog=null;}}
    }
    private String releaseMessage(GitHubUpdater.Release release){
        String notes=release.notes.length()>2000?release.notes.substring(0,2000):release.notes;
        return String.format(java.util.Locale.CHINA,"安装包 %.1f MB\n\n%s",release.size/1048576.0,notes);
    }
    private void message(String text){if(!activity.isDestroyed())new AlertDialog.Builder(UiStyle.dialog(activity)).setMessage(text).setPositiveButton("知道了",null).show();}
    private void task(String text,Work work){
        if(RunGate.busy()){message("请等脚本停止后再上传日志或更新");return;}
        if(!WORKING.compareAndSet(false,true)){message("正在处理，请稍候");return;}
        Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
        new Thread(()->{
            try{work.run();}catch(Exception e){activity.runOnUiThread(()->message("操作未完成："+safe(e)+"\n上传失败的诊断包会保留，可重试。"));}
            finally{WORKING.set(false);}
        },"remote-support").start();
    }
    private String safe(Exception e){
        // 不显示底层网络异常中的URL、凭据或请求头。
        if(e instanceof java.net.SocketTimeoutException)return "网络超时，请检查网络后重试";
        if(e instanceof javax.net.ssl.SSLException)return "HTTPS 证书验证失败";
        if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "无法连接服务器，请检查网络";
        String s=e.getMessage();
        if(s==null||s.contains("http")||s.contains("Bearer"))return e.getClass().getSimpleName();
        return SupportPolicy.redact(s,java.util.Collections.singletonList(config.get("upload_token")));
    }
    void show(){
        String previous=config.prefs.getString("last_report_id","");
        String[] items={"上传问题日志","重试上次上传","检查新版本","安装已下载更新","查看最近报告编号","服务设置"};
        UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(activity)).setTitle("帮助与更新 · "+version())
            .setItems(items,(d,n)->{if(n==0)feedback();else if(n==1)upload(false,"");else if(n==2)check();else if(n==3)install();else if(n==4)message(previous.isEmpty()?"还没有上传记录":"最近报告："+previous);else settings();})
            .setNegativeButton("关闭",null).show());
    }
    private String version(){try{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void feedback(){
        EditText input=new EditText(UiStyle.dialog(activity));input.setHint("哪里出错了？可填写出错时间和操作步骤");input.setMinLines(3);input.setMaxLines(6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1500)});
        UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(activity)).setTitle("上传问题日志")
            .setMessage("上传最近运行日志、任务状态和设备型号；账号密码会过滤，不含截图。请勿在描述中填写密码。")
            .setView(input).setPositiveButton("上传",(d,w)->upload(true,input.getText().toString())).setNegativeButton("取消",null).show());
    }
    private void upload(boolean create,String note){
        task("正在整理并上传日志…",()->{
            if(config.get("endpoint").isEmpty()||config.get("upload_token").isEmpty())throw new java.io.IOException("尚未配置日志服务器，请联系维护者");
            File report=create?DiagnosticReport.create(activity.getApplicationContext(),note):DiagnosticReport.pending(activity);
            if(!report.isFile())throw new java.io.IOException("没有待重试的日志包，请先上传问题日志");
            String id=SupportTransport.upload(report,config);
            config.prefs.edit().putString("last_report_id",id).apply();report.delete();
            activity.runOnUiThread(()->{
                if(activity.isDestroyed())return;
                new AlertDialog.Builder(UiStyle.dialog(activity)).setTitle("上传成功")
                    .setMessage("报告编号："+id+"\n把编号发给维护者即可定位本次日志。")
                    .setPositiveButton("复制编号",(d,w)->{android.content.ClipboardManager clip=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);clip.setPrimaryClip(ClipData.newPlainText("报告编号",id));})
                    .setNegativeButton("关闭",null).show();
            });
        });
    }
    private void check(){
        task("正在检查新版本…",()->{
            if(config.get("github_repo").isEmpty())throw new java.io.IOException("尚未配置 GitHub 更新仓库");
            GitHubUpdater.Release release=GitHubUpdater.check(config.get("github_repo"));
            int current=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
            activity.runOnUiThread(()->{
                if(activity.isDestroyed())return;
                if(release.code<=current){message("当前已是最新版本 · "+version());return;}
                new AlertDialog.Builder(UiStyle.dialog(activity)).setTitle("发现新版 "+release.version)
                    .setMessage(releaseMessage(release))
                    .setPositiveButton("下载更新",(d,w)->download(release)).setNegativeButton("稍后",null).show();
            });
        });
    }
    private void download(GitHubUpdater.Release release){task("正在下载更新，完成后将打开安装…",()->{
        GitHubUpdater.download(activity.getApplicationContext(),release);
        config.prefs.edit().putInt("downloaded_code",release.code).apply();
        activity.runOnUiThread(this::install);
    });}
    private void install(){
        if(RunGate.busy()){message("请停止脚本后再安装更新");return;}
        File file=GitHubUpdater.ready(activity);
        if(!file.isFile()){message("还没有已下载的更新，请先检查新版本");return;}
        try{
            GitHubUpdater.verify(activity,file,config.prefs.getInt("downloaded_code",0));
            if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
                new AlertDialog.Builder(UiStyle.dialog(activity)).setMessage("请允许江湖助手安装应用。返回后再次点“安装已下载更新”。")
                    .setPositiveButton("去设置",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()))))
                    .setNegativeButton("稍后",null).show();return;
            }
            Uri uri=Uri.parse("content://"+activity.getPackageName()+".updates/update.apk");
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("更新安装包",uri));activity.startActivity(intent);
        }catch(Exception e){message("无法安装："+safe(e));}
    }
    private void settings(){
        android.content.Context themed=UiStyle.dialog(activity);
        LinearLayout form=new LinearLayout(themed);form.setOrientation(1);form.setPadding(30,10,30,10);
        EditText endpoint=field(form,"日志上传 HTTPS 地址",config.get("endpoint"));
        EditText token=field(form,"上传凭证",config.get("upload_token"));token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText repo=field(form,"GitHub 仓库（owner/repo）",config.get("github_repo"));
        ScrollView scroll=new ScrollView(themed);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(themed).setTitle("服务设置").setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{config.save(endpoint.getText().toString().trim(),token.getText().toString().trim(),repo.getText().toString().trim());dialog.dismiss();}
            catch(Exception e){message(safe(e));}
        }));dialog.show();UiStyle.polish(dialog);
    }
    private EditText field(LinearLayout form,String label,String value){
        TextView name=new TextView(form.getContext());name.setText(label);form.addView(name);
        EditText input=new EditText(form.getContext());input.setSingleLine(true);input.setText(value);form.addView(input);return input;
    }
    interface Work{void run()throws Exception;}
}
