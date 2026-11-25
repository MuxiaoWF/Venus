package com.muxiao.Venus.common;

import static com.muxiao.Venus.common.tools.sendGetRequest;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.data.ConfigRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 自动打码组件下载器：native 运行库（按 ABI）+ 模型权重，按需下载不进 APK。
 * 清单 JSON 结构：{version, files:[{name, url, sha256, abi|null, extract?, extractAar?}]}。
 * 安装语义：下载 → SHA256 校验 → 整体替换旧目录 → 解包 → 记录版本；失败丢弃 tmp 保留旧版本。
 */
public final class SolverAssetManager {

    private static final String TAG_LOG = "VenusCaptcha";

    /** 下载进度回调（调用线程触发）。 */
    public interface ProgressListener {
        /** stage 见 STAGE_*；percent 0~100，-1 = 未知。 */
        void onProgress(int stage, String detail, int percent);
    }

    public static final int STAGE_FETCH_MANIFEST = 0;
    public static final int STAGE_DOWNLOAD = 1;
    public static final int STAGE_VERIFY = 2;
    public static final int STAGE_INSTALL = 3;
    public static final int STAGE_DONE = 4;

    /** 内置兜底清单：远端不可达时使用，开箱即可完整安装。 */
    private static final String BUILT_IN_MANIFEST =
            "{\"version\":3,\"files\":["
                    + "{\"name\":\"nine_resnet18.onnx\","
                    + "\"url\":\"https://hf-mirror.com/Yiffyi/resnet-geetest-v3-nine-pic/resolve/main/model.onnx\","
                    + "\"sha256\":\"5ee79f693144a2c66bfd08fe4bb0c1e71bda04522c6a2cb68c6c0bedb13aa1c2\",\"abi\":null},"
                    + "{\"name\":\"nine_hints.tar.gz\","
                    + "\"url\":\"https://hf-mirror.com/datasets/Yiffyi/dataset-geetest-v3-nine-pic/resolve/main/only-hints.tar.gz\","
                    + "\"sha256\":\"a2b374a328ce95965f4f4b6611aaf97859686c12ae2aff3f97a7abacb05ef408\","
                    + "\"abi\":null,\"extract\":\"hints\"},"
                    + "{\"name\":\"onnxruntime-android.aar\","
                    + "\"url\":\"https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar\","
                    + "\"sha256\":\"04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2\","
                    + "\"abi\":null,\"extractAar\":true}"
                    + "]}";

    private final Context context;
    private final ConfigRepository repo;

    public SolverAssetManager(Context context) {
        this.context = context.getApplicationContext();
        this.repo = new ConfigRepository(this.context);
    }

    public String installedVersion() {
        return repo.get(Constants.Prefs.SOLVER_ASSETS_VERSION, "0");
    }

    public void uninstall() {
        deleteRecursive(OrtLoader.baseDir(context));
        repo.putString(Constants.Prefs.SOLVER_ASSETS_VERSION, "0");
    }

    // ==================== 并发门禁与取消 ====================

    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean cancelled;

    public static boolean isInstalling() {
        return INSTALLING.get();
    }

