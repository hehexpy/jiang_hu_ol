package com.jianghuol.helper;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;

/** 逐槽外形唯一模板、独立箭头；外形标识只验槽位，职业必须读选角页和角色页。 */
final class SelectionLayout {
    static final class Observation {
        final String[] tokens;
        final Vision.Match[] targets;
        final int selected;
        Observation(String[] p,Vision.Match[] t,int selected){tokens=p;targets=t;this.selected=selected;}
        String encode(){return String.join("|",tokens);}
    }
    private final Vision vision;
    private final JSONObject config;
    SelectionLayout(Vision vision,JSONObject config){this.vision=vision;this.config=config;}
    private float[] box(JSONArray a)throws org.json.JSONException {
        float[] b=new float[4];for(int i=0;i<4;i++)b[i]=(float)a.getDouble(i);return b;
    }
    /** 选后只验证唯一选中箭头；旁边未操作角色的动态装饰不参与。 */
    int selectedSlot(Bitmap f) {
        if (f == null) return -1;
        try {
            JSONArray markers = config.getJSONArray("markers");
            int selected = -1;
            for (int i=0; i<3; i++) {
                if (vision.findNearRegion(f, config.getString("marker_template"), .90,
                        box(markers.getJSONArray(i))) != null) {
                    if (selected != -1) return -1;
                    selected = i;
                }
            }
            return selected;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("选中箭头登记无效", e);
        }
    }
    Observation read(Bitmap f) {
        try {
            JSONArray slots=config.getJSONArray("slots"),markers=config.getJSONArray("markers"),faces=config.getJSONArray("faces");
            String[] names=new String[3];Vision.Match[] targets=new Vision.Match[3];int selected=-1;
            for(int i=0;i<3;i++) {
                int found=0;
                for(int j=0;j<faces.length();j++) {
                    JSONObject e=faces.getJSONObject(j);
                    Vision.Match hit=vision.findNearRegion(f,e.getString("template"),.90,box(slots.getJSONArray(i)));
                    if(hit!=null){found++;names[i]=e.getString("name");targets[i]=hit;}
                }
                if(found!=1)return null;
                if(vision.findNearRegion(f,config.getString("marker_template"),.90,box(markers.getJSONArray(i)))!=null) {
                    if(selected!=-1)return null;selected=i;
                }
            }
            if(selected<0||new HashSet<>(java.util.Arrays.asList(names)).size()!=3)return null;
            return new Observation(names,targets,selected);
        }catch(org.json.JSONException e){throw new IllegalStateException("三槽模板登记无效",e);}
    }
    static String reordered(String before,int selected) {
        String[] p=before.split("\\|",-1);
        RotationSession.require(p.length==3 && (selected==0||selected==2),"选择前布局缺失");
        return selected==0?String.join("|",p[1],p[0],p[2]):String.join("|",p[1],p[2],p[0]);
    }
}
