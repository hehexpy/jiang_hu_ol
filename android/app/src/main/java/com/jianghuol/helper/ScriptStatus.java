package com.jianghuol.helper;

/** 展示层状态；只由真实流程回调结束，不从日志文字推断成功。 */
final class ScriptStatus {
    enum State { IDLE, RUNNING, STOPPING, SUCCESS, UNKNOWN, STOPPED }
    static final class Snapshot {
        final State state;
        final String task, phase, detail;
        final long elapsedSeconds, version;
        Snapshot(State s, String t, String p, String d, long seconds, long v) {
            state=s; task=t; phase=p; detail=d; elapsedSeconds=seconds; version=v;
        }
        boolean active() { return state==State.RUNNING || state==State.STOPPING; }
        String label() {
            switch(state) {
                case RUNNING: return "运行中";
                case STOPPING: return "正在停止";
                case SUCCESS: return "已确认成功";
                case UNKNOWN: return "失败 / 未确认";
                case STOPPED: return "已停止";
                default: return "待运行";
            }
        }
    }
    private static State state=State.IDLE;
    private static String task="江湖助手", phase="尚未开始", detail="启动手机版脚本后显示进度";
    private static long started, ended, version;
    private static long now() { return System.nanoTime()/1_000_000L; }
    static synchronized void begin(String name) {
        task=name; phase="准备运行"; detail="正在启动流程";
        started=now(); ended=0; state=State.RUNNING; version++;
    }
    static synchronized void update(String line) {
        if(state!=State.RUNNING && state!=State.STOPPING) return;
        String s=line.replaceFirst("^\\d{2}:\\d{2}:\\d{2}\\s+", "").trim();
        if(s.isEmpty() || s.startsWith("证据截图：") || s.startsWith("[结果]")) return;
        s=s.replaceAll("com\\.good\\.[A-Za-z0-9_.]+", "游戏客户端")
                .replaceAll("tpl_[A-Za-z0-9_]+\\.png", "界面按钮");
        // 只显示业务日志，不读取控件、输入内容或凭据。
        detail=s.length()>160?s.substring(0,160)+"…":s;
        if("①②③④⑤⑥".indexOf(s.charAt(0))>=0) phase=detail;
        version++;
    }
    static synchronized void stopping() {
        if(state==State.RUNNING) { state=State.STOPPING; detail="已请求停止，等待流程退出"; version++; }
    }
    static synchronized void finish(String summary, boolean confirmed) {
        if(state!=State.RUNNING && state!=State.STOPPING) return;
        state=state==State.STOPPING?State.STOPPED:(confirmed?State.SUCCESS:State.UNKNOWN);
        detail=summary.replaceAll("；证据 [^）]+", ""); ended=now(); version++;
    }
    static synchronized Snapshot snapshot() {
        return new Snapshot(state,task,phase,detail,started==0?0:Math.max(0,((ended==0?now():ended)-started)/1000),version);
    }
}
