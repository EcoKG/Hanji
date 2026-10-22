package io.hanji.electron;

import io.hanji.HanjiConfig;
import io.hanji.host.Displays;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates or installs the Electron runtime used by the {@code electron} window host.
 * <p>
 * Resolution order: the configured path ({@code hanji.electron} / {@code HANJI_ELECTRON}, either the executable
 * or a distribution folder), then the cache ({@code <cacheDir>/electron/<version>/<platform>}), then — if allowed —
 * a download of the official release from GitHub, verified with SHA-256.
 * <p>
 * To bundle Electron with an application, install it ahead of time and point {@code hanji.electron} at it:
 * <pre>java -cp hanji-core.jar io.hanji.electron.ElectronRuntime install --dir build/electron</pre>
 */
public final class ElectronRuntime {

    public static final String DEFAULT_VERSION = "44.9.3";

    /** SHA-256 of the default version's archives (independent of the checksum file served next to them). */
    private static final Map<String, String> KNOWN_SHA256 = Map.of(
            "electron-v44.9.3-darwin-arm64.zip", "4af51382278d00291dfcda265eddd9bab6e98cebb09f46aadc8438bf606de162",
            "electron-v44.9.3-darwin-x64.zip", "451363b59bb7d2c1c9e0c4baa99106004eaabe724aa21a0c6b4c87469293531a",
            "electron-v44.9.3-linux-arm64.zip", "8e70d53d920d84db869023280e5a1fbda82ecc58fea4443748e58309a3c80238",
            "electron-v44.9.3-linux-x64.zip", "6e7fd2d450d442507b846ace4ae6e2bd17e1b1653e86a033b5231abfc23e6df4",
            "electron-v44.9.3-win32-arm64.zip", "66662802d00c2626a203f8909467dab3504def4ce8615c152135b994a5cec220",
            "electron-v44.9.3-win32-x64.zip", "41a1ffa0541cb0f2c9b9fe920dd2cef1793ad0ea2ada1a9582958880f4d1b23c");

    private static final String RELEASES = "https://github.com/electron/electron/releases/download/";
    private static final String MARKER = ".hanji-complete";

    private ElectronRuntime() {
    }

    /** The Electron executable for {@code config}, downloading it if necessary and allowed; {@code null} if unavailable. */
    public static Path resolve(HanjiConfig config) throws IOException {
        Path installed = findInstalled(config);
        if (installed != null) {
            return installed;
        }
        if (!config.isElectronDownload()) {
            return null;
        }
        return install(config.getElectronVersion(), config.getCacheDir().resolve("electron"));
    }

