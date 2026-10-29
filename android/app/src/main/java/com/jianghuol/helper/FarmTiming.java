package com.jianghuol.helper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 展示时间用北京时间；所有耗时仅用调用方提供的单调时钟。 */
final class FarmTiming {
    final long started;
    private long stepStarted, previousInput=-1;
    String step="启动";
    FarmTiming(long now){started=now;stepStarted=now;}
    long elapsed(long now){return Math.max(0,now-started);}
    long stepElapsed(long now){return Math.max(0,now-stepStarted);}
    boolean change(String next,long now){
        if(step.equals(next))return false;
        step=next;stepStarted=now;return true;
    }
    long inputInterval(long now){long gap=previousInput<0?-1:Math.max(0,now-previousInput);previousInput=now;return gap;}
    static String wallTime(long millis){
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(millis));
    }
}
