package com.jianghuol.helper;

/** 单进程自动检查节流与提示去重；使用单调时钟，不受用户调时间影响。 */
final class UpdateNotice {
    static final long CHECK_INTERVAL=60*60*1000L;
    static final long FAILURE_INTERVAL=15*60*1000L;
    private String repository="";
    private long lastAttempt=-1,interval=CHECK_INTERVAL;
    private int notified;
    synchronized boolean due(String repo,long now){
        return !repo.isEmpty()&&(!repo.equals(repository)||lastAttempt<0||now-lastAttempt>=interval);
    }
    synchronized void attempted(String repo,long now){
        if(!repo.equals(repository)){repository=repo;notified=0;}
        lastAttempt=now;interval=FAILURE_INTERVAL;
    }
    synchronized void completed(String repo,boolean success){
        if(repo.equals(repository))interval=success?CHECK_INTERVAL:FAILURE_INTERVAL;
    }
    synchronized boolean shouldPrompt(String repo,int available,int installed){
        return repo.equals(repository)&&available>installed&&available!=notified;
    }
    synchronized void shown(int code){notified=code;}
}
