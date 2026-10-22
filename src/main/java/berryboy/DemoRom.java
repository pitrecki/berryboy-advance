package berryboy;

import berryboy.bus.Memory;
import berryboy.video.BitmapRenderer;

/** Original synthetic cartridge: ARM setup, Thumb loop fills all 38,400 pixels. */
public final class DemoRom {
    public static final int RUN_BUDGET = 200000;
    public static final int HALT_ADDRESS = 0x080000fa;
    private DemoRom() {
    }

    public static byte[] create() {
        var rom = new byte[0x200];
        word(rom, 0, 0xea00002e); // B 0x080000c0, skip development header
        text(rom, 0xa0, "BERRYBOY DEMO");
        text(rom, 0xac, "BBA0");
        rom[0xb2] = (byte) 0x96;
        var sum = 0x19;
        for (var offset = 0xa0; offset <= 0xbc; offset++) {
            sum += rom[offset] & 0xff;
        }
        rom[0xbd] = (byte) -sum;
        // The Nintendo logo is deliberately absent; this is a direct-entry test.
        word(rom, 0xc0, 0xe59f0038); // LDR r0, [pc,#0x38] => IO literal at 0x100
        word(rom, 0xc4, 0xe3a01b01); // MOV r1,#0x400 (BG2 enable)
        word(rom, 0xc8, 0xe3811003); // ORR r1,r1,#3 (mode 3)
        word(rom, 0xcc, 0xe1c010b0); // STRH r1,[r0]
        word(rom, 0xd0, 0xe3a01c01); // MOV r1,#0x100 (identity affine transform)
        word(rom, 0xd4, 0xe1c012b0); // STRH r1,[r0,#0x20] (BG2PA)
        word(rom, 0xd8, 0xe1c012b6); // STRH r1,[r0,#0x26] (BG2PD)
        word(rom, 0xdc, 0xe59f0020); // LDR r0,[pc,#0x20] => VRAM literal at 0x104
        word(rom, 0xe0, 0xe59f1020); // LDR r1,[pc,#0x20] => count literal at 0x108
        word(rom, 0xe4, 0xe3a02000); // MOV r2,#0
        word(rom, 0xe8, 0xe28f3001); // ADD r3,pc,#1 => 0x080000f1
        word(rom, 0xec, 0xe12fff13); // BX r3
        half(rom, 0xf0, 0x8002); // STRH r2,[r0]
        half(rom, 0xf2, 0x3002); // ADDS r0,#2
        half(rom, 0xf4, 0x3201); // ADDS r2,#1
        half(rom, 0xf6, 0x3901); // SUBS r1,#1
        half(rom, 0xf8, 0xd1fa); // BNE 0xf0 (offset -12)
        half(rom, 0xfa, 0xe7fe); // B 0xfa (done)
        word(rom, 0x100, Memory.IO_BASE);
        word(rom, 0x104, Memory.VRAM_BASE);
        word(rom, 0x108, BitmapRenderer.WIDTH * BitmapRenderer.HEIGHT);
        return rom;
    }

    private static void text(byte[] bytes, int offset, String text) {
        for (var index = 0; index < text.length(); index++) {
            bytes[offset + index] = (byte) text.charAt(index);
        }
    }

    private static void word(byte[] bytes, int offset, int value) {
        half(bytes, offset, value);
        half(bytes, offset + 2, value >>> 16);
    }

    private static void half(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }
}
