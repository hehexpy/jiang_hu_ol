package com.jianghuol.helper;

import android.content.Context;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** 应用级轮换入口；Activity销毁不影响租约及AtomicFile进度。 */
final class RotationRuntime {
    private static volatile String detail="尚未初始化换组";
    static String key(HelperApp.Account a){return a.pkg+"|"+a.account;}
    static void report(String line){detail=line;ScriptStatus.update(line);LogBus.get().i("rotation",line);}
    static String summary(Context ctx) {
        try {
            RoleRotation repo=new RoleRotation(ctx);if(!repo.enabled())return "换组未开启";
            RotationSession s=repo.load();if(s==null)return "请先核验当前队伍，建立第一组";
            StringBuilder out=new StringBuilder("第"+s.group+"组 · 下一次"+s.nextDirection());
            RoleStore roles=new RoleStore(ctx);
            for(RotationSession.Member m:s.members)out.append("\n").append(roles.labelOf(m.key.split("\\|",2)[0])).append("：")
                    .append(m.verified?"姓名已核验":m.entered?"已进入，待核姓名":m.selected?"姓名已采集，待进入":"待选角");
            String phase=s.phase==RotationSession.Phase.READY?"本组就绪，副本未执行":s.phase==RotationSession.Phase.COMPLETE?"本轮三组完成，副本未执行":s.phase==RotationSession.Phase.INITIAL?"核验第一组":s.phase==RotationSession.Phase.DISBAND?"队长解散":s.phase==RotationSession.Phase.RETURN?"全员返回角色列表":s.phase==RotationSession.Phase.SELECT?"选角与进入游戏":"按顺序核验队伍姓名";
            return (s.paused?"已暂停："+s.reason+"\n":"")+out+"\n"+phase+(RunGate.busy()?" · "+detail:"");
        }catch(Exception e){return "换组记录读取失败："+e.getMessage();}
    }
    static boolean canNext(Context ctx) {
        try {RoleRotation repo=new RoleRotation(ctx);RotationSession s=repo.load();
            return repo.enabled()&&!RunGate.busy()&&s!=null&&!s.paused&&s.phase==RotationSession.Phase.READY;
        }catch(Exception e){return false;}
    }
    static boolean canPrimary(Context ctx){
        try{RoleRotation repo=new RoleRotation(ctx);RotationSession s=repo.load();
            return repo.enabled()&&!RunGate.busy()&&s!=null&&s.phase!=RotationSession.Phase.COMPLETE;
        }catch(Exception e){return false;}
    }
    static String primaryLabel(Context ctx){
        try{RotationSession s=new RoleRotation(ctx).load();
            if(s!=null&&(s.paused||s.phase!=RotationSession.Phase.READY))return s.ordinaryRosterObserved||s.phase==RotationSession.Phase.SELECT?"继续完成本组":
                    s.phase==RotationSession.Phase.SELECT||s.phase==RotationSession.Phase.TEAM||s.phase==RotationSession.Phase.READY?"核验当前队伍并恢复":"恢复本轮换组";
        }catch(Exception ignored){}
        return "换下一组并组队";
    }
    static synchronized void start(Context context,String action) {
        Context ctx=context.getApplicationContext();
        try {
            RotationSession.require(HelperAccessibilityService.ready(),"请先开启无障碍");
            RotationSession.require(CaptureService.running(),"请回助手授权截屏后继续");
            RoleRotation repo=new RoleRotation(ctx);RotationSession.require(repo.enabled(),"请先开启换组");
            HelperApp.Team team=HelperApp.loadTeam();List<String> keys=new ArrayList<>();
            for(HelperApp.Account a:team.members)keys.add(key(a));
            java.util.Set<String> packages=new java.util.HashSet<>(),accounts=new java.util.HashSet<>();
            for(HelperApp.Account a:team.all())RotationSession.require(!a.account.isEmpty()&&packages.add(a.pkg)&&accounts.add(a.account),"需要五个独立客户端与账号");
            RotationSession s;
            if(action.equals("new"))s=new RotationSession(key(team.captain),keys);
            else {s=repo.load();RotationSession.require(s!=null,"请先建立第一组");}
            RotationSession.require(s.sameTeam(key(team.captain),keys),"客户端或账号变化，请明确建立新轮");
            if(action.equals("primary"))action=s.paused||s.phase!=RotationSession.Phase.READY?(s.ordinaryRosterObserved||s.phase==RotationSession.Phase.SELECT?"resume":
                    s.phase==RotationSession.Phase.SELECT||s.phase==RotationSession.Phase.TEAM||s.phase==RotationSession.Phase.READY?"reconcile":"resume"):"next";
            if(action.equals("next"))RotationSession.require(!s.paused&&s.phase==RotationSession.Phase.READY,"本组未就绪或本轮已完成，请查看记录");
            if(action.equals("resume"))RotationSession.require(s.phase!=RotationSession.Phase.READY&&s.phase!=RotationSession.Phase.COMPLETE,"本组已就绪，无需恢复；请手动换下一组");
            PhoneRunner runner=new PhoneRunner(HelperAccessibilityService.get(),new Vision(ctx.getAssets()),new PhoneRunner.Listener(){
                public void onLog(String line){ScriptStatus.update(line);}
                public void onDone(String text,boolean ok){}
            });
            RotationSession.require(RunGate.acquire(runner,runner::requestStop),"已有流程运行");
            try {if(action.equals("new"))repo.save(s);}catch(Exception e){RunGate.release(runner);throw e;}
            ScriptStatus.begin("换组 · 姓名与名册核验");HelperAccessibilityService.showScriptStatus();
            final String selectedAction=action;
            new Thread(()->{
                boolean ok=false;
                try {
                    RotationController controller=new RotationController(repo,runner.rotationPort(s,team));
                    if(selectedAction.equals("reconcile")){
                        try{ok=runner.rotationPort(s,team).reconcileRoster(s,repo);}
                        catch(Exception e){s.pause("当前队伍核验未通过："+e.getMessage());repo.save(s);}
                    }else ok=controller.execute(s,key(team.captain),keys,selectedAction.equals("next"));
                }catch(Throwable e){s.pause("换组异常："+e.getClass().getSimpleName());try{repo.save(s);}catch(Exception ignored){}LogBus.get().e("rotation","轮换异常",e);}
                finally {
                    RunGate.release(runner);
                    ScriptStatus.finish(ok?"第"+s.group+"组已就绪，副本未执行"+(s.group==3?"；等待手动换下一组":"；等待手动换下一组"):"换组已停止/未确认："+s.reason,ok);
                }
            },"role-rotation").start();
        }catch(Exception e){Toast.makeText(ctx,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
