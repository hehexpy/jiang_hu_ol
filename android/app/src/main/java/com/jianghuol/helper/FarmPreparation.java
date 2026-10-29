package com.jianghuol.helper;

/** 已采集前置的有序状态；一次派发只等下一真实状态，不重试交付或领奖。 */
final class FarmPreparation {
    /** 同一优先级查完已接与可接，才下降；绝不默认从6-6起跑。 */
    static final class Search {
        int chapter=8,tab,page,reset=5;
        boolean ready;int endFrames;
        void finishList(int resetSwipes){
            page=0;reset=resetSwipes;
            ready=false;endFrames=0;
            if(tab==0)tab=1;else{tab=0;chapter--;}
        }
        boolean endConfirmed(boolean atEnd){endFrames=atEnd?endFrames+1:0;return endFrames>=2;}
        boolean exhausted(){return chapter<6;}
    }
    /** 只拦实际会自动开战的三项接取，其他接取/交付/领奖/导航按新画面推进。 */
    static boolean mayStartBattle(String name){return name.startsWith("enemy_")||name.equals("accept_exposed")||name.equals("accept_sun")||name.equals("accept_kill_lord");}
    int next=-1, pending=-1, verified,resumed,candidate=-1,candidateFrames;
    boolean acquired, stopped;
    String reason="";
    long waitingSince=-1;
    void acquire(){acquired=true;pending=-1;reason="YUDANG_PRESERVED";}
    boolean select(int index, boolean startable){
        if(acquired||stopped)return false;
        if(next<0&&!startable)return false;
        if(next>=0&&index<next){noCheckpoint();return false;}
        if(next>=0&&index>next){
            if(!startable){candidate=-1;candidateFrames=0;return false;}
            if(candidate==index)candidateFrames++;else{candidate=index;candidateFrames=1;}
            if(candidateFrames<2)return false;
            resumed++;
        }
        candidate=-1;candidateFrames=0;
        if(pending>=0){verified++;pending=-1;}
        next=index;waitingSince=-1;return true;
    }
    void noCheckpoint(){candidate=-1;candidateFrames=0;}
    void dispatched(int index,long now){
        if(acquired||stopped||index!=next||pending>=0)throw new IllegalStateException("invalid prerequisite dispatch");
        pending=index;next=index+1;waitingSince=now;
    }
    /** 仅由明确的未入场拒绝触发；交付、领奖和结果未知都不能回退。 */
    boolean retryRejectedEnemy(int index,long now){
        if(acquired||stopped||index<0||pending!=index||next!=index+1)return false;
        pending=-1;next=index;waitingSince=now;noCheckpoint();return true;
    }
    boolean timeout(long now){
        if(waitingSince<0)waitingSince=now;
        if(now-waitingSince>180000){stopped=true;reason="PREPARATION_RESULT_UNKNOWN";return true;}
        return false;
    }
    private static final java.util.Set<String> ACTIONS=new java.util.HashSet<>(java.util.Arrays.asList(
        "option_intro","accept_intro","follow_intro","npc_wu_intro","finish_intro",
        "accept_infiltrate","enemy_infiltrate","npc_wu_infiltrate","finish_infiltrate",
        "accept_borrowed",
        "accept_clear",
        "accept_escape",
        "accept_exposed",
        "accept_feast",
        "accept_finale",
        "accept_gamble",
        "accept_kill_lord",
        "accept_opportunity",
        "accept_risk",
        "accept_scare",
        "accept_storm",
        "accept_strong",
        "accept_struggle",
        "accept_sun",
        "accept_trap",
        "accept_trouble",
        "accept_yudang",
        "enemy_chen",
        "enemy_guard",
        "enemy_lord_guard",
        "enemy_politician",
        "enemy_strong",
        "finish_borrowed",
        "finish_clear",
        "finish_escape",
        "finish_exposed",
        "finish_feast",
        "finish_finale",
        "finish_gamble",
        "finish_kill_lord",
        "finish_opportunity",
        "finish_risk",
        "finish_scare",
        "finish_storm",
        "finish_strong",
        "finish_struggle",
        "finish_sun",
        "finish_trap",
        "finish_trouble",
        "follow_escape",
        "intro_confirm",
        "npc_chang",
        "npc_chen_giver",
        "npc_chen_hall",
        "npc_fan",
        "npc_lord",
        "npc_patrol",
        "npc_patrol2",
        "npc_suicide",
        "npc_sun",
        "npc_wu",
        "npc_zhu",
        "reward_close",
        "reward_open",
        "reward_received_close",
        "reward_select",
        "tracker_exposed",
        "tracker_opportunity"));
    static boolean waitBeforeAutoBattle(String name){return mayStartBattle(name)&&!name.startsWith("enemy_");}
    static boolean allowed(String name,boolean acquired){return !acquired&&ACTIONS.contains(name);}
}
