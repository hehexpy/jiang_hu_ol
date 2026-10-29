package com.jianghuol.helper;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 当前队长余党专用循环。动作集合只有打手与战斗退出，无法交付任务。 */
public final class PhoneFarm {
    public interface Listener { void onLog(String line); void onDone(String summary, boolean confirmed); }
    private final HelperAccessibilityService acc;
    private final Vision vision;
    private final Listener listener;
    private final JSONObject templates;
    private final String captain;
    private final File folder;
    private final FarmCounter counter;
    private final FarmPreparation preparation=new FarmPreparation();
    private boolean preExitSent,pendingBattleObserved;
    private long battleNotBefore,cooldownCloseDeadline;
    private long farmRouteDeadline,farmRouteNext;private int farmRouteTaps;
    private int rejectedEnemy=-1;
    private final Map<Integer,Integer> rejectedEnemies=new HashMap<>();
    private boolean inheritedCooldownClosed;
    private long challengeDispatchAt;
    private boolean challengeSent;
    private String trackedTarget="";
    private int trackedTaps;
    private long trackedNextTap;
    private boolean trackedHere,trackedNavigationActive;
    private String lastActionFile;
    private final Object inputLock=new Object();
    private volatile boolean stopped;
    private int sequence,lastFrame=-1;
    private long inputAfterNs, discoveryDeadline,lastAcquiredNs;
    private final FarmTiming timing=new FarmTiming(SystemClock.elapsedRealtime());
    private int timingVerified, timingPending=-1;
    private boolean timingAcquired;
    private long preparationDispatchedAt=-1,recognitionMs,screenshotMs,matchNanos;private int matchCalls;
    private String waitReason="";private long waitStarted;

    private final FarmPreparation.Search search=new FarmPreparation.Search();
    private boolean tooltipDismissed;
    private boolean detailFollowDispatched;
    private int npcApproachIndex=-1,npcApproachRetries;
    private long npcApproachAfter;
    private long tooltipSettleUntil;
    private long yudangListCloseDeadline;
    private boolean discoveryOpened,rowSelected,navigationFollowed,giverDispatched,taskInventoryChecked,yudangListResume;
    private JSONObject navigationChoice,listedNavigation;
    private String previous="";
    private JSONObject frameScores;
    private final FarmRecognizer.TrackerFrames trackerFrames=new FarmRecognizer.TrackerFrames();

