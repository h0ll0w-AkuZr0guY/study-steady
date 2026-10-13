package com.winlator.study;

import org.json.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Persistent library. Removing a card never removes game files or Wine saves. */
public final class GameLibrary {
    public final File metadata, games;
    private final File root;
    public final ArrayList<JSONObject> entries = new ArrayList<>();
    public GameLibrary(File files, File containerRoot) throws IOException {
        root=containerRoot;
        metadata=new File(files,"study-library"); games=new File(root,".wine/drive_c/Games");
        if(!metadata.isDirectory()&&!metadata.mkdirs())throw new IOException("无法建立游戏库");
        if(!games.isDirectory()&&!games.mkdirs())throw new IOException("无法建立游戏目录");
        File index=new File(metadata,"library.json");
        if(index.isFile())try {
            JSONArray list=new JSONObject(read(index)).getJSONArray("games");
            for(int i=0;i<list.length();i++) { JSONObject g=list.getJSONObject(i); directory(g); entries.add(g); }
        }catch(JSONException e){throw new IOException("游戏库索引损坏，原游戏和存档仍保留",e);}
    }
    public synchronized void save() throws IOException {
        try { atomic(new File(metadata,"library.json"),new JSONObject().put("schemaVersion",1).put("games",new JSONArray(entries)).toString(2)); }
        catch(JSONException e){throw new IOException(e);}
    }
    public JSONObject find(String id) { for(JSONObject g:entries)if(g.optString("id").equals(id))return g; return null; }
    public File directory(JSONObject g) throws IOException {
        String relative=safe(g.optString("directory"));
        if(!relative.equals("StudySteady")&&!relative.matches("Games/g[0-9a-f]{32}"))throw new IOException("无效游戏目录");
        File base=new File(root,".wine/drive_c").getCanonicalFile();
        File result=new File(base,relative).getCanonicalFile();
        if(!result.getPath().startsWith(base.getPath()+File.separator))throw new IOException("目录超出游戏库");
        return result;
    }
    public JSONObject registerLegacy(String exe, boolean launcherWork) throws IOException {
        if(find("study-steady")!=null || !new File(root,".wine/drive_c/StudySteady/ststeady_patch.exe").isFile())return null;
        try {
            JSONObject g=new JSONObject().put("id","study-steady").put("name","Study Steady")
                .put("directory","StudySteady").put("exe",exe).put("work",launcherWork?"launcher":"root").put("controls",true);
            entries.add(g); save(); return g;
        }catch(JSONException e){throw new IOException(e);}
    }
    public JSONObject importZip(InputStream input,String name,Charset encoding,StudyArchive.Progress progress) throws IOException {
        String id="g"+UUID.randomUUID().toString().replace("-","");
        File stage=new File(games,".import-"+id), destination=new File(games,id);
        if(!stage.mkdir())throw new IOException("无法建立导入临时目录");
        HashSet<String> paths=new HashSet<>(); long total=0; int count=0; boolean committed=false;
        try {
            try(ZipInputStream zip=new ZipInputStream(input,encoding)) {
                ZipEntry e; byte[] b=new byte[262144];
                while((e=zip.getNextEntry())!=null) {
                    String path=safe(e.getName());
                    if(++count>100000)throw new IOException("ZIP 文件数量超过 100000");
                    if(!paths.add(path.toLowerCase(Locale.ROOT)))throw new IOException("ZIP 包含重名路径："+path);
                    File target=new File(stage,path);
                    if(!target.getCanonicalPath().startsWith(stage.getCanonicalPath()+File.separator))throw new IOException("ZIP 路径越界");
                    if(e.isDirectory()) { if(!target.isDirectory()&&!target.mkdirs())throw new IOException("目录冲突："+path); continue; }
                    if(e.getSize()>stage.getUsableSpace()-512L*1024*1024)throw new IOException("剩余空间不足，需保留至少 512 MB");
                    if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("目录冲突："+path);
                    long nextSpaceCheck=total;
                    try(OutputStream out=new FileOutputStream(target)) {
                        int n; while((n=zip.read(b))!=-1) {
                            total+=n;
                            if(total>64L*1024*1024*1024)throw new IOException("ZIP 解压大小超过 64 GB");
                            if(total>=nextSpaceCheck) { if(stage.getUsableSpace()<512L*1024*1024)throw new IOException("剩余空间不足"); nextSpaceCheck=total+16L*1024*1024; }
                            out.write(b,0,n); if(progress!=null)progress.update("正在解压 "+path+" · "+(total/1024/1024)+" MB",total,0);
                        }
                    }
                    zip.closeEntry(); // validates CRC; failure leaves no library entry
                }
            }
            List<String> exes=executables(stage);
            if(exes.isEmpty())throw new IOException("ZIP 中没有 EXE；请打包完整 Windows 游戏目录");
            String preferred=exes.get(0);
            for(String exe:exes)if(!exe.toLowerCase(Locale.ROOT).matches(".*(unins|uninstall|setup|install).*")){preferred=exe;break;}
            if(!stage.renameTo(destination))throw new IOException("无法提交导入目录");
            try {
                JSONObject g=new JSONObject().put("id",id).put("name",name).put("directory","Games/"+id)
                    .put("exe",preferred).put("work","launcher").put("controls",true).put("bytes",total);
                entries.add(g);
                try { save(); } catch(IOException e){entries.remove(g); destination.renameTo(stage); throw e;}
                committed=true; return g;
            }catch(JSONException e){destination.renameTo(stage);throw new IOException(e);}
        }finally{if(!committed)erase(stage);}
    }
    public static String safe(String value) throws IOException {
        String p=value.replace('\\','/');
        if(p.endsWith("/"))p=p.substring(0,p.length()-1);
        if(p.isEmpty()||p.length()>4096||p.startsWith("/")||p.indexOf(':')>=0)throw new IOException("无效 ZIP 路径："+p);
        for(char c:p.toCharArray())if(c<32||c==127)throw new IOException("路径含控制字符");
        for(String part:p.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" "))throw new IOException("无效路径段："+p);
        return p;
    }
    public static List<String> executables(File dir) throws IOException {
        ArrayList<String> result=new ArrayList<>(); scan(dir,dir,result,0); Collections.sort(result); return result;
    }
    private static void scan(File base,File dir,List<String> result,int depth) throws IOException {
        if(depth>32)throw new IOException("游戏目录层级超过 32");
        File[] children=dir.listFiles(); if(children==null)return;
        for(File c:children) {
            if(Files.isSymbolicLink(c.toPath()))continue;
            if(c.isDirectory())scan(base,c,result,depth+1);
            else if(c.getName().toLowerCase(Locale.ROOT).endsWith(".exe"))result.add(base.toPath().relativize(c.toPath()).toString().replace('\\','/'));
        }
    }
    private static void erase(File f) throws IOException {
        if(!f.exists())return;
        if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())) { File[] children=f.listFiles(); if(children!=null)for(File c:children)erase(c); }
        if(!f.delete())throw new IOException("无法清理导入临时文件："+f.getName());
    }
    public static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8); }
    public static void atomic(File f,String value) throws IOException {
        File temp=new File(f.getParentFile(),f.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        try{Files.move(temp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
