import com.winlator.study.GameLibrary;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
public class GameLibraryQa {
    static int count;
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);System.out.println("PASS "+label);count++;}
    static byte[] zip(String... paths)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(String p:paths){z.putNextEntry(new ZipEntry(p));z.write("synthetic".getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return b.toByteArray();}
    static void rejects(GameLibrary l,String...p)throws Exception{int before=l.entries.size();try{l.importZip(new ByteArrayInputStream(zip(p)),"bad",StandardCharsets.UTF_8,null);throw new AssertionError("accepted malformed archive");}catch(IOException e){check(l.entries.size()==before,"reject "+Arrays.toString(p));}}
    public static void main(String[] args)throws Exception{
        File tmp=Files.createTempDirectory("study-library-qa").toFile(),root=new File(tmp,"container");root.mkdirs();GameLibrary l=new GameLibrary(new File(tmp,"files"),root);
        JSONObject g=l.importZip(new ByteArrayInputStream(zip("中文游戏/game.exe","中文游戏/data.bin")),"中文游戏",StandardCharsets.UTF_8,null);
        check(new File(l.directory(g),"中文游戏/game.exe").isFile(),"Unicode ZIP paths");
        JSONObject g2=l.importZip(new ByteArrayInputStream(zip("game.exe")),"same title",StandardCharsets.UTF_8,null);
        check(!g.optString("directory").equals(g2.optString("directory")),"independent imported directories");
        g.put("name","renamed");l.save();GameLibrary reopened=new GameLibrary(new File(tmp,"files"),root);
        check(reopened.entries.size()==2&&reopened.find(g.optString("id")).getString("name").equals("renamed"),"persistent library metadata");
        File game=l.directory(g);new File(game,"savedata").mkdir();Files.write(new File(game,"savedata/slot.sav").toPath(),new byte[]{42});l.entries.remove(g);l.save();
        check(new File(game,"savedata/slot.sav").isFile(),"remove card retains saves");
        rejects(l,"../outside.exe");rejects(l,"/outside.exe");rejects(l,"C:/outside.exe");rejects(l,"game.exe","GAME.exe");rejects(l,"data.bin");rejects(l,"game.exe","folder/../bad");rejects(l,"game.exe","x./bad");
        for(File f:l.games.listFiles())check(!f.getName().startsWith(".import-"),"failed import staging removed");
        File bad=new File(root,".wine/drive_c/StudySteady");bad.mkdirs();Files.write(new File(bad,"ststeady_patch.exe").toPath(),new byte[]{1});l.registerLegacy("ststeady_patch.exe",false);l.registerLegacy("ststeady_patch.exe",false);
        check(l.entries.stream().filter(x->x.optString("id").equals("study-steady")).count()==1,"idempotent legacy registration");
        System.out.println("TOTAL "+count+" library checks passed");
    }
}
