package javax.microedition.io;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import javax.microedition.io.file.FileConnection;
/** Local filesystem double; SDK compilation separately checks the real API. */
public final class Connector {
    public static final int READ=1,READ_WRITE=3;
    public static boolean failWrite;
    public static volatile boolean failList;
    public static volatile java.util.concurrent.CountDownLatch listingEntered, releaseListing;
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
            public java.util.Enumeration list(String filter, boolean hidden) throws IOException {
                var entered=listingEntered;var release=releaseListing;
                if(entered!=null)entered.countDown();
                if(release!=null)try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Listing wait timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                if(failList)throw new IOException("Injected list failure");
                var names=new java.util.Vector<String>();
                try(var children=Files.list(path)){
                    children.forEach(child->names.add(child.getFileName().toString()+(Files.isDirectory(child)?"/":"")));
                }
                return names.elements();
            }
        };
    }
}
