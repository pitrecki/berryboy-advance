package javax.microedition.rms;
import java.util.*;
/** Atomic record double with a deliberately failing commit. */
public final class RecordStore {
    private static final Map<String,byte[]> records=new HashMap<>();
    public static boolean failCommit;
    private final String name;
    private RecordStore(String name){this.name=name;}
    public static RecordStore openRecordStore(String name,boolean create){return new RecordStore(name);}
    public int getNumRecords(){return records.containsKey(name)?1:0;}
    public byte[] getRecord(int id){return records.get(name).clone();}
    public int addRecord(byte[] b,int offset,int size) throws RecordStoreException{setRecord(1,b,offset,size);return 1;}
    public void setRecord(int id,byte[] b,int offset,int size) throws RecordStoreException{
        if(failCommit){failCommit=false;throw new RecordStoreException("Injected record commit failure");}
        records.put(name,Arrays.copyOfRange(b,offset,offset+size));
    }
    public void closeRecordStore(){}
}
