package com.jianghuol.helper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

/** 可拖动小球与居中控制面板；启动前同步收起，不持有Activity。 */
final class StatusOverlay {
    private static final int BG=0xff162621, INK=0xfff0ede4, MUTED=0xffaebfb3, GREEN=0xffa7d4b2, RED=0xffdf8d77;
    private final AccessibilityService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private LinearLayout root,body,compact,compactLabels;
    private TextView ball,title,detail,farmStart,scheduleStart,stop,compactTitle,compactDetail;
    private final OverlayProgress progress=new OverlayProgress();
    private WindowManager.LayoutParams params;
    private boolean expanded,wasRunning;
    private int bubbleX,bubbleY;
    private long version=-1,second=-1;
    private final Runnable refresh=new Runnable(){public void run(){if(root!=null){render();main.postDelayed(this,250);}}};
    StatusOverlay(AccessibilityService service){this.service=service;wm=(WindowManager)service.getSystemService(AccessibilityService.WINDOW_SERVICE);}
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private TextView text(String label,int size,int color){TextView t=new TextView(service);t.setText(label);t.setTextSize(size);t.setTextColor(color);return t;}
    private void add(LinearLayout parent,View view,int height,int bottom){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));lp.bottomMargin=dp(bottom);parent.addView(view,lp);}
    private TextView action(String label,int color,Runnable click){TextView t=text(label,16,color==GREEN?BG:INK);t.setGravity(Gravity.CENTER);UiStyle.clickable(t,color,14);t.setOnClickListener(v->click.run());return t;}
    void show(){main.post(()->{
        if(root!=null){render();return;}
        expanded=false;wasRunning=false;version=-1;second=-1;
        root=new LinearLayout(service);root.setOrientation(LinearLayout.VERTICAL);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ball=text("铜",22,INK);ball.setTypeface(Typeface.DEFAULT,Typeface.BOLD);ball.setGravity(Gravity.CENTER);
        GradientDrawable circle=background(BG,50);circle.setStroke(dp(2),GREEN);ball.setBackground(circle);ball.setElevation(dp(6));
        compact=new LinearLayout(service);compact.setGravity(Gravity.CENTER_VERTICAL);compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.addView(ball,new LinearLayout.LayoutParams(dp(56),dp(56)));
        compactLabels=new LinearLayout(service);compactLabels.setOrientation(LinearLayout.VERTICAL);compactLabels.setGravity(Gravity.CENTER_VERTICAL);
        compactTitle=text("当前任务",10,INK);compactTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        compactDetail=text("准备执行",10,GREEN);
        for(TextView label:new TextView[]{compactTitle,compactDetail}){label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);compactLabels.addView(label);}
        compact.addView(compactLabels,new LinearLayout.LayoutParams(0,-1,1));root.addView(compact,new LinearLayout.LayoutParams(-1,dp(56)));dragBall();
        body=new LinearLayout(service);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(16),dp(18),dp(14));
        GradientDrawable bg=background(BG,22);bg.setStroke(dp(1),UiStyle.LINE);body.setBackground(bg);body.setElevation(dp(10));root.addView(body);
        LinearLayout header=new LinearLayout(service);header.setGravity(Gravity.CENTER_VERTICAL);
        title=text("刷铜助手",19,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(38),1));
        TextView close=action("收起",0xff2d443a,()->{expanded=false;version=-1;render();});header.addView(close,new LinearLayout.LayoutParams(dp(68),dp(42)));add(body,header,46,8);
        detail=text("设置次数，选择任务，然后开始。",13,MUTED);detail.setMaxLines(3);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);add(body,detail,-2,16);
        farmStart=action("当前账号刷铜",GREEN,()->{
            if(RunGate.busy()||CopperWorkflow.active())return;
            String current=currentGame();if(current.isEmpty())return;
            collapseForRun();CopperWorkflow.startCurrent(service,current);
        });add(body,farmStart,52,10);
        scheduleStart=action("开始执行任务",0xff2d443a,()->{
            if(RunGate.busy()||CopperWorkflow.active())return;
            String current=HelperAccessibilityService.get().currentPackage();
            collapseForRun();CopperWorkflow.startSchedule(service,current);
        });add(body,scheduleStart,52,10);
        TextView count=action("设置刷铜次数",0xff2d443a,()->CopperSchedule.countDialog(service,true,()->{version=-1;render();}));count.setTag("count");add(body,count,48,14);
        stop=action("停止执行",0xff78473a,()->{ScriptStatus.stopping();RunGate.stop();expanded=false;version=-1;render();});add(body,stop,52,10);
        TextView helper=action("返回助手 · 编辑任务",0xff2d443a,()->{
            expanded=false;version=-1;render();service.startActivity(new Intent(service,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });helper.setTag("helper");add(body,helper,46,0);
        int screenWidth=service.getResources().getDisplayMetrics().widthPixels;
        int collapsedWidth=Math.min(dp(136),Math.round(screenWidth*.37f));
        params=new WindowManager.LayoutParams(collapsedWidth,dp(56),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;
        params.x=screenWidth-collapsedWidth-dp(8);params.y=Math.round(service.getResources().getDisplayMetrics().heightPixels*.42f);
        bubbleX=params.x;bubbleY=params.y;
        try{wm.addView(root,params);render();main.post(refresh);}catch(RuntimeException e){root=null;LogBus.get().w("overlay","悬浮球未显示："+e.getClass().getSimpleName());}
    });}
    private String currentGame(){
        HelperAccessibilityService acc=HelperAccessibilityService.get();String current=acc==null?"":acc.currentPackage();
        for(String[] app:new RoleStore(service).gameApps())if(app[0].equals(current))return current;
        Toast.makeText(service,"请先回到要刷铜的游戏客户端",Toast.LENGTH_LONG).show();return "";
    }
    private void collapseForRun(){
        expanded=false;version=-1;render();
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=Math.round(service.getResources().getDisplayMetrics().widthPixels*.40f);params.y=0;
        if(root.isAttachedToWindow())wm.updateViewLayout(root,params);
    }
    private void dragBall(){View.OnTouchListener drag=new View.OnTouchListener(){
        float downX,downY;int x,y;boolean moved;
        public boolean onTouch(View v,MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:downX=event.getRawX();downY=event.getRawY();x=params.x;y=params.y;moved=false;return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                    if(Math.hypot(dx,dy)>ViewConfiguration.get(service).getScaledTouchSlop())moved=true;
                    if(moved){params.x=x+Math.round(dx);params.y=y+Math.round(dy);clamp();wm.updateViewLayout(root,params);}return true;
                case MotionEvent.ACTION_UP:bubbleX=params.x;bubbleY=params.y;if(!moved){expanded=true;version=-1;render();}return true;
                case MotionEvent.ACTION_CANCEL:return true;
                default:return false;
            }
        }
    };ball.setOnTouchListener(drag);compact.setOnTouchListener(drag);compactLabels.setOnTouchListener(drag);}
    private void clamp(){
        int w=service.getResources().getDisplayMetrics().widthPixels,h=service.getResources().getDisplayMetrics().heightPixels;
        params.x=Math.max(0,Math.min(params.x,w-params.width));params.y=Math.max(0,Math.min(params.y,h-dp(80)));
        if(RunGate.busy()||CopperWorkflow.active()||ScriptStatus.snapshot().active()){
            params.y=0;params.x=Math.max(Math.round(w*.40f),Math.min(params.x,Math.round(w*.77f)-params.width));
        }
    }
    private void render(){
        if(root==null)return;
        ScriptStatus.Snapshot s=ScriptStatus.snapshot();boolean running=RunGate.busy()||CopperWorkflow.active()||s.active();
        if(running&&!wasRunning){
            expanded=false;
            bubbleX=Math.round(service.getResources().getDisplayMetrics().widthPixels*.40f);bubbleY=0;
            params.gravity=Gravity.TOP|Gravity.LEFT;params.x=bubbleX;params.y=0;
        }
        if(version==s.version&&second==s.elapsedSeconds&&running==wasRunning)return;
        wasRunning=running;version=s.version;second=s.elapsedSeconds;
        int w=service.getResources().getDisplayMetrics().widthPixels;
        boolean showProgress=true;
        progress.update(s);compactTitle.setText(progress.title(s));compactDetail.setText(progress.detail(s));
        compact.setVisibility(expanded?View.GONE:View.VISIBLE);ball.setVisibility(expanded?View.GONE:View.VISIBLE);body.setVisibility(expanded?View.VISIBLE:View.GONE);
        compactLabels.setVisibility(showProgress?View.VISIBLE:View.GONE);
        int badge=dp(showProgress?32:56);ball.setLayoutParams(new LinearLayout.LayoutParams(badge,badge));ball.setTextSize(showProgress?14:22);
        compact.setPadding(showProgress?dp(4):0,0,showProgress?dp(4):0,0);
        GradientDrawable capsule=background(BG,18);capsule.setStroke(dp(1),GREEN);compact.setBackground(showProgress?capsule:null);
        ball.setText(running?"停":"铜");ball.setTextColor(running?RED:INK);ball.setContentDescription(running?"打开停止控制":"打开刷铜控制台");
        title.setText(running?"刷铜 · 运行中":"刷铜助手");
        detail.setText(s.state==ScriptStatus.State.IDLE?"当前账号直接刷铜，或按任务列表顺序执行。":s.detail);
        int idle=running?View.GONE:View.VISIBLE;
        farmStart.setVisibility(idle);scheduleStart.setVisibility(idle);body.findViewWithTag("count").setVisibility(idle);body.findViewWithTag("helper").setVisibility(idle);detail.setVisibility(View.VISIBLE);
        farmStart.setText("当前账号刷铜 · "+service.getSharedPreferences("farm",0).getInt("count",3)+" 场");
        stop.setVisibility(running?View.VISIBLE:View.GONE);stop.setEnabled(running);
        if(expanded){
            params.width=Math.min(dp(360),Math.round(w*.88f));params.height=-2;
            if(running){params.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;params.x=0;params.y=dp(2);}
            else{params.gravity=Gravity.CENTER;params.x=0;params.y=0;}
        }else{
            // 从居中面板收起时回到右侧；运行中固定顶端，避免遮挡任务或弹窗。
            if(params.gravity!= (Gravity.TOP|Gravity.LEFT)){params.x=bubbleX;params.y=bubbleY;}
            params.gravity=Gravity.TOP|Gravity.LEFT;params.width=showProgress?Math.min(dp(136),Math.round(w*.37f)):dp(56);params.height=dp(56);clamp();
        }
        if(root.isAttachedToWindow())wm.updateViewLayout(root,params);
    }
    void hide(){main.post(()->{main.removeCallbacks(refresh);if(root!=null){try{wm.removeViewImmediate(root);}catch(RuntimeException ignored){}root=null;}});}
}
