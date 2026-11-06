package javax.microedition.io;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import javax.microedition.io.file.FileConnection;
/** Local filesystem double; SDK compilation separately checks the real API. */
public final class Connector {
    public static final int READ=1,READ_WRITE=3;
    public static boolean failWrite;
    public static Object open(String url,int mode) throws IOException {
        final Path path=Paths.get(URI.create(url));
        return new FileConnection() {
            public boolean exists(){return Files.exists(path);}
            public boolean isDirectory(){return Files.isDirectory(path);}
            public long fileSize() throws IOException{return Files.size(path);}
            public InputStream openInputStream() throws IOException{return Files.newInputStream(path);}
            public OutputStream openOutputStream() throws IOException {
                if(failWrite){failWrite=false;return new OutputStream(){
                    public void write(int b) throws IOException {throw new IOException("Injected partial write");}
                };}
                return Files.newOutputStream(path);
            }
            public void create() throws IOException{Files.createFile(path);}
            public void truncate(long size) throws IOException{try(var f=new RandomAccessFile(path.toFile(),"rw")){f.setLength(size);}}
            public void close(){}
        };
    }
}