    /** The configured or cached Electron executable, without downloading. */
    public static Path findInstalled(HanjiConfig config) {
        String explicit = config.getElectronPath();
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            Path exe = Files.isDirectory(p) ? executableIn(p) : p;
            return Files.isRegularFile(exe) ? exe : null;
        }
        Path dir = config.getCacheDir().resolve("electron").resolve(config.getElectronVersion()).resolve(platform());
        return isComplete(dir) ? executableIn(dir) : null;
    }

    /**
     * Downloads, verifies and extracts Electron {@code version} for this platform into
     * {@code <root>/<version>/<platform>} (no-op when already present). Returns the executable.
     */
    public static Path install(String version, Path root) throws IOException {
        String platform = platform();
        Path target = root.resolve(version).resolve(platform);
        if (isComplete(target)) {
            return executableIn(target);
        }
        Files.createDirectories(root);
        String archive = "electron-v" + version + "-" + platform + ".zip";
        String base = RELEASES + "v" + version + "/";
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        String expected = KNOWN_SHA256.get(archive);
        if (expected == null) {
            expected = checksumFromRelease(http, base, archive);
        }
        Path zip = Files.createTempFile(root, "download-", ".zip");
        Path staging = null;
        try {
            log("Downloading Electron " + version + " (" + platform + "), one time, cached in " + root);
            String actual = download(http, URI.create(base + archive), zip);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("Checksum mismatch for " + archive + ": expected " + expected + " but got " + actual);
            }
            staging = Files.createTempDirectory(root, "extract-");
            if (!Displays.isWindows()) {
                Files.setPosixFilePermissions(staging, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            extract(zip, staging);
            Path exe = executableIn(staging);
            if (!Files.isRegularFile(exe)) {
                throw new IOException("Electron executable missing after extraction: " + exe);
            }
            Files.writeString(staging.resolve(MARKER), version + " " + platform + "\n");
            Files.createDirectories(target.getParent());
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                staging = null;
            } catch (IOException raced) {
                if (!isComplete(target)) {
                    throw raced; // not a concurrent install that finished first
                }
            }
            log("Electron " + version + " installed");
            return executableIn(target);
        } finally {
            Files.deleteIfExists(zip);
            if (staging != null) {
                deleteRecursively(staging);
            }
        }
    }

    // ------------------------------------------------------------------ platform

    /** Electron's platform id: linux-x64, linux-arm64, darwin-x64, darwin-arm64, win32-x64, win32-arm64. */
    public static String platform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64";
        String os = Displays.isWindows() ? "win32" : Displays.isMac() ? "darwin" : "linux";
        return os + "-" + cpu;
    }

    /** The executable inside an extracted Electron distribution. */
    public static Path executableIn(Path dir) {
        if (Displays.isMac()) {
            return dir.resolve("Electron.app").resolve("Contents").resolve("MacOS").resolve("Electron");
        }
        return dir.resolve(Displays.isWindows() ? "electron.exe" : "electron");
    }

    /** Platform cache folder: ~/Library/Caches/hanji, %LOCALAPPDATA%\hanji\cache or $XDG_CACHE_HOME/hanji. */
    public static Path defaultCacheDir() {
        String home = System.getProperty("user.home");
        if (Displays.isMac()) {
            return Path.of(home, "Library", "Caches", "hanji");
        }
        if (Displays.isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            return local != null ? Path.of(local, "hanji", "cache") : Path.of(home, "AppData", "Local", "hanji", "cache");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "hanji") : Path.of(home, ".cache", "hanji");
    }

    private static boolean isComplete(Path dir) {
        return Files.isRegularFile(dir.resolve(MARKER)) && Files.isRegularFile(executableIn(dir));
    }

    // ------------------------------------------------------------------ download & verify

    private static String download(HttpClient http, URI uri, Path file) throws IOException {
        HttpResponse<InputStream> response = send(http, uri, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest sha = sha256();
        try (InputStream in = new DigestInputStream(response.body(), sha);
             OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent / 10 != lastPercent / 10) {
                        lastPercent = percent;
                        log("  " + percent + "% (" + done / (1024 * 1024) + " / " + total / (1024 * 1024) + " MB)");
                    }
                }
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String checksumFromRelease(HttpClient http, String base, String archive) throws IOException {
        URI uri = URI.create(base + "SHASUMS256.txt");
        HttpResponse<String> response = send(http, uri, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        for (String line : response.body().split("\\R")) {
            String[] parts = line.trim().split("\\s+\\*?");
            if (parts.length == 2 && parts[1].equals(archive)) {
                return parts[0];
            }
        }
        throw new IOException("No checksum for " + archive + " in " + uri);
    }

    private static <T> HttpResponse<T> send(HttpClient http, URI uri, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + uri, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ extraction

    private static void extract(Path zip, Path dest) throws IOException {
        if (Displays.isMac()) {
            // ditto keeps the symbolic links and permissions inside Electron.app (its frameworks need them)
            run("ditto", "-x", "-k", zip.toString(), dest.toString());
        } else if (Displays.isWindows()) {
            extractPlain(zip, dest);
        } else {
            extractWithPermissions(zip, dest);
        }
    }

    private static void extractPlain(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Bad archive entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Linux: zipfs exposes the archived POSIX permissions (executables must stay executable). */
    private static void extractWithPermissions(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (FileSystem fs = FileSystems.newFileSystem(zip, Map.of("enablePosixFileAttributes", "true"));
             Stream<Path> entries = Files.walk(fs.getPath("/"))) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.toString().replaceFirst("^/", "");
                if (name.isEmpty()) {
                    continue;
                }
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Bad archive entry: " + name);
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(entry, out, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(entry);
                    if (!perms.isEmpty()) {
                        Files.setPosixFilePermissions(out, perms);
                    }
                } catch (UnsupportedOperationException | IOException ignored) {
                    // fall back to the defaults below
                }
            }
        }
        for (String exe : new String[]{"electron", "chrome-sandbox", "chrome_crashpad_handler"}) {
            Path p = root.resolve(exe);
            if (Files.isRegularFile(p) && !Files.isExecutable(p)) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(p, perms);
            }
        }
    }

    private static void run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IOException(String.join(" ", cmd) + " failed: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private static void log(String message) {
        System.err.println("[hanji] " + message);
    }

    // ------------------------------------------------------------------ CLI

    /**
     * {@code install [--version V] [--dir DIR]}: installs Electron into DIR (default: the cache) and prints the
     * executable path. Use it to bundle Electron with an application.
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !args[0].equals("install")) {
            System.err.println("Usage: ElectronRuntime install [--version " + DEFAULT_VERSION + "] [--dir DIR]");
            System.exit(2);
        }
        String version = DEFAULT_VERSION;
        Path dir = defaultCacheDir().resolve("electron");
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (args[i].equals("--version")) {
                version = args[i + 1];
            } else if (args[i].equals("--dir")) {
                dir = Path.of(args[i + 1]);
            }
        }
        System.out.println(install(version, dir));
    }
}
