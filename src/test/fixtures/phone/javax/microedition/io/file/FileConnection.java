package javax.microedition.io.file;
import java.io.*;
public interface FileConnection {
    boolean exists(); boolean isDirectory(); long fileSize() throws IOException;
    InputStream openInputStream() throws IOException; OutputStream openOutputStream() throws IOException;
    void create() throws IOException; void truncate(long size) throws IOException; void close() throws IOException;
    java.util.Enumeration list(String filter, boolean hidden) throws IOException;
}
