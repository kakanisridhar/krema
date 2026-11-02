package build.krema.cli.bundle.windows;

import static build.krema.cli.bundle.windows.WindowsRegistryHelper.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import build.krema.cli.JdkResolver;
import build.krema.cli.bundle.AppBundleConfig;
import build.krema.cli.bundle.AppBundler;

/**
 * Windows-specific application bundler using jpackage.
 * Creates .exe and .msi installers for distribution.
 */
public class WindowsAppBundler implements AppBundler {

    @Override
    public Path createDevBundle(AppBundleConfig config) throws IOException {
        // Windows doesn't require bundling for dev mode.
        // The app runs as a regular Java process; native features work without
        // a formal bundle structure.
        return null;
    }

    @Override
    public Path createProductionBundle(AppBundleConfig config) throws IOException {
        String jpackagePath = findJpackage();
        if (jpackagePath == null) {
            throw new IOException("jpackage not found. Requires JDK 16+ with jpackage available.");
        }

        Path outputDir = config.outputDir();
        Files.createDirectories(outputDir);

        // Stage a clean jpackage input directory containing only what the app needs:
        // the main jar, every runtime dependency jar, and the native DLLs.
        // (Pointing jpackage at target/ directly would drag in classes/, the bundle
        // output itself, and would miss dependencies that live in ~/.m2.)
        Path inputDir = stageInputDirectory(config, Paths.get("target"));
        Path mainJar = findMainJar(inputDir, config.mainClass());

        List<String> command = new ArrayList<>();
        command.add(jpackagePath);
        command.add("--type");
        command.add("exe");
        command.add("--input");
        command.add(inputDir.toAbsolutePath().toString());
        command.add("--main-jar");
        command.add(mainJar.getFileName().toString());
        command.add("--main-class");
        command.add(config.mainClass());
        command.add("--name");
        command.add(config.appName());
        command.add("--app-version");
        command.add(toWindowsVersion(config.version()));
        command.add("--dest");
        command.add(outputDir.toAbsolutePath().toString());

        if (config.iconPath() != null && Files.exists(config.iconPath())) {
            command.add("--icon");
            command.add(config.iconPath().toAbsolutePath().toString());
        }

        // JVM options for the generated launcher. $APPDIR is expanded by the jpackage
        // launcher to the app/ directory, where the jars and DLLs are installed.
        command.add("--java-options");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--java-options");
        command.add("-Djava.library.path=$APPDIR");

        // Windows-specific options
        command.add("--win-dir-chooser");
        command.add("--win-shortcut");
        command.add("--win-menu");

        System.out.println("[WindowsAppBundler] Running jpackage..." + command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("jpackage failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("jpackage interrupted", e);
        }

        System.out.println("[WindowsAppBundler] Bundle created at: " + outputDir);

        // Generate URL protocol registration scripts if deep link schemes are configured
        List<String> schemes = config.deepLinkSchemes();
        if (schemes != null && !schemes.isEmpty()) {
            generateProtocolRegistrationScripts(config, outputDir);
        }

        return outputDir;
    }

    /**
     * Builds a fresh jpackage input directory next to the output directory.
     * All jars are placed flat at the top level (jpackage puts every jar in the
     * input directory on the launcher classpath) together with the native DLLs.
     */
    static Path stageInputDirectory(AppBundleConfig config, Path targetDir) throws IOException {
        Path inputDir = config.outputDir().resolveSibling(config.outputDir().getFileName() + "-input");
        deleteRecursively(inputDir);
        Files.createDirectories(inputDir);

        // 1. Application jar(s) built into target/
        int appJars = 0;
        if (Files.isDirectory(targetDir)) {
            try (var stream = Files.list(targetDir)) {
                for (Path jar : stream.filter(WindowsAppBundler::isApplicationJar).toList()) {
                    copyInto(jar, inputDir);
                    appJars++;
                }
            }
        }
        if (appJars == 0) {
            throw new IOException("No application jar found in " + targetDir.toAbsolutePath()
                + ". Run 'krema build' first.");
        }

        // 2. Runtime dependency jars from the resolved classpath
        int depJars = 0;
        if (config.classpath() != null && !config.classpath().isBlank()) {
            for (String entry : config.classpath().split(File.pathSeparator)) {
                if (entry.isBlank()) continue;
                Path jar = Path.of(entry.trim());
                if (Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar")) {
                    if (copyInto(jar, inputDir)) depJars++;
                }
            }
        }

        // 3. Dependency jars copied by maven-dependency-plugin, if any
        for (String depDir : new String[] {"dependency", "lib", "libs"}) {
            Path dir = targetDir.resolve(depDir);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    if (copyInto(jar, inputDir)) depJars++;
                }
            }
        }

        // 4. Native libraries (webview.dll and companions such as libwinpthread-1.dll)
        int dlls = 0;
        if (config.libPath() != null && Files.isDirectory(config.libPath())) {
            try (var stream = Files.list(config.libPath())) {
                for (Path dll : stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dll")).toList()) {
                    copyInto(dll, inputDir);
                    dlls++;
                }
            }
        }

