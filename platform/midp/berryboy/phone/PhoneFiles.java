package berryboy.phone;

import berryboy.phone.library.FileUrls;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/** Bounded JSR-75 IO. The caller owns file permissions and the target generation. */
final class PhoneFiles {
    private PhoneFiles() { }
    static byte[] readBios(String url) throws IOException {
        if (url == null || url.length() == 0) throw new IOException("Select a GBA BIOS in Display & audio > BIOS file");
        byte[] bytes = read(url, 16384);
        if (bytes.length != 16384) throw new IOException("GBA BIOS must be exactly 16 KiB (16384 bytes)");
        return bytes;
    }
    static String childUrl(String directory, String name) throws IOException {
        return FileUrls.child(directory, name);
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
