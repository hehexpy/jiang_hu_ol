package com.jianghuol.helper;
import java.util.List;

/** 选角采集姓名，进入世界屏障，最终按申请顺序验证一次姓名。 */
final class RotationController {
    interface Repository { void save(RotationSession session) throws Exception; }
    /** 只能在任何选角点击之前抛出；结果未知或点击后失败不得使用此类型。 */
    static final class SelectionNotDispatched extends IllegalStateException {
        SelectionNotDispatched(String reason){super(reason);}
    }
    static final class Selection {
        final boolean slotConfirmed;
        final String nameGlyph;
        Selection(boolean slotConfirmed,String nameGlyph){this.slotConfirmed=slotConfirmed;this.nameGlyph=nameGlyph;}
    }
    interface Port {
        boolean stopped();
        void verifyCaptain() throws Exception;
        boolean exactRoster(RotationSession session,Repository repository) throws Exception;
        default boolean reconcileRoster(RotationSession session,Repository repository) throws Exception { return false; }
        boolean disbandAndVerify(String captain) throws Exception;
        boolean disbandStillVerified(String captain) throws Exception;
        boolean returnToSelection(String key) throws Exception;
        boolean onSelectionPage(String key) throws Exception;
        String selectionLayout(String key) throws Exception;
        Selection select(String key,String direction,boolean mayClick) throws Exception;
        boolean enterSelected(String key) throws Exception;
        boolean groupMembers(RotationSession session,Repository repository) throws Exception;
        void status(String stage,int verified,String reason);
    }
    private final Repository repository;
    private final Port port;
    RotationController(Repository repository,Port port){this.repository=repository;this.port=port;}
    private void checkpoint(RotationSession s)throws Exception{
        repository.save(s);
        String[] phases={"采集第一组姓名","本组就绪，副本未执行","队长解散","全员返回角色列表","选角与进入游戏","按顺序核验队伍姓名","本轮三组完成，副本未执行"};
        port.status("第"+(s.phase==RotationSession.Phase.TEAM||s.phase==RotationSession.Phase.SELECT?s.group%3+1:s.group)+"组 · "+phases[s.phase.ordinal()]+" · 下一次"+s.nextDirection(),s.verifiedCount(),s.reason);
    }
    private void running(){RotationSession.require(!port.stopped(),"已停止；保留本轮进度");}
    private void require(boolean ok,String why){running();RotationSession.require(ok,why);}
    boolean execute(RotationSession s,String captain,List<String> members,boolean next){
        try{
            running();require(s.sameTeam(captain,members),"账号或客户端配置已变化，保留旧轮记录");
            if(s.paused){s.resume();checkpoint(s);}
            if(next){s.next();checkpoint(s);}
            else require(s.phase!=RotationSession.Phase.READY&&s.phase!=RotationSession.Phase.COMPLETE,"本组已就绪，等待手动换组");
            port.verifyCaptain();checkpoint(s);
            if(s.phase==RotationSession.Phase.INITIAL){
                for(RotationSession.Member m:s.members){
                    running();
                    if(!m.selected){
                        require(port.returnToSelection(m.key),"第一组未到选角页");
                        m.layoutBefore=port.selectionLayout(m.key);
                        Selection selection=port.select(m.key,"中间",false);
                        require(selection!=null&&selection.slotConfirmed,"当前中间角色姓名未知");
                        s.initialName(m.key,selection.nameGlyph);checkpoint(s);
                    }
                    if(!m.entered){require(port.enterSelected(m.key),"第一组当前角色未进入");s.entered(m.key);checkpoint(s);}
                }
                if(!s.initialGroupingPrepared){
                    require(port.disbandAndVerify(s.captain),"第一组重新申请前清场未确认");
                    s.prepareInitialGrouping(true);checkpoint(s);
                }
                require(port.groupMembers(s,repository),"第一组真实申请顺序或人数未确认");
                require(port.exactRoster(s,repository),"第一组四名姓名与实际顺序未确认");
                s.initialRoster(true);checkpoint(s);return true;
            }
            if(s.phase==RotationSession.Phase.DISBAND){
                require(port.disbandAndVerify(s.captain),"队长解散未确认");s.disbanded(true);s.ordinaryRosterObserved=false;checkpoint(s);
            }
            if(s.phase==RotationSession.Phase.RETURN){
                if(s.ordinaryRosterObserved){
                    require(port.disbandAndVerify(s.captain),"普通组队后重新解散未确认");s.ordinaryRosterObserved=false;checkpoint(s);
                }
                require(port.disbandStillVerified(s.captain),"现场已有队伍，解散证据失效");
                for(RotationSession.Member m:s.members){
                    running();require(port.returnToSelection(m.key),"有队员未返回选角，禁止选择");s.returnedToSelection(m.key);checkpoint(s);
                }
            }
            if(s.phase==RotationSession.Phase.SELECT){
                if(s.ordinaryRosterObserved){
                    require(port.disbandAndVerify(s.captain),"继续未换队员前解散普通队伍未确认");
                    // 已换成员保留，不再点左右；未换成员重新返回选角。
                    for(RotationSession.Member m:s.members)if(!m.selectIntent){
                        running();require(port.returnToSelection(m.key),"未换队员返回角色列表未确认");
                    }
                    s.ordinaryRosterObserved=false;checkpoint(s);
                }
                for(RotationSession.Member m:s.members){running();if(!m.selectIntent)require(port.onSelectionPage(m.key),"全员选角屏障现场不符");}
                for(RotationSession.Member m:s.members){
                    running();
                    if(!m.selected){
                        Selection selection=null;
                        for(int attempt=0;attempt<2;attempt++){
                            boolean mayClick=!m.selectIntent;
                            if(mayClick){m.layoutBefore=port.selectionLayout(m.key);s.reserveSelection(m.key);checkpoint(s);running();}
                            try{selection=port.select(m.key,s.nextDirection(),mayClick);break;}
                            catch(SelectionNotDispatched e){
                                // 仅有明确未调用点击的证据才撤销意图；普通异常保持原意图。
                                if(!mayClick)throw e;
                                s.selectionNotDispatched(m.key);checkpoint(s);running();
                                if(attempt==1)throw e;
                                port.status("点击尚未发出，重新确认当前槽位",s.verifiedCount(),e.getMessage());
                            }
                        }
                        require(selection!=null,"选角结果未知，禁止重复左右点击");
                        s.selected(m.key,selection.nameGlyph,selection.slotConfirmed);checkpoint(s);
                    }
                    if(!m.entered){require(port.enterSelected(m.key),"已选角色未进入世界");s.entered(m.key);checkpoint(s);}
                }
            }
            if(s.phase==RotationSession.Phase.TEAM){
                require(port.groupMembers(s,repository),"申请顺序或人数未确认，保留本轮进度");
                require(port.exactRoster(s,repository),"队长名册姓名与申请顺序不符");
                s.rosterVerified(true);checkpoint(s);
            }
            return s.phase==RotationSession.Phase.READY||s.phase==RotationSession.Phase.COMPLETE;
        }catch(Exception e){
            s.pause(e.getMessage());try{checkpoint(s);}catch(Exception save){port.status("记录保存失败；禁止继续换角",s.verifiedCount(),save.getClass().getSimpleName());}
            return false;
        }
    }
}
