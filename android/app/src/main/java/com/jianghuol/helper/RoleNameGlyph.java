package com.jianghuol.helper;

/** 比较角色昵称白字的整行字形，不识别文字内容；名册与角色页真实关系的辅助证据。 */
final class RoleNameGlyph {
    static final int W = 160, H = 40;
    final boolean[] mask;
    final double aspect;
    private RoleNameGlyph(boolean[] mask, double aspect) { this.mask = mask; this.aspect = aspect; }
    /** 选角页现场绿色职业字形，转白后与角色面板共用同一归一化比较器。 */
    static RoleNameGlyph extractGreen(int[] pixels,int width,int height) {
        if(pixels==null)return null;int[] binary=new int[pixels.length];
        for(int i=0;i<pixels.length;i++) {
            int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            binary[i]=g>=200&&g>r*2&&g>b*2?0xffffffff:0xff000000;
        }
        RoleNameGlyph glyph=extract(binary,width,height);
        return glyph!=null&&glyph.aspect>=1.3&&glyph.aspect<=2.6?glyph:null;
    }
    /** 姓名行以显式空格分开等级；只保留左侧完整白字，不推断姓名文字。 */
    static RoleNameGlyph selectionName(int[] pixels, int width, int height) {
        if (pixels == null || pixels.length != width * height) return null;
        boolean[] columns = new boolean[width];
        int left=width,right=-1,top=height,bottom=-1;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int c=pixels[y*width+x],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(Math.min(r,Math.min(g,b))>=160 && Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=40) {
                columns[x]=true;left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
        }
        int rh=bottom-top+1;
        if(rh<5 || left<=1 || right>=width-2 || top<=0 || bottom>=height-1)return null;
        int gapStart=-1,gapEnd=-1,maxGap=0;
        for(int x=left;x<=right;x++)if(!columns[x]) {
            int start=x;while(x<=right&&!columns[x])x++;
            if(x-start>maxGap){maxGap=x-start;gapStart=start;gapEnd=x;}
        }
        // 中文字内及数字间距远小于姓名/等级间的空格；边界不完整宁可停止。
        if(maxGap<rh*.40 || gapStart-left<rh*1.3)return null;
        double levelAspect=(right-gapEnd+1)/(double)rh;
        if(levelAspect<1.5 || levelAspect>3.5)return null;
        int w=gapStart-left;int[] name=new int[w*height];
        for(int y=0;y<height;y++)System.arraycopy(pixels,y*width+left,name,y*w,w);
        return extract(name,w,height);
    }
    String encode() {
        StringBuilder out=new StringBuilder(Double.toString(aspect)).append(':');
        for(int i=0;i<mask.length;i+=4){int v=0;for(int j=0;j<4;j++)v=v*2+(mask[i+j]?1:0);out.append(Character.forDigit(v,16));}
        return out.toString();
    }
    static RoleNameGlyph decode(String value) {
        if(value==null)return null;int split=value.indexOf(':');
        if(split<1||value.length()-split-1!=W*H/4)return null;
        try {
            double a=Double.parseDouble(value.substring(0,split));if(!Double.isFinite(a)||a<=0)return null;
            boolean[] bits=new boolean[W*H];int count=0;
            for(int i=0;i<W*H/4;i++){
                int v=Character.digit(value.charAt(split+1+i),16);if(v<0)return null;
                for(int j=0;j<4;j++){bits[i*4+j]=(v&(8>>j))!=0;if(bits[i*4+j])count++;}
            }
            return count<15?null:new RoleNameGlyph(bits,a);
        }catch(NumberFormatException e){return null;}
    }
    static RoleNameGlyph extract(int[] pixels, int width, int height) {
        if (pixels == null || width < 4 || height < 4 || pixels.length != width * height) return null;
        boolean[] raw = new boolean[pixels.length];
        int left = width, right = -1, top = height, bottom = -1, count = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int c = pixels[y * width + x], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            // 白字；青色面板底、边框、绿色职业均排除。
            if (Math.min(r, Math.min(g, b)) >= 160 && Math.max(r, Math.max(g,b)) - Math.min(r, Math.min(g,b)) <= 40) {
                raw[y * width + x] = true; count++;
                left = Math.min(left,x); right = Math.max(right,x); top = Math.min(top,y); bottom = Math.max(bottom,y);
            }
        }
        if (count < 15 || right - left < 4 || bottom - top < 4) return null;
        int rw = right-left+1, rh = bottom-top+1;
        boolean[] out = new boolean[W*H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            out[y*W+x] = raw[(top + Math.min(rh-1, y*rh/H))*width + left + Math.min(rw-1,x*rw/W)];
        return new RoleNameGlyph(out, rw/(double)rh);
    }
    double score(RoleNameGlyph other) {
        if (other == null || Math.abs(aspect/other.aspect-1) > .12) return 0;
        // 中文昵称等宽分格，最差一字决定结果，避免长昵称一个错字被均值掩盖。
        int chars = Math.max(1, (int)Math.round(aspect));
        if(chars != Math.max(1,(int)Math.round(other.aspect))) return 0;
        double worst = 1;
        for(int c=0;c<chars;c++) {
            int x0=c*W/chars,x1=(c+1)*W/chars;
            double forward=coverage(mask,other.mask,x0,x1),backward=coverage(other.mask,mask,x0,x1);
            worst=Math.min(worst,Math.min(forward,backward));
        }
        return worst;
    }
    private static double coverage(boolean[] from,boolean[] to,int x0,int x1) {
        return coverage(from,to,x0,x1,1);
    }
    private static double coverage(boolean[] from,boolean[] to,int x0,int x1,int radiusX) {
        int count=0,covered=0;
        for(int y=0;y<H;y++)for(int x=x0;x<x1;x++)if(from[y*W+x]) {
            count++; boolean hit=false;
            // 横向1格、纵向2格：540宽帧中约一个源像素；不扩张到相邻字符。
            for(int dy=-2;dy<=2&&!hit;dy++)for(int dx=-radiusX;dx<=radiusX;dx++) {
                int xx=x+dx,yy=y+dy;
                if(xx>=x0&&xx<x1&&yy>=0&&yy<H&&to[yy*W+xx]){hit=true;break;}
            }
            if(hit)covered++;
        }
        return count==0?0:covered/(double)count;
    }
    boolean matches(RoleNameGlyph other) { return score(other) >= .90; }
    /** 职业两字每字占80格；横向4格约一个540帧源像素，昵称比较仍保持原1格。 */
    double roleScore(RoleNameGlyph other) {
        if(other==null||Math.abs(aspect/other.aspect-1)>.12)return 0;
        int chars=Math.max(1,(int)Math.round(aspect));
        if(chars!=2||Math.round(other.aspect)!=2)return 0;double worst=1;
        for(int c=0;c<chars;c++) {
            int x0=c*W/chars,x1=(c+1)*W/chars;
            worst=Math.min(worst,Math.min(coverage(mask,other.mask,x0,x1,4),coverage(other.mask,mask,x0,x1,4)));
        }
        return worst;
    }
    boolean roleMatches(RoleNameGlyph other){return roleScore(other)>=.90;}
}
