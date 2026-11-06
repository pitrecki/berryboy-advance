package berryboy.phone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/** Bounded JSR-75 IO. The caller owns file permissions and the target generation. */
final class PhoneFiles {
    private PhoneFiles() { }
    static String childUrl(String directory, String name) throws IOException {
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
    static byte[] read(String url, int maximum) throws IOException {
        checkUrl(url);
        FileConnection file = (FileConnection) Connector.open(url, Connector.READ);
        try {
            if (!file.exists() || file.isDirectory()) throw new IOException("File not found: " + url);
            long size = file.fileSize();
            if (size < 0 || size > maximum) throw new IOException("File exceeds supported size");
            byte[] data = new byte[(int) size];
            InputStream input = file.openInputStream();
            try {
                int position = 0;
                while (position < data.length) {
                    int count = input.read(data, position, data.length - position);
                    if (count <= 0) throw new IOException("Incomplete file read");
                    position += count;
                }
            } finally { input.close(); }
            return data;
        } finally { file.close(); }
    }
    static void writeGeneration(String url, byte[] data) throws IOException {
        checkUrl(url);
        FileConnection file = (FileConnection) Connector.open(url, Connector.READ_WRITE);
        try {
            if (!file.exists()) file.create();
            file.truncate(0);
            OutputStream output = file.openOutputStream();
            try { output.write(data); output.flush(); } finally { output.close(); }
        } finally { file.close(); }
    }
    private static void checkUrl(String url) {
        if (url == null || !url.startsWith("file:///")) throw new IllegalArgumentException("Use a file:/// URL or Browse");
    }
}
