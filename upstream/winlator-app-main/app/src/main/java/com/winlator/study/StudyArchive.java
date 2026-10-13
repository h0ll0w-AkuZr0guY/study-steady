package com.winlator.study;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming game import and save backup. Caller owns the supplied streams. */
public final class StudyArchive {
    public interface Progress {
        void update(String text, long done, long total);
    }

    private static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    private static final long MAX_CONFIG_BYTES = 16L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 20000;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final String GAME = ".wine/drive_c/StudySteady";
    private static final String GAME_STAGE = ".wine/drive_c/.StudySteady-import";
    private static final String GAME_MANIFEST = "study-manifest.json";
    private static final String SAVE_MANIFEST = "save-manifest.json";
    private static final String TRANSLATION_DIRECTORY = "补丁/汉化补丁/";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private StudyArchive() {}

    public static boolean hasStagedImport(File containerRoot) {
        try {
            File root = checkedRoot(containerRoot);
            File stage = resolve(root, GAME_STAGE, false);
            return Files.isDirectory(stage.toPath(), NOFOLLOW)
                    && Files.isRegularFile(new File(stage, GAME_MANIFEST).toPath(), NOFOLLOW);
        } catch (IOException e) {
            return false;
        }
    }

    /** Imports a ZIP without buffering the ZIP or any large asset in memory. */
    public static void importGame(InputStream in, File containerRoot, Progress progress)
            throws IOException {
        if (in == null) throw new IOException("没有选择游戏包。");
        File root = checkedRoot(containerRoot);
        File target = resolve(root, GAME, true);
        requireAbsent(target, "游戏已存在，请使用新的容器。");
        File stage = createStage(root, GAME_STAGE);
        boolean committed = false;
        try {
            Extraction extracted = extract(in, stage, false, progress);
            Manifest manifest = readManifest(extracted.manifest, false);
            validateStage(stage, manifest, false, extracted.files, progress);
            commitGame(stage, target);
            committed = true;
            notifyProgress(progress, "游戏导入完成", manifest.total, manifest.total);
        } finally {
            if (!committed) cleanupCreatedStage(root, stage);
        }
    }

    /** Finishes the low-storage USB flow after the host streamed files into staging. */
    public static void finishStagedImport(File containerRoot, Progress progress)
            throws IOException {
        File root = checkedRoot(containerRoot);
        File target = resolve(root, GAME, false);
        requireAbsent(target, "游戏已存在，请使用新的容器。");
        File stage = resolve(root, GAME_STAGE, false);
        if (!Files.isDirectory(stage.toPath(), NOFOLLOW))
            throw new IOException("未找到 USB 导入暂存目录。");
        File manifestFile = resolve(stage, GAME_MANIFEST, false);
        byte[] bytes;
        try (InputStream in = new FileInputStream(manifestFile)) {
            bytes = readBounded(in, MAX_MANIFEST_BYTES);
        }
        Manifest manifest = readManifest(bytes, false);
        validateStage(stage, manifest, false, null, progress);
        commitGame(stage, target);
        notifyProgress(progress, "USB 游戏导入完成", manifest.total, manifest.total);
    }

