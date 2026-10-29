package com.jianghuol.helper;

import android.content.Context;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** 悬浮窗唯一刷铜入口；串行复用已有脚本，外层租约覆盖阶段切换。 */
final class CopperWorkflow {
    private static volatile boolean active, stopped;
    static boolean active(){return active;}
    static void startCurrent(Context context,String current){
        try{org.json.JSONArray items=new org.json.JSONArray().put(new org.json.JSONObject().put("kind",CopperPlan.CURRENT).put("count",context.getSharedPreferences("farm",0).getInt("count",3)));
            start(context,current,items);
        }catch(Exception e){message(context,"无法启动");}
    }
    static void startSchedule(Context context,String current){
        try{start(context,current,new CopperSchedule(context).load());}
        catch(Exception e){message(context,"任务读取失败");}
    }
    private static synchronized void start(Context context,String current,org.json.JSONArray items){
        Context ctx=context.getApplicationContext();
        ContextHolder.set(ctx);
        if(active||RunGate.busy()){message(ctx,"已有流程运行");return;}
        if(!HelperAccessibilityService.ready()||!CaptureService.running()){
            message(ctx,"请在助手首页开启无障碍并授权截屏");return;
        }
        try{CopperSchedule.validate(items);}catch(Exception e){message(ctx,e.getMessage());return;}
        boolean known=false;for(String[] app:new RoleStore(ctx).gameApps())if(app[0].equals(current))known=true;
        for(int i=0;i<items.length();i++)if(CopperPlan.CURRENT.equals(items.optJSONObject(i).optString("kind"))&&!known){message(ctx,"请先回到要刷铜的游戏客户端");return;}
        try{
            boolean batches=false;for(int i=0;i<items.length();i++)batches|=!CopperPlan.CURRENT.equals(items.getJSONObject(i).getString("kind"));
            if(batches){
                HelperApp.Team team=HelperApp.loadTeam();
                RotationSession.require(team.members.size()==4&&!team.captain.account.isEmpty()&&!team.captain.password.isEmpty()&&!team.captain.charId.isEmpty(),"请先配置固定队长账号和四个队友客户端，并完成队长身份采集");
                java.util.Set<String> packages=new java.util.HashSet<>();packages.add(team.captain.pkg);
                for(HelperApp.Account member:team.members)RotationSession.require(packages.add(member.pkg),"队友客户端必须独立，不能包含队长");
                for(int i=0;i<items.length();i++){org.json.JSONArray rows=items.getJSONObject(i).optJSONArray("members");if(CopperPlan.hasMembers(items.getJSONObject(i).getString("kind")))for(int j=0;j<4;j++)RotationSession.require(!rows.getJSONObject(j).getString("account").equals(team.captain.account),"队友账号不能与队长相同");}
                new RoleRotation(ctx).setEnabled(true);
            }
        }catch(Exception e){message(ctx,e.getMessage());return;}
        active=true;stopped=false;
        new Thread(()->run(ctx,current,items),"copper-workflow").start();
    }
    private static void message(Context ctx,String text){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->Toast.makeText(ctx,text,Toast.LENGTH_LONG).show());}
    private static void check(){if(stopped)throw new IllegalStateException("用户停止，后续阶段未启动");}
    private static PhoneRunner runner(Context ctx,boolean[] result){
        return new PhoneRunner(HelperAccessibilityService.get(),new Vision(ctx.getAssets()),new PhoneRunner.Listener(){
            public void onLog(String line){ScriptStatus.update(line);}
            public void onDone(String summary,boolean ok){result[0]=ok;LogBus.get().i("copper",summary);}
        });
    }
    private static void run(Context ctx,String current,org.json.JSONArray items){
        Object lease=new Object();boolean acquired=false,ok=false;String summary="流程未完成";
        try{
            acquired=RunGate.beginSequence(lease,()->stopped=true);
            if(!acquired)throw new IllegalStateException("已有流程控制游戏");
            String captainPackage=new RoleStore(ctx).leader();
            String[] captainCredentials=new AccountStore(ctx).get(captainPackage);
            final boolean[] accountsPrepared={false};
            for(int index=0;index<items.length();index++){
                check();org.json.JSONObject item=items.getJSONObject(index);
                String kind=item.getString("kind");int count=item.optInt("count",0);
                boolean teamMode=!CopperPlan.CURRENT.equals(kind);
                final HelperApp.Team team;
                if(teamMode){
                    RoleStore roles=new RoleStore(ctx);List<String> packages=roles.members();
                    RotationSession.require(roles.leader().equals(captainPackage)&&java.util.Arrays.equals(captainCredentials,new AccountStore(ctx).get(captainPackage)),"固定队长配置发生变化");
                    RotationSession.require(packages.size()==4&&!packages.contains(captainPackage)&&new java.util.HashSet<>(packages).size()==4,"需要四个独立队友客户端");
                    if(CopperPlan.hasMembers(kind)){
                        accountsPrepared[0]=false;
                        org.json.JSONArray rows=item.getJSONArray("members");
                        android.content.SharedPreferences.Editor edit=ctx.getSharedPreferences("t046_accounts",0).edit();
                        for(int j=0;j<4;j++)edit.putString(packages.get(j),rows.getJSONObject(j).toString());
                        check();RotationSession.require(edit.commit(),"本批账号保存失败");
                    }
                    team=HelperApp.loadTeam();
                    java.util.Set<String> names=new java.util.HashSet<>();names.add(team.captain.account);
                    for(HelperApp.Account member:team.members)RotationSession.require(!member.account.isEmpty()&&!member.password.isEmpty()&&names.add(member.account),"请填写四个不同队友账号及密码，不能与队长重复");
                }else team=null;
                final String target=teamMode?team.captain.pkg:current;
                final int itemNumber=index+1;
                boolean passed=CopperPlan.execute(kind,new CopperPlan.Port(){
                    public boolean stopped(){return CopperWorkflow.stopped;}
                    public boolean team()throws Exception{
                        ScriptStatus.begin("操作 "+itemNumber+" · 登录并组队");
                        boolean[] login={false};PhoneRunner loginRunner=runner(ctx,login);
                        if(!accountsPrepared[0]&&!loginRunner.loginScheduledMembers(team))return false;check();accountsPrepared[0]=true;
                        boolean[] joined={false};runner(ctx,joined).run();check();
                        if(!joined[0])return false;requireReady(ctx,team);return true;
                    }
                    public boolean farm(int group)throws Exception{
                        check();if(teamMode)requireReady(ctx,team);final boolean[] farmDone={false};
                        if(teamMode){
                            ScriptStatus.begin("任务 "+itemNumber+" · 刷铜前准备");
                            PhoneRunner handoff=runner(ctx,new boolean[]{false});
                            if(!handoff.prepareCaptainForFarm(target)){
                                if(!handoff.readyRegroupNeeded())throw new IllegalStateException("未确认队长实时队伍与主界面，停止刷铜交接");
                                RotationSession saved=requireReady(ctx,team);boolean[] restored={false};
                                runner(ctx,restored).regroupReadyTeam(saved);check();
                                if(!restored[0]||!runner(ctx,new boolean[]{false}).prepareCaptainForFarm(target))
                                    throw new IllegalStateException("当前角色重新组队未确认，停止刷铜");
                            }
                            check();
                        }
                        new PhoneFarm(HelperAccessibilityService.get(),ctx.getAssets(),target,ctx.getExternalFilesDir(null),count,new PhoneFarm.Listener(){
                            public void onLog(String line){ScriptStatus.update("任务 "+itemNumber+" · 第"+group+"组 · "+line);}
                            public void onDone(String text,boolean confirmed){farmDone[0]=confirmed;LogBus.get().i("copper",text);}
                        }).run();return farmDone[0];
                    }
                    public boolean rotate()throws Exception{
                        check();RoleRotation repo=new RoleRotation(ctx);RotationSession session=repo.load();
                        List<String> expectedKeys=new ArrayList<>();for(HelperApp.Account member:team.members)expectedKeys.add(RotationRuntime.key(member));
                        RotationSession.require(session!=null&&session.sameTeam(RotationRuntime.key(team.captain),expectedKeys),"换角记录与当前队伍不符");
                        boolean advance=session.phase==RotationSession.Phase.READY;
                        if(advance)requireReady(ctx,team);
                        else RotationSession.require(session.paused&&(session.phase==RotationSession.Phase.DISBAND||session.phase==RotationSession.Phase.RETURN||session.phase==RotationSession.Phase.SELECT||session.phase==RotationSession.Phase.TEAM),"没有可安全续接的换角记录");
                        PhoneRunner next=runner(ctx,new boolean[]{false});
                        if(!RunGate.acquire(next,next::requestStop))return false;
                        ScriptStatus.begin("任务 "+itemNumber+" · 换队友角色");
                        try{boolean changed=new RotationController(repo,next.rotationPort(session,team)).execute(session,RotationRuntime.key(team.captain),session.memberKeys(),advance);
                            if(changed)requireReady(ctx,team);return changed;
                        }finally{RunGate.release(next);}
                    }
                    public boolean switchAccounts()throws Exception{
                        check();RoleRotation repo=new RoleRotation(ctx);List<String> keys=new ArrayList<>();
                        for(HelperApp.Account member:team.members)keys.add(RotationRuntime.key(member));
                        RotationSession pending=new RotationSession(RotationRuntime.key(team.captain),keys);
                        pending.pause("队友账号已切换，需登录并组队后核验名册");repo.save(pending);
                        ScriptStatus.begin("操作 "+itemNumber+" · 更换队友账号");
                        boolean logged=runner(ctx,new boolean[]{false}).loginScheduledMembers(team);check();accountsPrepared[0]=logged;return logged;
                    }
                });
                check();if(!passed)throw new IllegalStateException("任务 "+itemNumber+" 未确认完成；保留现场，不执行下一项");
                ctx.getSharedPreferences("copper-schedule",0).edit().putInt("completed",index+1).apply();
            }
            ok=true;summary="任务列表全部完成（"+items.length()+"项），已停止";
        }catch(Throwable e){summary=stopped?"已停止，后续阶段未执行":e.getMessage();LogBus.get().e("copper","流程结束："+summary,e);}
        finally{
            if(acquired){ScriptStatus.begin("刷铜任务");if(stopped)ScriptStatus.stopping();ScriptStatus.finish(summary,ok);RunGate.release(lease);}
            active=false;message(ctx,summary);
        }
    }
    private static RotationSession requireReady(Context ctx,HelperApp.Team team)throws Exception{
        RotationSession s=new RoleRotation(ctx).load();List<String> keys=new ArrayList<>();
        for(HelperApp.Account a:team.members)keys.add(RotationRuntime.key(a));
        RotationSession.require(s!=null&&s.sameTeam(RotationRuntime.key(team.captain),keys)&&!s.paused&&s.phase==RotationSession.Phase.READY&&s.verifiedCount()==4,"本组姓名与实际名册未就绪，保留记录");
        return s;
    }
}
