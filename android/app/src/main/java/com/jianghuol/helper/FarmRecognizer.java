package com.jianghuol.helper;
import android.graphics.Bitmap;import org.json.JSONArray;import org.json.JSONObject;
/** 任务识别补充：颜色与文字模板独立，避免灰度模板把白字进度当成绿字完成。 */
final class FarmRecognizer{
 interface Matcher{Vision.Match hit(Bitmap b,String key)throws Exception;}
 /** Direct navigation needs the exact task title observed in its selected list row, plus map and NPC. */
 static JSONObject directNavigation(Bitmap b,Matcher match,JSONObject expected,boolean world)throws Exception{
  if(!world||expected==null)return null;
  if(match.hit(b,expected.getString("giver"))==null||match.hit(b,expected.getString("map"))==null)return null;
  return expected;
 }
 static final class TrackerFrames {
  Bitmap source,task,newTask,questTitle;int generation;
  Bitmap of(Bitmap b){return of(b,"task");}
  Bitmap of(Bitmap b,String palette){
   if(source!=b||generation!=b.getGenerationId()){
    if(task!=null)task.recycle();if(newTask!=null)newTask.recycle();if(questTitle!=null)questTitle.recycle();task=null;newTask=null;questTitle=null;source=b;generation=b.getGenerationId();
   }
   boolean isNew="new_task".equals(palette),isTitle="quest_title".equals(palette);Bitmap prepared=isTitle?questTitle:isNew?newTask:task;if(prepared!=null)return prepared;
   Bitmap normalized=Bitmap.createScaledBitmap(b,540,Math.round(b.getHeight()*540f/b.getWidth()),true);
   int w=normalized.getWidth(),h=normalized.getHeight();int[] pixels=new int[w*h];normalized.getPixels(pixels,0,w,0,0,w,h);
   for(int i=0;i<pixels.length;i++){int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,blue=c&255;
    boolean hit=isTitle?(r>180&&g>70&&blue<130):isNew?((r>220&&g>170&&blue<155&&r>blue*1.4)||(r>200&&blue>140&&g<120)):
     ((r>200&&g>200&&blue>200)||(r<100&&g>200&&blue<100&&g>r*1.8));pixels[i]=hit?255:0;
   }
   int[] smooth=new int[w*h];java.util.Arrays.fill(smooth,0xff000000);
   for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x;int value=(pixels[i-w-1]+2*pixels[i-w]+pixels[i-w+1]+2*pixels[i-1]+4*pixels[i]+2*pixels[i+1]+pixels[i+w-1]+2*pixels[i+w]+pixels[i+w+1]+8)/16;smooth[i]=0xff000000|(value<<16)|(value<<8)|value;}
   Bitmap small=Bitmap.createBitmap(smooth,w,h,Bitmap.Config.ARGB_8888);prepared=Bitmap.createScaledBitmap(small,b.getWidth(),b.getHeight(),true);if(prepared!=small)small.recycle();if(normalized!=b)normalized.recycle();if(isTitle)questTitle=prepared;else if(isNew)newTask=prepared;else task=prepared;return prepared;
  }
 }
 static Vision.Match template(Bitmap b,Vision vision,JSONObject templates,String key,double threshold,TrackerFrames glyphs)throws Exception{
  JSONObject base=templates.getJSONObject(key);JSONArray alternatives=base.optJSONArray("alternates");Vision.Match best=null;
  for(int index=-1;index<(alternatives==null?0:alternatives.length());index++){
   JSONObject spec=index<0?base:templates.getJSONObject(alternatives.getString(index));JSONArray box=spec.getJSONArray("box");float[] region=new float[4];for(int i=0;i<4;i++)region[i]=(float)box.getDouble(i);
   Bitmap frame=spec.optBoolean("glyph")?glyphs.of(b,spec.optString("glyph_palette","task")):b;
   Vision.Match hit=vision.findNearRegion(frame,spec.getString("template"),threshold,region);if(hit!=null&&(best==null||hit.score>best.score))best=hit;
  }
  return best;
 }
 static boolean activeBattle(Bitmap b,Matcher match)throws Exception{
  return match.hit(b,"battle_coin")!=null&&match.hit(b,"battle_chest")!=null
   &&(match.hit(b,"battle_escape")!=null||match.hit(b,"battle_yudang_name")!=null);
 }
 static boolean modal(Bitmap b,Matcher match)throws Exception{
  return match.hit(b,"quest_navigation_loading")!=null||questModal(b,match);
 }
 /** 只认已接列表中的完整余党标题；不点详情、不交付、不以完成字样计场。 */
 static Vision.Match acceptedYudangList(Bitmap b,Matcher match,JSONObject templates)throws Exception{
  if(match.hit(b,"discover_list_title")==null||!goldTab(b,templates.getJSONObject("_discovery").getJSONArray("tab_boxes").getJSONArray(0)))return null;
  if(match.hit(b,"discover_row_yudang_accepted_live")==null)return null;
  return match.hit(b,"discover_tasks_close");
 }
 /** “请稍候”也是战斗入场过渡；本判据仅表示不能操作的真实任务/物品窗口。 */
 static boolean questModal(Bitmap b,Matcher match)throws Exception{
  if(bagFull(b,match)||battleCooldown(b,match))return true;
  for(String key:new String[]{"finish_risk","accept_risk","quest_close","bag_close","reward_title"})if(match.hit(b,key)!=null)return true;
  return (match.hit(b,"discover_follow_close")!=null&&match.hit(b,"discover_follow_escape")!=null)
   ||(match.hit(b,"discover_intro_title")!=null&&match.hit(b,"discover_intro_close")!=null)
   ||(match.hit(b,"early_option_npc")!=null&&match.hit(b,"early_action_option_intro")!=null);
 }
 static Vision.Match checkpoint(Bitmap b,Matcher match,JSONObject templates,JSONObject rule,boolean world)throws Exception{
  if(bagFull(b,match)||battleCooldown(b,match))return null;
  JSONObject cfg=templates.getJSONObject("_discovery");
  if("world".equals(rule.optString("kind"))&&!world)return null;
  if(rule.getString("name").startsWith("accept_")&&acceptedStamp(b,cfg.getJSONArray("completion_box")))return null;
  if(rule.getString("name").startsWith("finish_")&&!completedStamp(b,cfg.getJSONArray("completion_box")))return null;
  JSONArray guards=rule.getJSONArray("guards");for(int g=0;g<guards.length();g++)if(match.hit(b,guards.getString(g))==null)return null;
  if("world".equals(rule.optString("kind"))&&greenAtMatch(b,match.hit(b,guards.getString(0)))!=rule.getBoolean("tracker_green"))return null;
  return match.hit(b,rule.getString("action"));
 }
 /** 只在上一交付已派发、对应主线追踪已消失时打开下一NPC；未知标题不会接取。 */
 static Vision.Match handoff(Bitmap b,Matcher match,JSONObject rule,boolean world,int pending)throws Exception{
  if(!world||!rule.has("after_completion")||pending!=rule.getInt("after_completion"))return null;
  if(match.hit(b,rule.getString("completed_tracker"))!=null)return null;
  if(match.hit(b,rule.getJSONArray("guards").getString(1))==null)return null;
  return match.hit(b,rule.getString("action"));
 }
 enum Color{GOLD,GREEN,RED}
 static JSONObject trackedQuest(Bitmap b,Matcher match,JSONObject cfg)throws Exception{
  JSONObject found=null;JSONArray routes=cfg.getJSONArray("navigation");
  for(int i=0;i<routes.length();i++){
   JSONObject route=routes.getJSONObject(i);if(!route.has("tracked_title"))continue;
   Vision.Match hit=match.hit(b,route.getString("tracked_title"));
   if(hit!=null&&!greenAtMatch(b,hit)){
    if(found!=null&&!found.getString("name").equals(route.getString("name")))throw new IllegalStateException("白字任务不唯一");
    found=route;
   }
  }
  return found;
 }
 static Vision.Match continueChallenge(Bitmap b,Matcher match,JSONObject templates)throws Exception{
  JSONObject r=templates.getJSONObject("_challenge_resume");
  if(bagFull(b,match)||battleCooldown(b,match)||match.hit(b,r.getString("title"))==null||match.hit(b,r.getString("goal"))==null)return null;
  return match.hit(b,r.getString("action"));
 }
 static boolean battleCooldown(Bitmap b,Matcher match)throws Exception{return match.hit(b,"bag_full_error_title")!=null&&match.hit(b,"battle_cooldown_message")!=null;}
 static boolean bagFull(Bitmap b,Matcher match)throws Exception{return match.hit(b,"bag_full_error_title")!=null&&match.hit(b,"bag_full_message")!=null;}
 static double ratio(Bitmap b,JSONArray box,Color color)throws Exception{
  int count=0,total=0;
  for(int y=(int)(box.getDouble(1)*b.getHeight());y<box.getDouble(3)*b.getHeight();y++)for(int x=(int)(box.getDouble(0)*b.getWidth());x<box.getDouble(2)*b.getWidth();x++){
   int c=b.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,blue=c&255;total++;
   boolean hit=color==Color.GOLD?r>150&&g>110&&blue<105&&r>blue*1.6:color==Color.GREEN?r<100&&g>140&&blue<100&&g>r*1.5:r>130&&g<90&&blue<90;
   if(hit)count++;
  }
  return total==0?0:count/(double)total;
 }
 static boolean goldTab(Bitmap b,JSONArray box)throws Exception{return ratio(b,box,Color.GOLD)>.04;}
 static boolean greenAtMatch(Bitmap b,Vision.Match hit)throws Exception{
  if(hit==null)return false;
  JSONArray box=new JSONArray();box.put(Math.max(0,(hit.cx-hit.w/2)/(double)b.getWidth()));box.put(Math.max(0,(hit.cy-hit.h/2)/(double)b.getHeight()));box.put(Math.min(1,(hit.cx+(hit.w+1)/2)/(double)b.getWidth()));box.put(Math.min(1,(hit.cy+(hit.h+1)/2)/(double)b.getHeight()));
  return ratio(b,box,Color.GREEN)>.08;
 }
 static boolean greenTracker(Bitmap b,JSONArray box)throws Exception{return ratio(b,box,Color.GREEN)>.08;}
 static boolean acceptedStamp(Bitmap b,JSONArray box)throws Exception{return ratio(b,box,Color.GOLD)>.15;}
 static boolean completedStamp(Bitmap b,JSONArray box)throws Exception{return ratio(b,box,Color.RED)>.15;}
 /** Bright cyan thumb inside the registered scrollbar track, independent of quest text. */
 static final class Scroll {
  final boolean top,bottom,single;
  final double first,last;
  Scroll(boolean top,boolean bottom,boolean single,double first,double last){this.top=top;this.bottom=bottom;this.single=single;this.first=first;this.last=last;}
 }
 static Scroll scrollbar(Bitmap b,JSONObject cfg)throws Exception{
  JSONArray box=cfg.getJSONArray("scrollbar_box");
  int x0=(int)(box.getDouble(0)*b.getWidth()),x1=(int)(box.getDouble(2)*b.getWidth());
  int first=-1,last=-1,rows=0;
  for(int y=(int)(box.getDouble(1)*b.getHeight());y<box.getDouble(3)*b.getHeight();y++){
   int hits=0;for(int x=x0;x<x1;x++){int c=b.getPixel(x,y),r=(c>>16)&255,g=(c>>8)&255,blue=c&255;if(g>190&&blue>130&&r<180)hits++;}
   if(hits>=(x1-x0)*.25){if(first<0)first=y;last=y;rows++;}
  }
  double a=first/(double)b.getHeight(),z=last/(double)b.getHeight();
  boolean single=rows<b.getHeight()*.008;
  return new Scroll(!single&&a<cfg.getDouble("scrollbar_top_max"),!single&&z>cfg.getDouble("scrollbar_bottom_min"),single,a,z);
 }
}
