import com.winlator.study.StudyArchive;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public class StudyArchiveQa {
 // All fixtures are synthetic bytes. The runner supplies an isolated temporary root.
 static Path testRoot;
 static int passed;
 static String hash(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format("%02x",x&255));return s.toString();}
 static void ok(boolean v,String name){if(!v)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
 static byte[] manifest(Map<String,byte[]> fs,boolean save)throws Exception{JSONObject j=new JSONObject();j.put("schema",1);j.put("gameId","study-steady");j.put(save?"kind":"launcher",save?"save-backup":"ststeady_patch.exe");JSONArray a=new JSONArray();long total=0;for(Map.Entry<String,byte[]> e:fs.entrySet()){JSONObject f=new JSONObject();f.put("path",e.getKey());f.put("size",e.getValue().length);f.put("sha256",hash(e.getValue()));a.put(f);total+=e.getValue().length;}j.put("totalBytes",total);j.put("files",a);return j.toString().getBytes("UTF-8");}
 static byte[] zip(Map<String,byte[]> fs,boolean save,byte[] manifest)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:fs.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}z.putNextEntry(new ZipEntry(save?"save-manifest.json":"study-manifest.json"));z.write(manifest);z.closeEntry();}return out.toByteArray();}
 static File root()throws Exception{return (testRoot==null?Files.createTempDirectory("study-qa-"):Files.createTempDirectory(testRoot,"study-qa-")).toFile();}
 static Map<String,byte[]> game(){Map<String,byte[]> f=new LinkedHashMap<>();f.put("ststeady_patch.exe",new byte[]{1,2});f.put("script.noa",new byte[]{3,4});f.put("psb.noa",new byte[]{5,6});f.put("savedata/00001",new byte[]{7});return f;}
 static void put(File r,String p,String s)throws Exception{File f=new File(r,p);f.getParentFile().mkdirs();Files.write(f.toPath(),s.getBytes("UTF-8"));}
 static String get(File r,String p)throws Exception{return new String(Files.readAllBytes(new File(r,p).toPath()),"UTF-8");}
 interface Job{void run()throws Exception;}
 static void rejects(Job j,String name)throws Exception{try{j.run();throw new AssertionError("accepted "+name);}catch(IOException e){passed++;System.out.println("PASS reject "+name);}}
 public static void main(String[]args)throws Exception{
  if(args.length>0){testRoot=Paths.get(args[0]);Files.createDirectories(testRoot);}
  Map<String,byte[]> f=game();File r=root();StudyArchive.importGame(new ByteArrayInputStream(zip(f,false,manifest(f,false))),r,null);ok(new File(r,".wine/drive_c/StudySteady/psb.noa").isFile(),"import commit");ok(!new File(r,".wine/drive_c/.StudySteady-import").exists(),"import staging removed");rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(f,false,manifest(f,false))),r,null),"existing game");
  // The translation-folder launcher is allowed only at its exact known relative path.
  String nestedLauncher="补丁/汉化补丁/ststeady_patch.exe";
  Map<String,byte[]> nested=game();nested.remove("ststeady_patch.exe");nested.put(nestedLauncher,new byte[]{11,12});nested.put("补丁/汉化补丁/hook.dll",new byte[]{13});nested.put("补丁/汉化补丁/patchcn.noa",new byte[]{14});
  JSONObject nestedJson=new JSONObject(new String(manifest(nested,false),"UTF-8"));nestedJson.put("launcher",nestedLauncher);byte[] nestedManifest=nestedJson.toString().getBytes("UTF-8");
  File nestedRoot=root();StudyArchive.importGame(new ByteArrayInputStream(zip(nested,false,nestedManifest)),nestedRoot,null);
  ok(new File(nestedRoot,".wine/drive_c/StudySteady/"+nestedLauncher).isFile()&&new File(nestedRoot,".wine/drive_c/StudySteady/补丁/汉化补丁/hook.dll").isFile()&&new File(nestedRoot,".wine/drive_c/StudySteady/补丁/汉化补丁/patchcn.noa").isFile(),"nested translation files import");
  Map<String,byte[]> missingLauncher=new LinkedHashMap<>(nested);missingLauncher.remove(nestedLauncher);JSONObject missingJson=new JSONObject(new String(manifest(missingLauncher,false),"UTF-8"));missingJson.put("launcher",nestedLauncher);
  rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(missingLauncher,false,missingJson.toString().getBytes("UTF-8"))),root(),null),"missing selected launcher");
  Map<String,byte[]> unexpectedNested=new LinkedHashMap<>(nested);unexpectedNested.put("补丁/汉化补丁/other.exe",new byte[]{15});JSONObject unexpectedJson=new JSONObject(new String(manifest(unexpectedNested,false),"UTF-8"));unexpectedJson.put("launcher",nestedLauncher);
  rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(unexpectedNested,false,unexpectedJson.toString().getBytes("UTF-8"))),root(),null),"unapproved nested executable");
  Map<String,byte[]> coreMissing=new LinkedHashMap<>(nested);coreMissing.remove("script.noa");JSONObject coreJson=new JSONObject(new String(manifest(coreMissing,false),"UTF-8"));coreJson.put("launcher",nestedLauncher);
  rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(coreMissing,false,coreJson.toString().getBytes("UTF-8"))),root(),null),"nested launcher missing core");
  File bad=root();byte[] badManifest=manifest(f,false);JSONObject bj=new JSONObject(new String(badManifest,"UTF-8"));bj.getJSONArray("files").getJSONObject(0).put("sha256",String.join("",Collections.nCopies(64,"0")));rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(f,false,bj.toString().getBytes("UTF-8"))),bad,null),"corrupt hash");ok(!new File(bad,".wine/drive_c/.StudySteady-import").exists(),"failure cleanup");
  Map<String,byte[]> slip=game();slip.put("../outside",new byte[]{3});rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(slip,false,manifest(slip,false))),root(),null),"zip slip");
  Map<String,byte[]> dup=game();dup.put("SCRIPT.noa",new byte[]{9});rejects(()->StudyArchive.importGame(new ByteArrayInputStream(zip(dup,false,manifest(dup,false))),root(),null),"case collision");
  File usb=root();String st=".wine/drive_c/.StudySteady-import/";for(Map.Entry<String,byte[]> e:f.entrySet()){File p=new File(usb,st+e.getKey());p.getParentFile().mkdirs();Files.write(p.toPath(),e.getValue());}Files.write(new File(usb,st+"study-manifest.json").toPath(),manifest(f,false));ok(StudyArchive.hasStagedImport(usb),"USB detection");StudyArchive.finishStagedImport(usb,null);ok(new File(usb,".wine/drive_c/StudySteady/script.noa").isFile(),"USB commit");
  File brokenUsb=root();put(brokenUsb,st+"study-manifest.json",new String(manifest(f,false),"UTF-8"));put(brokenUsb,st+"unexpected","x");rejects(()->StudyArchive.finishStagedImport(brokenUsb,null),"USB missing/extra files");ok(new File(brokenUsb,st+"unexpected").exists(),"USB failure retained");
  File saves=root();put(saves,".wine/user.reg","oldregistry");put(saves,".wine/drive_c/users/winlator/AppData/Roaming/game/slot.dat","slotA");put(saves,".wine/drive_c/StudySteady/savedata/sysenv.dat","sysA");put(saves,".wine/drive_c/StudySteady/options.ini","iniA");put(saves,".wine/drive_c/StudySteady/script.noa","largeasset");ByteArrayOutputStream bo=new ByteArrayOutputStream();StudyArchive.backup(saves,bo,null);Set<String> backupNames=new HashSet<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bo.toByteArray()))){ZipEntry e;while((e=z.getNextEntry())!=null)backupNames.add(e.getName());}ok(backupNames.contains("save-manifest.json")&&backupNames.contains(".wine/drive_c/StudySteady/options.ini")&&!backupNames.contains(".wine/drive_c/StudySteady/script.noa"),"backup scope");put(saves,".wine/user.reg","modified");StudyArchive.restore(new ByteArrayInputStream(bo.toByteArray()),saves,null);ok(get(saves,".wine/user.reg").equals("oldregistry"),"restore data");File[] retained=saves.listFiles((d,n)->n.startsWith(".StudySteady-before-restore-"));ok(retained!=null&&retained.length==1&&get(retained[0],".wine/user.reg").equals("modified"),"original retained");
  File rollback=root();put(rollback,".wine/user.reg","original");new File(rollback,".wine/system.reg").mkdir();Map<String,byte[]> rf=new LinkedHashMap<>();rf.put(".wine/user.reg","new".getBytes("UTF-8"));rf.put(".wine/system.reg","new2".getBytes("UTF-8"));rejects(()->StudyArchive.restore(new ByteArrayInputStream(zip(rf,true,manifest(rf,true))),rollback,null),"restore commit failure");ok(get(rollback,".wine/user.reg").equals("original"),"rollback restored original");ok(!new File(rollback,".StudySteady-restore").exists(),"rollback staging cleaned");
  Map<String,byte[]> escape=new LinkedHashMap<>();escape.put(".wine/drive_c/StudySteady/ststeady_patch.exe",new byte[]{8});rejects(()->StudyArchive.restore(new ByteArrayInputStream(zip(escape,true,manifest(escape,true))),saves,null),"restore executable escape");
  JSONObject large=new JSONObject(new String(manifest(game(),false),"UTF-8"));large.getJSONArray("files").getJSONObject(2).put("size",5000000000L);large.put("totalBytes",5000000005L);java.lang.reflect.Method read=StudyArchive.class.getDeclaredMethod("readManifest",byte[].class,boolean.class);read.setAccessible(true);Object result=read.invoke(null,large.toString().getBytes("UTF-8"),false);java.lang.reflect.Field total=result.getClass().getDeclaredField("total");total.setAccessible(true);ok(total.getLong(result)==5000000005L,"64-bit manifest sizes");
  File multi=root();String generic=".wine/drive_c/Games/g0123456789abcdef0123456789abcdef/中文游戏/";
  put(multi,generic+"engine/saves/slot01.bin","nested-slot");
  put(multi,generic+"data/quick.sav","quick-slot");
  put(multi,generic+"options.ini","options");
  put(multi,generic+"game.exe","game-binary");
  put(multi,".wine/drive_c/StudySteady/buding/hanhuabuding/savedata/00001","legacy-nested");
  ByteArrayOutputStream multiOut=new ByteArrayOutputStream();StudyArchive.backup(multi,multiOut,null);
  Set<String> multiNames=new HashSet<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(multiOut.toByteArray()))){ZipEntry e;while((e=z.getNextEntry())!=null)multiNames.add(e.getName());}
  ok(multiNames.contains(generic+"engine/saves/slot01.bin")&&multiNames.contains(generic+"data/quick.sav")&&multiNames.contains(generic+"options.ini")&&!multiNames.contains(generic+"game.exe"),"generic nested game save scope");
  ok(multiNames.contains(".wine/drive_c/StudySteady/buding/hanhuabuding/savedata/00001"),"legacy nested translation saves");
  put(multi,generic+"engine/saves/slot01.bin","changed");StudyArchive.restore(new ByteArrayInputStream(multiOut.toByteArray()),multi,null);
  ok(get(multi,generic+"engine/saves/slot01.bin").equals("nested-slot")&&get(multi,generic+"game.exe").equals("game-binary"),"generic save restore preserves game executable");
  Map<String,byte[]> invalidGame=new LinkedHashMap<>();invalidGame.put(".wine/drive_c/Games/arbitrary/save/slot.dat",new byte[]{1});
  rejects(()->StudyArchive.restore(new ByteArrayInputStream(zip(invalidGame,true,manifest(invalidGame,true))),multi,null),"restore outside imported UUID directory");
  System.out.println("TOTAL "+passed+" checks passed");
 }
}
