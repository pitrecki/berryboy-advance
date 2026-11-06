package javax.microedition.io.file;
public final class FileSystemRegistry {
    public static String[] roots = {"tmp/"};
    public static java.util.Enumeration listRoots() { return java.util.Collections.enumeration(java.util.Arrays.asList(roots)); }
}
