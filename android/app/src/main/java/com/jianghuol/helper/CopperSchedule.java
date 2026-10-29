package com.jianghuol.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.util.*;

/** 结构化任务及本机私有配置；账号口令不写运行报告。 */
final class CopperSchedule {
    private final Context ctx;
    CopperSchedule(Context ctx){this.ctx=ctx;}
    JSONArray load()throws JSONException{return new JSONArray(ctx.getSharedPreferences("copper-schedule",0).getString("items","[]"));}
    void save(JSONArray items)throws Exception{
        if(RunGate.busy()||CopperWorkflow.active())throw new IllegalStateException("请停止后编辑任务");
        if(!ctx.getSharedPreferences("copper-schedule",0).edit().putString("items",items.toString()).commit())throw new IllegalStateException("保存失败");
    }
    static String label(JSONObject item){
        switch(item.optString("kind")){
            case CopperPlan.TEAM:return "登录并组队";
            case CopperPlan.FARM:return "队长刷铜";
            case CopperPlan.ROTATE:return "换队友角色并组队";
            case CopperPlan.SWITCH:return "更换队友账号";
            case CopperPlan.BATCH:return "快捷批次 · 三角色轮刷";
            case CopperPlan.CURRENT:return "当前账号刷铜";
            default:return "未知操作";
        }
    }
    static String description(JSONObject item){return label(item)+(CopperPlan.hasCount(item.optString("kind"))?" · "+item.optInt("count")+"场"+(CopperPlan.BATCH.equals(item.optString("kind"))?"/组":""):"");}
    private static String hint(String kind){
        switch(kind){
            case CopperPlan.TEAM:return "使用任务页配置登录并组队，核验四名队友后继续。";
            case CopperPlan.FARM:return "只让固定队长刷指定场次，不自动换角。需要队伍已组好并核验。";
            case CopperPlan.ROTATE:return "队长不变，四名队友换到下一角色并重新组队，不自动刷铜。";
            case CopperPlan.SWITCH:return "只更换四名队友账号，队长不变。换号后需添加「登录并组队」，再刷铜或换角。";
            case CopperPlan.BATCH:return "快捷操作：换号组队，三个角色各刷指定场次。也可用独立步骤自由组合。";
            default:return "运行悬浮窗所在的当前游戏账号。";
        }
    }
    static void validate(JSONArray items){
        if(items.length()==0)throw new IllegalArgumentException("请先点＋添加任务");
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)throw new IllegalArgumentException("任务损坏");
            JSONArray members=item.optJSONArray("members");
            CopperPlan.validate(item.optString("kind"),item.optInt("count",0),members==null?0:members.length());
            if(CopperPlan.hasMembers(item.optString("kind"))){
                Set<String> names=new HashSet<>();
                for(int m=0;m<4;m++){
                    JSONObject row=members.optJSONObject(m);
                    if(row==null||row.optString("account").trim().isEmpty()||row.optString("password").isEmpty()||!names.add(row.optString("account").trim()))throw new IllegalArgumentException("每批需四个不同账号及密码");
                }
            }
        }
    }
    void add(Runnable changed){
        String[] kinds={CopperPlan.TEAM,CopperPlan.FARM,CopperPlan.ROTATE,CopperPlan.SWITCH,CopperPlan.BATCH,CopperPlan.CURRENT};
        UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(ctx)).setTitle("添加操作").setItems(new String[]{"登录并组队","队长刷铜 · 设置场次","换队友角色并组队","更换队友账号","快捷批次 · 三角色轮刷","当前账号刷铜 · 单独运行"},(d,n)->edit(-1,kinds[n],changed)).setNegativeButton("取消",null).show());
    }
    void edit(int index,String kind,Runnable changed){
        try{
            JSONArray items=load();JSONObject old=index<0?new JSONObject():items.getJSONObject(index);
            LinearLayout form=new LinearLayout(ctx);form.setOrientation(1);int pad=(int)(16*ctx.getResources().getDisplayMetrics().density);form.setPadding(pad,pad/2,pad,pad/2);
            TextView hint=new TextView(ctx);hint.setText(hint(kind));hint.setTextSize(13);hint.setTextColor(UiStyle.MUTED);hint.setPadding(0,0,0,pad);form.addView(hint);
            TextView countLabel=new TextView(ctx);countLabel.setText(CopperPlan.BATCH.equals(kind)?"每组刷铜场次":"刷铜场次");countLabel.setTextSize(12);countLabel.setTextColor(UiStyle.MUTED);form.addView(countLabel);
            EditText count=new EditText(ctx);count.setInputType(InputType.TYPE_CLASS_NUMBER);count.setHint("每组战斗场次");count.setText(String.valueOf(old.optInt("count",ctx.getSharedPreferences("farm",0).getInt("count",3))));UiStyle.field(count);form.addView(count);
            if(!CopperPlan.hasCount(kind)){countLabel.setVisibility(android.view.View.GONE);count.setVisibility(android.view.View.GONE);}
            List<EditText> accounts=new ArrayList<>(),passwords=new ArrayList<>();
            RoleStore roleStore=new RoleStore(ctx);List<String> clients=roleStore.members();
            if(CopperPlan.hasMembers(kind))for(int i=0;i<4;i++){
                JSONArray previous=old.optJSONArray("members");JSONObject row=previous==null?new JSONObject():previous.optJSONObject(i);if(row==null)row=new JSONObject();
                if(index<0&&clients.size()==4){String[] saved=new AccountStore(ctx).get(clients.get(i));row.put("account",saved[0]).put("password",saved[1]);}
                TextView title=new TextView(ctx);title.setText("队友 "+(i+1)+(clients.size()==4?" · "+roleStore.labelOf(clients.get(i)):""));title.setTextSize(14);title.setTextColor(UiStyle.ACCENT);title.setPadding(0,pad/2,0,pad/3);form.addView(title);
                EditText account=new EditText(ctx);account.setSingleLine(true);account.setHint("账号");account.setText(row.optString("account"));UiStyle.field(account);form.addView(account);accounts.add(account);
                EditText password=new EditText(ctx);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("密码");password.setText(row.optString("password"));UiStyle.field(password);form.addView(password);passwords.add(password);
            }
            ScrollView scroll=new ScrollView(ctx);scroll.addView(form);
            AlertDialog dialog=new AlertDialog.Builder(UiStyle.dialog(ctx)).setTitle(label(new JSONObject().put("kind",kind))).setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
            dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
                int rounds;
                try{rounds=CopperPlan.hasCount(kind)?Integer.parseInt(count.getText().toString()):0;CopperPlan.validate(kind,rounds,accounts.size());}
                catch(Exception e){count.setError(e instanceof NumberFormatException?"请输入有效场次":e.getMessage());count.requestFocus();return;}
                Set<String> entered=new HashSet<>();
                for(int i=0;i<accounts.size();i++){
                    EditText account=accounts.get(i),password=passwords.get(i);String name=account.getText().toString().trim();
                    EditText invalid=null;String message=null;
                    if(name.isEmpty()){invalid=account;message="请输入队友账号";}
                    else if(!entered.add(name)){invalid=account;message="队友账号不能重复";}
                    else if(password.getText().toString().isEmpty()){invalid=password;message="请输入该账号的密码";}
                    if(invalid!=null){invalid.setError(message);invalid.requestFocus();return;}
                }
                try{
                    JSONObject item=new JSONObject().put("kind",kind).put("count",rounds);JSONArray members=new JSONArray();
                    for(int i=0;i<accounts.size();i++)members.put(new JSONObject().put("account",accounts.get(i).getText().toString().trim()).put("password",passwords.get(i).getText().toString()));
                    item.put("members",members);validate(new JSONArray().put(item));
                    if(index<0)items.put(item);else items.put(index,item);save(items);dialog.dismiss();changed.run();
                }catch(Exception e){Toast.makeText(ctx,e.getMessage(),Toast.LENGTH_LONG).show();}
            }));dialog.show();UiStyle.polish(dialog);
        }catch(Exception e){Toast.makeText(ctx,"任务无法读取："+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    void menu(int index,Runnable changed){
        try{JSONObject item=load().getJSONObject(index);
            UiStyle.polish(new AlertDialog.Builder(UiStyle.dialog(ctx)).setTitle(label(item)).setItems(new String[]{"编辑","上移","下移","删除"},(d,n)->{
                try{JSONArray old=load(),out=new JSONArray();List<JSONObject> rows=new ArrayList<>();for(int i=0;i<old.length();i++)rows.add(old.getJSONObject(i));
                    if(n==0){edit(index,item.optString("kind"),changed);return;}
                    if(n==1&&index>0)Collections.swap(rows,index,index-1);
                    if(n==2&&index<rows.size()-1)Collections.swap(rows,index,index+1);
                    if(n==3)rows.remove(index);for(JSONObject row:rows)out.put(row);save(out);changed.run();
                }catch(Exception e){Toast.makeText(ctx,e.getMessage(),Toast.LENGTH_LONG).show();}
            }).show());
        }catch(Exception e){Toast.makeText(ctx,"任务无法读取",Toast.LENGTH_LONG).show();}
    }
    static void countDialog(Context ctx,boolean overlay,Runnable changed){
        EditText input=new EditText(ctx);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setText(String.valueOf(ctx.getSharedPreferences("farm",0).getInt("count",3)));
        UiStyle.field(input);
        LinearLayout form=new LinearLayout(ctx);form.setOrientation(1);int pad=UiStyle.dp(ctx,24);form.setPadding(pad,UiStyle.dp(ctx,8),pad,0);
        TextView hint=new TextView(ctx);hint.setText("完成指定场次后自动停止");hint.setTextSize(13);hint.setTextColor(UiStyle.MUTED);form.addView(hint);form.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(UiStyle.dialog(ctx)).setTitle("刷铜次数").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        if(overlay)dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{int n=Integer.parseInt(input.getText().toString());CopperPlan.validate(CopperPlan.CURRENT,n,0);if(RunGate.busy())throw new IllegalStateException("运行中不能修改");ctx.getSharedPreferences("farm",0).edit().putInt("count",n).apply();dialog.dismiss();changed.run();}
            catch(Exception e){input.setError(e.getMessage());}
        }));dialog.show();UiStyle.polish(dialog);
    }
}
