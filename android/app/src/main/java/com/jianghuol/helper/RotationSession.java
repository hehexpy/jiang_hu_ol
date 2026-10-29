package com.jianghuol.helper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** 姓名方案：选角采一次模板，按持久申请顺序在队长名册验证；中断不重选。 */
final class RotationSession {
    enum Phase { INITIAL, READY, DISBAND, RETURN, SELECT, TEAM, COMPLETE }
    enum Dungeon { NOT_EXECUTED, UNKNOWN, CONFIRMED_COMPLETE }
    static final class Member {
        final String key;
        final List<String> completedNames = new ArrayList<>();
        boolean exited, selectionPage, selectIntent, selected, entered, verified;
        String layoutBefore="", nameGlyph="";
        Member(String key) { this.key=key; }
        void clearStage() {
            exited=selectionPage=selectIntent=selected=entered=verified=false;
            nameGlyph="";layoutBefore="";completedNames.clear();
        }
    }
    final String captain;
    final List<Member> members=new ArrayList<>();
    final List<String> applicationOrder=new ArrayList<>();
    Phase phase=Phase.INITIAL;
    Dungeon dungeon=Dungeon.NOT_EXECUTED;
    int group=1, acceptedApplications;
    boolean paused,dungeonRequired,initialGroupingPrepared,ordinaryRosterObserved;
    String reason="",captainToken="";
    RotationSession(String captain,List<String> keys) {
        require(captain!=null&&!captain.isEmpty(),"缺固定队长");
        require(keys!=null&&keys.size()==4&&new HashSet<>(keys).size()==4&&!keys.contains(captain),"需要四个独立队员");
        this.captain=captain;
        for(String key:keys){require(key!=null&&!key.isEmpty(),"队员身份为空");members.add(new Member(key));}
    }
    static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    private void active(Phase expected){require(!paused&&phase==expected,"阶段未满足或已停止："+phase);}
    Member member(String key){for(Member m:members)if(m.key.equals(key))return m;throw new IllegalStateException("不是本轮队员");}
    void initialName(String key,String glyph) {
        active(Phase.INITIAL);Member m=member(key);
        require(RoleNameGlyph.decode(glyph)!=null,"第一组姓名不完整");
        require(m.nameGlyph.isEmpty()||m.nameGlyph.equals(glyph),"第一组采集记录已存在");
        m.nameGlyph=glyph;m.selected=m.selectIntent=true;
    }
    void initialRoster(boolean exact){
        active(Phase.INITIAL);require(exact&&verifiedCount()==4,"第一组真实姓名名册未确认");
        for(Member m:members)m.completedNames.clear();
        phase=Phase.READY;
    }
    /** 选角采名会使队员离队；真实清场后才开始记录第一组申请。 */
    void prepareInitialGrouping(boolean disbandConfirmed){
        active(Phase.INITIAL);
        require(disbandConfirmed&&members.stream().allMatch(m->m.entered),"第一组清场或进入屏障未确认");
        require(!initialGroupingPrepared,"第一组已开始申请，禁止再次清场");
        applicationOrder.clear();acceptedApplications=0;
        for(Member m:members)m.verified=false;
        initialGroupingPrepared=true;
    }
    private void groupingActive(){
        require(!paused&&(phase==Phase.TEAM||(phase==Phase.INITIAL&&initialGroupingPrepared)),"申请阶段未满足");
    }
    String nextDirection(){return group==1?"左边":"右边";}
    void next(){
        active(Phase.READY);
        require(!dungeonRequired||dungeon==Dungeon.CONFIRMED_COMPLETE,"副本结果未确认");
        for(Member m:members){require(m.verified,"当前名册尚未验证");m.clearStage();}
        applicationOrder.clear();acceptedApplications=0;phase=Phase.DISBAND;
    }
    void disbanded(boolean ok){active(Phase.DISBAND);require(ok,"解散未确认");phase=Phase.RETURN;}
    void returnedToSelection(String key){
        active(Phase.RETURN);Member m=member(key);m.exited=m.selectionPage=true;
        if(members.stream().allMatch(x->x.selectionPage))phase=Phase.SELECT;
    }
    void reserveSelection(String key){
        active(Phase.SELECT);Member m=member(key);
        require(!m.selectIntent&&!m.selected&&!m.entered,"已有选角意图，必须观察恢复");m.selectIntent=true;
    }
    void selectionNotDispatched(String key){
        active(Phase.SELECT);Member m=member(key);
        require(m.selectIntent&&!m.selected&&!m.entered&&m.nameGlyph.isEmpty(),"不能撤销已选或未知阶段的角色记录");
        m.selectIntent=false;m.layoutBefore="";
    }
    void selected(String key,String glyph,boolean slotConfirmed){
        active(Phase.SELECT);Member m=member(key);
        require(m.selectIntent&&slotConfirmed&&RoleNameGlyph.decode(glyph)!=null,"选角姓名或目标槽位不完整");
        m.nameGlyph=glyph;m.selected=m.selectIntent=true;
    }
    void entered(String key){
        require(!paused&&(phase==Phase.INITIAL||phase==Phase.SELECT),"进入阶段不符");
        Member m=member(key);require(m.selected&&!m.nameGlyph.isEmpty(),"缺选角姓名");m.entered=true;
        if(phase==Phase.SELECT&&members.stream().allMatch(x->x.entered))phase=Phase.TEAM;
    }
    /** 申请前持久化顺序；恢复时只观察已记录请求，不自动重复申请。 */
    void reserveApplication(String key){
        groupingActive();Member m=member(key);
        require(m.entered&&members.stream().allMatch(x->x.entered),"四员进入屏障未齐");
        require(!applicationOrder.contains(key),"已有申请意图，先观察实际名册");
        require(acceptedApplications==applicationOrder.size(),"前一位申请回执尚未确认");
        applicationOrder.add(key);
    }
    void applicationAccepted(int count){
        groupingActive();require(count==applicationOrder.size()&&count>=acceptedApplications,"实际名册人数与申请顺序不符");
        acceptedApplications=count;
    }
    /** 刷新名册确认只剩原顺序前缀时，缺席的后缀允许重新申请，不重复选角。 */
    void reconcileRosterPrefix(int count){
        groupingActive();require(count>=0&&count<acceptedApplications,"名册前缀恢复条件不符");
        for(int i=0;i<count;i++)require(member(applicationOrder.get(i)).verified,"保留成员姓名尚未确认");
        while(applicationOrder.size()>count)member(applicationOrder.remove(applicationOrder.size()-1)).verified=false;
        acceptedApplications=count;
    }
    void rosterRowVerified(String key,int row){
        require(!paused&&(phase==Phase.INITIAL||phase==Phase.TEAM),"姓名验证阶段不符");
        Member m=member(key);
        require(row>=0&&row<applicationOrder.size()&&applicationOrder.get(row).equals(key)&&m.entered,
                "名册行与申请顺序不符");
        m.verified=true;
    }
    void rosterVerified(boolean exact){
        active(Phase.TEAM);require(exact&&verifiedCount()==4&&applicationOrder.size()==4,"本轮四名实际成员未确认");
        for(Member m:members)m.completedNames.clear();
        group=group%3+1;dungeon=Dungeon.NOT_EXECUTED;phase=Phase.READY;
    }
    /** 只在普通流程按实际申请顺序确认4/4后建立起点，不恢复上一轮。 */
    void seedOrdinaryNames(List<String> names) {
        active(Phase.INITIAL);
        require(names!=null&&names.size()==4,"普通队伍姓名未齐");
        for(String name:names)require(RoleNameGlyph.decode(name)!=null,"普通队伍姓名不完整");
        require(members.stream().allMatch(m->!m.selected&&!m.entered),"起点已建立，不能重复覆盖");
        for(int i=0;i<4;i++){
            Member m=members.get(i);initialName(m.key,names.get(i));entered(m.key);applicationOrder.add(m.key);
        }
        acceptedApplications=4;initialGroupingPrepared=true;
        for(int i=0;i<4;i++)rosterRowVerified(members.get(i).key,i);
        initialRoster(true);
    }
    void pause(String why){paused=true;reason=why==null?"未确认":why;}
    void resume(){paused=false;reason="";}
    int verifiedCount(){return (int)members.stream().filter(m->m.verified).count();}
    List<String> memberKeys(){List<String> out=new ArrayList<>();for(Member m:members)out.add(m.key);return out;}
    boolean sameTeam(String cap,List<String> keys){return captain.equals(cap)&&memberKeys().equals(keys);}
    /** 真实四行姓名唯一对应本轮目标（未选成员只能对应上一组角色），整批通过才更新。 */
    /** 重连仅确认原选择，不清进度、不再点左右；未知点击结果必须暂停。 */
    boolean reconnectMayEnter(String key,String observedName,double threshold) {
        Member m=member(key);
        require(!m.selectIntent||m.selected,"掉线前选择结果未知；禁止重复左右点击");
        if(m.selected) {
            RoleNameGlyph expected=RoleNameGlyph.decode(m.nameGlyph);
            require(expected!=null&&expected.score(RoleNameGlyph.decode(observedName))>=threshold,
                    "掉线重登后当前姓名与已选角色不同；禁止重复左右点击");
        }
        return m.entered;
    }