    public static void backup(File containerRoot, OutputStream out, Progress progress)
            throws IOException {
        if (out == null) throw new IOException("未提供备份输出。");
        File root = checkedRoot(containerRoot);
        List<String> paths = new ArrayList<>();
        collectBackup(root, ".wine/drive_c/users", paths);
        File game = resolve(root, GAME, false);
        if (Files.isDirectory(game.toPath(), NOFOLLOW)) {
            File[] children = game.listFiles();
            if (children == null) throw new IOException("无法读取游戏存档目录。");
            for (File child : children) {
                String relative = GAME + "/" + child.getName();
                if (isSaveDirectory(child.getName())) collectBackup(root, relative, paths);
                else if (isConfigFile(child.getName())
                        && Files.isRegularFile(child.toPath(), NOFOLLOW)) {
                    if (child.length() > MAX_CONFIG_BYTES)
                        throw new IOException("配置文件过大：" + child.getName());
                    paths.add(relative);
                }
            }
        }
        File games = resolve(root, ".wine/drive_c/Games", false);
        if (Files.isDirectory(games.toPath(), NOFOLLOW)) {
            File[] gameDirs=games.listFiles();
            if(gameDirs!=null)for(File dir:gameDirs)
                if(dir.getName().matches("g[0-9a-f]{32}") && Files.isDirectory(dir.toPath(),NOFOLLOW))
                    collectGameSaves(root,".wine/drive_c/Games/"+dir.getName(),paths,0);
        }
        if(Files.isDirectory(game.toPath(),NOFOLLOW))collectNestedGameSaves(root,GAME,paths,0);
        for (String reg : new String[]{"user.reg", "system.reg", "userdef.reg"}) {
            File file = resolve(root, ".wine/" + reg, false);
            if (Files.isRegularFile(file.toPath(), NOFOLLOW)) paths.add(".wine/" + reg);
        }
        Collections.sort(paths);
        paths=new ArrayList<>(new java.util.LinkedHashSet<>(paths));
        if (paths.size() > MAX_FILES) throw new IOException("存档文件数量超过限制。");
        long total = 0;
        Set<String> names = new HashSet<>();
        for (String path : paths) {
            validateSavePath(path);
            if (!names.add(fold(path))) throw new IOException("存档包含重名路径：" + path);
            total = addBounded(total, resolve(root, path, false).length());
        }
        List<Record> records = new ArrayList<>();
        long done = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipOutputStream zip = new ZipOutputStream(new NonClosingOutput(out))) {
            for (String path : paths) {
                File file = resolve(root, path, false);
                if (!Files.isRegularFile(file.toPath(), NOFOLLOW))
                    throw new IOException("备份期间文件发生变化：" + path);
                long expected = file.length();
                MessageDigest digest = sha256();
                ZipEntry entry = new ZipEntry(path);
                zip.putNextEntry(entry);
                long count = 0;
                try (InputStream input = new FileInputStream(file)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        count = addBounded(count, n);
                        if (count > expected) throw new IOException("备份期间文件发生变化：" + path);
                        zip.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        done = addBounded(done, n);
                        notifyProgress(progress, "备份 " + path, done, total);
                    }
                }
                if (count != expected || file.length() != expected)
                    throw new IOException("备份期间文件发生变化：" + path);
                zip.closeEntry();
                records.add(new Record(path, count, hex(digest.digest())));
            }
            if (done != total) throw new IOException("备份期间存档大小发生变化。");
            byte[] manifest = encodeManifest(records, total);
            zip.putNextEntry(new ZipEntry(SAVE_MANIFEST));
            zip.write(manifest);
            zip.closeEntry();
        }
        notifyProgress(progress, "存档备份完成", total, total);
    }

    /** Validates every byte first, then replaces files with rollback on failure. */
    public static void restore(InputStream in, File containerRoot, Progress progress)
            throws IOException {
        if (in == null) throw new IOException("未选择存档备份。");
        File root = checkedRoot(containerRoot);
        File stage = createStage(root, ".StudySteady-restore");
        File payload = new File(stage, "payload");
        if (!payload.mkdir()) {
            cleanupCreatedStage(root, stage);
            throw new IOException("无法创建存档暂存目录。");
        }
        boolean committed = false;
        boolean preserveStage = false;
        try {
            Extraction extracted = extract(in, payload, true, progress);
            Manifest manifest = readManifest(extracted.manifest, true);
            validateStage(payload, manifest, true, extracted.files, progress);
            File originals = new File(stage, "originals");
            if (!originals.mkdir()) throw new IOException("无法创建存档回滚目录。");
            List<Move> changes = new ArrayList<>();
            try {
                for (Record record : manifest.files.values()) {
                    File source = resolve(payload, record.path, false);
                    File destination = resolve(root, record.path, true);
                    File old = resolve(originals, record.path, true);
                    if (Files.exists(destination.toPath(), NOFOLLOW)
                            && !Files.isRegularFile(destination.toPath(), NOFOLLOW))
                        throw new IOException("目标存档路径不是普通文件：" + record.path);
                    Move change = new Move(destination, old);
                    changes.add(change);
                    if (Files.exists(destination.toPath(), NOFOLLOW)) {
                        if (!destination.renameTo(old))
                            throw new IOException("无法保留原存档：" + record.path);
                        change.oldMoved = true;
                    }
                    if (!source.renameTo(destination))
                        throw new IOException("无法写入恢复存档：" + record.path);
                    change.newMoved = true;
                }
                File retained = resolve(root, ".StudySteady-before-restore-" + UUID.randomUUID(), false);
                if (!originals.renameTo(retained))
                    throw new IOException("无法保留恢复前的原存档。");
                committed = true;
            } catch (IOException error) {
                IOException rollback = rollback(changes, payload, root);
                if (rollback != null) {
                    preserveStage = true;
                    error.addSuppressed(rollback);
                    throw new IOException("恢复失败，部分回滚未完成；原存档已保留在 "
                            + stage.getAbsolutePath(), error);
                }
                throw error;
            }
            notifyProgress(progress, "存档恢复完成，恢复前文件已保留", manifest.total, manifest.total);
        } finally {
            if (!preserveStage) cleanupCreatedStage(root, stage);
            if (!committed && preserveStage)
                notifyProgress(progress, "保留暂存目录供恢复原存档", 0, 0);
        }
    }

    private static IOException rollback(List<Move> changes, File payload, File root) {
        IOException failure = null;
        for (int i = changes.size() - 1; i >= 0; i--) {
            Move change = changes.get(i);
            try {
                String relative = root.toPath().relativize(change.destination.toPath())
                        .toString().replace(File.separatorChar, '/');
                File stagedNew = resolve(payload, relative, true);
                if (change.newMoved && !change.destination.renameTo(stagedNew))
                    throw new IOException("无法回滚新存档：" + relative);
                if (change.oldMoved && !change.original.renameTo(change.destination))
                    throw new IOException("无法回滚原存档：" + relative);
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private static void commitGame(File stage, File target) throws IOException {
        requireAbsent(target, "游戏目录已存在，导入已停止。");
        if (!stage.renameTo(target)) throw new IOException("无法完成游戏目录的原子导入。");
    }

    private static Extraction extract(InputStream input, File stage, boolean saves,
            Progress progress) throws IOException {
        Extraction result = new Extraction();
        String manifestName = saves ? SAVE_MANIFEST : GAME_MANIFEST;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[BUFFER_SIZE];
        long done = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new NonClosingInput(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                validatePath(path);
                if (++entries > MAX_FILES + 1) throw new IOException("ZIP 文件数量超过限制。");
                if (!names.add(fold(path))) throw new IOException("ZIP 含重复路径：" + path);
                if (entry.isDirectory()) throw new IOException("ZIP 应只包含文件：" + path);
                if (path.equals(manifestName)) {
                    result.manifest = readBounded(zip, MAX_MANIFEST_BYTES);
                    try (OutputStream out = new FileOutputStream(resolve(stage, path, true))) {
                        out.write(result.manifest);
                    }
                } else {
                    if (saves) validateSavePath(path);
                    else validateGamePath(path);
                    if (entry.getSize() > MAX_BYTES) throw new IOException("ZIP 文件过大：" + path);
                    MessageDigest digest = sha256();
                    long size = 0;
                    try (OutputStream out = new FileOutputStream(resolve(stage, path, true))) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            size = addBounded(size, n);
                            done = addBounded(done, n);
                            if (saves && isRootConfig(path) && size > MAX_CONFIG_BYTES)
                                throw new IOException("配置文件超过大小限制：" + path);
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            notifyProgress(progress, "导入 " + path, done, 0);
                        }
                    }
                    result.files.put(path, new Record(path, size, hex(digest.digest())));
                }
                zip.closeEntry();
            }
        }
        if (result.manifest == null) throw new IOException("ZIP 缺少 " + manifestName);
        return result;
    }

    private static void validateStage(File stage, Manifest manifest, boolean saves,
            Map<String, Record> observed, Progress progress) throws IOException {
        String manifestName = saves ? SAVE_MANIFEST : GAME_MANIFEST;
        Set<String> actual = new HashSet<>();
        collectStage(stage, stage, actual);
        Set<String> expected = new HashSet<>(manifest.files.keySet());
        expected.add(manifestName);
        if (!actual.equals(expected)) throw new IOException("文件列表与清单不一致，存在缺失或额外文件。");
        if (observed != null && !observed.keySet().equals(manifest.files.keySet()))
            throw new IOException("ZIP 文件列表与清单不一致。");
        long done = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        for (Record expectedFile : manifest.files.values()) {
            File file = resolve(stage, expectedFile.path, false);
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW) || file.length() != expectedFile.size)
                throw new IOException("文件大小不符：" + expectedFile.path);
            Record measured = observed == null ? null : observed.get(expectedFile.path);
            if (measured == null) {
                MessageDigest digest = sha256();
                long size = 0;
                try (InputStream in = new FileInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        size = addBounded(size, n);
                        if (size > expectedFile.size)
                            throw new IOException("文件在校验期间发生变化：" + expectedFile.path);
                        digest.update(buffer, 0, n);
                        notifyProgress(progress, "校验 " + expectedFile.path,
                                done + size, manifest.total);
                    }
                }
                measured = new Record(expectedFile.path, size, hex(digest.digest()));
            }
            if (measured.size != expectedFile.size || !measured.sha256.equals(expectedFile.sha256))
                throw new IOException("文件校验失败：" + expectedFile.path);
            done = addBounded(done, measured.size);
            notifyProgress(progress, "校验 " + expectedFile.path, done, manifest.total);
        }
        if (done != manifest.total) throw new IOException("文件总大小不符。");
    }

    private static Manifest readManifest(byte[] bytes, boolean saves) throws IOException {
        try {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (integer(json, "schema") != 1 || !"study-steady".equals(json.getString("gameId")))
                throw new IOException("不支持的清单版本或游戏。");
            String launcher = null;
            if (saves) {
                if (!"save-backup".equals(json.getString("kind")))
                    throw new IOException("这不是存档备份包。");
            } else {
                launcher = json.getString("launcher");
                validateGamePath(launcher);
                if (!launcher.endsWith(".exe"))
                    throw new IOException("游戏启动程序必须是已允许的 EXE 文件。");
            }
            long expectedTotal = integer(json, "totalBytes");
            if (expectedTotal > MAX_BYTES) throw new IOException("清单总大小超过限制。");
            JSONArray array = json.getJSONArray("files");
            if (array.length() > MAX_FILES) throw new IOException("清单文件数量超过限制。");
            Manifest manifest = new Manifest();
            Set<String> names = new HashSet<>();
            long total = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                String path = entry.getString("path");
                if (saves) validateSavePath(path);
                else validateGamePath(path);
                if (!names.add(fold(path))) throw new IOException("清单含重复路径：" + path);
                long size = integer(entry, "size");
                if (saves && isRootConfig(path) && size > MAX_CONFIG_BYTES)
                    throw new IOException("配置文件超过大小限制：" + path);
                String hash = entry.getString("sha256");
                if (!hash.matches("[0-9a-f]{64}")) throw new IOException("无效的 SHA-256：" + path);
                total = addBounded(total, size);
                manifest.files.put(path, new Record(path, size, hash));
            }
            if (total != expectedTotal) throw new IOException("清单文件总大小不一致。");
            if (!saves) {
                for (String required : new String[]{launcher, "script.noa", "psb.noa"})
                    if (!manifest.files.containsKey(required))
                        throw new IOException("游戏包缺少必需文件：" + required);
            }
            manifest.total = total;
            return manifest;
        } catch (JSONException error) {
            throw new IOException("清单 JSON 格式无效。", error);
        }
    }

    private static long integer(JSONObject json, String field) throws JSONException, IOException {
        Object value = json.get(field);
        if (!(value instanceof Number) || !value.toString().matches("[0-9]+"))
            throw new IOException("清单整数无效：" + field);
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException error) {
            throw new IOException("清单整数溢出：" + field, error);
        }
    }

    private static byte[] encodeManifest(List<Record> records, long total) throws IOException {
        try {
            JSONObject json = new JSONObject();
            json.put("schema", 1);
            json.put("gameId", "study-steady");
            json.put("kind", "save-backup");
            json.put("totalBytes", total);
            JSONArray entries = new JSONArray();
            for (Record record : records) {
                JSONObject entry = new JSONObject();
                entry.put("path", record.path);
                entry.put("size", record.size);
                entry.put("sha256", record.sha256);
                entries.put(entry);
            }
            json.put("files", entries);
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("存档清单超过 1 MiB 限制。");
            return bytes;
        } catch (JSONException error) {
            throw new IOException("无法生成存档清单。", error);
        }
    }

    private static void validateGamePath(String path) throws IOException {
        validatePath(path);
        if (path.indexOf('/') == -1) {
            if (path.endsWith(".noa") || path.equals("ststeady.exe")
                    || path.equals("ststeady_patch.exe") || path.equals("emotedriver.dll")
                    || path.equals("hook.dll") || path.equals("AymKze.txt")) return;
        } else if (path.equals(TRANSLATION_DIRECTORY + "ststeady_patch.exe")
                || path.equals(TRANSLATION_DIRECTORY + "hook.dll")
                || path.equals(TRANSLATION_DIRECTORY + "patchcn.noa")) return;
        else if (path.startsWith("savedata/") && !isExecutable(path)) return;
        throw new IOException("游戏包包含不允许的路径：" + path);
    }

    private static void validateSavePath(String path) throws IOException {
        validatePath(path);
        if (path.equals(".wine/user.reg") || path.equals(".wine/system.reg")
                || path.equals(".wine/userdef.reg")) return;
        String[] parts = path.split("/");
        if (path.startsWith(".wine/drive_c/users/") && parts.length >= 5) return;
        if (path.startsWith(GAME + "/")) {
            String rest = path.substring(GAME.length() + 1);
            int slash = rest.indexOf('/');
            if (slash > 0 && isSaveDirectory(rest.substring(0, slash)) && !isExecutable(rest)) return;
            if (slash == -1 && isConfigFile(rest)) return;
            if(isNestedSavePath(rest))return;
        }
        if(path.startsWith(".wine/drive_c/Games/")) {
            String rest=path.substring(".wine/drive_c/Games/".length());
            int slash=rest.indexOf('/');
            if(slash>0 && rest.substring(0,slash).matches("g[0-9a-f]{32}")) {
                String local=rest.substring(slash+1);
                if(!isExecutable(local) && (isNestedSavePath(local)||isConfigFile(local)||isSaveFile(local)))return;
            }
        }
        throw new IOException("备份包含不允许的存档路径：" + path);
    }

    private static boolean isRootConfig(String path) {
        if (!path.startsWith(GAME + "/")) return false;
        String rest = path.substring(GAME.length() + 1);
        return rest.indexOf('/') == -1 && isConfigFile(rest);
    }

    private static boolean isSaveDirectory(String name) {
        return name.equalsIgnoreCase("savedata") || name.equalsIgnoreCase("save") || name.equalsIgnoreCase("saves");
    }

    private static boolean isNestedSavePath(String rest) {
        if(isExecutable(rest))return false;
        String[] parts=rest.split("/");
        for(int i=0;i<parts.length-1;i++)if(isSaveDirectory(parts[i]))return true;
        return false;
    }
    private static boolean isSaveFile(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(sav|save|ksd|asd|rms)$");
    }
    private static void collectNestedGameSaves(File root,String relative,List<String> paths,int depth) throws IOException {
        if(depth>32)return;
        File dir=resolve(root,relative,false);
        if(!Files.isDirectory(dir.toPath(),NOFOLLOW))return;
        File[] children=dir.listFiles();if(children==null)return;
        for(File c:children)if(Files.isDirectory(c.toPath(),NOFOLLOW)) {
            String p=relative+"/"+c.getName();
            if(isSaveDirectory(c.getName()))collectBackup(root,p,paths);else collectNestedGameSaves(root,p,paths,depth+1);
        }
    }
    private static void collectGameSaves(File root,String relative,List<String> paths,int depth) throws IOException {
        if(depth>32)return;
        File dir=resolve(root,relative,false);if(!Files.isDirectory(dir.toPath(),NOFOLLOW))return;
        File[] children=dir.listFiles();if(children==null)return;
        for(File c:children) {
            String p=relative+"/"+c.getName();
            if(Files.isDirectory(c.toPath(),NOFOLLOW)) {
                if(isSaveDirectory(c.getName()))collectBackup(root,p,paths);else collectGameSaves(root,p,paths,depth+1);
            }else if(Files.isRegularFile(c.toPath(),NOFOLLOW)&&(isConfigFile(c.getName())||isSaveFile(c.getName()))) {
                if(c.length()<=MAX_CONFIG_BYTES)paths.add(p);
            }
        }
    }

    private static boolean isConfigFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dat") || lower.endsWith(".ini");
    }

    private static boolean isExecutable(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(exe|dll|bat|cmd|ps1|sh|apk|so|com|vbs|js|jar|msi|scr)$");
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || path.length() == 0 || path.length() > 1024
                || path.startsWith("/") || path.indexOf('\\') >= 0)
            throw new IOException("无效的文件路径。");
        String[] parts = path.split("/", -1);
        if (parts.length > 64) throw new IOException("文件路径层级过深。");
        for (String part : parts) {
            if (part.length() == 0 || part.length() > 255 || part.equals(".") || part.equals("..")
                    || part.endsWith(".") || part.endsWith(" "))
                throw new IOException("不安全的文件路径：" + path);
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (Character.isISOControl(ch) || ":<>\"|?*".indexOf(ch) >= 0)
                    throw new IOException("不安全的文件路径：" + path);
            }
            String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
                throw new IOException("路径使用了 Windows 保留名称：" + path);
        }
    }

    private static String fold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static File checkedRoot(File root) throws IOException {
        if (root == null) throw new IOException("未提供游戏容器。");
        File canonical = root.getCanonicalFile();
        if (!Files.isDirectory(canonical.toPath(), NOFOLLOW))
            throw new IOException("游戏容器目录不存在。");
        return canonical;
    }

    /** Checks every ancestor, including absent paths, before optional parent creation. */
    private static File resolve(File root, String path, boolean createParents) throws IOException {
        validatePath(path);
        File canonicalRoot = root.getCanonicalFile();
        if (!Files.isDirectory(canonicalRoot.toPath(), NOFOLLOW))
            throw new IOException("根目录不存在。");
        File current = canonicalRoot;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            current = new File(current, parts[i]);
            if (Files.isSymbolicLink(current.toPath())) throw new IOException("拒绝符号链接路径：" + path);
            if (!current.getCanonicalPath().startsWith(canonicalRoot.getPath() + File.separator))
                throw new IOException("文件路径超出容器：" + path);
            boolean exists = Files.exists(current.toPath(), NOFOLLOW);
            if (i < parts.length - 1) {
                if (exists && !Files.isDirectory(current.toPath(), NOFOLLOW))
                    throw new IOException("路径父级不是目录：" + path);
                if (!exists && createParents && !current.mkdir())
                    throw new IOException("无法创建目录：" + path);
            }
        }
        return current;
    }

    private static File createStage(File root, String relative) throws IOException {
        File stage = resolve(root, relative, true);
        requireAbsent(stage, "暂存目录已存在，请先完成或移走上次导入。");
        if (!stage.mkdir()) throw new IOException("无法创建暂存目录。");
        return stage;
    }

    private static void requireAbsent(File file, String message) throws IOException {
        if (Files.exists(file.toPath(), NOFOLLOW)) throw new IOException(message);
    }

    private static void collectBackup(File root, String relative, List<String> result)
            throws IOException {
        validatePath(relative);
        File file = new File(root, relative);
        // Wine user folders commonly point to host folders. Never traverse those links.
        if (Files.isSymbolicLink(file.toPath())) return;
        file = resolve(root, relative, false);
        if (!Files.exists(file.toPath(), NOFOLLOW)) return;
        if (Files.isRegularFile(file.toPath(), NOFOLLOW)) {
            validateSavePath(relative);
            result.add(relative);
            if (result.size() > MAX_FILES) throw new IOException("存档文件数量超过限制。");
        } else if (Files.isDirectory(file.toPath(), NOFOLLOW)) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取存档目录：" + relative);
            for (File child : children) collectBackup(root, relative + "/" + child.getName(), result);
        } else throw new IOException("存档路径包含非普通文件：" + relative);
    }

    private static void collectStage(File root, File current, Set<String> result)
            throws IOException {
        if (Files.isSymbolicLink(current.toPath())) throw new IOException("暂存目录包含符号链接。");
        if (!current.equals(root)) {
            String path = root.toPath().relativize(current.toPath()).toString()
                    .replace(File.separatorChar, '/');
            validatePath(path);
            resolve(root, path, false);
        }
        if (Files.isDirectory(current.toPath(), NOFOLLOW)) {
            File[] children = current.listFiles();
            if (children == null) throw new IOException("无法读取暂存目录。");
            for (File child : children) collectStage(root, child, result);
        } else if (Files.isRegularFile(current.toPath(), NOFOLLOW)) {
            String path = root.toPath().relativize(current.toPath()).toString()
                    .replace(File.separatorChar, '/');
            if (!result.add(path) || result.size() > MAX_FILES + 1)
                throw new IOException("暂存文件过多或重复。");
        } else throw new IOException("暂存目录包含非普通文件。");
    }

    /** Only called for the exact directory exclusively created by this invocation. */
    private static void cleanupCreatedStage(File root, File stage) throws IOException {
        File checked = stage.getCanonicalFile();
        boolean expected = checked.equals(new File(root, GAME_STAGE).getCanonicalFile())
                || checked.equals(new File(root, ".StudySteady-restore").getCanonicalFile());
        if (!expected || Files.isSymbolicLink(stage.toPath())
                || !checked.toPath().startsWith(root.getCanonicalFile().toPath()))
            throw new IOException("拒绝清理未经验证的暂存目录。");
        deleteStagedTree(checked, checked);
    }

    private static void deleteStagedTree(File root, File current) throws IOException {
        if (!Files.exists(current.toPath(), NOFOLLOW)) return;
        if (Files.isSymbolicLink(current.toPath())) {
            Files.delete(current.toPath());
            return;
        }
        if (!current.getCanonicalFile().toPath().startsWith(root.toPath()))
            throw new IOException("拒绝删除暂存目录以外的路径。");
        if (Files.isDirectory(current.toPath(), NOFOLLOW)) {
            File[] children = current.listFiles();
            if (children == null) throw new IOException("无法清理暂存目录。");
            for (File child : children) deleteStagedTree(root, child);
        }
        Files.delete(current.toPath());
    }

    private static byte[] readBounded(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() > maximum - n) throw new IOException("清单超过 1 MiB 限制。");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static long addBounded(long value, long increment) throws IOException {
        if (increment < 0 || value > MAX_BYTES - increment)
            throw new IOException("文件总大小超过 64 GiB 限制。");
        return value + increment;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("设备不支持 SHA-256。", error);
        }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = digits[(bytes[i] & 255) >>> 4];
            chars[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }

    private static void notifyProgress(Progress progress, String text, long done, long total) {
        if (progress != null) progress.update(text, done, total);
    }

    private static final class Record {
        final String path;
        final long size;
        final String sha256;
        Record(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class Manifest {
        long total;
        final Map<String, Record> files = new LinkedHashMap<>();
    }

    private static final class Extraction {
        byte[] manifest;
        final Map<String, Record> files = new LinkedHashMap<>();
    }

    private static final class Move {
        final File destination;
        final File original;
        boolean oldMoved;
        boolean newMoved;
        Move(File destination, File original) {
            this.destination = destination;
            this.original = original;
        }
    }

    private static final class NonClosingInput extends FilterInputStream {
        NonClosingInput(InputStream in) { super(in); }
        @Override public void close() {}
    }

    private static final class NonClosingOutput extends FilterOutputStream {
        NonClosingOutput(OutputStream out) { super(out); }
        @Override public void close() throws IOException { flush(); }
    }
}