    public PhoneFarm(HelperAccessibilityService acc, AssetManager assets, String captain,
                     File base, int target, Listener listener) throws Exception {
        if(captain==null||captain.isEmpty())throw new IllegalArgumentException("未设置队长");
        this.acc=acc;this.vision=new Vision(assets);this.captain=captain;this.listener=listener;
        this.counter=new FarmCounter(target);
        try(java.io.InputStream in=assets.open("farm_templates.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            templates=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
        search.reset=templates.getJSONObject("_discovery").getInt("reset_swipes");
        folder=new File(base,"farm/"+System.currentTimeMillis()+"-"+System.nanoTime());
        if(!folder.mkdirs())throw new java.io.IOException("无法创建刷铜证据目录");
        vision.setCancelled(()->stopped);
    }

    public void requestStop(){
        synchronized(inputLock){stopped=true;}
        ScriptStatus.stopping();
    }
    private void log(String line){
        String stamped="["+FarmTiming.wallTime(System.currentTimeMillis())+" +"+timing.elapsed(SystemClock.elapsedRealtime())+"ms] "+line;
        LogBus.get().i("farm",stamped);listener.onLog(line); // 完整日志保留时间戳，悬浮状态显示可读的动作摘要。
    }
    private void event(String kind,JSONObject data){
        try{
            long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
            data.put("event",kind).put("timestamp",FarmTiming.wallTime(wall))
                .put("wall_time_ms",wall).put("run_elapsed_ms",timing.elapsed(now))
                .put("step",timing.step).put("step_elapsed_ms",timing.stepElapsed(now)).put("frame",sequence);
            try(FileOutputStream out=new FileOutputStream(new File(folder,"timing.jsonl"),true)){
                out.write((data.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            }
        }catch(Exception error){LogBus.get().w("farm","[计时] 时间线写入失败："+error.getClass().getSimpleName());}
    }
    private void waitFor(String reason){
        if(waitReason.equals(reason))return;
        long now=SystemClock.elapsedRealtime();
        try{
            if(!waitReason.isEmpty())event("WAIT_END",new JSONObject().put("reason",waitReason).put("duration_ms",Math.max(0,now-waitStarted)));
            waitReason=reason;waitStarted=now;
            if(!reason.isEmpty())event("WAIT_BEGIN",new JSONObject().put("reason",reason));
        }catch(Exception ignored){}
    }
    private void timingStep()throws Exception{
        String next;
        if(preparation.acquired)next="战斗"+counter.battleId+"/"+counter.phase;
        else if(preparation.next>=0&&preparation.next<templates.getJSONArray("_preparation").length())
            next="前置"+(preparation.next+1)+"/"+templates.getJSONArray("_preparation").getJSONObject(preparation.next).getString("name");
        else next="任务检索6-"+search.chapter+"/列表"+search.tab+"/页"+search.page;
        long now=SystemClock.elapsedRealtime();
        if(!timing.step.equals(next)){
            waitFor("");
            event("STEP_END",new JSONObject().put("duration_ms",timing.stepElapsed(now)).put("next_step",next));
            timing.change(next,now);event("STEP_BEGIN",new JSONObject());
        }
    }
    private void timingProgress()throws Exception{
        if(preparation.verified>timingVerified&&timingPending>=0){
            long elapsed=preparationDispatchedAt<0?-1:SystemClock.elapsedRealtime()-preparationDispatchedAt;
            event("RESULT_CONFIRMED",new JSONObject().put("preparation_index",timingPending).put("duration_ms",elapsed).put("verified",preparation.verified));
            log("[计时] 第"+(timingPending+1)+"步结果确认，派发后 "+elapsed+"ms");
            preparationDispatchedAt=-1;
        }
        if(preparation.acquired&&!timingAcquired)event("YUDANG_CONFIRMED",new JSONObject().put("preparation_pending",timingPending));
        timingVerified=preparation.verified;timingPending=preparation.pending;timingAcquired=preparation.acquired;
    }

    private CaptureService.Snapshot fresh() throws Exception {
        long end=SystemClock.elapsedRealtime()+3000;
        while(!stopped&&SystemClock.elapsedRealtime()<end){
            CaptureService.Snapshot s=CaptureService.snapshot();
            if(s!=null&&s.number>lastFrame&&s.acquiredNs>inputAfterNs&&System.nanoTime()>=s.acquiredNs&&System.nanoTime()-s.acquiredNs<2_000_000_000L){lastFrame=s.number;lastAcquiredNs=s.acquiredNs;return s;}
            Thread.sleep(40);
        }
        if(stopped)return null;
        throw new java.io.IOException("截屏停止更新");
    }
    private Vision.Match match(Bitmap b,String key)throws Exception{
        long startedNs=System.nanoTime();
        Vision.Match hit;
        try{hit=FarmRecognizer.template(b,vision,templates,key,templates.getJSONObject(key).getDouble("threshold"),trackerFrames);}
        finally{matchNanos+=System.nanoTime()-startedNs;matchCalls++;}
        if(frameScores!=null)frameScores.put(key,hit==null?0:hit.score);return hit;
    }
    private void atomic(String name,JSONObject data)throws Exception{
        AtomicFile f=new AtomicFile(new File(folder,name));FileOutputStream out=null;
        try{out=f.startWrite();out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));f.finishWrite(out);if("state.json".equals(name)){try{timingProgress();}catch(Exception error){LogBus.get().w("farm","[计时] 状态时间线记录失败："+error.getClass().getSimpleName());}}}
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    private void screenshot(Bitmap b,String name)throws Exception{
        try(FileOutputStream out=new FileOutputStream(new File(folder,name))){
            if(!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("截图编码失败");
            out.flush();out.getFD().sync();
        }
    }
    private JSONObject state()throws Exception{
        return new JSONObject().put("timestamp",FarmTiming.wallTime(System.currentTimeMillis())).put("wall_time_ms",System.currentTimeMillis())
            .put("run_elapsed_ms",timing.elapsed(SystemClock.elapsedRealtime())).put("timing_step",timing.step)
            .put("step_elapsed_ms",timing.stepElapsed(SystemClock.elapsedRealtime())).put("wait_reason",waitReason)
            .put("recognition_ms",recognitionMs).put("screenshot_ms",screenshotMs).put("template_match_ms",matchNanos/1_000_000).put("template_match_calls",matchCalls)
            .put("target",counter.target).put("completed",counter.completed)
            .put("battle_entry_rejected",counter.entryRejected).put("battle_wait_until_ms",battleNotBefore)
            .put("battle_id",counter.battleId).put("failed",counter.failed).put("interrupted",counter.interrupted)
            .put("unknown",counter.unknown).put("phase",counter.phase.name()).put("reason",counter.reason)
            .put("frame",lastFrame).put("stop_requested",stopped)
            .put("preparation_next",preparation.next).put("preparation_pending",preparation.pending)
            .put("preparation_reason",preparation.reason).put("preparation_stopped",preparation.stopped)
            .put("last_image_age_ms",lastAcquiredNs==0?-1:(System.nanoTime()-lastAcquiredNs)/1_000_000)
            .put("preparation_verified",preparation.verified).put("yudang_acquired",preparation.acquired)
            .put("preparation_resumed",preparation.resumed).put("resume_candidate",preparation.candidate)
            .put("navigation_task",listedNavigation==null?"":listedNavigation.getString("name")).put("navigation_followed",navigationFollowed).put("giver_dispatched",giverDispatched).put("white_tracking_route",trackedNavigationActive)
            .put("task_inventory_checked",taskInventoryChecked).put("search_chapter",search.chapter).put("search_tab",search.tab).put("search_page",search.page);
    }
    private void tap(Vision.Match hit,String action,String image)throws Exception{tap(hit,action,image,0,0);}
    private void tap(Vision.Match hit,String action,String image,float dx,float dy)throws Exception{
        synchronized(inputLock){
            if(stopped)return;
            if(!HelperAccessibilityService.ready()||!captain.equals(acc.currentPackage()))
                throw new IllegalStateException("队长前台或无障碍状态变化");
            JSONObject intent=state().put("action",action).put("status","DISPATCH_INTENT")
                .put("before",image).put("x",hit.fx()+dx).put("y",hit.fy()+dy).put("score",hit.score);
            String file=String.format(java.util.Locale.ROOT,"action-%05d.json",sequence);
            atomic(file,intent); // 落盘失败不派发；派发失败/中断不重试。
            if(stopped)return;
            if(hit.fx()+dx<0||hit.fx()+dx>1||hit.fy()+dy<0||hit.fy()+dy>1)throw new IllegalArgumentException("点击超出屏幕");
            long dispatchAt=SystemClock.elapsedRealtime();
            intent.put("dispatch_started_at",FarmTiming.wallTime(System.currentTimeMillis())).put("dispatch_started_elapsed_ms",timing.elapsed(dispatchAt));
            atomic(file,intent);
            event("ACTION_DISPATCH",new JSONObject().put("action",action).put("file",file).put("since_previous_input_ms",timing.inputInterval(dispatchAt)));
            if(preparation.pending>=0&&action.equals("PREPARATION_"+templates.getJSONArray("_preparation").getJSONObject(preparation.pending).getString("name")))preparationDispatchedAt=dispatchAt;
            log("[动作] "+action+"；证据 "+file);
            acc.tapN(hit.fx()+dx,hit.fy()+dy);inputAfterNs=System.nanoTime()+250_000_000L;
            atomic(file,intent.put("status","DISPATCHED_RESULT_UNVERIFIED").put("dispatch_returned_at",FarmTiming.wallTime(System.currentTimeMillis()))
                .put("dispatch_returned_elapsed_ms",timing.elapsed(SystemClock.elapsedRealtime())));lastActionFile=file;
        }
    }
    private void scrollDiscovery(boolean reset,String image)throws Exception{
        synchronized(inputLock){
            if(stopped)return;
            JSONObject cfg=templates.getJSONObject("_discovery");JSONArray a=cfg.getJSONArray("swipe");
            float x0=(float)a.getDouble(0),y0=(float)a.getDouble(reset?3:1),x1=(float)a.getDouble(2),y1=(float)a.getDouble(reset?1:3);
            String file=String.format(java.util.Locale.ROOT,"action-%05d.json",sequence);
            JSONObject intent=state().put("action",reset?"TASK_LIST_RESET":"TASK_LIST_SCROLL").put("status","DISPATCH_INTENT").put("before",image);
            atomic(file,intent);
            if(!captain.equals(acc.currentPackage()))throw new IllegalStateException("任务检索时队长不在前台");
            long dispatchAt=SystemClock.elapsedRealtime();
            intent.put("dispatch_started_at",FarmTiming.wallTime(System.currentTimeMillis())).put("dispatch_started_elapsed_ms",timing.elapsed(dispatchAt));atomic(file,intent);
            event("ACTION_DISPATCH",new JSONObject().put("action",intent.getString("action")).put("file",file).put("since_previous_input_ms",timing.inputInterval(dispatchAt)));
            log("[动作] "+intent.getString("action")+"；证据 "+file);
            acc.swipeN(x0,y0,x1,y1,a.getInt(4));inputAfterNs=System.nanoTime()+(a.getInt(4)+300)*1_000_000L;
            atomic(file,intent.put("status","DISPATCHED_RESULT_UNVERIFIED").put("dispatch_returned_at",FarmTiming.wallTime(System.currentTimeMillis()))
                .put("dispatch_returned_elapsed_ms",timing.elapsed(SystemClock.elapsedRealtime())));lastActionFile=file;
        }
    }
    /** 白字追踪可重复；只有同一目标的本地图提示允许随后NPC交互。 */
    private boolean traceTarget(Bitmap b,String key,String image)throws Exception{
        if(!trackedTarget.equals(key)){trackedTarget=key;trackedTaps=0;trackedHere=false;trackedNextTap=0;}
        if(trackedHere)return true;
        Vision.Match title=match(b,key);
        if(title==null||FarmRecognizer.greenAtMatch(b,title))return false;
        if(trackedTaps>0&&match(b,"tracked_target_here")!=null){trackedHere=true;log("[追踪] 已确认目标在本地图，转目标交互");return true;}
        if(SystemClock.elapsedRealtime()<trackedNextTap){waitFor("TRACKER_INPUT_INTERVAL");return false;}
        waitFor("");
        if(trackedTaps>=templates.getJSONObject("_discovery").getInt("tracker_max_taps"))throw new IllegalStateException("白字追踪未确认目标在本地图");
        trackedTaps++;trackedNextTap=SystemClock.elapsedRealtime()+templates.getJSONObject("_discovery").getLong("tracker_interval_ms");
        tap(title,"TASK_WHITE_TRACK_"+trackedTaps,image);return false;
    }
    /** 先查余党/6-8，再查6-7、6-6；已接与可接都查，不重跑固定起点。 */
    private void discover(Bitmap b,boolean world,String image)throws Exception{
        if(discoveryDeadline==0)discoveryDeadline=SystemClock.elapsedRealtime()+360000;
        if(SystemClock.elapsedRealtime()>discoveryDeadline)throw new IllegalStateException("当前任务入口结果未知，留图停止");
        JSONObject cfg=templates.getJSONObject("_discovery");
        if(yudangListResume){
            if(SystemClock.elapsedRealtime()>yudangListCloseDeadline)throw new IllegalStateException("余党列表关闭结果未知，保留现场");
            if(world)throw new IllegalStateException("余党列表已关闭但世界追踪未确认，保留现场");
            waitFor("YUDANG_LIST_RETURN");return;
        }
        if(!rowSelected&&!world&&match(b,"discover_detail_title_intro")!=null&&match(b,"discover_detail_follow")!=null){
            Vision.Match close=match(b,"discover_detail_close");
            if(close!=null){tap(close,"TASK_DETAIL_INSPECT_CLOSE",image);return;}
        }
        if(world&&!rowSelected){
            JSONObject tracked=FarmRecognizer.trackedQuest(b,this::match,cfg);
            if(tracked!=null){
                trackedNavigationActive=true;listedNavigation=tracked;navigationChoice=tracked;rowSelected=true;taskInventoryChecked=true;search.tab=1;
                log("[追踪] 从当前白字任务接续 "+tracked.getString("name"));
            }
        }
        if(rowSelected&&trackedNavigationActive&&listedNavigation!=null&&listedNavigation.has("tracked_title")&&!giverDispatched&&world){
            if(!traceTarget(b,listedNavigation.getString("tracked_title"),image))return;
            navigationChoice=listedNavigation;navigationFollowed=true;
        }
        if(rowSelected){
            if(search.tab==0&&!detailFollowDispatched&&!world){
                JSONArray choices=cfg.getJSONArray("navigation");JSONObject detail=null;
                for(int i=0;i<choices.length();i++){
                    JSONObject choice=choices.getJSONObject(i);
                    if(choice.has("detail_title")&&match(b,choice.getString("detail_title"))!=null){
                        if(detail!=null)throw new IllegalStateException("已接详情任务标题不唯一");
                        detail=choice;
                    }
                }
                Vision.Match follow=detail==null?null:match(b,"discover_detail_follow");
                if(follow!=null){detailFollowDispatched=true;tap(follow,"TASK_DETAIL_FOLLOW_"+detail.getString("name"),image);return;}
            }
            if(!navigationFollowed){
                Vision.Match confirm=match(b,"discover_follow_escape");
                if(confirm==null){
                    JSONObject direct=FarmRecognizer.directNavigation(b,this::match,listedNavigation,world);
                    if(direct==null)return;
                    navigationChoice=direct;navigationFollowed=true;giverDispatched=true;
                    JSONArray offset=direct.getJSONArray("offset");
                    tap(match(b,direct.getString("giver")),"TASK_GIVER_DIRECT_"+direct.getString("name"),image,(float)offset.getDouble(0),(float)offset.getDouble(1));
                    return;
                }
                JSONArray choices=cfg.getJSONArray("navigation");
                for(int i=0;i<choices.length();i++)if(match(b,choices.getJSONObject(i).getString("follow_title"))!=null){
                    if(navigationChoice!=null)throw new IllegalStateException("任务导航标题不唯一");navigationChoice=choices.getJSONObject(i);
                }
                if(navigationChoice==null)throw new IllegalStateException("追踪任务不在必要前置白名单，保留现场");
                navigationFollowed=true;tap(confirm,"TASK_FOLLOW_"+navigationChoice.getString("name"),image);return;
            }
            if(navigationFollowed&&giverDispatched&&!world&&match(b,navigationChoice.getString("title"))!=null
                &&match(b,"accept_risk")==null&&!FarmRecognizer.completedStamp(b,cfg.getJSONArray("completion_box"))){
                Vision.Match close=match(b,"discover_quest_close");if(close!=null){tap(close,"TASK_RESUME_INCOMPLETE",image);return;}
            }
            if(world&&!giverDispatched){
                Vision.Match giver=match(b,navigationChoice.getString("giver"));
                if(giver==null||match(b,navigationChoice.getString("map"))==null)return;
                JSONArray offset=navigationChoice.getJSONArray("offset");giverDispatched=true;
                tap(giver,"TASK_GIVER_"+navigationChoice.getString("name"),image,(float)offset.getDouble(0),(float)offset.getDouble(1));
            }
            return;
        }
        boolean list=match(b,"discover_list_title")!=null&&match(b,"discover_tasks_close")!=null;
        if(!list){
            search.endConfirmed(false);
            // 先关已识别的任务查看窗再核对当前进度；绝不在检查进度之前点接受/完成。
            if(!world&&!discoveryOpened){
                Vision.Match close=match(b,"discover_quest_close");boolean known=false;
                JSONArray route=templates.getJSONArray("_preparation");
                if(close!=null)for(int i=0;i<route.length();i++){
                    JSONObject r=route.getJSONObject(i);String name=r.getString("name");
                    if((name.startsWith("accept_")||name.startsWith("finish_"))&&match(b,r.getJSONArray("guards").getString(0))!=null){known=true;break;}
                }
                if(known){tap(close,"TASK_INSPECT_CLOSE",image);return;}
                Vision.Match intro=match(b,"discover_intro_close");
                if(intro!=null&&match(b,"discover_intro_title")!=null){tap(intro,"TASK_INTRO_INSPECT_CLOSE",image);return;}
            }
            if(world&&!discoveryOpened){Vision.Match open=match(b,"discover_world_tasks");if(open!=null){discoveryOpened=true;tap(open,"TASK_LIST_OPEN",image);}}
            return;
        }
        // 标题点击会收起说明，但画面更新晚于输入回执；只等待，不重发点击。
        if(match(b,"discover_task_tooltip")!=null){
            if(tooltipDismissed){
                if(SystemClock.elapsedRealtime()<tooltipSettleUntil){waitFor("TASK_TOOLTIP_VISIBLE");return;}
                throw new IllegalStateException("任务列表被说明浮层遮挡，无法确认进度");
            }
            tooltipDismissed=true;tap(match(b,"discover_list_title"),"TASK_LIST_DISMISS_TOOLTIP",image);
            tooltipSettleUntil=SystemClock.elapsedRealtime()+cfg.getLong("tooltip_settle_ms");return;
        }
        waitFor("");
        Vision.Match yudangClose=FarmRecognizer.acceptedYudangList(b,this::match,templates);
        if(yudangClose!=null){
            yudangListResume=true;
            yudangListCloseDeadline=SystemClock.elapsedRealtime()+cfg.getLong("yudang_list_close_timeout_ms");
            log("[前置] 已接列表确认余党，仅关闭列表后重新核对世界追踪");
            tap(yudangClose,"YUDANG_LIST_CLOSE_ONLY",image);return;
        }
        JSONArray tabs=cfg.getJSONArray("tab_boxes");boolean accepted=FarmRecognizer.goldTab(b,tabs.getJSONArray(0)),available=FarmRecognizer.goldTab(b,tabs.getJSONArray(1));
        if(accepted==available){search.endConfirmed(false);return;}
        if((search.tab==0)!=accepted){
            search.endConfirmed(false);
            Vision.Match tab=match(b,search.tab==0?"discover_accepted":"discover_available_idle");
            if(tab!=null)tap(tab,"TASK_TAB_"+search.tab,image);return;
        }
        FarmRecognizer.Scroll boundary=FarmRecognizer.scrollbar(b,cfg);
        if(frameScores!=null)frameScores.put("scrollbar_first",boundary.first).put("scrollbar_last",boundary.last).put("scrollbar_single",boundary.single);
        if(!search.ready){
            if(boundary.top||boundary.single)search.ready=true;
            else{
                if(search.reset<=0)throw new IllegalStateException("任务列表到顶未确认，保留现场");
                search.reset--;scrollDiscovery(true,image);return;
            }
        }
        Vision.Match row=match(b,"discover_chapter_"+search.chapter);
        if(row!=null){
            JSONArray column=cfg.getJSONArray("trace_column");float y=row.fy();Vision.Match trace=vision.findNearRegion(b,templates.getJSONObject(search.tab==0?"discover_detail":"discover_trace").getString("template"),search.tab==0?.94:.90,new float[]{(float)column.getDouble(0),Math.max((float)column.getDouble(4),y+(float)column.getDouble(2)),(float)column.getDouble(1),Math.min((float)column.getDouble(5),y+(float)column.getDouble(3))});
            if(trace==null)throw new IllegalStateException("找到章节但同行详情/追踪按钮未确认");
            JSONArray routes=cfg.getJSONArray("navigation");
            for(int i=0;i<routes.length();i++){
                JSONObject route=routes.getJSONObject(i);if(!route.has("row_title"))continue;
                Vision.Match title=match(b,route.getString("row_title"));
                if(title!=null&&Math.abs(title.fy()-row.fy())<cfg.getDouble("row_title_tolerance")){
                    if(listedNavigation!=null)throw new IllegalStateException("同行任务完整标题不唯一");
                    listedNavigation=route;
                }
            }
            if(listedNavigation==null)throw new IllegalStateException("章节同行的完整任务名未确认，保留任务列表");
            rowSelected=true;taskInventoryChecked=true;log("[前置] 从当前任务列表接续6-"+search.chapter+"，已接/可接="+search.tab);
            tap(trace,(search.tab==0?"TASK_DETAIL_CHAPTER_":"TASK_TRACE_CHAPTER_")+search.chapter,image);return;
        }
        if(boundary.bottom||boundary.single){
            if(!search.endConfirmed(true))return;
            search.finishList(cfg.getInt("reset_swipes"));tooltipDismissed=false;
            if(search.chapter<6)throw new IllegalStateException("已接和可接均未找到可识别的6-8/6-7/6-6，留图停止");
            log("[前置] 检索6-"+search.chapter+"，列表="+search.tab);
        }else{
            search.endConfirmed(false);
            if(++search.page>=cfg.getInt("max_pages"))throw new IllegalStateException("任务列表到底未确认，不降低任务章节");
            scrollDiscovery(false,image);
        }
    }
    private long pacing(String key)throws Exception{return templates.getJSONObject("_battle_pacing").getLong(key);}
    /** 只有明确冷却正文+错误标题才能关闭；普通错误保持未知停止。 */
    private boolean handleCooldown(Bitmap b,String image)throws Exception{
        if(!FarmRecognizer.battleCooldown(b,this::match)){cooldownCloseDeadline=0;return false;}
        if(cooldownCloseDeadline>0){
            if(SystemClock.elapsedRealtime()>cooldownCloseDeadline)throw new IllegalStateException("冷却提示关闭结果未知");
            return true;
        }
        Vision.Match close=match(b,"battle_cooldown_close");
        if(close==null)throw new IllegalStateException("冷却提示关闭按钮未确认");
        long now=SystemClock.elapsedRealtime();
        if(preparation.acquired){
            if(!counter.rejectEntry(now,pacing("farm_rejected_wait_ms"),(int)pacing("farm_max_rejections")))throw new IllegalStateException("冷却拒绝状态不符或重复拒绝");
        }else if(preparation.pending>=0){
            int index=preparation.pending;String name=templates.getJSONArray("_preparation").getJSONObject(index).getString("name");
            if(!name.startsWith("enemy_")||pendingBattleObserved||rejectedEnemies.getOrDefault(index,0)>=templates.getJSONObject("_battle_pacing").getInt("max_rejections"))
                throw new IllegalStateException("冷却拒绝不能安全接续");
            rejectedEnemies.put(index,rejectedEnemies.getOrDefault(index,0)+1);rejectedEnemy=index;
        }else{
            if(inheritedCooldownClosed||taskInventoryChecked)throw new IllegalStateException("无对应入场意图的冷却拒绝");
            inheritedCooldownClosed=true;
        }
        battleNotBefore=now+pacing(preparation.acquired?"farm_rejected_wait_ms":"rejected_wait_ms");
        log("[战斗] 游戏明确拒绝入场，等待冷却；不计完成场次");
        tap(close,"BATTLE_COOLDOWN_CLOSE",image);cooldownCloseDeadline=now+3000;return true;
    }
    /** 已采集白名单有序接续；未识别分支停止，不盲点通用完成。 */
    private void prepare(Bitmap b,boolean world,boolean quest,boolean victory,boolean exit,String image)throws Exception{
        if(FarmRecognizer.bagFull(b,this::match)){
            preparation.stopped=true;preparation.reason="BAG_FULL";
            counter.stop("BAG_FULL: 背包空间不足，请清理后接续当前任务");
            log("[前置] 背包满，交付未成功，保留错误画面并停止");return;
        }
        if(world&&quest){preparation.acquire();counter.deferEntryUntil(battleNotBefore);log("[前置] 已确认余党，切换专用战斗循环");return;}
        if(preparation.next>=0&&preparation.timeout(SystemClock.elapsedRealtime()))throw new IllegalStateException(preparation.reason);
        if(victory&&exit){
            preparation.noCheckpoint();
            if(!preExitSent){tap(match(b,"exit_battle"),"PREPARATION_EXIT",image);preExitSent=true;battleNotBefore=SystemClock.elapsedRealtime()+pacing("after_exit_ms");}
            return;
        }
        if(world&&!victory&&!exit)preExitSent=false;
        if(challengeDispatchAt>0&&!pendingBattleObserved&&SystemClock.elapsedRealtime()-challengeDispatchAt>templates.getJSONObject("_challenge_resume").getLong("entry_timeout_ms"))throw new IllegalStateException("继续挑战入场结果未知");
        if(taskInventoryChecked&&FarmRecognizer.continueChallenge(b,this::match,templates)!=null){
            if(SystemClock.elapsedRealtime()<battleNotBefore){waitFor("BATTLE_COOLDOWN");return;}
            if(!challengeSent){
                challengeSent=true;
                challengeDispatchAt=SystemClock.elapsedRealtime();pendingBattleObserved=false;
                tap(FarmRecognizer.continueChallenge(b,this::match,templates),"PREPARATION_CHALLENGE_CONTINUE_SUN",image);
            }
            return;
        }
        if(!taskInventoryChecked){discover(b,world,image);return;}
        JSONArray steps=templates.getJSONArray("_preparation");
        if(rejectedEnemy>=0){
            if(!world||SystemClock.elapsedRealtime()<battleNotBefore)return;
            JSONObject rejected=steps.getJSONObject(rejectedEnemy);
            if(FarmRecognizer.checkpoint(b,this::match,templates,rejected,true)==null)return;
            if(!preparation.retryRejectedEnemy(rejectedEnemy,SystemClock.elapsedRealtime()))throw new IllegalStateException("冷却后接续状态已改变");
            rejectedEnemy=-1;
        }
        int first=preparation.next<0?0:preparation.next;
        int end=steps.length();
        JSONObject selected=null;Vision.Match action=null;int selectedIndex=-1;
        for(int i=first;i<end;i++){
            JSONObject rule=steps.getJSONObject(i);
            if((preparation.next<0||i>first)&&!rule.getBoolean("startable"))continue;
            Vision.Match m=FarmRecognizer.checkpoint(b,this::match,templates,rule,world);
            if(m==null&&i==first&&preparation.next>=0)m=FarmRecognizer.handoff(b,this::match,rule,world,preparation.pending);
            if(m==null)continue;
            if(selected!=null){
                if(!selected.getString("name").equals(rule.getString("name")))throw new IllegalStateException("前置状态不唯一，停止并保留截图");
                continue; // equivalent occurrences: use earliest unconsumed checkpoint
            }
            selected=rule;action=m;selectedIndex=i;
            if(preparation.next>=0&&i==first)break; // exact expected state always wins
        }
        if(selected==null){
            preparation.noCheckpoint();
            if(world&&npcApproachIndex==preparation.pending&&npcApproachIndex>=0&&npcApproachRetries==0&&SystemClock.elapsedRealtime()>=npcApproachAfter){
                JSONObject prior=steps.getJSONObject(npcApproachIndex);JSONArray guards=prior.getJSONArray("guards");
                Vision.Match npc=match(b,prior.getString("action"));
                if(npc!=null&&match(b,guards.getString(1))!=null){
                    npcApproachRetries++;JSONArray offset=prior.getJSONArray("offset");
                    tap(npc,"PREPARATION_NPC_INTERACT_"+prior.getString("name"),image,(float)offset.getDouble(0),(float)offset.getDouble(1));return;
                }
            }
            if(preparation.next<0)discover(b,world,image);else waitFor("EXPECTED_STATE_NOT_FOUND");return;
        }
        String name=selected.getString("name");
        if(!"BATTLE_COOLDOWN".equals(waitReason))waitFor("");
        if(FarmPreparation.waitBeforeAutoBattle(name)&&SystemClock.elapsedRealtime()<battleNotBefore){waitFor("BATTLE_COOLDOWN");return;}
        waitFor("");
        if(world&&!selected.getBoolean("tracker_green")&&selected.getJSONArray("guards").length()>0){
            String tracker=selected.getJSONArray("guards").getString(0);
            if(!"new_task".equals(templates.getJSONObject(tracker).optString("glyph_palette"))&&match(b,tracker)!=null&&!traceTarget(b,tracker,image))return;
        }
        if(!FarmPreparation.allowed(name,preparation.acquired))throw new IllegalStateException("禁止余党交付动作");
        synchronized(inputLock){
            if(stopped)return;
            if(!preparation.select(selectedIndex,selected.getBoolean("startable")))return;
            preparation.dispatched(selectedIndex,SystemClock.elapsedRealtime());atomic("state.json",state());
        }
        JSONArray offset=selected.getJSONArray("offset");
        log("[前置] "+(selectedIndex+1)+"/"+steps.length()+" "+name);
        if(name.startsWith("enemy_"))pendingBattleObserved=false;
        tap(action,"PREPARATION_"+name,image,(float)offset.getDouble(0),(float)offset.getDouble(1));
        if(world&&name.startsWith("npc_")){
            npcApproachIndex=selectedIndex;npcApproachRetries=0;
            npcApproachAfter=SystemClock.elapsedRealtime()+templates.getJSONObject("_discovery").getLong("npc_approach_settle_ms");
        }
    }
    public void run(){
        if(!RunGate.acquire(this,this::requestStop)){listener.onDone("已有流程控制游戏",false);return;}
        boolean confirmed=false;
        try{
            ScriptStatus.begin("刷铜 · 当前队长 "+counter.target+" 场");HelperAccessibilityService.showScriptStatus();
            atomic("state.json",state());
            event("RUN_BEGIN",new JSONObject().put("target",counter.target));
            log("[刷铜] 仅打镖局打手，余党任务保留；目标 "+counter.target+" 场");
            long readyDeadline=SystemClock.elapsedRealtime()+10000;
            // 从助手界面返回已登录队长；不登录其他账号，不启动组队。
            acc.launch(captain);
            inputAfterNs=System.nanoTime(); // 首帧必须在本次启动之后采集，不能沿用展开悬浮窗的旧帧。
            while(!counter.terminal()&&!stopped){
                if(!captain.equals(acc.currentPackage())){
                    if(SystemClock.elapsedRealtime()>readyDeadline)throw new IllegalStateException("队长不在前台");
                    Thread.sleep(80);continue;
                }
                CaptureService.Snapshot s=fresh();if(s==null)break;
                timingStep();
                matchNanos=0;matchCalls=0;
                long recognizeAt=SystemClock.elapsedRealtime();
                Bitmap b=s.bitmap;
                Map<String,Vision.Match> hits=new HashMap<>();
                String[] names={"tracker_yudang_white","tracker_yudang_green","farm_npc","world_bag",
                    "battle_coin","battle_chest","battle_escape","battle_yudang_name","victory","exit_battle",
                    "finish_risk","accept_risk","quest_close","bag_close","reward_title"};
                JSONObject scores=new JSONObject();frameScores=scores;
                for(String name:names){Vision.Match m=match(b,name);hits.put(name,m);if(m!=null)scores.put(name,m.score);}
                if(stopped)break;
                // 弹窗背后的世界控件也会命中，必须优先排除模态。
                boolean loading=match(b,"quest_navigation_loading")!=null;
                boolean forbidden=preparation.acquired?FarmRecognizer.questModal(b,this::match):FarmRecognizer.modal(b,this::match);
                boolean quest=hits.get("tracker_yudang_white")!=null||hits.get("tracker_yudang_green")!=null;
                boolean world=hits.get("world_bag")!=null&&!forbidden&&!loading;
                boolean battle=!loading&&FarmRecognizer.activeBattle(b,(bitmap,key)->hits.get(key));
                boolean victory=!loading&&hits.get("victory")!=null,exit=!loading&&hits.get("exit_battle")!=null;
                recognitionMs=SystemClock.elapsedRealtime()-recognizeAt;
                String image=String.format(java.util.Locale.ROOT,"frame-%05d.png",++sequence);
                long screenshotAt=SystemClock.elapsedRealtime();screenshot(b,image);screenshotMs=SystemClock.elapsedRealtime()-screenshotAt;
                if(lastActionFile!=null){atomic(lastActionFile+".after.json",state().put("after",image));lastActionFile=null;}
                if(!preparation.acquired&&battle)pendingBattleObserved=true;
                // 前置可能极短，结算已明确出现时结束“继续挑战”的入场等待；刷铜计数仍必须实际战斗帧。
                if(!preparation.acquired&&(battle||(victory&&exit)))challengeDispatchAt=0;
                if(handleCooldown(b,image)){
                    atomic(String.format(java.util.Locale.ROOT,"frame-%05d.json",sequence),state().put("image",image).put("scores",scores).put("action","COOLDOWN_REJECTED"));
                    atomic("state.json",state());Thread.sleep(90);continue;
                }
                if(!preparation.acquired){
                    prepare(b,world,quest,victory,exit,image);
                    atomic(String.format(java.util.Locale.ROOT,"frame-%05d.json",sequence),state().put("image",image).put("scores",scores).put("action","PREPARATION"));
                    atomic("state.json",state());Thread.sleep(90);continue;
                }
                // 组队/换角汇合会把队长带回城内；保留余党时先回到打手所在地图。
                // 这里只点已确认的余党追踪文字，绝不进入交付/领奖动作集合。
                if(counter.phase==FarmCounter.Phase.READY&&hits.get("farm_npc")==null&&!forbidden&&!victory&&!exit){
                    if(farmRouteDeadline==0)farmRouteDeadline=SystemClock.elapsedRealtime()+30000;
                    if(SystemClock.elapsedRealtime()>farmRouteDeadline)throw new IllegalStateException("余党导航未确认镖局打手，保留任务");
                    Vision.Match route=hits.get("tracker_yudang_green");if(route==null)route=hits.get("tracker_yudang_white");
                    if(world&&route!=null&&SystemClock.elapsedRealtime()>=farmRouteNext){
                        if(++farmRouteTaps>templates.getJSONObject("_discovery").getInt("tracker_max_taps"))throw new IllegalStateException("余党导航次数已达上限");
                        farmRouteNext=SystemClock.elapsedRealtime()+templates.getJSONObject("_discovery").getLong("tracker_interval_ms");
                        // 点击匹配文字内部的右侧，避免仅选中任务编号。
                        tap(route,"YUDANG_ROUTE_"+farmRouteTaps,image,route.w/(3f*b.getWidth()),0);
                    }
                    waitFor("YUDANG_RETURN_TO_NPC");
                    atomic(String.format(java.util.Locale.ROOT,"frame-%05d.json",sequence),state().put("image",image).put("scores",scores).put("action","YUDANG_ROUTE"));
                    atomic("state.json",state());Thread.sleep(90);continue;
                }
                // 传图时NPC可能先于追踪文字恢复，不能把这一帧当任务丢失，也不能据此开战。
                if(counter.phase==FarmCounter.Phase.READY&&hits.get("farm_npc")!=null&&!quest&&farmRouteDeadline>0){
                    if(SystemClock.elapsedRealtime()>farmRouteDeadline)throw new IllegalStateException("到达打手后余党标题仍未确认");
                    waitFor("YUDANG_CONFIRM_AFTER_ROUTE");
                    atomic(String.format(java.util.Locale.ROOT,"frame-%05d.json",sequence),state().put("image",image).put("scores",scores).put("action","WAIT_YUDANG_TITLE"));
                    atomic("state.json",state());Thread.sleep(90);continue;
                }
                if(hits.get("farm_npc")!=null&&quest){farmRouteDeadline=0;farmRouteTaps=0;}
                FarmCounter.Action action;
                if(loading)waitFor("BATTLE_ENTRY_LOADING");
                else if(SystemClock.elapsedRealtime()<battleNotBefore)waitFor("BATTLE_COOLDOWN");
                else waitFor("");
                synchronized(inputLock){
                    if(stopped)break;
                    action=counter.step(SystemClock.elapsedRealtime(),world,quest,battle,
                        victory,exit,hits.get("farm_npc")!=null,forbidden,false);
                }
                JSONObject record=state().put("image",image).put("scores",scores).put("action",action.name());
                atomic(String.format(java.util.Locale.ROOT,"frame-%05d.json",sequence),record);
                atomic("state.json",state());
                String status=counter.phase+" "+counter.completed+"/"+counter.target;
                if(!status.equals(previous)){log("[刷铜] "+status+"，第 "+counter.battleId+" 场");previous=status;}
                if(action==FarmCounter.Action.NPC)tap(hits.get("farm_npc"),"NPC",image);
                else if(action==FarmCounter.Action.EXIT){tap(hits.get("exit_battle"),"EXIT",image);battleNotBefore=SystemClock.elapsedRealtime()+pacing("farm_after_exit_ms");counter.deferEntryUntil(battleNotBefore);}
                Thread.sleep(90);
            }
            if(stopped){counter.stop("USER_STOP");if(!preparation.acquired){preparation.stopped=true;preparation.reason="USER_STOP_PREPARATION";}}
            confirmed=counter.phase==FarmCounter.Phase.DONE;
            atomic("state.json",state());
            CaptureService.Snapshot end=CaptureService.snapshot();
            long screenshotDeadline=SystemClock.elapsedRealtime()+1500;
            while(lastActionFile!=null&&(end==null||end.acquiredNs<=inputAfterNs)&&SystemClock.elapsedRealtime()<screenshotDeadline){Thread.sleep(40);end=CaptureService.snapshot();}
            if(end!=null){
                screenshot(end.bitmap,"stopped.png");
                if(lastActionFile!=null)atomic(lastActionFile+".after.json",state().put("after","stopped.png").put("fresh_after_input",end.acquiredNs>inputAfterNs));
            }
        }catch(Exception error){
            if(stopped){counter.stop("USER_STOP");if(!preparation.acquired){preparation.stopped=true;preparation.reason="USER_STOP_PREPARATION";}}
            else if(!counter.terminal()){counter.unknown++;counter.reason=error.getClass().getSimpleName()+": "+error.getMessage();counter.phase=FarmCounter.Phase.STOPPED;}
            try{atomic("state.json",state());}catch(Exception ignored){}
            log("[刷铜] 停止："+counter.reason);
        }finally{
            synchronized(inputLock){stopped=true;}
            waitFor("");
            try{event("STEP_END",new JSONObject().put("duration_ms",timing.stepElapsed(SystemClock.elapsedRealtime())).put("next_step","运行结束"));}catch(Exception ignored){}
            try{event("RUN_END",new JSONObject().put("duration_ms",timing.elapsed(SystemClock.elapsedRealtime())).put("reason",counter.reason).put("completed",counter.completed));}catch(Exception ignored){}
            RunGate.release(this);
            String summary="完成 "+counter.completed+"/"+counter.target+" 场；失败 "+counter.failed
                +"，中断 "+counter.interrupted+"，未知 "+counter.unknown+"；"+counter.reason+"；证据 "+folder.getName();
            ScriptStatus.finish(summary,confirmed);listener.onDone(summary,confirmed);
        }
    }
}
