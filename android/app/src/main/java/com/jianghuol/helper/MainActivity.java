package com.jianghuol.helper;

import android.app.*;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static LogFile phoneLogFile;
    private SupportCenter supportCenter;
    private static final int BG=0xff101b19,CARD=0xff1b2926,PAPER=0xffeee9dc,MUTED=0xffa4b0a5,RED=0xffd86b53,GREEN=0xff8fbb9a,LINE=0xff33453e;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout content,nav,dock;
    private TextView status,eventLog,rotationInfo,rotationNext,farmCountSetting;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener farmSettingsListener=(prefs,key)->{
        if("count".equals(key))ui.post(()->{
            if(farmCountSetting!=null)farmCountSetting.setText("设置刷铜次数 · "+prefs.getInt("count",3)+" 场");
        });
    };
    private Chronometer teamStopwatch;
    private BridgeClient bridge;
    private JSONArray features=new JSONArray();
    private JSONObject latest=new JSONObject();
    private int tab=0,rounds=4;
    private static final int RQ_CAPTURE=9011;
    /** 截屏授权是从哪个入口发起的：null=一键上号（组队），否则是攻防动作名。 */
    private String pendingAssaultAction=null;
    private int pendingFarmCount;
    private String farmTargetPackage="";
    private boolean stopped=false;
    private volatile boolean fetching=false;
    private String chosen="copper",connection="等待连接";
    private RoleStore roles;
    private AccountStore accounts;
    private final String[] tabs={"任务","工具","记录"};
    private final Runnable refresh=new Runnable(){public void run(){updateLog();updateRotationUi();if(!stopped)ui.postDelayed(this,2500);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(state!=null){pendingRotation=state.getString("pendingRotation");pendingFarmCount=state.getInt("pendingFarmCount",0);farmTargetPackage=state.getString("farmTargetPackage","");}
        String token=getIntent().getStringExtra("bridge_token");
        if(token!=null)getPreferences(0).edit().putString("bridge_token",token).apply();
        ContextHolder.set(this);
        if(phoneLogFile==null){phoneLogFile=new LogFile(getApplicationContext());LogBus.get().addSink(phoneLogFile);}
        LogBus.get().addSink(new LogBus.LogcatSink());
        LogBus.get().i("helper", "MainActivity onCreate, sink=" + LogBus.get().snapshot().size());
        roles=new RoleStore(this);roles.export();accounts=new AccountStore(this);accounts.export();
        supportCenter=new SupportCenter(this);
        getSharedPreferences("farm",0).registerOnSharedPreferenceChangeListener(farmSettingsListener);
        bridge=new BridgeClient(getPreferences(0).getString("bridge_token",""));build();ui.post(refresh);if(state==null)handleFarmOverlay(getIntent());    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleFarmOverlay(intent);}
    private void handleFarmOverlay(Intent intent){
        if(!intent.getBooleanExtra("overlay_farm_start",false))return;
        String target=intent.getStringExtra("overlay_farm_package");
        intent.removeExtra("overlay_farm_start");intent.removeExtra("overlay_farm_package");
        if(RunGate.busy()){toast("已有流程运行");return;}
        boolean known=false;for(String[] app:roles.gameApps())if(app[0].equals(target))known=true;
        if(!known){toast("未确认刷铜客户端");return;}
        farmTargetPackage=target;
        int count=getSharedPreferences("farm",0).getInt("count",1);
        if(count<1||count>100000){toast("请先设置有效场次");return;}
        toast("请使用游戏悬浮窗开始刷铜");
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private void add(LinearLayout p,View v,int bottom){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);l.bottomMargin=dp(bottom);p.addView(v,l);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private TextView button(String s,int color,Runnable action){TextView b=text(s,15,color==GREEN?BG:PAPER);b.setGravity(17);b.setPadding(dp(14),dp(14),dp(14),dp(14));b.setMinHeight(dp(48));UiStyle.clickable(b,color,14);b.setOnClickListener(v->action.run());return b;}
    private void build(){
        LinearLayout page=column();page.setBackgroundColor(BG);page.setPadding(dp(20),dp(12),dp(20),dp(8));setContentView(page);
        LinearLayout mast=new LinearLayout(this);mast.setGravity(16);TextView brand=text("江湖助手",20,PAPER);brand.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));mast.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView seal=text("铜钱 · 同行",11,GREEN);seal.setPadding(dp(10),dp(7),dp(10),dp(7));GradientDrawable stamp=bg(BG,5);stamp.setStroke(dp(1),LINE);seal.setBackground(stamp);mast.addView(seal);add(page,mast,8);
        add(page,button("打开悬浮控制台",LINE,()->{
            if(!HelperAccessibilityService.ready()){toast("请先开启江湖助手无障碍服务");return;}
            HelperAccessibilityService.showScriptStatus();
        }),4);
        teamStopwatch=new Chronometer(this);
        teamStopwatch.setTextColor(GREEN);
        teamStopwatch.setTextSize(14);
        teamStopwatch.setFormat("组队耗时  %s");
        teamStopwatch.setText("组队耗时  未开始");
        teamStopwatch.setVisibility(View.GONE);add(page,teamStopwatch,0);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dock=new LinearLayout(this);dock.setPadding(0,dp(10),0,0);
        TextView start=button("开始执行任务",GREEN,this::startTaskList);dock.addView(start,new LinearLayout.LayoutParams(0,-2,3));
        TextView stop=button("停止执行",LINE,()->{
            if(pendingRotation!=null&&pendingRotation.startsWith("schedule_"))pendingRotation="schedule_cancelled";
            ScriptStatus.stopping();RunGate.stop();
        });LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,2);sp.leftMargin=dp(10);dock.addView(stop,sp);add(page,dock,0);
        nav=new LinearLayout(this);nav.setPadding(0,dp(8),0,0);add(page,nav,0);renderNav();render();
    }
    private void renderNav(){nav.removeAllViews();for(int i=0;i<tabs.length;i++){final int n=i;TextView item=text(tabs[i],14,tab==i?PAPER:MUTED);item.setGravity(17);item.setPadding(0,dp(12),0,dp(12));if(tab==i)item.setBackground(bg(LINE,10));item.setOnClickListener(v->{tab=n;renderNav();render();});nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(16),dp(16),dp(16));c.setBackground(UiStyle.shape(this,CARD,20,0xff2c3d36));add(content,c,12);return c;}
    private void heading(String label,String title,String desc){add(content,text(label,11,GREEN),5);TextView h=text(title,24,PAPER);h.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));add(content,h,7);add(content,text(desc,12,MUTED),18);}
    private String label(String s){switch(s){case "RUNNING":return "正在执行";case "STOP_REQUESTED":return "正在停止";case "FINISHED":return "执行结束";case "FAILED":return "运行中断";case "IDLE":return "尚未开始";default:return s;}}
    private void render(){
        content.removeAllViews();status=null;eventLog=null;phoneLog=null;rotationInfo=null;rotationNext=null;farmCountSetting=null;
        dock.setVisibility(tab==0?View.VISIBLE:View.GONE);
        if(tab==0){
            heading("刷铜控制台","安排任务，轻松刷铜","添加任务后，点底部「开始执行任务」。");
            renderCopperTeam();
            LinearLayout schedule=card();add(schedule,text("任务列表",19,PAPER),8);
            add(schedule,text("按顺序执行，可添加、编辑、排序和删除。全部完成后停止。",12,MUTED),12);
            try{
                JSONArray items=new CopperSchedule(this).load();
                for(int i=0;i<items.length();i++){
                    final int index=i;JSONObject item=items.getJSONObject(i);
                    add(schedule,button((i+1)+"  "+CopperSchedule.description(item),LINE,()->new CopperSchedule(this).menu(index,this::render)),8);
                }
                if(items.length()==0)add(schedule,text("还没有操作\n例如：登录组队 → 刷铜10场 → 换角色 → 再刷10场。",13,MUTED),12);
            }catch(Exception e){add(schedule,text("任务读取失败，请检查记录。",13,RED),8);}
            add(schedule,button("＋ 添加任务",GREEN,()->new CopperSchedule(this).add(this::render)),0);
            LinearLayout hero=card();
            add(hero,text("当前账号",18,PAPER),8);
            add(hero,text("使用当前游戏角色，自动接续必要前置，余党任务始终保留。",13,MUTED),12);
            farmCountSetting=button("设置刷铜次数 · "+getSharedPreferences("farm",0).getInt("count",3)+" 场",LINE,()->CopperSchedule.countDialog(this,false,this::render));add(hero,farmCountSetting,8);
            add(hero,text("回到游戏，展开悬浮窗，点「当前账号刷铜」。",12,GREEN),0);
            LinearLayout access=card();add(access,text("运行准备",16,PAPER),8);
            add(access,text("无障碍："+(HelperAccessibilityService.ready()?"已连接":"未连接")+"   截屏："+(CaptureService.running()?"已就绪":"未授权"),13,MUTED),10);
            add(access,button("开启无障碍",LINE,()->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))),8);
            add(access,button("授权截屏并显示悬浮窗",GREEN,()->{
                if(!HelperAccessibilityService.ready()){toast("请先开启无障碍");return;}
                HelperAccessibilityService.showScriptStatus();
                if(!CaptureService.running()){
                    pendingRotation="setup_only";
                    MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                    startActivityForResult(manager.createScreenCaptureIntent(),RQ_CAPTURE);
                }
            }),0);
        }else if(tab==1){
            heading("辅助操作","工具","独立运行组队与换角；自动刷铜请回到任务页配置。");
            renderRoles();
            // 未完成实机成功验收的功能暂不展示；既有执行实现保留。
        }else{
            heading("运行状态","运行记录","查看当前进度与本次运行结果。");
            LinearLayout c=card();eventLog=text("尚未开始",14,PAPER);add(c,eventLog,0);updateLog();
            add(content,button("刷新状态",LINE,this::updateLog),12);
            add(content,button("问题日志上传 · 检查更新",GREEN,()->supportCenter.show()),12);
        }
    }

    /** 「队伍」页顶部的角色配置卡：在 App 里指定谁当队长、谁是队员。 */
    private void renderCopperTeam(){
        LinearLayout c=card();add(c,text("刷铜队伍",19,PAPER),6);
        add(c,text("已沿用原有账号配置。批次任务先上号组队，再由队长刷铜。",12,MUTED),12);
        String leader=roles.leader();List<String> members=roles.members();
        add(c,button("队长客户端 · "+(leader.isEmpty()?"请选择":roles.labelOf(leader)),LINE,this::pickLeader),8);
        add(c,button("队员客户端 · 已选 "+members.size()+" 个",LINE,this::pickMembers),10);
        add(c,button("设置队长和队员账号密码",LINE,this::pickCopperAccount),8);
        add(c,text("队长保持固定，下一批只更换队友账号。",11,MUTED),0);
    }
    private void pickCopperAccount(){
        if(RunGate.busy()||CopperWorkflow.active()){toast("请先停止流程");return;}
        List<String> packages=new ArrayList<>(),names=new ArrayList<>();
        if(!roles.leader().isEmpty()){packages.add(roles.leader());names.add("队长");}
        List<String> members=roles.members();for(int i=0;i<members.size();i++){packages.add(members.get(i));names.add("队员 "+(i+1));}
        if(packages.isEmpty()){toast("请先选择队长和队员客户端");return;}
        String[] labels=new String[packages.size()];
        for(int i=0;i<labels.length;i++){String[] login=accounts.get(packages.get(i));labels[i]=names.get(i)+" · "+roles.labelOf(packages.get(i))+" · "+(!login[0].isEmpty()&&!login[1].isEmpty()?"已填写":"未填写");}
        UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(this)).setTitle("选择要设置的账号").setItems(labels,(d,n)->editCopperAccount(packages.get(n),names.get(n))).setNegativeButton("取消",null).show());
    }
    private void editCopperAccount(String pkg,String role){
        if(RunGate.busy()||CopperWorkflow.active()){toast("请先停止流程");return;}
        LinearLayout form=column();form.setPadding(dp(24),dp(8),dp(24),0);
        add(form,text(roles.labelOf(pkg),13,MUTED),10);
        String[] previous=accounts.get(pkg);
        EditText account=new EditText(this);account.setSingleLine(true);account.setHint("游戏账号");account.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);account.setText(previous[0]);UiStyle.field(account);form.addView(account);
        EditText password=new EditText(this);password.setSingleLine(true);password.setHint("密码");password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setText(previous[1]);UiStyle.field(password);form.addView(password);
        AlertDialog dialog=new AlertDialog.Builder(UiStyle.dialog(this)).setTitle(role+"账号密码").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            if(RunGate.busy()||CopperWorkflow.active()){toast("请先停止流程");return;}
            if(account.getText().toString().trim().isEmpty()){account.setError("请输入游戏账号");account.requestFocus();return;}
            if(password.getText().toString().isEmpty()){password.setError("请输入密码");password.requestFocus();return;}
            accounts.set(pkg,account.getText().toString(),password.getText().toString());dialog.dismiss();render();
        }));dialog.show();UiStyle.polish(dialog);
    }
    private void renderRoles(){
        LinearLayout c=card();
        add(c,text("组队与换角",18,PAPER),6);
        add(c,text("使用任务页已选的客户端和账号配置。",12,MUTED),12);
        add(c,button("登录并组队",GREEN,()->phoneRun()),10);
        RoleRotation rotation=new RoleRotation(this);
        android.widget.Switch toggle=new android.widget.Switch(this);
        toggle.setText("启用队友换角");toggle.setTextColor(PAPER);toggle.setChecked(rotation.enabled());
        toggle.setOnCheckedChangeListener((b,on)->{try{rotation.setEnabled(on);}catch(Exception e){toast(e.getMessage());b.setChecked(rotation.enabled());}updateRotationUi();});
        add(c,toggle,10);
        rotationInfo=text(displayDetail(RotationRuntime.summary(this)),12,MUTED);add(c,rotationInfo,8);
        add(c,button("核验当前队伍 · 明确开始新一轮",LINE,()->new AlertDialog.Builder(this)
                .setTitle("开始新一轮三组换角")
                .setMessage("将用当前四名角色建立第一组并核验实际名册。此操作会替换上一轮记录，不会自动换角。")
                .setPositiveButton("核验并开始",(d,w)->rotationAction("new")).setNegativeButton("取消",null).show()),6);
        add(c,button("恢复本轮换组",LINE,()->rotationAction("resume")),6);
        rotationNext=button("换下一组并组队",GREEN,()->rotationAction("primary"));add(c,rotationNext,6);updateRotationUi();

    }

    // ---------------- 一键上号 · 手机版 ----------------

    /** 内置功能清单：手机闭环不依赖电脑桥，攻防三项直接可跑。 */
    private JSONArray builtinFeatures(){
        JSONArray a=new JSONArray();
        String[][] rows={
            {"recruit","征兵","手机上直接跑：导航到征兵页 → 读我的士兵两页 → 招募一次 → 对账","ready","移植 clicker/assault_v1 的状态机与 34 个模板；提交前落 pending，崩了也不重发"},
            {"attack","进攻","手机上直接跑：占领页 → 选目标 → 确认框核对完整国名 → 提交","partial","攻防无成功样本，回到占领页只报 UNKNOWN，不冒充成功"},
            {"defend","驻防","手机上直接跑：占领页 → 驻防 → 提交","partial","同上；结果未知时会阻止重发"},
            {"party","组队","用「队伍」页的一键上号·手机版","ready","PhoneRunner：①上号 ②清场 ③邀请 ④接受 ⑤核对"},
        };
        for(String[] r:rows){
            try{JSONObject o=new JSONObject();o.put("id",r[0]);o.put("title",r[1]);o.put("detail",r[2]);o.put("state",r[3]);o.put("note",r[4]);o.put("source","手机闭环内置");a.put(o);}catch(Exception ignored){}
        }
        return a;
    }

    /** 攻防·手机版：移植 clicker/assault_v1 的状态机，不连电脑。 */
    private void phoneAssault(String action){
        try{
            if(!HelperAccessibilityService.ready()){toast("先开无障碍服务");return;}
            if(!CaptureService.running()){
                pendingAssaultAction=action;   // 记住发起入口，授权回来还走攻防
                MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(),RQ_CAPTURE);
                return;
            }
            launchPhoneAssault(action);
        }catch(Throwable t){toast("启动失败："+t.getMessage());}
    }

    private void launchPhoneAssault(String action){
        if(RunGate.busy()){toast("已有流程控制游戏，请先停止");return;}
        if(!HelperAccessibilityService.ready()){toast("无障碍服务尚未就绪，请重试");return;}
        final TextView log_=new TextView(this);log_.setTextSize(13);log_.setTextColor(PAPER);log_.setPadding(dp(4),dp(4),dp(4),dp(4));
        LinearLayout c=card();add(c,text("攻防·手机版 "+action,16,PAPER),8);ScrollView sv=new ScrollView(this);sv.addView(log_,new LinearLayout.LayoutParams(-1,-2));add(c,sv,0);
        final java.util.concurrent.atomic.AtomicInteger n=new java.util.concurrent.atomic.AtomicInteger();
        PhoneAssault pa=new PhoneAssault(HelperAccessibilityService.get(),new Vision(getAssets()),
            new PhoneAssault.Listener(){
                public void onLog(String line){ScriptStatus.update(line);ui.post(()->{if(log_.getParent()!=null)log_.append(line+"\n");});}
                public void onDone(String summary){ScriptStatus.finish(summary,false);ui.post(()->{
                    if(log_.getParent()!=null)log_.append("\n"+summary+"\n");
                    new AlertDialog.Builder(MainActivity.this).setTitle("攻防·手机版").setMessage(summary)
                        .setPositiveButton("知道了",null).show();
                    if(!RunGate.busy())CaptureService.stop(MainActivity.this);});
                }
            }, getExternalFilesDir(null));
        ScriptStatus.begin("攻防 · "+action);HelperAccessibilityService.showScriptStatus();
        new Thread(()->{try{pa.run(action);}catch(Throwable t){LogBus.get().e("assault","线程异常",t);}
            finally{ScriptStatus.finish("流程退出，结果未确认",false);}},"phone-assault").start();
    }

    private android.graphics.Bitmap captureStub;
    private TextView phoneLog;
    private PhoneRunner runner;
    private Thread runnerThread;
    private String pendingRotation;
    private void startTaskList(){
        if(RunGate.busy()||CopperWorkflow.active()){toast("已有流程运行，请先停止");return;}
        try{
            JSONArray items=new CopperSchedule(this).load();CopperSchedule.validate(items);
            if(!HelperAccessibilityService.ready()){
                UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(this)).setTitle("开启无障碍")
                    .setMessage("请开启江湖助手无障碍服务，然后返回点击开始执行任务。")
                    .setPositiveButton("去开启",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消",null).show());return;
            }
            boolean current=false;for(int i=0;i<items.length();i++)current|=CopperPlan.CURRENT.equals(items.getJSONObject(i).getString("kind"));
            if(current){
                List<String[]> apps=roles.gameApps();if(apps.isEmpty()){toast("未找到游戏客户端");return;}
                String[] labels=new String[apps.size()];for(int i=0;i<labels.length;i++)labels[i]=apps.get(i)[1];
                UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(this)).setTitle("当前账号刷铜使用的客户端")
                    .setItems(labels,(d,n)->{farmTargetPackage=apps.get(n)[0];prepareTaskList();}).setNegativeButton("取消",null).show());
            }else{farmTargetPackage="";prepareTaskList();}
        }catch(Exception e){UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(this)).setTitle("暂时无法开始")
            .setMessage(e.getMessage()).setPositiveButton("知道了",null).show());}
    }
    private void prepareTaskList(){
        if(RunGate.busy()||CopperWorkflow.active()){toast("已有流程运行");return;}
        if(!CaptureService.running()){
            pendingRotation="schedule_start";pendingAssaultAction=null;pendingFarmCount=0;
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(),RQ_CAPTURE);return;
        }
        HelperAccessibilityService.showScriptStatus();CopperWorkflow.startSchedule(this,farmTargetPackage);
    }
    private void awaitTaskCapture(int remaining){
        if(!"schedule_wait".equals(pendingRotation))return;
        if(CaptureService.running()){pendingRotation=null;prepareTaskList();return;}
        if(remaining<=0){pendingRotation=null;toast("截屏尚未就绪，请再点开始执行任务");return;}
        ui.postDelayed(()->awaitTaskCapture(remaining-1),100);
    }
    private void updateRotationUi(){
        if(rotationInfo!=null)rotationInfo.setText(displayDetail(RotationRuntime.summary(this)));
        if(rotationNext!=null){rotationNext.setText(RotationRuntime.primaryLabel(this));boolean available=RotationRuntime.canPrimary(this);rotationNext.setEnabled(available);rotationNext.setAlpha(available?1f:.4f);}
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putString("pendingRotation",pendingRotation);out.putInt("pendingFarmCount",pendingFarmCount);out.putString("farmTargetPackage",farmTargetPackage);super.onSaveInstanceState(out);}
    private void rotationAction(String action){
        if(RunGate.busy()){toast("已有流程运行");return;}
        if(!HelperAccessibilityService.ready()){toast("请先开启无障碍");return;}
        if(!CaptureService.running()){
            pendingRotation=action;
            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(),RQ_CAPTURE);return;
        }
        RotationRuntime.start(this,action);
    }

    private void phoneRun(){
        try{
            if(RunGate.busy()){toast("已有流程控制游戏，请先停止");return;}
            if(!HelperAccessibilityService.ready()){
                new AlertDialog.Builder(this).setTitle("先开无障碍")
                    .setMessage("手机版要在游戏里点击，需要到系统设置里打开「江湖助手·手势」无障碍服务。")
                    .setPositiveButton("去设置",(d,w)->startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消",null).show();
                return;
            }
            if(!CaptureService.running()){
                MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(),RQ_CAPTURE);
                return;
            }
            launchPhoneRunner();
        }catch(Throwable t){
            Toast.makeText(this,"启动失败："+t.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void launchPhoneRunner(){
        if(RunGate.busy()){toast("已有流程控制游戏，请先停止");return;}
        if(!HelperAccessibilityService.ready()){toast("无障碍服务尚未就绪，请重试");return;}
        if(runnerThread!=null&&runnerThread.isAlive()){
            Toast.makeText(this,"已在运行中",Toast.LENGTH_SHORT).show();return;
        }
        if(phoneLog!=null)phoneLog.setText("准备就绪…\n");
        Vision vis=new Vision(getAssets());
        runner=new PhoneRunner(HelperAccessibilityService.get(),vis,new PhoneRunner.Listener(){
            public void onLog(String line){ScriptStatus.update(line);ui.post(()->{if(phoneLog!=null)phoneLog.append(line+"\n");});}
            public void onDone(String summary,boolean passed){
                ScriptStatus.finish(summary,passed);
                ui.post(()->{
                    if(phoneLog!=null)phoneLog.append("\n"+summary+"\n"+(passed?"✔ 人数齐了":"✗ 未确认成功，看上面日志"));
                    new AlertDialog.Builder(MainActivity.this).setTitle("一键上号 · 手机版")
                        .setMessage(summary).setPositiveButton("知道了",null).show();
                    if(!RunGate.busy()&&!new RoleRotation(MainActivity.this).enabled())CaptureService.stop(MainActivity.this);
                    phoneLog=null;
                });
            }
        });
        final long timerStart=SystemClock.elapsedRealtime();
        teamStopwatch.setVisibility(View.VISIBLE);teamStopwatch.setBase(timerStart);
        teamStopwatch.start();
        ScriptStatus.begin("一键上号＋组队");HelperAccessibilityService.showScriptStatus();
        runnerThread=new Thread(()->{try{runner.run();}catch(Throwable t){
            LogBus.get().e("phone","运行线程炸了",t);
            ui.post(()->Toast.makeText(MainActivity.this,"运行异常："+t,Toast.LENGTH_LONG).show());
        }finally{
            ScriptStatus.finish("流程异常退出，结果未确认",false);
            final long seconds=(SystemClock.elapsedRealtime()-timerStart)/1000;
            ui.post(()->{
                teamStopwatch.stop();
                teamStopwatch.setText(String.format(Locale.ROOT,"组队耗时  %02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60));
            });
        }},"phone-runner");
        runnerThread.start();
    }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==RQ_CAPTURE){
            if(res!=RESULT_OK){Toast.makeText(this,"没授权截屏，手机版跑不了",Toast.LENGTH_LONG).show();pendingAssaultAction=null;pendingRotation=null;pendingFarmCount=0;return;}
            Intent i=new Intent(this,CaptureService.class);
            i.putExtra(CaptureService.EXTRA_CODE,res);i.putExtra(CaptureService.EXTRA_DATA,data);
            if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            // 谁发起的授权就回到谁：攻防回攻防，其余（一键上号）回组队
            if(pendingFarmCount>0){
                final int n=pendingFarmCount;pendingFarmCount=0;ui.postDelayed(()->launchPhoneFarm(n),1200);
            }else if(pendingRotation!=null){
                final String a=pendingRotation;pendingRotation=null;if("setup_only".equals(a)){HelperAccessibilityService.showScriptStatus();render();}else if("schedule_start".equals(a)){pendingRotation="schedule_wait";awaitTaskCapture(50);}else if("schedule_cancelled".equals(a)){toast("已取消任务启动");}else ui.postDelayed(()->rotationAction(a),1200);
            }else if(pendingAssaultAction!=null){
                final String a=pendingAssaultAction;pendingAssaultAction=null;
                ui.postDelayed(()->launchPhoneAssault(a),1200);
            }else{
                ui.postDelayed(this::launchPhoneRunner,1200);
            }
        }
    }

    private void pickLeader(){
        if(RunGate.busy()){toast("请先停止流程");return;}
        List<String[]> apps=roles.gameApps();
        if(apps.isEmpty()){toast("没找到 com.good.* 客户端");return;}
        String[] labels=new String[apps.size()];
        String lp=roles.leader();
        int cur=-1;
        for(int i=0;i<apps.size();i++){labels[i]=apps.get(i)[1];if(apps.get(i)[0].equals(lp))cur=i;}
        new AlertDialog.Builder(this).setTitle("选择队长客户端")
            .setSingleChoiceItems(labels,cur,(d,w)->{roles.setLeader(apps.get(w)[0]);d.dismiss();render();})
            .setNegativeButton("取消",null).show();
    }

    private void pickMembers(){
        if(RunGate.busy()){toast("请先停止流程");return;}
        List<String[]> apps=roles.gameApps();
        if(apps.isEmpty()){toast("没找到 com.good.* 客户端");return;}
        String[] labels=new String[apps.size()];
        boolean[] checked=new boolean[apps.size()];
        List<String> cur=roles.members();
        for(int i=0;i<apps.size();i++){labels[i]=apps.get(i)[1];checked[i]=cur.contains(apps.get(i)[0]);}
        new AlertDialog.Builder(this).setTitle("选择队员客户端（可多选）")
            .setMultiChoiceItems(labels,checked,(d,w,isChecked)->checked[w]=isChecked)
            .setPositiveButton("确定",(d,w)->{
                List<String> sel=new ArrayList<>();
                for(int i=0;i<apps.size();i++)if(checked[i])sel.add(apps.get(i)[0]);
                roles.setMembers(sel);render();
            })
            .setNegativeButton("取消",null).show();
    }

    private void start(){if(RunGate.busy()){toast("手机流程正在控制游戏");return;}if("copper".equals(chosen)){toast("请在游戏悬浮窗开始刷铜");return;}try{JSONObject b=new JSONObject();b.put("action",chosen);b.put("rounds",rounds);request("POST","/start",b);}catch(Exception e){toast("配置有误");}}
    private void phoneFarm(int count){
        if(RunGate.busy()){toast("已有流程运行");return;}
        if(!HelperAccessibilityService.ready()){toast("先开无障碍服务");return;}
        if(!CaptureService.running()){
            pendingFarmCount=count;pendingAssaultAction=null;pendingRotation=null;
            MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(),RQ_CAPTURE);return;
        }
        launchPhoneFarm(count);
    }
    private void launchPhoneFarm(int count){
        if(RunGate.busy()){toast("已有流程运行");return;}
        try{
            String target=farmTargetPackage.isEmpty()?roles.leader():farmTargetPackage;
            PhoneFarm farm=new PhoneFarm(HelperAccessibilityService.get(),getAssets(),target,getExternalFilesDir(null),count,
                new PhoneFarm.Listener(){
                    public void onLog(String line){ScriptStatus.update(line);}
                    public void onDone(String summary,boolean confirmed){ui.post(()->toast(summary));}
                });
            new Thread(farm::run,"phone-farm").start();
        }catch(Exception e){toast("刷铜启动失败："+e.getMessage());}
    }
    private void request(String method,String path,JSONObject b){io.execute(()->{try{JSONObject r=bridge.request(method,path,b);ui.post(()->{latest=r;connection="电脑已连接";render();});}catch(Exception e){ui.post(()->toast(e.getMessage()));}});}
    private void poll(){if(fetching||stopped||RunGate.busy())return;fetching=true;io.execute(()->{try{JSONObject r=bridge.request("GET","/status",null);ui.post(()->{boolean first=features.length()==0;latest=r;features=r.optJSONArray("features");if(features==null)features=new JSONArray();connection="电脑已连接";if(first)render();if(status!=null)status.setText(connection+"  ·  "+label(latest.optString("state","IDLE")));updateLog();});}catch(Exception e){ui.post(()->{connection="等待电脑连接";if(status!=null)status.setText(connection);});}finally{fetching=false;}});}
    private void updateLog(){
        if(eventLog==null)return;ScriptStatus.Snapshot s=ScriptStatus.snapshot();
        eventLog.setText(s.task+" · "+s.label()+"\n\n"+displayDetail(s.detail)+(s.elapsedSeconds>0?"\n\n耗时 "+s.elapsedSeconds+" 秒":""));
    }
    private String displayDetail(String detail){return detail.replace("副本未执行","等待开始任务").replace("启动手机版脚本后显示进度","从悬浮球启动后显示运行进度");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){stopped=true;getSharedPreferences("farm",0).unregisterOnSharedPreferenceChangeListener(farmSettingsListener);if(teamStopwatch!=null)teamStopwatch.stop();ui.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