    public static void cancel() {
        cancelled = true;
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("cancelled");
        }
    }

    public static boolean isCancelled(@Nullable Throwable t) {
        while (t != null) {
            if (t instanceof CancelledException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static void checkCancelled() {
        if (cancelled) throw new CancelledException();
    }

    /** 拉取清单并安装（后台线程调用）。已就位条目跳过，仅下载补齐差异。 */
    public String install(@Nullable ProgressListener listener) throws Exception {
        if (!INSTALLING.compareAndSet(false, true))
            throw new IllegalStateException("install already running");
        cancelled = false;
        try {
            return installLocked(listener);
        } finally {
            INSTALLING.set(false);
        }
    }

    private String installLocked(@Nullable ProgressListener listener) throws Exception {
        File base = OrtLoader.baseDir(context);
        File tmp = new File(base, ".tmp");
        deleteRecursive(tmp);
        checkCancelled();

        notify(listener, STAGE_FETCH_MANIFEST, "fetching manifest", -1);
        JsonObject manifest;
        try {
            String manifestJson = sendGetRequest(Constants.Urls.SOLVER_MANIFEST_URL, null, null);
            manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
        } catch (Exception e) {
            Logger.debug(TAG_LOG, "remote manifest unreachable, using built-in: " + e);
            manifest = JsonParser.parseString(BUILT_IN_MANIFEST).getAsJsonObject();
        }
        int version = manifest.has("version") ? manifest.get("version").getAsInt() : 0;
        JsonArray files = manifest.has("files") ? manifest.getAsJsonArray("files") : null;
        if (version <= 0 || files == null || files.isEmpty())
            throw new IllegalStateException("manifest invalid: version=" + version);
        String abi = OrtLoader.primaryAbi();

        List<File> staged = new ArrayList<>();
        List<Integer> freshEntries = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                checkCancelled();
                JsonObject entry = files.get(i).getAsJsonObject();
                String name = entry.get("name").getAsString();
                String url = entry.get("url").getAsString();
                String sha256 = entry.has("sha256") ? entry.get("sha256").getAsString() : null;
                String entryAbi = entry.has("abi") && !entry.get("abi").isJsonNull()
                        ? entry.get("abi").getAsString() : null;
                if (entryAbi != null && !entryAbi.equals(abi)) continue;

                if (isEntryReady(entry, name, sha256, version, base)) {
                    notify(listener, STAGE_VERIFY, name + "（已存在，跳过）",
                            (i + 1) * 100 / files.size());
                    continue;
                }

                freshEntries.add(i);
                File target = new File(tmp, (entryAbi != null ? entryAbi + "/" : "models/") + name);
                //noinspection ResultOfMethodCallIgnored
                Objects.requireNonNull(target.getParentFile()).mkdirs();
                downloadTo(url, target, listener, name, i, files.size());
                if (sha256 != null && !sha256.isEmpty()) {
                    checkCancelled();
                    notify(listener, STAGE_VERIFY, name, i * 100 / files.size());
                    String actual = sha256(target);
                    if (!actual.equalsIgnoreCase(sha256))
                        throw new IllegalStateException("sha256 mismatch: " + name);
                }
                staged.add(target);
            }
        } catch (Exception e) {
            deleteRecursive(tmp);
            throw e;
        }
        checkCancelled();

        if (staged.isEmpty()) {
            deleteRecursive(tmp);
            if (!String.valueOf(version).equals(installedVersion()))
                repo.putString(Constants.Prefs.SOLVER_ASSETS_VERSION, String.valueOf(version));
            notify(listener, STAGE_DONE, "v" + version, 100);
            return String.valueOf(version);
        }

        notify(listener, STAGE_INSTALL, null, 90);
        File nativeDir = OrtLoader.nativeDir(context);
        File modelDir = OrtLoader.modelDir(context);
        //noinspection ResultOfMethodCallIgnored
        nativeDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        modelDir.mkdirs();
        for (File f : staged) {
            String rel = f.getAbsolutePath().substring(tmp.getAbsolutePath().length() + 1);
            File dest = new File(base, rel);
            if (dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            if (!f.renameTo(dest))
                throw new IllegalStateException("install failed: " + rel);
        }
        for (int idx : freshEntries) {
            JsonObject entry = files.get(idx).getAsJsonObject();
            if (!entry.has("extract") || entry.get("extract").isJsonNull()) continue;
            String name = entry.get("name").getAsString();
            if (!name.endsWith(".tar.gz")) continue;
            String extract = entry.get("extract").getAsString();
            File archive = new File(modelDir, name);
            if (!archive.isFile()) continue;
            File destDir = new File(modelDir, extract);
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            extractTarGz(archive, destDir);
        }
        for (int idx : freshEntries) {
            JsonObject entry = files.get(idx).getAsJsonObject();
            if (!entry.has("extractAar") || !entry.get("extractAar").getAsBoolean()) continue;
            String name = entry.get("name").getAsString();
            if (!name.endsWith(".aar")) continue;
            File archive = new File(modelDir, name);
            if (!archive.isFile()) continue;
            extractAar(archive, nativeDir, OrtLoader.primaryAbi());
            notify(listener, STAGE_INSTALL, name, 95);
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
        deleteRecursive(tmp);
        repo.putString(Constants.Prefs.SOLVER_ASSETS_VERSION, String.valueOf(version));
        notify(listener, STAGE_DONE, "v" + version, 100);
        return String.valueOf(version);
    }

    /** 单条目核验：归档按 sha256、解包型按产物+版本一致。 */
    private boolean isEntryReady(JsonObject entry, String name, @Nullable String sha256,
                                 int manifestVersion, File base) throws Exception {
        String sub = entry.has("abi") && !entry.get("abi").isJsonNull()
                ? entry.get("abi").getAsString() + "/" : "models/";
        File finalDest = new File(base, sub + name);
        if (finalDest.isFile() && (sha256 == null || sha256.isEmpty()
                || sha256(finalDest).equalsIgnoreCase(sha256)))
            return true;

        boolean extractAar = entry.has("extractAar") && entry.get("extractAar").getAsBoolean();
        boolean extractTar = entry.has("extract") && !entry.get("extract").isJsonNull();
        if (!extractAar && !extractTar) return false;
        if (!String.valueOf(manifestVersion).equals(installedVersion())) return false;
        if (extractAar) return OrtLoader.isReady(context);
        File[] children = new File(OrtLoader.modelDir(context),
                entry.get("extract").getAsString()).listFiles();
        return children != null && children.length > 0;
    }

    /** 从 onnxruntime AAR（zip）提取 jni/<abi>/ 下的 .so。 */
    private void extractAar(File archive, File nativeDir, String abi) throws Exception {
        String prefix = "jni/" + abi + "/";
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.FileInputStream(archive))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                String entryName = e.getName();
                if (e.isDirectory() || !entryName.startsWith(prefix)) continue;
                String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                if (fileName.isEmpty() || fileName.contains("..")) continue;
                try (FileOutputStream fout = new FileOutputStream(new File(nativeDir, fileName))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) fout.write(buf, 0, n);
                }
            }
        }
    }

    /** 极简 ustar 解包（gzip 流式），文件名取最后一段平铺到 destDir。 */
    private static void extractTarGz(File archive, File destDir) throws Exception {
        byte[] header = new byte[512];
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.FileInputStream(archive), 8192)) {
            while (true) {
                int off = 0, read;
                while (off < 512 && (read = in.read(header, off, 512 - off)) != -1) off += read;
                if (off < 512 || header[0] == 0) break;
                String fullName = new String(header, 0, 100, StandardCharsets.UTF_8).trim();
                if (fullName.isEmpty()) continue;
                long size = tarSize(header);
                if (size < 0) throw new IllegalStateException("tar entry size invalid: " + fullName);
                byte type = header[156];
                boolean regular = type == 0 || type == '0';
                String fileName = fullName.substring(fullName.lastIndexOf('/') + 1);
                if (fileName.contains("..") || fileName.isEmpty()) fileName = null;

                long padded = (size + 511) / 512 * 512;
                long consumed = 0;
                if (regular && fileName != null && size > 0) {
                    File out = new File(destDir, fileName);
                    try (FileOutputStream fout = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        long left = size;
                        while (left > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, left))) != -1) {
                            fout.write(buf, 0, read);
                            left -= read;
                        }
                        if (left != 0) throw new IllegalStateException("tar truncated: " + fullName);
                    }
                    consumed = size;
                }
                skipFully(in, padded - consumed);
            }
        }
    }

    /** GZIP 流上 InputStream.skip() 不可靠，用 read 丢弃式跳过。 */
    private static void skipFully(java.io.InputStream in, long n) throws Exception {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r <= 0) return;
            left -= r;
        }
    }

    /** tar 头 124~136 字节的八进制长度字段。 */
    private static long tarSize(byte[] header) {
        long size = 0;
        for (int i = 124; i < 136; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') break;
            if (b < '0' || b > '7') return -1;
            size = size * 8 + (b - '0');
        }
        return size;
    }

    private void downloadTo(String url, File target, @Nullable ProgressListener listener,
                            String name, int index, int total) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                Logger.debug(TAG_LOG, "download retry " + attempt + " for " + name);
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                java.util.concurrent.TimeUnit.SECONDS.sleep(1);
            }
            try {
                downloadOnce(url, target, listener, name, index, total);
                return;
            } catch (CancelledException ce) {
                throw ce; // 用户取消：不重试，直接上抛
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private void downloadOnce(String url, File target, @Nullable ProgressListener listener,
                              String name, int index, int total) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = ApiClient.get().newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IllegalStateException("download http " + response.code() + ": " + url);
            long contentLength = response.body().contentLength();
            try (java.io.InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                long done = 0;
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkCancelled();
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (listener != null && now - lastNotify >= 150) {
                        lastNotify = now;
                        int percent = -1;
                        if (contentLength > 0 && total > 0) {
                            double overall = (index + (double) done / contentLength) / total * 100;
                            percent = (int) Math.min(overall, 99);
                        }
                        notify(listener, STAGE_DOWNLOAD,
                            name + " " + humanBytes(done) + " / "
                                + (contentLength > 0 ? humanBytes(contentLength) : "?"),
                            percent);
                    }
                }
                // 流「正常」结束但字节不足：服务器提前断流（OkHttp 对未知长度body不报错），必须判失败
                if (contentLength > 0 && done < contentLength)
                    throw new IllegalStateException("connection closed early: " + done + "/" + contentLength);
            }
        }
    }

    private static String humanBytes(long bytes) {
        double mib = bytes / 1048576.0;
        if (mib >= 1.0) return String.format(java.util.Locale.US, "%.1f MiB", mib);
        return String.format(java.util.Locale.US, "%.0f KiB", bytes / 1024.0);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void notify(@Nullable ProgressListener listener, int stage, String detail, int percent) {
        if (listener != null)
            listener.onProgress(stage, detail, percent);
    }
}
