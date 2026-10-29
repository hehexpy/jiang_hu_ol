package com.jianghuol.helper;

import java.util.ArrayList;
import java.util.List;

/** 只比较ID字形，不解释字符内容；白字/黄字分别去底色，逐字比较防止末位误认。 */
final class IdGlyph {
    static final int W = 16, H = 24;
    final List<boolean[]> glyphs;

    private final List<boolean[]> fineGlyphs;
    final double boundaryScore;
    final boolean complete;
    private IdGlyph(List<boolean[]> glyphs, List<boolean[]> fineGlyphs, double boundaryScore, boolean complete) {
        this.glyphs = glyphs; this.fineGlyphs = fineGlyphs;
        this.boundaryScore=boundaryScore; this.complete=complete;
    }

    private static boolean[] normalize(boolean[] mask, int stride, int left, int right,
                                       int top, int bottom, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int sy = top + y * (bottom - top + 1) / h;
            int sx = left + x * (right - left) / w;
            out[y * w + x] = mask[sy * stride + sx];
        }
        return out;
    }
    int size() { return glyphs.size(); }

    static IdGlyph extract(int[] pixels, int width, int height, boolean own, int mergeGap) {
        if (width < 1 || height < 6 || pixels.length != width * height) return null;
        boolean[] mask = new boolean[pixels.length];
        boolean[] columns = new boolean[width];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
            mask[i] = own ? r >= 175 && g >= 175 && b >= 170
                    && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 60
                    : r >= 170 && g >= 150 && b < 125 && r - b > 70;
            if (mask[i]) columns[i % width] = true;
        }
        List<int[]> spans = new ArrayList<>();
        for (int x = 0; x < width;) {
            if (!columns[x]) { x++; continue; }
            int left = x++;
            while (x < width && columns[x]) x++;
            if (!spans.isEmpty() && left - spans.get(spans.size() - 1)[1] <= mergeGap) {
                spans.get(spans.size() - 1)[1] = x;
            } else spans.add(new int[]{left, x});
        }
        List<boolean[]> chars = new ArrayList<>(), fine = new ArrayList<>();
        for (int[] span : spans) {
            int top = height, bottom = -1;
            for (int y = 0; y < height; y++) for (int x = span[0]; x < span[1]; x++) {
                if (mask[y * width + x]) { top = Math.min(top, y); bottom = Math.max(bottom, y); }
            }
            if (bottom - top + 1 < Math.max(6, height / 3)) continue;
            chars.add(normalize(mask, width, span[0], span[1], top, bottom, W, H));
            fine.add(normalize(mask, width, span[0], span[1], top, bottom, W * 2, H * 2));
        }
        // 长度不同只在完整边界可信时判不同；不能把ROI截断的ID当作另一人。
        boolean inside = !spans.isEmpty() && spans.get(0)[0] > 0
                && spans.get(spans.size()-1)[1] < width;
        double boundary = own ? 1 : envelope(mask,width,height,spans);
        // 好友弹窗的区域包括“(ID:”与末尾“)”，它们不参与角色ID比较。
        if (!own) {
            if (chars.size() < 11) return null;
            chars = new ArrayList<>(chars.subList(4, chars.size() - 1));
            fine = new ArrayList<>(fine.subList(4, fine.size() - 1));
        }
        if (chars.size() < 6 || chars.size() > 64) return null;
        return new IdGlyph(chars, fine, boundary, inside && boundary >= .84);
    }

    /** 左右括号应为镜像弧线。没有右括号/截到ROI边缘时，长度差不能证明不同。 */
    private static double envelope(boolean[] mask,int stride,int height,List<int[]> spans) {
        if(spans.size()<11) return 0;
        int[] left=spans.get(0),right=spans.get(spans.size()-1);
        boolean[][] shapes=new boolean[2][];
        int index=0;
        for(int[] span:new int[][]{left,right}) {
            int top=height,bottom=-1;
            for(int y=0;y<height;y++)for(int x=span[0];x<span[1];x++)if(mask[y*stride+x]) {
                top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
            int h=bottom-top+1,w=span[1]-span[0];
            if(h<6 || w/(double)h>.60 || w/(double)h<.08) return 0;
            shapes[index++]=normalize(mask,stride,span[0],span[1],top,bottom,8,24);
        }
        boolean[] mirrored=new boolean[8*24];
        for(int y=0;y<24;y++)for(int x=0;x<8;x++) mirrored[y*8+x]=shapes[1][y*8+7-x];
        return Math.min(envelopeCoverage(shapes[0],mirrored),envelopeCoverage(mirrored,shapes[0]));
    }
    private static double envelopeCoverage(boolean[] a,boolean[] b) {
        int count=0,matched=0;
        for(int y=0;y<24;y++)for(int x=0;x<8;x++)if(a[y*8+x]) {
            count++;boolean hit=false;
            for(int dy=-1;dy<=1&&!hit;dy++)for(int dx=-1;dx<=1;dx++) {
                int xx=x+dx,yy=y+dy;
                if(xx>=0&&xx<8&&yy>=0&&yy<24&&b[yy*8+xx]){hit=true;break;}
            }
            if(hit)matched++;
        }
        return count==0?0:matched/(double)count;
    }

    static final class Score {
        final double min, mean;
        Score(double min, double mean) { this.min = min; this.mean = mean; }
        boolean matches() { return min >= 0.82 && mean >= 0.88; }
        // 换队长真实白38/黄35为0.716~0.730；正样本最低0.857。
        // 0.77以下确认不同，0.77~0.82仍保留不确定区间。
        boolean different() { return min < 0.77; }
    }

    Score compare(IdGlyph other) {
        if (other == null) return null;
        if (size() != other.size()) return complete && other.complete ? new Score(0,0) : null;
        Score primary = compareAt(glyphs, other.glyphs, W, H, 1);
        // 白/黄抗锯齿的1px边界差在16x24取整时会放大。同一“4”原始
        // 高度16/17px，粗采样落入不确定区间；仅此时用缓存的双倍网格重验。
        // 平移的归一化范围不扩大，阈值不变；明确不同的ID不能被兜底翻为相同。
        if (primary.matches() || primary.different()) return primary;
        Score fine = compareAt(fineGlyphs, other.fineGlyphs, W * 2, H * 2, 2);
        return fine.matches() ? fine : primary;
    }

    private static Score compareAt(List<boolean[]> left, List<boolean[]> right, int w, int h, int shift) {
        double min = 1, sum = 0;
        for (int i = 0; i < left.size(); i++) {
            boolean[] a = left.get(i), b = right.get(i);
            int count = 0;
            for (int j = 0; j < a.length; j++) { if (a[j]) count++; if (b[j]) count++; }
            double best = 0;
            for (int dy = -shift; dy <= shift; dy++) for (int dx = -shift; dx <= shift; dx++) {
                int overlap = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int ax = x - dx, ay = y - dy;
                    if (ax >= 0 && ax < w && ay >= 0 && ay < h && a[ay * w + ax] && b[y * w + x]) overlap++;
                }
                best = Math.max(best, count == 0 ? 0 : 2.0 * overlap / count);
            }
            min = Math.min(min, best); sum += best;
        }
        return new Score(min, sum / left.size());
    }
}
