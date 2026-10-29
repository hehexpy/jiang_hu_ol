package com.jianghuol.helper;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 只接受现场登记的职业字形模板；未知职业返回空，不能靠外观猜。 */
final class ProfessionRecognizer {
    static final class Entry {
        final String name, panel, selection, roster;
        Entry(JSONObject p) {
            name = p.optString("name"); panel = p.optString("panel"); selection = p.optString("selection");
            roster=p.optString("roster");
        }
    }
    private final Vision vision;
    private final List<Entry> entries = new ArrayList<>();
    private final float[] panelBox, selectionBox,panelGlyphBox,selectionNameBox;
    private final double panelThreshold, selectionThreshold, rosterNameThreshold,rosterRecoveryMargin;
    private final float[][] rosterNameBoxes=new float[4][];
    private final JSONObject buttons;
    private final int friendTabTimeout;
    private final JSONObject travel;
    private final JSONObject disconnect;
    private final SelectionLayout slots;
    ProfessionRecognizer(AssetManager assets, Vision vision) throws IOException {
        this.vision = vision;
        try (java.io.InputStream in = assets.open("rotation_templates.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONObject root = new JSONObject(bytes.toString("UTF-8"));
            friendTabTimeout=root.getInt("friend_tab_timeout_ms");
            RotationSession.require(friendTabTimeout>0,"好友页签等待配置必须为正数");
            buttons = root.optJSONObject("buttons");
            travel = root.optJSONObject("world_map");
            disconnect = root.getJSONObject("disconnect_recovery");
            slots = root.has("selection_layout") ? new SelectionLayout(vision,root.getJSONObject("selection_layout")) : null;
            rosterNameThreshold=root.getDouble("roster_name_threshold");
            rosterRecoveryMargin=root.getDouble("roster_recovery_margin");
            RotationSession.require(rosterRecoveryMargin>0&&rosterRecoveryMargin<=1,"姓名恢复余量未校准");
            RotationSession.require(rosterNameThreshold>=.80&&rosterNameThreshold<=1,"姓名阈值未校准");
            selectionNameBox = box(root.getJSONArray("selection_name_box"));
            JSONArray names=root.getJSONArray("roster_name_boxes");
            RotationSession.require(names.length()==4,"四行姓名坐标未登记");
            for(int i=0;i<4;i++)rosterNameBoxes[i]=box(names.getJSONArray(i));
            panelBox = box(root.getJSONArray("panel_box"));
            panelGlyphBox=box(root.getJSONArray("panel_glyph_box"));
            selectionBox = root.has("selection_box") ? box(root.getJSONArray("selection_box")) : null;
            panelThreshold = root.getDouble("panel_threshold");
            selectionThreshold = root.optDouble("selection_threshold", .90);
            RotationSession.require(panelThreshold >= .90 && selectionThreshold >= .90, "职业阈值未校准");
            JSONArray list = root.getJSONArray("professions");
            for (int i = 0; i < list.length(); i++) {
                Entry e = new Entry(list.getJSONObject(i));
                RotationSession.require(!e.name.isEmpty() && !e.panel.isEmpty(), "职业模板登记不完整");
                for (Entry old : entries) RotationSession.require(!old.name.equals(e.name), "职业名称重复登记");
                entries.add(e);
            }
        } catch (org.json.JSONException | IllegalStateException e) {
            throw new IOException("职业模板登记无效", e);
        }
    }
    private static float[] box(JSONArray a) throws org.json.JSONException {
        if (a.length() != 4) throw new IllegalStateException("职业区域不合法");
        float[] b = new float[4]; for (int i = 0; i < 4; i++) b[i] = (float) a.getDouble(i);
        RotationSession.require(b[0] >= 0 && b[1] >= 0 && b[2] <= 1 && b[3] <= 1
                && b[0] < b[2] && b[1] < b[3], "职业区域越界");
        return b;
    }
    String panel(Bitmap frame) {
        if (vision.findNear(frame, "tpl_role_title.png", .88) == null) return "";
        return readPanel(frame);
    }
    String rosterProfession(Bitmap frame,float cy) {
        String result="";int found=0;
        for(Entry e:entries)if(!e.roster.isEmpty()&&vision.findNearRegion(frame,e.roster,.90,
                new float[]{.29f,cy-.020f,.61f,cy+.020f})!=null){result=e.name;found++;}
        return found==1?result:"";
    }
    boolean hasRosterProfession(String name){for(Entry e:entries)if(e.name.equals(name))return !e.roster.isEmpty();return false;}
    double rosterNameThreshold(){return rosterNameThreshold;}
    double rosterRecoveryMargin(){return rosterRecoveryMargin;}
    RoleNameGlyph captureRosterName(Bitmap f,int row) {
        if(f==null || row<0 || row>=4 || vision.findNear(f,"tpl_team_tab.png",.88)==null)return null;
        return captureGlyph(f,rosterNameBoxes[row],false);
    }
    float rosterNameY(int row){return (rosterNameBoxes[row][1]+rosterNameBoxes[row][3])/2;}
    RoleNameGlyph captureSelectionName(Bitmap f) {
        if(f==null || enter(f)==null)return null;
        float[] b=selectionNameBox;
        int x=(int)(f.getWidth()*b[0]),y=(int)(f.getHeight()*b[1]);
        int w=(int)(f.getWidth()*b[2])-x,h=(int)(f.getHeight()*b[3])-y;
        int[] px=new int[w*h];f.getPixels(px,0,w,x,y,w,h);
        return RoleNameGlyph.selectionName(px,w,h);
    }
    RoleNameGlyph captureSelectionGlyph(Bitmap frame) {
        if(frame==null||selectionBox==null||enter(frame)==null)return null;
        return captureGlyph(frame,selectionBox,true);
    }
    RoleNameGlyph capturePanelGlyph(Bitmap frame) {
        if(frame==null||vision.findNear(frame,"tpl_role_title.png",.88)==null)return null;
        return captureGlyph(frame,panelGlyphBox,false);
    }
    private RoleNameGlyph captureGlyph(Bitmap f,float[] box,boolean green) {
        int x=(int)(f.getWidth()*box[0]),y=(int)(f.getHeight()*box[1]);
        int w=(int)(f.getWidth()*box[2])-x,h=(int)(f.getHeight()*box[3])-y;
        int[] px=new int[w*h];f.getPixels(px,0,w,x,y,w,h);
        return green?RoleNameGlyph.extractGreen(px,w,h):RoleNameGlyph.extract(px,w,h);
    }
    Vision.Match enter(Bitmap f) {
        return vision.findNearRegion(f, "tpl_enter_game.png", .90, new float[]{.30f,.60f,.70f,.68f});
    }
    int selectedSlot(Bitmap f) {
        return slots == null || enter(f) == null ? -1 : slots.selectedSlot(f);
    }
    SelectionLayout.Observation layout(Bitmap f) {
        return slots==null || enter(f)==null?null:slots.read(f);
    }
    int friendTabTimeout(){return friendTabTimeout;}
    Vision.Match button(Bitmap f, String key) {
        if (buttons == null || !buttons.has(key)) return null;
        try {
            JSONObject b = buttons.getJSONObject(key);
            Vision.Match hit=vision.findNearRegion(f, b.getString("template"), b.getDouble("threshold"),
                    box(b.getJSONArray("box")));
            if(hit==null||!b.has("tap_offset"))return hit;
            // 边框锚点只负责定位；有效点击区可能位于控件内部。
            JSONArray offset=b.getJSONArray("tap_offset");
            RotationSession.require(offset.length()==2,"按钮点击偏移未登记完整");
            int x=hit.cx+(int)Math.round(offset.getDouble(0)*f.getWidth());
            int y=hit.cy+(int)Math.round(offset.getDouble(1)*f.getHeight());
            RotationSession.require(x>=0&&x<f.getWidth()&&y>=0&&y<f.getHeight(),"按钮点击目标越界");
            return new Vision.Match(x,y,hit.w,hit.h,hit.score,f.getWidth(),f.getHeight());
        } catch(org.json.JSONException e) { throw new IllegalStateException("换角按钮登记无效", e); }
    }
    /** 同时出现掉线全文和该弹窗右上角X，才能关闭；绝不把登录页X当掉线。 */
    Vision.Match disconnectClose(Bitmap f) {
        return button(f,"disconnect_message")==null?null:button(f,"disconnect_x");
    }
    int recoveryLimit(String key) {
        try { int n=disconnect.getInt(key);RotationSession.require(n>0,"掉线恢复配置无效");return n; }
        catch(org.json.JSONException e){throw new IllegalStateException("掉线恢复配置缺失",e);}
    }
    boolean selectionReady() { return selectionBox != null; }
    /** 不依赖血量/地图背景：两个独立世界操作入口同时命中。 */
    boolean worldControls(Bitmap frame) {
        return frame != null && button(frame,"map_button") != null
                && vision.findNear(frame,"tpl_surround_label.png",.80) != null;
    }
    /** 完整三字城名与底部世界地图按钮，独立于可能被任务提示遮挡的街景。 */
    boolean cityWorld(Bitmap frame) {
        return frame != null && button(frame,"city_full_name") != null
                && button(frame,"map_button") != null && enter(frame) == null
                && mapLandmark(frame) == null;
    }
    /** 顶/底滚轴边框任一与底部X联合确认，顶部跑马灯/横向位置变化时走底边。 */
    boolean mapOpen(Bitmap f) {
        return f != null && button(f,"map_close") != null
                && (button(f,"map_header") != null || button(f,"map_footer") != null);
    }
    Vision.Match mapLandmark(Bitmap f) {
        return vision.findNearRegion(f, "rotation/map_landmark.png", .90,
                new float[]{.02f,.07f,.65f,.30f});
    }
    int mapSetting(String key) {
        try { int value=travel.getInt(key);RotationSession.require(value>0,"地图配置必须为正数");return value; }
        catch (org.json.JSONException e) { throw new IllegalStateException("地图配置缺失："+key,e); }
    }
    float[] mapDrag() {
        try {
            JSONArray a = travel.getJSONArray("drag");
            RotationSession.require(a.length()==4, "地图手势不完整");
            float[] v = new float[4];
            for (int i=0;i<4;i++) { v[i]=(float)a.getDouble(i); RotationSession.require(v[i]>=0 && v[i]<=1,"地图手势越界"); }
            return v;
        }
        catch (org.json.JSONException | NullPointerException e) { throw new IllegalStateException("地图拖动未登记", e); }
    }
    Vision.Match mapCity(Bitmap f) {
        if (mapLandmark(f) == null) return null;
        float[] area = new float[]{.30f,.28f,.80f,.40f};
        Vision.Match left = vision.findNearRegion(f, "rotation/map_city_left.png", .90, area);
        Vision.Match right = vision.findNearRegion(f, "rotation/map_city_right.png", .90, area);
        // 头像会覆盖城字；只在襄阳局部使用外框兜底，仍须左侧文字与地标共同确认。
        if (right == null) {
            try {
                JSONObject edge=buttons.getJSONObject("map_city_edge");
                Vision.Match hit=vision.findNearRegion(f,edge.getString("template"),edge.getDouble("threshold"),area);
                if(hit!=null){
                    JSONArray offset=edge.getJSONArray("tap_offset");
                    right=new Vision.Match(hit.cx+(int)Math.round(offset.getDouble(0)*f.getWidth()),
                            hit.cy+(int)Math.round(offset.getDouble(1)*f.getHeight()),hit.w,hit.h,hit.score,f.getWidth(),f.getHeight());
                }
            }catch(org.json.JSONException e){throw new IllegalStateException("襄阳标牌边框登记无效",e);}
        }
        if (left == null || right == null || Math.abs(left.fy()-right.fy()) > .007
                || right.fx()-left.fx() < .09 || right.fx()-left.fx() > .17) return null;
        // 边框兜底的点击偏移已登记到标牌内部，避免点在装饰边缘。
        return right;
    }
    /** 从(ID:的实际位置开始截取，右端留出完整括号，适配更长ID且不混入左侧昵称。 */
    IdGlyph friendId(Bitmap f) {
        if (f == null || vision.findNear(f,"tpl_apply_btn.png",.90)==null) return null;
        Vision.Match prefix=vision.findNearRegion(f,"rotation/friend_id_prefix.png",.90,
                new float[]{.15f,.34f,.895f,.382f});
        if(prefix==null)return null;
        int x0=Math.max(0,prefix.cx-prefix.w/2-Math.max(2,f.getWidth()/270));
        int y0=(int)(f.getHeight()*.349),x1=(int)(f.getWidth()*.895),y1=(int)(f.getHeight()*.376);
        int w=x1-x0,h=y1-y0;
        if(w<=0||h<=0)return null;
        int[] px=new int[w*h];f.getPixels(px,0,w,x0,y0,w,h);
        IdGlyph id=IdGlyph.extract(px,w,h,false,Math.max(1,f.getWidth()/540));
        return id!=null && id.complete?id:null;
    }
    private String readPanel(Bitmap frame) {
        String result="";int matches=0;
        for(Entry e:entries)if(vision.findNearRegion(frame,e.panel,panelThreshold,panelBox)!=null){result=e.name;matches++;}
        return matches==1?result:"";
    }
}
