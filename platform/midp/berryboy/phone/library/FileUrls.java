package berryboy.phone.library;

import java.io.IOException;

/** JSR-75 URL operations shared by file IO and the storage picker. */
public final class FileUrls {
    public static final String ROOTS = "file:///";
    private FileUrls() { }
    public static String directory(String url) {
        if (url == null || !url.startsWith(ROOTS)) throw new IllegalArgumentException("Use a local file:/// folder URL");
        return url.endsWith("/") ? url : url + "/";
    }
    public static String parent(String url) {
        if (url == null || !url.startsWith(ROOTS) || url.length() <= ROOTS.length()) return ROOTS;
        String value = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return value.substring(0, value.lastIndexOf('/') + 1);
    }
    public static String child(String directory, String name) throws IOException {
        byte[] bytes = name.getBytes("UTF-8");
        StringBuffer url = new StringBuffer(directory);
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') url.append((char) c);
            else { url.append('%'); url.append(hex.charAt(c >>> 4)); url.append(hex.charAt(c & 15)); }
        }
        return url.toString();
    }
    public static boolean matches(String name, String extension) {
        if (name.length() < extension.length()) return false;
        int start = name.length() - extension.length();
        for (int i = 0; i < extension.length(); i++) {
            char c = name.charAt(start + i);
            if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
            if (c != extension.charAt(i)) return false;
        }
        return true;
    }
}
