package com.jianghuol.helper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅把现有流程状态整理成两行展示，不参与业务判断或计数。 */
final class OverlayProgress {
    private String task="", item="", count="", activity="准备执行";
    private static String match(String source,String pattern){
        Matcher m=Pattern.compile(pattern).matcher(source);return m.find()?m.group(1):"";
    }
    void update(ScriptStatus.Snapshot s){
        if(!task.equals(s.task)){
            task=s.task;item="";count="";activity="准备执行";
            String target=match(task,"当前队长\\s+(\\d+)\\s*场");
            if(!target.isEmpty())count="0/"+target+"场";
        }
        String text=s.task+" "+s.detail;
        String nextItem=match(text,"(?:任务|操作)\\s*(\\d+)");
        if(!nextItem.isEmpty())item=nextItem;
        // 只接收真实流程已发布的场数，NPC次数、步骤号和24/24任务进度均不解析。
        Matcher m=Pattern.compile("(?:\\[刷铜\\]\\s+(?:READY|ENTRY|BATTLE|EXIT|DONE|STOPPED)|完成)\\s+(\\d+)/(\\d+)\\s*(?:场|，)").matcher(s.detail);
        if(m.find())count=m.group(1)+"/"+m.group(2)+"场";
        if(text.contains("换队友角色")||text.contains("[换组]"))activity="换角组队";
        else if(text.contains("更换队友账号"))activity="更换账号";
        else if(text.contains("登录并组队"))activity="登录组队";
        else if(text.contains("刷铜前准备"))activity="核验队伍";
        else if(text.contains("[前置]")||text.contains("[发现]")||text.contains("[前置动作]"))activity="任务前置";
        else if(text.contains("刷铜"))activity="刷铜";
    }
    String title(ScriptStatus.Snapshot s){
        if(s.state==ScriptStatus.State.IDLE)return "待运行";
        if(s.state==ScriptStatus.State.STOPPING)return "正在停止";
        if(s.state==ScriptStatus.State.SUCCESS)return "任务已完成";
        if(s.state==ScriptStatus.State.STOPPED)return "任务已停止";
        if(s.state==ScriptStatus.State.UNKNOWN)return "任务未确认";
        return item.isEmpty()?"当前任务":"任务"+item;
    }
    String detail(ScriptStatus.Snapshot s){
        if(s.state==ScriptStatus.State.IDLE)return "点击展开控制";
        if(s.state==ScriptStatus.State.STOPPING)return "等待流程退出";
        if(s.state==ScriptStatus.State.SUCCESS&&count.isEmpty())return "列表已结束";
        return activity+(count.isEmpty()?"":" "+count);
    }
}
