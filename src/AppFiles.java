import java.io.File;

/** Resolves data files: next to the packaged app's jar when run from a jpackage image, else the working directory. */
final class AppFiles {
    private AppFiles() {
    }

    static File file(String relative) {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null)
            return new File(new File(new File(exe).getParentFile(), "app"), relative);
        return new File(relative);
    }
}