        System.out.println("[WindowsAppBundler] Staged " + appJars + " app jar(s), " + depJars
            + " dependency jar(s), " + dlls + " native librar" + (dlls == 1 ? "y" : "ies")
            + " in " + inputDir);
        return inputDir;
    }

    /**
     * Picks the jar that contains the main class. Falls back to the only jar
     * that is not a dependency if the main class cannot be located.
     */
    static Path findMainJar(Path inputDir, String mainClass) throws IOException {
        String entryName = mainClass.replace('.', '/') + ".class";
        List<Path> jars;
        try (var stream = Files.list(inputDir)) {
            jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                if (jarFile.getEntry(entryName) != null) {
                    return jar;
                }
            } catch (IOException ignored) {
                // Not a readable jar; skip
            }
        }
        throw new IOException("Main class " + mainClass + " not found in any staged jar in " + inputDir
            + ". Check [build] main_class in krema.toml.");
    }

    private static boolean isApplicationJar(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p)
            && name.endsWith(".jar")
            && !name.endsWith("-sources.jar")
            && !name.endsWith("-javadoc.jar")
            && !name.endsWith("-tests.jar")
            && !name.startsWith("original-");
    }

    /** Copies a file into dir, returning false (and warning) if a different file with the same name was already staged. */
    private static boolean copyInto(Path file, Path dir) throws IOException {
        Path dest = dir.resolve(file.getFileName());
        if (Files.exists(dest)) {
            if (Files.size(dest) != Files.size(file)) {
                System.err.println("[WindowsAppBundler] Warning: skipping " + file
                    + " (a different file named " + file.getFileName() + " is already bundled)");
            }
            return false;
        }
        Files.copy(file, dest);
        return true;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * jpackage on Windows requires a purely numeric version (up to 4 components),
     * so strip qualifiers like "-SNAPSHOT" or "-beta.1".
     */
    static String toWindowsVersion(String version) {
        if (version == null) return "1.0.0";
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})").matcher(version.trim());
        return m.find() ? m.group(1) : "1.0.0";
    }

    /**
     * Generates URL protocol registration files for deep link support.
     * Creates .reg file, PowerShell registration script, and unregistration script.
     */
    private void generateProtocolRegistrationScripts(AppBundleConfig config, Path outputDir) {
        List<String> schemes = config.deepLinkSchemes();
        String appName = config.appName();

        // Determine the installed exe path (standard jpackage location)
        // Users should update this path if they customize the install location
        String defaultInstallPath = "C:\\Program Files\\" + appName + "\\" + appName + ".exe";

        try {
            // Generate .reg file for manual import
            Path regFile = outputDir.resolve(appName + "-protocols.reg");
            generateRegFile(schemes, defaultInstallPath, appName, regFile);
            System.out.println("[WindowsAppBundler] Generated protocol registry file: " + regFile);

            // Generate PowerShell registration script
            Path registerScript = outputDir.resolve("register-protocols.ps1");
            generatePowerShellScript(schemes, defaultInstallPath, appName, registerScript);
            System.out.println("[WindowsAppBundler] Generated registration script: " + registerScript);

            // Generate PowerShell unregistration script
            Path unregisterScript = outputDir.resolve("unregister-protocols.ps1");
            generateUnregisterScript(schemes, unregisterScript);
            System.out.println("[WindowsAppBundler] Generated unregistration script: " + unregisterScript);

            // Print instructions
            System.out.println();
            System.out.println("[WindowsAppBundler] Deep link protocols configured: " + String.join(", ", schemes));
            System.out.println("[WindowsAppBundler] To enable deep links, choose one of:");
            System.out.println("  1. Double-click " + regFile.getFileName() + " to import registry entries");
            System.out.println("  2. Run: powershell -ExecutionPolicy Bypass -File " + registerScript.getFileName());
            System.out.println("  Note: Update the exe path in scripts if using a custom install location");

        } catch (IOException e) {
            System.err.println("[WindowsAppBundler] Warning: Failed to generate protocol scripts: " + e.getMessage());
        }
    }

    @Override
    public Process launch(Path bundlePath, Map<String, String> env) throws IOException {
        // Find the .exe in the bundle directory
        Path exe = null;
        try (var stream = Files.list(bundlePath)) {
            exe = stream
                .filter(p -> p.toString().endsWith(".exe"))
                .findFirst()
                .orElse(null);
        }
        if (exe == null) {
            throw new IOException("No .exe found in bundle: " + bundlePath);
        }

        ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString());
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.inheritIO();
        return pb.start();
    }

    @Override
    public boolean requiresBundleForNativeFeatures() {
        return false;
    }

    private static String findJpackage() {
        // Check JAVA_HOME first
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path path = Path.of(javaHome, "bin", "jpackage.exe");
            if (Files.isExecutable(path)) return path.toString();
        }

        // Check current JDK
        String jdkHome = System.getProperty("java.home");
        if (jdkHome != null) {
            Path path = Path.of(jdkHome, "bin", "jpackage.exe");
            if (Files.isExecutable(path)) return path.toString();
        }

        // Check the JDK Krema resolves for builds
        Path resolvedJdk = JdkResolver.resolve();
        if (resolvedJdk != null) {
            Path path = resolvedJdk.resolve("bin").resolve("jpackage.exe");
            if (Files.isExecutable(path)) return path.toString();
        }

        // Check PATH
        try {
            Process p = new ProcessBuilder("where", "jpackage").start();
            String output = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() == 0 && !output.isEmpty()) {
                return output.lines().findFirst().orElse(null);
            }
        } catch (Exception ignored) {
        }

        return null;
    }
}