    void reconcileOrdinaryNames(List<String> rows,double threshold,double margin){
        require(phase!=Phase.INITIAL||members.stream().allMatch(m->m.selected),"第一组缺选角姓名，不能由人数建立本组");
        require(rows!=null&&rows.size()==4&&threshold>=.80&&threshold<=1&&margin>0&&margin<=1,"需四行完整姓名及恢复余量");
        require(members.stream().noneMatch(m->m.selectIntent&&!m.selected),"存在结果未知的选角意图，不能按旧角色同步");
        List<String> order=new ArrayList<>();
        for(int row=0;row<4;row++){
            RoleNameGlyph actual=RoleNameGlyph.decode(rows.get(row));require(actual!=null,"名册姓名不完整");
            String found=null;double best=-1,second=-1;
            for(Member m:members){
                String expected=m.selected?m.nameGlyph:m.completedNames.isEmpty()?"":m.completedNames.get(m.completedNames.size()-1);
                RoleNameGlyph target=RoleNameGlyph.decode(expected);
                double score=target==null?-1:target.score(actual);
                if(score>best){second=best;best=score;found=m.key;}else second=Math.max(second,score);
            }
            require(best>=threshold&&best-second>=margin&&found!=null&&!order.contains(found),"名册第"+(row+1)+"行姓名未唯一确认，保留原进度");order.add(found);
        }
        boolean allSelected=members.stream().allMatch(m->m.selected);
        require(allSelected||phase==Phase.SELECT||phase==Phase.RETURN||phase==Phase.DISBAND,"缺选角姓名，不能由人数建立本组");
        // 以上校验无副作用；失败不能留下部分成功或伪造未采集姓名。
        applicationOrder.clear();acceptedApplications=0;
        for(Member m:members){m.verified=false;if(m.selected)m.entered=true;}
        if(!allSelected){ordinaryRosterObserved=true;pause("普通队伍已齐；仍有队员未换角，请继续完成本组");return;}
        applicationOrder.addAll(order);acceptedApplications=4;
        for(Member m:members)m.verified=true;
        resume();ordinaryRosterObserved=false;
        if(phase==Phase.INITIAL)initialRoster(true);
        else if(phase==Phase.SELECT||phase==Phase.TEAM){phase=Phase.TEAM;rosterVerified(true);}
    }
    Properties encode(){
        Properties p=new Properties();p.setProperty("version","5");p.setProperty("captain",captain);
        p.setProperty("captainToken",captainToken);p.setProperty("phase",phase.name());p.setProperty("group",""+group);
        p.setProperty("paused",""+paused);p.setProperty("reason",reason);p.setProperty("dungeon",dungeon.name());
        p.setProperty("initialGroupingPrepared",""+initialGroupingPrepared);
        p.setProperty("ordinaryRosterObserved",""+ordinaryRosterObserved);
        p.setProperty("dungeonRequired",""+dungeonRequired);p.setProperty("order",String.join("\n",applicationOrder));p.setProperty("acceptedApplications",""+acceptedApplications);
        for(int i=0;i<4;i++){
            Member m=members.get(i);String k="m"+i+".";
            p.setProperty(k+"key",m.key);p.setProperty(k+"nameGlyph",m.nameGlyph);p.setProperty(k+"layoutBefore",m.layoutBefore);
            p.setProperty(k+"exited",""+m.exited);p.setProperty(k+"selectionPage",""+m.selectionPage);
            p.setProperty(k+"selectIntent",""+m.selectIntent);p.setProperty(k+"selected",""+m.selected);
            p.setProperty(k+"entered",""+m.entered);p.setProperty(k+"verified",""+m.verified);
        }return p;
    }
    private static String value(Properties p,String key){String v=p.getProperty(key);require(v!=null,"会话缺字段："+key);return v;}
    private static boolean bool(Properties p,String key){String v=value(p,key);require(v.equals("true")||v.equals("false"),"会话布尔值损坏");return Boolean.parseBoolean(v);}
    static RotationSession decode(Properties p){
        require("4".equals(value(p,"version"))||"5".equals(value(p,"version")),"旧职业会话需迁移真实姓名证据；原进度保留，禁止重新选角");
        List<String> keys=new ArrayList<>();for(int i=0;i<4;i++)keys.add(value(p,"m"+i+".key"));
        RotationSession s=new RotationSession(value(p,"captain"),keys);s.captainToken=value(p,"captainToken");
        s.phase=Phase.valueOf(value(p,"phase"));s.group=Integer.parseInt(value(p,"group"));
        require(s.group>=1&&s.group<=3,"会话组次损坏");s.paused=bool(p,"paused");s.reason=value(p,"reason");
        s.dungeon=Dungeon.valueOf(value(p,"dungeon"));s.dungeonRequired=bool(p,"dungeonRequired");
        // 旧版INITIAL曾臆填四人；只在真实清场后纠正，保留姓名与已进入记录。
        s.initialGroupingPrepared=p.containsKey("initialGroupingPrepared")&&bool(p,"initialGroupingPrepared");
        s.ordinaryRosterObserved=p.containsKey("ordinaryRosterObserved")&&bool(p,"ordinaryRosterObserved");
        s.acceptedApplications=Integer.parseInt(value(p,"acceptedApplications"));
        String order=value(p,"order");if(!order.isEmpty())java.util.Collections.addAll(s.applicationOrder,order.split("\n",-1));
        require(s.applicationOrder.size()<=4&&new HashSet<>(s.applicationOrder).size()==s.applicationOrder.size()
                &&keys.containsAll(s.applicationOrder),"申请顺序损坏");
        require(s.acceptedApplications>=0&&s.acceptedApplications<=s.applicationOrder.size(),"申请人数记录损坏");
        for(int i=0;i<4;i++){
            Member m=s.members.get(i);String k="m"+i+".";m.nameGlyph=value(p,k+"nameGlyph");m.layoutBefore=value(p,k+"layoutBefore");
            m.exited=bool(p,k+"exited");m.selectionPage=bool(p,k+"selectionPage");m.selectIntent=bool(p,k+"selectIntent");
            m.selected=bool(p,k+"selected");m.entered=bool(p,k+"entered");m.verified=bool(p,k+"verified");
            require(!m.selected||(RoleNameGlyph.decode(m.nameGlyph)!=null&&(s.phase==Phase.INITIAL||m.selectIntent)),"选角姓名记录损坏");
            require(!m.entered||m.selected,"进入记录缺少选角证据");
            require(!m.verified||(m.entered&&s.applicationOrder.contains(m.key)),"名册验证记录损坏");
        }
        if(s.phase==Phase.COMPLETE){require(s.group==3,"旧轮完成记录损坏");s.phase=Phase.READY;}
        if(s.phase==Phase.TEAM)require(s.members.stream().allMatch(m->m.entered),"进入屏障记录损坏");
        if(s.phase==Phase.SELECT||s.phase==Phase.TEAM)require(s.members.stream().allMatch(m->m.exited&&m.selectionPage),"选角屏障损坏");
        if(s.phase==Phase.READY||s.phase==Phase.COMPLETE)require(s.verifiedCount()==4,"就绪名册不完整");
        return s;
    }
}
