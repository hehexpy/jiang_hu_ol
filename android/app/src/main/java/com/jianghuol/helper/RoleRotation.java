package com.jianghuol.helper;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** 可选功能持久记录。读取失败必须显式报错，不能把损坏记录当新一轮。 */
final class RoleRotation implements RotationController.Repository {
    private final Context context;
    private final AtomicFile file;
    RoleRotation(Context context) {
        this.context = context.getApplicationContext();
        file = new AtomicFile(new File(this.context.getFilesDir(), "role-rotation.properties"));
    }
    boolean enabled() {
        return context.getSharedPreferences("role-rotation", Context.MODE_PRIVATE).getBoolean("enabled", false);
    }
    void setEnabled(boolean enabled) {
        RotationSession.require(!RunGate.busy(), "流程运行中不能更改换角开关");
        RotationSession.require(context.getSharedPreferences("role-rotation", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).commit(), "换角开关保存失败");
    }
    RotationSession load() throws IOException {
        synchronized (RoleRotation.class) { return loadLocked(); }
    }
    private RotationSession loadLocked() throws IOException {
        // AtomicFile.openRead会优先恢复未完成写入的备份，不能仅检查主文件。
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        Properties p = new Properties();
        try (java.io.InputStream input = file.openRead()) { p.load(input); }
        try { return RotationSession.decode(p); }
        catch (RuntimeException e) { throw new IOException("换角记录损坏，禁止自动重置", e); }
    }
    public void save(RotationSession session) throws IOException {
        synchronized (RoleRotation.class) { saveLocked(session); }
    }
    private void saveLocked(RotationSession session) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            session.encode().store(output, "role rotation v5; current target only; no credentials");
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }
    /** 显式新轮入口；普通组队也会由PhoneRunner在本轮开始时建立全新记录，Activity重建不清记录。 */
    synchronized void startExplicitly(RotationSession session) throws IOException {
        RotationSession.require(enabled() && !RunGate.busy(), "未启用换角或有流程运行");
        RotationSession.require(session.phase == RotationSession.Phase.INITIAL, "新轮状态不合法");
        save(session);
    }
}
