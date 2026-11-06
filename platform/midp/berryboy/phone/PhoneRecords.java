package berryboy.phone;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** A single RMS record is the commit point for preferences or a file generation. */
final class PhoneRecords {
    private PhoneRecords() { }
    static byte[] read(String name) throws RecordStoreException {
        RecordStore store = RecordStore.openRecordStore(name, true);
        try { return store.getNumRecords() == 0 ? null : store.getRecord(1); }
        finally { store.closeRecordStore(); }
    }
    static void write(String name, byte[] bytes) throws RecordStoreException {
        RecordStore store = RecordStore.openRecordStore(name, true);
        try {
            if (store.getNumRecords() == 0) store.addRecord(bytes, 0, bytes.length);
            else store.setRecord(1, bytes, 0, bytes.length);
        } finally { store.closeRecordStore(); }
    }
}
