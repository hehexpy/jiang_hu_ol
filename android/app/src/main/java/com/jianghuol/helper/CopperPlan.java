package com.jianghuol.helper;

/** 任务只接受登记类型；不解析用户输入的流程语句。 */
final class CopperPlan {
    static final String CURRENT="current", BATCH="batch";
    static final String TEAM="team", FARM="farm", ROTATE="rotate", SWITCH="switch";
    static boolean hasCount(String kind){return CURRENT.equals(kind)||BATCH.equals(kind)||FARM.equals(kind);}
    static boolean hasMembers(String kind){return BATCH.equals(kind)||SWITCH.equals(kind);}
    static int groups(String kind){
        if(CURRENT.equals(kind))return 1;
        if(BATCH.equals(kind))return 3;
        if(TEAM.equals(kind)||FARM.equals(kind)||ROTATE.equals(kind)||SWITCH.equals(kind))return 1;
        throw new IllegalArgumentException("未知任务类型");
    }
    static void validate(String kind,int count,int members){
        groups(kind);
        if(hasCount(kind)&&(count<1||count>100000))throw new IllegalArgumentException("场次须为1至100000");
        if(hasMembers(kind)&&members!=4)throw new IllegalArgumentException("每批需要四名队友");
    }
    interface Port {
        boolean stopped();
        boolean team() throws Exception;
        boolean farm(int group) throws Exception;
        boolean rotate() throws Exception;
        default boolean switchAccounts()throws Exception{return false;}
    }
    static boolean execute(String kind,Port p)throws Exception{
        int groups=groups(kind);
        if(p.stopped())return false;
        if(TEAM.equals(kind))return p.team()&&!p.stopped();
        if(ROTATE.equals(kind))return p.rotate()&&!p.stopped();
        if(SWITCH.equals(kind))return p.switchAccounts()&&!p.stopped();
        if(BATCH.equals(kind)&&!p.team())return false;
        for(int g=1;g<=groups;g++){
            if(p.stopped()||!p.farm(g)||p.stopped())return false;
            if(g<groups&&(!p.rotate()||p.stopped()))return false;
        }
        return !p.stopped();
    }
}
