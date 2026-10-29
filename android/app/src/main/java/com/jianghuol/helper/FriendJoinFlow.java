package com.jianghuol.helper;

/** 首行好友循环；刷新是删/加后的必经步骤，UNKNOWN不等于空列表。 */
final class FriendJoinFlow {
    enum FirstRow { CAPTAIN, OTHER, EMPTY, UNKNOWN }

    interface Driver {
        boolean stopped();
        boolean refreshFriends();
        FirstRow readFirstRow();
        boolean deleteFirst();
        boolean addCaptain();
        boolean applyToCaptain();
    }

    static boolean run(Driver d, int maxDeletes) {
        if (d.stopped() || !d.refreshFriends()) return false;
        int deleted = 0;
        boolean added = false;
        while (!d.stopped()) {
            FirstRow row = d.readFirstRow();
            if (d.stopped()) return false;
            switch (row) {
                case CAPTAIN:
                    return d.applyToCaptain();
                case OTHER:
                    // 添加后首行仍不符，不能继续删；身份/刷新可能失败。
                    if (added || deleted >= maxDeletes || !d.deleteFirst()) return false;
                    deleted++;
                    break;
                case EMPTY:
                    if (added || !d.addCaptain()) return false;
                    added = true;
                    break;
                default:
                    return false;
            }
            if (d.stopped() || !d.refreshFriends()) return false;
        }
        return false;
    }
}
