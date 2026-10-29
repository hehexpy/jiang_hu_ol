package com.jianghuol.helper;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * HelperApp —— 进程内公共件持有者 + 「队伍」配置的读入口。
 *
 * 为什么不放在 MainActivity 里：PhoneRunner 跑在工作线程，需要不依赖 Activity
 * 生命周期的入口；配置真源是 SharedPreferences（RoleStore / AccountStore），
 * 这里只做**组**装成 PhoneRunner 要用的扁平结构。
 */
public final class HelperApp {

    private HelperApp() {
    }

    /** 一个号：客户端包名 + 游戏账号 + 密码 + 游戏内角色 ID。 */
    public static final class Account {
        public final String pkg, account, password, charId;
        public final boolean isCaptain;

        Account(String pkg, String account, String password, String charId, boolean isCaptain) {
            this.pkg = pkg; this.account = account; this.password = password;
            this.charId = charId == null ? "" : charId;
            this.isCaptain = isCaptain;
        }
    }

    /** 一支队伍：1 队长 + N 队员。 */
    public static final class Team {
        public final Account captain;
        public final List<Account> members;

        Team(Account captain, List<Account> members) {
            this.captain = captain; this.members = members;
        }

        public List<Account> all() {
            List<Account> out = new ArrayList<>();
            out.add(captain);
            out.addAll(members);
            return out;
        }
    }

    /**
     * 从 App 内配置读出队伍结构。
     *
     * 角色 ID（charId）不在 App 里维护（点游戏头像拍的，永远只认 archive 里那份），
     * 所以用 **account 名** 去 char_ids.json 查；查不到就为空字符串，
     * PhoneRunner 见到空 ID 会直接中止并提示（绝不猜 ID，会把陌生人加成好友）。
     */
    public static Team loadTeam() {
        Context ctx = ContextHolder.get();
        RoleStore roles = new RoleStore(ctx);
        AccountStore accounts = new AccountStore(ctx);
        java.util.Map<String, String> idByAccount = Archive.idsByAccount(ctx);

        String lp = roles.leader();
        if (lp.isEmpty()) throw new IllegalStateException("App 里还没选队长客户端");
        Account cap = mkAccount(ctx, roles, accounts, idByAccount, lp, true);
        List<Account> members = new ArrayList<>();
        for (String p : roles.members()) {
            members.add(mkAccount(ctx, roles, accounts, idByAccount, p, false));
        }
        if (members.isEmpty()) throw new IllegalStateException("App 里还没选队员客户端");
        return new Team(cap, members);
    }

    private static Account mkAccount(Context ctx, RoleStore roles, AccountStore accounts,
                                     java.util.Map<String, String> idByAccount,
                                     String pkg, boolean isCaptain) {
        String[] cred = accounts.get(pkg);
        String account = cred[0] == null ? "" : cred[0].trim();
        String password = cred[1] == null ? "" : cred[1].trim();
        String charId = idByAccount.get(account);
        return new Account(pkg, account, password, charId == null ? "" : charId, isCaptain);
    }
}
