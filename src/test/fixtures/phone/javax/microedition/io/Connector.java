package javax.microedition.io;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import javax.microedition.io.file.FileConnection;
/** Local filesystem double; SDK compilation separately checks the real API. */
public final class Connector {
    public static final int READ=1,READ_WRITE=3;
    public static boolean failWrite;
    public static int failAfterBytes=-1;
    public static boolean failTruncate,failFlush,failOutputClose,failConnectionClose;
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
                final int limit=failWrite?0:failAfterBytes;failWrite=false;failAfterBytes=-1;
                final boolean flushFailure=failFlush,closeFailure=failOutputClose;
                failFlush=false;failOutputClose=false;
                final OutputStream delegate=Files.newOutputStream(path);
                if(limit<0&&!flushFailure&&!closeFailure)return delegate;
                return new OutputStream(){
                    private int written;
                    public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                    public void write(byte[] bytes,int offset,int length)throws IOException{
                        java.util.Objects.checkFromIndexSize(offset,length,bytes.length);
                        if(length==0)return;
                        int allowed=limit<0?length:Math.min(length,Math.max(0,limit-written));
                        delegate.write(bytes,offset,allowed);written+=allowed;
                        if(allowed<length||limit>=0&&written==limit)throw new IOException("Injected partial write after "+written+" bytes");
                    }
                    public void flush()throws IOException{delegate.flush();if(flushFailure)throw new IOException("Injected flush failure");}
                    public void close()throws IOException{delegate.close();if(closeFailure)throw new IOException("Injected output close failure");}
                };
            }
            public void create() throws IOException{Files.createFile(path);}
            public void truncate(long size) throws IOException{
                try(var f=new RandomAccessFile(path.toFile(),"rw")){f.setLength(size);}
                if(failTruncate){failTruncate=false;throw new IOException("Injected truncate failure");}
            }
            public void close()throws IOException{
                if(mode==READ_WRITE&&failConnectionClose){failConnectionClose=false;throw new IOException("Injected connection close failure");}
            }
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
