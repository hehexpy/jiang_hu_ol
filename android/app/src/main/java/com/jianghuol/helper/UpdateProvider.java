package com.jianghuol.helper;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** 仅通过临时读取授权分享已校验的单个APK，不能访问其他缓存。 */
public final class UpdateProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{
        if(!"/update.apk".equals(uri.getPath())||!getContext().getPackageName().concat(".updates").equals(uri.getAuthority())
                ||uri.getQuery()!=null)throw new FileNotFoundException("未知更新文件");
        File f=GitHubUpdater.ready(getContext());if(!f.isFile())throw new FileNotFoundException("未下载更新");return f;
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("只允许读取");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public String getType(Uri uri){return "application/vnd.android.package-archive";}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{File f=file(uri);String[] names=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(names);Object[] values=new Object[names.length];
            for(int i=0;i<names.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(names[i])?"jianghu-update.apk":OpenableColumns.SIZE.equals(names[i])?f.length():null;
            cursor.addRow(values);return cursor;
        }catch(FileNotFoundException e){return null;}
    }
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
