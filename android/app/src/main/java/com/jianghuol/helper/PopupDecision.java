package com.jianghuol.helper;

/** 先处理前景结果，不能因背后仍有领取按钮而再次提交。 */
final class PopupDecision {
    static boolean genericAllowed(boolean world, boolean knownPanel) { return world || knownPanel; }
    static boolean candidateAllowed(boolean world, boolean knownPanel, boolean roleTitle, float y) {
        return knownPanel || (world && (y >= .13f || roleTitle));
    }
    enum Action { CLOSE_RESULT, CLAIM, CLOSE_REWARD, CLOSE_OTHER, WAIT, NONE }
    static Action choose(boolean result, boolean reward, boolean claim, boolean claimed,
                         boolean rewardClose, boolean otherClose) {
        if (result) return Action.CLOSE_RESULT;
        if (reward) {
            if (claim && !claimed) return Action.CLAIM;
            if (rewardClose) return Action.CLOSE_REWARD;
            return Action.WAIT;
        }
        return otherClose ? Action.CLOSE_OTHER : Action.NONE;
    }
}
