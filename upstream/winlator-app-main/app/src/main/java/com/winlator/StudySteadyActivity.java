package com.winlator;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.winlator.box64.Box64Preset;
import com.winlator.container.*;
import com.winlator.core.*;
import com.winlator.study.*;
import com.winlator.xenvironment.*;
import org.json.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

/** Offline PC visual-novel library. Game files and saves survive application restarts. */
public class StudySteadyActivity extends AppCompatActivity {
    private static final int IMPORT=101, EXPORT=102, RESTORE=103, ICON=104;
    private static final String GAME_ID="study.gameId";
    private TextView status;
    private ProgressBar progress;
    private Container container;
    private GameLibrary library;
    private SharedPreferences prefs;
    private boolean busy;
    private long lastProgress;
    private String iconGame;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);prefs=PreferenceManager.getDefaultSharedPreferences(this);
        if(state!=null)iconGame=state.getString("iconGame");
        render("正在准备运行环境…");execute(this::initialize,"游戏库已准备好。",this::consumeLaunch);
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String text,Runnable run){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setEnabled(!busy);b.setOnClickListener(v->run.run());return b;}
    private void render(String message) {
        if(isDestroyed())return;
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(1);
        page.setPadding(dp(24),dp(24),dp(24),dp(24));page.setBackgroundColor(0xff171c28);scroll.addView(page);setContentView(scroll);
        page.addView(label("Study Steady",30,Color.WHITE));TextView subtitle=label("把喜欢的故事随身带走",15,0xffacbfd9);subtitle.setPadding(0,dp(6),0,dp(16));page.addView(subtitle);
        LinearLayout toolbar=new LinearLayout(this);
        toolbar.addView(button("导入游戏 ZIP",()->pick(IMPORT,false)),new LinearLayout.LayoutParams(0,dp(54),1));
        toolbar.addView(button("运行环境",this::environmentMenu),new LinearLayout.LayoutParams(0,dp(54),1));page.addView(toolbar);
        status=label(message,14,0xffacbfd9);status.setPadding(0,dp(12),0,dp(12));page.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setVisibility(busy?View.VISIBLE:View.GONE);page.addView(progress);
        if(library==null)return;
        int columns=Math.max(1,Math.min(4,(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)/250));
        LinearLayout row=null;int i=0;
        for(JSONObject g:library.entries) {
            if(i%columns==0){row=new LinearLayout(this);page.addView(row);}i++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(14),dp(14),dp(14),dp(12));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff262e41);bg.setCornerRadius(dp(16));card.setBackground(bg);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(240),1);cp.setMargins(dp(5),dp(9),dp(5),dp(9));row.addView(card,cp);
            ImageView image=new ImageView(this);image.setImageBitmap(gameIcon(g));image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);card.addView(image,new LinearLayout.LayoutParams(-1,0,1));
            TextView title=label(g.optString("name"),18,Color.WHITE);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title);
            TextView exe=label(g.optString("exe","选择 EXE"),12,0xffacbfd9);exe.setMaxLines(1);exe.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);card.addView(exe);
            card.addView(button("管理",()->gameMenu(g)),new LinearLayout.LayoutParams(-1,dp(44)));
            card.setOnClickListener(v->{if(!busy)launch(g);});card.setOnLongClickListener(v->{if(!busy)gameMenu(g);return true;});image.setOnClickListener(v->{if(!busy)launch(g);});
        }
        if(row!=null)for(int n=i%columns;n>0&&n<columns;n++)row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(240),1));
        if(library.entries.isEmpty())page.addView(label("先导入完整游戏 ZIP，再选择启动 EXE。",18,Color.WHITE));
        TextView help=label("点图标启动 · 长按或点管理修改游戏\n游戏内 SAVE / LOAD 会保留到下次启动。卸载或清除数据前请先备份。",13,0xffacbfd9);help.setPadding(dp(6),dp(22),0,dp(18));page.addView(help);
    }
    private Bitmap gameIcon(JSONObject g) {
        Bitmap b=BitmapFactory.decodeFile(new File(library.metadata,g.optString("id")+".png").getPath());if(b!=null)return b;
        b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.HSVToColor(new float[]{Math.floorMod(g.optString("id").hashCode(),360),.38f,.58f}));c.drawRoundRect(0,0,256,256,44,44,p);
        p.setColor(Color.WHITE);p.setTextSize(108);p.setTextAlign(Paint.Align.CENTER);String n=g.optString("name","S");c.drawText(n.isEmpty()?"S":n.substring(0,n.offsetByCodePoints(0,1)),128,166,p);return b;
    }
    private void execute(Work work,String complete,Runnable after) {
        if(busy)return;busy=true;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);render("正在处理…");
        worker.execute(()->{String outcome=complete;boolean success=true;
            try{work.run();}catch(Exception e){android.util.Log.e("StudySteady","Operation failed",e);outcome="未完成："+e.getMessage();success=false;}
            String result=outcome;boolean ok=success;runOnUiThread(()->{if(isDestroyed())return;busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);render(result);if(ok&&after!=null)after.run();});});
    }
    private void report(String text,long done,long total) {
        long now=System.currentTimeMillis();if(now-lastProgress<250)return;lastProgress=now;
        runOnUiThread(()->{if(isDestroyed())return;status.setText(text);progress.setIndeterminate(total<=0);if(total>0)progress.setProgress((int)(done*1000/total));});
    }
    private void initialize() throws Exception {
        RootFS root=RootFS.find(this);
        if(!root.isValid()) {
            report("正在安装离线运行环境",0,0);if(!root.getRootDir().isDirectory()&&!root.getRootDir().mkdirs())throw new IOException("无法建立运行目录");
            if(!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,this,RootFSInstaller.FILENAME,root.getRootDir()))throw new IOException("运行环境解压失败，请检查空间");
            root.createRFSVersionFile(RootFSInstaller.LATEST_VERSION);SettingsFragment.resetPreferenceVersions(this);
        }
        ContainerManager manager=new ContainerManager(this);container=manager.getContainerById(prefs.getInt("study_container_id",0));
        if(container==null) {
            String driver=GraphicsDrivers.getDefaultDriver(this);if(driver.startsWith("turnip"))driver="turnip,zink";
            JSONObject data=new JSONObject().put("name","Study Steady").put("screenSize","1280x720").put("graphicsDriver",driver).put("dxwrapper","dxvk")
                .put("box64Preset",Box64Preset.INTERMEDIATE).put("wincomponents",Container.DEFAULT_WINCOMPONENTS).put("envVars",Container.DEFAULT_ENV_VARS+" LANG=ja_JP.UTF-8 LC_ALL=ja_JP.UTF-8")
                .put("extraData",new JSONObject().put("appVersion",AppUtils.getVersionCode(this)));
            CountDownLatch done=new CountDownLatch(1);runOnUiThread(()->manager.createContainerAsync(data,c->{container=c;done.countDown();}));done.await();
            if(container==null)throw new IOException("创建运行环境失败");prefs.edit().putInt("study_container_id",container.id).commit();
        }
        if(!prefs.getBoolean("study_library_migrated",false))prefs.edit().putBoolean("enable_wine_debug",false).putInt("box64_logs",0).putBoolean("move_cursor_to_touchpoint",true).apply();
        installJapaneseLocale(root);installFont();library=new GameLibrary(getFilesDir(),container.getRootDir());
        File helper=new File(container.getRootDir(),".wine/drive_c/windows/study-launch.exe");
        try(InputStream in=getAssets().open("study/study-launch.exe");OutputStream out=new FileOutputStream(helper)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        if(!prefs.getBoolean("study_library_migrated",false))library.registerLegacy(prefs.getString("study_exe","ststeady_patch.exe"),prefs.getBoolean("study_work_launcher",false));
        if(!prefs.getBoolean("study_plain_touch_default",false)) {
            for(JSONObject g:library.entries)g.put("controls",false);
            library.save();prefs.edit().putBoolean("study_plain_touch_default",true).apply();
        }
        prefs.edit().putBoolean("study_library_migrated",true).apply();
    }
    private void installFont() throws IOException {
        File font=new File("/system/fonts/NotoSansCJK-Regular.ttc"),dest=new File(container.getRootDir(),".wine/drive_c/windows/Fonts/NotoSansCJK-Regular.ttc");
        if(font.isFile()&&!dest.isFile()) {
            dest.getParentFile().mkdirs();try(InputStream in=new FileInputStream(font);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            try(WineRegistryEditor reg=new WineRegistryEditor(new File(container.getRootDir(),".wine/user.reg"))){for(String name:new String[]{"MS Gothic","MS PGothic","ＭＳ ゴシック","ＭＳ Ｐゴシック","SimHei","SimSun","Microsoft YaHei"})reg.setStringValue("Software\\Wine\\Fonts\\Replacements",name,"Noto Sans CJK SC");}
        }
    }
    private void installJapaneseLocale(RootFS root) throws Exception {
        File folder=new File(root.getRootDir(),"usr/lib/locale/ja_JP.utf8");
        if(new File(folder,"LC_CTYPE").isFile())return;
        report("正在准备日语 UTF-8 文件名支持",0,0);
        File charmap=new File(root.getRootDir(),"usr/share/i18n/charmaps/UTF-8");
        try(InputStream in=new java.util.zip.GZIPInputStream(new FileInputStream(charmap.getPath()+".gz"));OutputStream out=new FileOutputStream(charmap)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        String base=root.getRootDir().getAbsolutePath();EnvVars env=new EnvVars();env.put("LD_LIBRARY_PATH",base+"/lib");env.put("I18NPATH",base+"/usr/share/i18n");
        CountDownLatch done=new CountDownLatch(1);final int[] result={-1};
        ProcessHelper.exec(base+"/usr/bin/localedef --no-archive -i "+base+"/usr/share/i18n/locales/ja_JP -f "+charmap.getPath()+" "+folder.getPath(),env,root.getRootDir(),code->{result[0]=code;done.countDown();});
        if(!done.await(30,TimeUnit.SECONDS)||result[0]!=0||!new File(folder,"LC_CTYPE").isFile())throw new IOException("日语 locale 安装失败，游戏和存档已保留");
    }
    private void idle() throws IOException {if(container==null)throw new IOException("运行环境尚未准备好");if(XServerDisplayActivity.isStudySessionActive())throw new IOException("请先在游戏内保存，并通过运行菜单退出环境");}
    private void gameMenu(JSONObject g) {
        new AlertDialog.Builder(this).setTitle(g.optString("name")).setItems(new String[]{"开始游戏","选择启动 EXE","重命名","选择图标图片","添加到安卓桌面","游戏运行设置","从游戏库移除（保留文件）"},(d,n)->{
            switch(n){case 0:launch(g);break;case 1:selectExe(g);break;case 2:rename(g);break;case 3:iconGame=g.optString("id");pick(ICON,false);break;case 4:pin(g);break;case 5:gameSettings(g);break;case 6:remove(g);break;}
        }).setNegativeButton("关闭",null).show();
    }
    private void change(JSONObject g,String key,Object value) {
        try{g.put(key,value);library.save();updatePin(g);render("设置已保存，下次启动生效。");}catch(Exception e){render("无法保存："+e.getMessage());}
    }
    private void rename(JSONObject g) {
        EditText edit=new EditText(this);edit.setSingleLine();edit.setText(g.optString("name"));
        new AlertDialog.Builder(this).setTitle("游戏名称").setView(edit).setPositiveButton("保存",(d,w)->{String value=edit.getText().toString().trim();if(!value.isEmpty()&&value.length()<=100)change(g,"name",value);}).setNegativeButton("取消",null).show();
    }
    private void selectExe(JSONObject g) {
        execute(()->{List<String> exes=GameLibrary.executables(library.directory(g));
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("选择启动 EXE").setSingleChoiceItems(exes.toArray(new String[0]),exes.indexOf(g.optString("exe")),(d,i)->{d.dismiss();change(g,"exe",exes.get(i));}).setNegativeButton("取消",null).show());
        },"选择游戏程序。",null);
    }
    private void gameSettings(JSONObject g) {
        new AlertDialog.Builder(this).setTitle("游戏运行设置").setItems(new String[]{"CPU：继承运行环境","CPU：性能优先","CPU：均衡","CPU：稳定优先","工作目录：EXE 所在目录","工作目录：游戏根目录","虚拟按键："+(g.optBoolean("controls",true)?"关闭":"开启"),"全屏缩放："+(g.optBoolean("fullscreen",false)?"关闭":"开启")},(d,n)->{
            if(n<4)change(g,"box64",new String[]{"",Box64Preset.PERFORMANCE,Box64Preset.INTERMEDIATE,Box64Preset.STABILITY}[n]);else if(n<6)change(g,"work",n==4?"launcher":"root");else if(n==6)change(g,"controls",!g.optBoolean("controls",true));else change(g,"fullscreen",!g.optBoolean("fullscreen",false));
        }).setNegativeButton("关闭",null).show();
    }
    private void remove(JSONObject g) {
        new AlertDialog.Builder(this).setTitle("移除游戏图标？").setMessage("保留游戏文件和存档。安卓桌面快捷方式会停用。")
            .setPositiveButton("移除图标",(d,w)->{try{library.entries.remove(g);library.save();getSystemService(ShortcutManager.class).disableShortcuts(Collections.singletonList(g.optString("id")));render("图标已移除，文件仍保留。");}catch(Exception e){render(e.getMessage());}}).setNegativeButton("取消",null).show();
    }
    private Intent gameIntent(JSONObject g){return new Intent(this,StudySteadyActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse("study://game/"+g.optString("id"))).putExtra(GAME_ID,g.optString("id"));}
    private ShortcutInfo pinInfo(JSONObject g){return new ShortcutInfo.Builder(this,g.optString("id")).setShortLabel(g.optString("name")).setIcon(Icon.createWithBitmap(gameIcon(g))).setIntent(gameIntent(g)).build();}
    private void pin(JSONObject g){ShortcutManager sm=getSystemService(ShortcutManager.class);if(sm.isRequestPinShortcutSupported())Toast.makeText(this,sm.requestPinShortcut(pinInfo(g),null)?"请在系统桌面确认添加":"桌面暂不接受快捷方式",Toast.LENGTH_LONG).show();else Toast.makeText(this,"当前桌面不支持固定快捷方式",Toast.LENGTH_LONG).show();}
    private void updatePin(JSONObject g){getSystemService(ShortcutManager.class).updateShortcuts(Collections.singletonList(pinInfo(g)));}
    private void launch(JSONObject g) {
        if(busy)return;
        try {
            idle();File dir=library.directory(g);String relative=GameLibrary.safe(g.optString("exe"));File selected=new File(dir,relative).getCanonicalFile();
            if(!selected.getPath().startsWith(dir.getCanonicalPath()+File.separator)||!selected.isFile()||!relative.toLowerCase(Locale.ROOT).endsWith(".exe"))throw new IOException("启动 EXE 不存在，请重新选择");
            if(selected.getName().contains("\""))throw new IOException("EXE 名称含引号，请修改文件名");
            String drives="";for(Drive drive:container.drivesIterator())if(!drive.letter.equalsIgnoreCase("L")&&!drive.letter.equalsIgnoreCase("M"))drives+=drive.letter+":"+drive.path;
            container.setDrives(drives+"L:"+selected.getParentFile().getAbsolutePath()+"M:"+dir.getAbsolutePath());container.saveData();
            String dosRoot="C:\\"+g.optString("directory").replace('/','\\');
            String path=dosRoot+"\\"+relative.replace('/','\\');
            String work=g.optString("work","launcher").equals("root")?dosRoot:path.substring(0,path.lastIndexOf('\\'));
            File handoff=new File(container.getRootDir(),".wine/drive_c/StudyLauncher/"+g.optString("id")+".launch");handoff.getParentFile().mkdirs();
            try(OutputStream out=new FileOutputStream(handoff)){out.write(("\ufeff"+work+"\0"+path+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_16LE));}
            File desktop=new File(container.getUserDir(),"Desktop");desktop.mkdirs();File entry=new File(desktop,"Study-"+g.optString("id")+".desktop");
            String extras="studyShell=1\nstudyLaunchConfig=C:\\StudyLauncher\\"+g.optString("id")+".launch\nstudyWorkingDir="+work+"\nforceFullscreen="+(g.optBoolean("fullscreen",false)?"1":"0")+"\n";
            if(g.optBoolean("controls",false))extras+="controlsProfile=99\n";if(!g.optString("box64").isEmpty())extras+="box64Preset="+g.optString("box64")+"\n";
            FileUtils.writeString(entry,"[Desktop Entry]\nName=Study Steady\nType=Application\nExec=wine "+StringUtils.escapeDOSPath(StringUtils.escapeDOSPath(path))+"\n[Extra Data]\n"+extras);
            if(!new Shortcut(container,entry).path.equals(path))throw new IOException("启动路径校验失败");
            startActivity(new Intent(this,XServerDisplayActivity.class).putExtra("container_id",container.id).putExtra("shortcut_path",entry.getPath()));
        }catch(Exception e){render("无法启动："+e.getMessage());}
    }
    private void environmentMenu() {
        new AlertDialog.Builder(this).setTitle("运行环境").setItems(new String[]{"性能与图形","打开运行环境桌面","高级设置","导出存档备份","恢复存档备份","ZIP 文件名编码","关于 Study Steady"},(d,n)->{
            if(n==0)performance();else if(n==1){if(container!=null&&!XServerDisplayActivity.isStudySessionActive())startActivity(new Intent(this,XServerDisplayActivity.class).putExtra("container_id",container.id));}
            else if(n==2)startActivity(new Intent(this,MainActivity.class));else if(n==3)pick(EXPORT,true);else if(n==4)pick(RESTORE,false);
            else if(n==5)new AlertDialog.Builder(this).setTitle("ZIP 文件名编码").setItems(new String[]{"UTF-8（默认）","GBK（旧中文 ZIP）"},(a,b)->prefs.edit().putString("study_zip_charset",b==0?"UTF-8":"GBK").apply()).show();
            else new AlertDialog.Builder(this).setTitle("Study Steady 0.2.0").setMessage("离线 PC Galgame 游戏管理器\n基于 Winlator 11.2 / Wine / Box64。兼容性按游戏实测；安装包不含任何游戏。\n\n存档持续保留在应用内部。备份覆盖 Wine 用户目录、注册表及常见游戏存档目录；特殊引擎的自定义保存位置请另外备份。")
                .setPositiveButton("查看项目",(a,b)->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/h0ll0w-AkuZr0guY/study-steady")))).setNegativeButton("关闭",null).show();
        }).setNegativeButton("关闭",null).show();
    }
    private void performance() {
        if(container==null)return;
        new AlertDialog.Builder(this).setTitle("下次启动生效 · "+container.getBox64Preset()).setItems(new String[]{"性能优先（Box64 Performance）","均衡（Box64 Intermediate）","稳定优先（Box64 Stability）","Adreno：Turnip + Zink + DXVK","通用：Vortek + Gladio + DXVK","旧游戏兼容：VirGL + WineD3D","分辨率：1280 × 720","分辨率：960 × 540","显示 FPS："+(container.getHUDMode()==0?"开启":"关闭"),"调试日志："+(prefs.getBoolean("enable_wine_debug",false)?"关闭":"开启")},(d,n)->{
            try{idle();if(n<3)container.setBox64Preset(new String[]{Box64Preset.PERFORMANCE,Box64Preset.INTERMEDIATE,Box64Preset.STABILITY}[n]);
                else if(n<6){container.setGraphicsDriver(new String[]{"turnip,zink","vortek,gladio","vortek,virgl"}[n-3]);container.setGraphicsDriverConfig("");container.setDXWrapper(n==5?"wined3d":"dxvk");}
                else if(n<8)container.setScreenSize(n==6?"1280x720":"960x540");else if(n==8)container.setHUDMode((byte)(container.getHUDMode()==0?1:0));
                else prefs.edit().putBoolean("enable_wine_debug",!prefs.getBoolean("enable_wine_debug",false)).putString("wine_debug_channels","debugstr").apply();container.saveData();render("运行设置已保存。");
            }catch(Exception e){render(e.getMessage());}
        }).setNegativeButton("关闭",null).show();
    }
    private void pick(int code,boolean create) {
        if(busy||container==null)return;try{idle();}catch(IOException e){render(e.getMessage());return;}
        Intent intent=new Intent(create?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(code==ICON?"image/*":create?"application/zip":"*/*");
        if(create)intent.putExtra(Intent.EXTRA_TITLE,"StudySteady-saves-"+System.currentTimeMillis()+".zip");startActivityForResult(intent,code);
    }
    private String documentName(Uri uri) {
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "Game.zip";
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==IMPORT) {
            String name=documentName(uri);if(name.toLowerCase(Locale.ROOT).endsWith(".zip"))name=name.substring(0,name.length()-4);String title=name;
            final JSONObject[] imported={null};execute(()->{idle();try(InputStream in=getContentResolver().openInputStream(uri)){imported[0]=library.importZip(in,title,Charset.forName(prefs.getString("study_zip_charset","UTF-8")),this::report);}},"游戏已导入，请确认启动 EXE。",()->selectExe(imported[0]));
        }else if(request==ICON) {
            JSONObject g=library.find(iconGame);if(g==null)return;
            execute(()->{idle();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
                if(options.outWidth<=0||options.outHeight<=0)throw new IOException("图片格式不受支持");options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>512)options.inSampleSize*=2;options.inJustDecodeBounds=false;
                Bitmap decoded;try(InputStream in=getContentResolver().openInputStream(uri)){decoded=BitmapFactory.decodeStream(in,null,options);}if(decoded==null)throw new IOException("无法读取图片");
                Bitmap icon=Bitmap.createScaledBitmap(decoded,256,256,true);try(OutputStream out=new FileOutputStream(new File(library.metadata,g.optString("id")+".png"))){icon.compress(Bitmap.CompressFormat.PNG,100,out);}if(icon!=decoded)icon.recycle();decoded.recycle();
            },"游戏图标已保存。",()->updatePin(g));
        }else if(request==EXPORT)execute(()->{idle();try(OutputStream out=getContentResolver().openOutputStream(uri)){StudyArchive.backup(container.getRootDir(),out,this::report);}},"存档备份已导出。",null);
        else if(request==RESTORE)execute(()->{idle();File before=new File(getFilesDir(),"save-before-restore-"+System.currentTimeMillis()+".zip");try(OutputStream out=new FileOutputStream(before)){StudyArchive.backup(container.getRootDir(),out,this::report);}try(InputStream in=getContentResolver().openInputStream(uri)){StudyArchive.restore(in,container.getRootDir(),this::report);}},"存档已恢复；恢复前的备份已保留。",null);
    }
    private void consumeLaunch(){String id=getIntent().getStringExtra(GAME_ID);getIntent().removeExtra(GAME_ID);if(id!=null&&library!=null){JSONObject g=library.find(id);if(g!=null)launch(g);else render("此游戏已从游戏库移除。");}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!busy&&library!=null)consumeLaunch();}
    @Override protected void onResume(){super.onResume();if(library!=null&&!busy){Container fresh=new ContainerManager(this).getContainerById(container.id);if(fresh!=null)container=fresh;render("游戏库 · "+library.entries.size()+" 款游戏");}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("iconGame",iconGame);}
    @Override public void onBackPressed(){if(busy)Toast.makeText(this,"正在处理文件，请等待完成",Toast.LENGTH_SHORT).show();else super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
