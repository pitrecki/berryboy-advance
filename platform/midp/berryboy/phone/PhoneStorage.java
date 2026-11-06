package berryboy.phone;

import berryboy.cheat.CheatCode;
import berryboy.cheat.CheatList;
import berryboy.state.StateBuffer;
import berryboy.state.StateChecksum;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Two sidecar generations and an atomic RMS pointer; original .sav files are read only. */
final class PhoneStorage {
    private final String url;
    private final String filePrefix;
    private final String key;
    PhoneStorage(String url, int romChecksum) {
        this.url = url;
        filePrefix = url + ".berryboy-" + Integer.toHexString(romChecksum) + "-";
        key = "bb-" + Integer.toHexString(romChecksum) + "-" + Integer.toHexString(url.hashCode()) + "-";
    }
    byte[] read(String kind) throws Exception {
        byte[] metadata = PhoneRecords.read(key + kind);
        if (metadata == null) return null;
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(metadata));
        int generation = input.readUnsignedByte();
        int size = input.readInt(); int checksum = input.readInt();
        if (generation > 1 || size < 0 || size > StateBuffer.MAX_BYTES) throw new IOException("Invalid save metadata");
        byte[] data = PhoneFiles.read(filePrefix + kind + "-" + generation, StateBuffer.MAX_BYTES);
        if (data.length != size || StateChecksum.bytes(data, data.length) != checksum) throw new IOException("Incomplete save generation");
        return data;
    }
    void write(String kind, byte[] bytes) throws Exception {
        byte[] previous = PhoneRecords.read(key + kind);
        int generation = previous == null ? 0 : 1 - previous[0];
        if (generation < 0 || generation > 1) throw new IOException("Invalid save metadata");
        PhoneFiles.writeGeneration(filePrefix + kind + "-" + generation, bytes);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(buffer);
        output.writeByte(generation); output.writeInt(bytes.length);
        output.writeInt(StateChecksum.bytes(bytes, bytes.length));
        output.writeLong(System.currentTimeMillis()); output.flush();
        PhoneRecords.write(key + kind, buffer.toByteArray()); // Publish only after the complete file closes.
    }
    byte[] initialBackup(int maximum) throws Exception {
        byte[] own = read("sav");
        if (own != null) return own;
        try { return PhoneFiles.read(url.substring(0, url.length() - 4) + ".sav", maximum); }
        catch (IOException missing) {
            // Distinguish absence from permission, malformed file and real IO errors.
            javax.microedition.io.file.FileConnection file = (javax.microedition.io.file.FileConnection)
                    javax.microedition.io.Connector.open(url.substring(0, url.length() - 4) + ".sav", javax.microedition.io.Connector.READ);
            try { if (file.exists()) throw missing; } finally { file.close(); }
            return null;
        }
    }
    String[] slots() throws Exception {
        String[] values = new String[5];
        for (int i = 0; i < values.length; i++) values[i] = PhoneRecords.read(key + "st" + i) == null ? "Empty" : "Saved";
        return values;
    }
    CheatList cheats() throws Exception {
        byte[] data = PhoneRecords.read(key + "cht");
        CheatList result = new CheatList();
        if (data == null) return result;
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int count = input.readUnsignedByte();
        if (count > CheatList.CAPACITY) throw new IOException("Invalid cheat profile");
        for (int i = 0; i < count; i++) {
            String name = input.readUTF(); int format = input.readUnsignedByte(); String source = input.readUTF();
            result.add(name, new CheatCode(source, format));
            if (!input.readBoolean()) result.toggle(i);
        }
        if (input.available() != 0) throw new IOException("Invalid cheat profile length");
        return result;
    }
    void cheats(CheatList cheats) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(cheats.size());
        for (int i = 0; i < cheats.size(); i++) {
            output.writeUTF(cheats.name(i)); output.writeByte(cheats.code(i).format());
            output.writeUTF(cheats.code(i).source()); output.writeBoolean(cheats.enabled(i));
        }
        output.flush(); PhoneRecords.write(key + "cht", bytes.toByteArray());
    }
}
