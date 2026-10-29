package com.jianghuol.helper;

/** 所有手机游戏流程共用的进程内租约；Activity重建不释放仍在运行的流程。 */
final class RunGate {
    private static Object owner;
    private static Runnable stopAction;
    private static Thread sequenceThread;
    private static Object child;
    private static Runnable childStop;
    private static boolean sequenceStopped;
    static synchronized boolean beginSequence(Object candidate, Runnable onStop) {
        if (owner != null) return false;
        owner=candidate;stopAction=onStop;sequenceThread=Thread.currentThread();sequenceStopped=false;
        return true;
    }
    static synchronized boolean acquire(Object candidate, Runnable onStop) {
        if(sequenceThread==Thread.currentThread()) {
            if(sequenceStopped || child!=null)return false;
            child=candidate;childStop=onStop;return true;
        }
        if (owner != null) return false;
        owner = candidate; stopAction = onStop; return true;
    }
    static synchronized void release(Object candidate) {
        if(child==candidate){child=null;childStop=null;return;}
        if (owner == candidate) { owner = null; stopAction = null;sequenceThread=null;child=null;childStop=null; }
    }
    static synchronized boolean busy() { return owner != null; }
    static void stop() {
        Runnable action, nested;
        synchronized (RunGate.class) { action = stopAction;nested=childStop;if(sequenceThread!=null)sequenceStopped=true; }
        if (action != null) action.run();
        if (nested != null) nested.run();
    }
}
