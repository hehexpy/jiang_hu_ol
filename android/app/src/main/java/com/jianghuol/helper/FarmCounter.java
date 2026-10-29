package com.jianghuol.helper;

/** 纯计数核心：明确战斗帧、胜利帧、回世界三者齐全才计场；无设备依赖。 */
final class FarmCounter {
    enum Phase { READY, ENTRY, BATTLE, EXIT, DONE, STOPPED }
    enum Action { WAIT, NPC, EXIT, STOP }
    final int target;
    int completed, battleId, failed, interrupted, unknown;
    Phase phase=Phase.READY;
    String reason="";
    private long changedAt,entryNotBefore;
    int entryRejected;
    private int consecutiveRejections;
    private boolean clockStarted;

    FarmCounter(int target) {
        if(target<1 || target>100000)throw new IllegalArgumentException("场次须为1至100000");
        this.target=target;
    }
    boolean terminal(){return phase==Phase.DONE||phase==Phase.STOPPED;}
    void stop(String why) {
        if(terminal())return;
        if(phase!=Phase.READY)interrupted++;
        reason=why;phase=Phase.STOPPED;
    }
    private void move(Phase next,long now){phase=next;changedAt=now;}
    private Action uncertain(String why){unknown++;reason=why;phase=Phase.STOPPED;return Action.STOP;}

    void deferEntryUntil(long when){entryNotBefore=Math.max(entryNotBefore,when);}
    boolean rejectEntry(long now,long waitMs){
        return rejectEntry(now,waitMs,1);
    }
    boolean rejectEntry(long now,long waitMs,int maxRejections){
        if(phase!=Phase.ENTRY||consecutiveRejections>=maxRejections)return false;
        entryRejected++;consecutiveRejections++;battleId--;move(Phase.READY,now);
        deferEntryUntil(now+waitMs);return true;
    }

    Action step(long now, boolean world, boolean quest, boolean battle,
                boolean victory, boolean exitButton, boolean npc,
                boolean forbidden, boolean defeat) {
        if(terminal())return Action.STOP;
        if(!clockStarted){changedAt=now;clockStarted=true;}
        if(forbidden)return uncertain("FORBIDDEN_QUEST_PAGE");
        if(defeat){failed++;reason="BATTLE_FAILED";phase=Phase.STOPPED;return Action.STOP;}
        if(world&&!quest)return uncertain("QUEST_NOT_CONFIRMED");
        long limit=phase==Phase.BATTLE?180000:phase==Phase.READY?30000:20000;
        if(now-(phase==Phase.READY?Math.max(changedAt,entryNotBefore):changedAt)>limit)return uncertain("TIMEOUT_"+phase);
        switch(phase){
            case READY:
                // 启动停在遗留结算页时绝不补计，也不自动点退出。
                if(victory||exitButton)return uncertain("STALE_RESULT_AT_START");
                if(now<entryNotBefore)return Action.WAIT;
                if(world&&quest&&npc&&!battle){battleId++;move(Phase.ENTRY,now);return Action.NPC;}
                break;
            case ENTRY:
                // 正向战斗控件必须在独立帧出现，弹窗/黑屏/结算页不能充当入场。
                if(battle&&!world&&!victory&&!exitButton){consecutiveRejections=0;move(Phase.BATTLE,now);}
                else if(victory||exitButton)return uncertain("ENTRY_EVIDENCE_MISSING");
                break;
            case BATTLE:
                if(victory&&exitButton){move(Phase.EXIT,now);return Action.EXIT;}
                if(world)return uncertain("RETURN_WITHOUT_VICTORY");
                break;
            case EXIT:
                if(world&&quest&&!battle&&!victory&&!exitButton){
                    completed++;
                    if(completed==target){move(Phase.DONE,now);reason="TARGET_REACHED";return Action.STOP;}
                    move(Phase.READY,now);
                }
                // 不反复点退出；等待自动退出或输入结果，超时只记未知。
                break;
            default:return Action.STOP;
        }
        return Action.WAIT;
    }
}
