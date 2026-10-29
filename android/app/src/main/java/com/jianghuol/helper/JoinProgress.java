package com.jianghuol.helper;

/** 申请回执只记已发出；最终成功必须全部派发且本轮队长名册人数恰好齐全。 */
final class JoinProgress {
    enum State { PENDING, CONFIRMED, READ_FAILED, STOPPED }
    private final int expected;
    private int dispatched, acknowledged, unreadable;
    JoinProgress(int expected) { this.expected = expected; }
    boolean request(boolean sent, boolean ack) {
        if (!sent) return false;
        dispatched++;
        if (ack) acknowledged++;
        return true;
    }
    int sent() { return dispatched; }
    int acknowledged() { return acknowledged; }
    State observe(int roster, boolean stopped) {
        if (stopped) return State.STOPPED;
        if (roster < 0) return ++unreadable >= 3 ? State.READ_FAILED : State.PENDING;
        unreadable = 0;
        return expected > 0 && dispatched == expected && roster == expected
                ? State.CONFIRMED : State.PENDING;
    }
}
