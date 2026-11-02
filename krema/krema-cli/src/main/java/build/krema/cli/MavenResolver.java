package build.krema.cli;

import build.krema.core.platform.PlatformDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks that Maven is available on the system and resolves project classpaths.
 */
public final class MavenResolver {

    private MavenResolver() {}

    /**
     * Checks that Maven is available on PATH.
     * Prints an error and returns false if not found.
     */
    public static boolean checkAvailable() {
        String whichCmd = PlatformDetector.isWindows() ? "where" : "which";
        try {
            var pb = new ProcessBuilder(whichCmd, "mvn");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
        } catch (IOException | InterruptedException ignored) {}

        System.err.println("[Krema] Maven is required but was not found on PATH.");
        System.err.println("[Krema] Install it with:");
        System.err.println("[Krema]   Windows:  choco install maven  (or scoop install maven)");
        System.err.println("[Krema]   macOS:    brew install maven");
        System.err.println("[Krema]   Linux:    sudo apt install maven  (or your package manager)");
        return false;
    }

    /**
     * Resolves the project's runtime dependency classpath via
     * {@code mvn dependency:build-classpath}.
     *
     * @param javaHome JDK to run Maven with, or null to use Maven's default
     * @return the classpath string (entries separated by the platform path separator),
     *         or null if resolution failed
     */
    public static String resolveClasspath(Path javaHome) throws IOException, InterruptedException {
        Path cpFile = Files.createTempFile("krema-cp", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(ShellCommand.of(
                "mvn", "dependency:build-classpath",
                "-Dmdep.outputFile=" + cpFile.toAbsolutePath(),
                "-Dmdep.includeScope=runtime",
                "-q"
            ));
            if (javaHome != null) {
                pb.environment().put("JAVA_HOME", javaHome.toString());
            }
            pb.inheritIO();
            Process process = pb.start();
            if (process.waitFor() != 0) {
                return null;
            }
            return Files.readString(cpFile).trim();
        } finally {
            Files.deleteIfExists(cpFile);
        }
    }
}
