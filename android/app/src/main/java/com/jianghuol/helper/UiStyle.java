package com.jianghuol.helper;

import android.content.Context;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

/** 首页、悬浮框和表单共用的视觉基础，不含业务行为。 */
final class UiStyle {
    static final int BG=0xff101b19,CARD=0xff1b2926,TEXT=0xfff0ede4,MUTED=0xffaebfb3,ACCENT=0xffa7d4b2,LINE=0xff3c5148;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static Context dialog(Context c){return new ContextThemeWrapper(c,R.style.HelperDialog);}
    static void polish(AlertDialog dialog){
        if(dialog.getWindow()==null)return;
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        dialog.getWindow().getDecorView().setClipToOutline(true);
        android.widget.Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if(save!=null){save.setTextColor(BG);clickable(save,ACCENT,12);save.setMinWidth(dp(dialog.getContext(),88));}
    }
    static GradientDrawable shape(Context c,int color,int radius,int border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border!=0)d.setStroke(dp(c,1),border);return d;
    }
    static void clickable(View v,int color,int radius){v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),shape(v.getContext(),color,radius,0),null));}
    static void field(EditText input){
        Context c=input.getContext();input.setTextColor(TEXT);input.setHintTextColor(0xff82968b);input.setTextSize(16);
        input.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));input.setMinHeight(dp(c,50));input.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));
        input.setBackground(shape(c,BG,12,LINE));input.setSelectAllOnFocus(true);
        input.setOnFocusChangeListener((v,on)->input.setBackground(shape(c,BG,12,on?ACCENT:LINE)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,6);lp.bottomMargin=dp(c,12);input.setLayoutParams(lp);
    }
}
