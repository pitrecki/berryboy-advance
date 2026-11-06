package berryboy.mobile.library;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class LibraryProfileTest {
    @Test
    void startsAtAllStorageAndPreservesIndependentLocalSelections() {
        var profile = new LibraryProfile();
        assertThat(profile.directory()).isEqualTo("file:///");
        profile.setDirectory("file:///store/home/user/gry");
        profile.setRom("file:///store/home/user/gry/Golden%20Sun.gba");
        profile.setBios("file:///SDCard/system/gba_bios.bin");
        var image = profile.encode(); var before = image.clone();
        var restored = LibraryProfile.decode(image);
        assertSoftly(s -> {
            s.assertThat(restored.directory()).isEqualTo("file:///store/home/user/gry/");
            s.assertThat(restored.rom()).isEqualTo(profile.rom());
            s.assertThat(restored.bios()).isEqualTo(profile.bios());
            s.assertThat(image).isEqualTo(before);
        });
    }
    @Test
    void folderChangesClearOnlyThePreviousRomAndDoNotChangeCopiedProfiles() {
        var profile = new LibraryProfile(); profile.setDirectory("file:///store/a/");
        profile.setRom("file:///store/a/game.gba"); profile.setBios("file:///store/bios.bin");
        var copy = profile.copy(); copy.setDirectory("file:///SDCard/b");
        assertSoftly(s -> {
            s.assertThat(copy.rom()).isEmpty();
            s.assertThat(copy.bios()).isEqualTo(profile.bios());
            s.assertThat(profile.rom()).isEqualTo("file:///store/a/game.gba");
            s.assertThat(profile.directory()).isEqualTo("file:///store/a/");
        });
        profile.setDirectory("file:///store/a");
        assertThat(profile.rom()).isEqualTo("file:///store/a/game.gba");
    }
    @Test
    void roundTripsUnicodeAndPercentEscapesWithoutReinterpretingThePath() {
        var profile = new LibraryProfile(); profile.setDirectory("file:///store/żółć %20/日本語/");
        assertThat(LibraryProfile.decode(profile.encode()).directory()).isEqualTo(profile.directory());
    }
    @Test
    void rejectsInvalidLocationsWithoutChangingThePreviousProfile() {
        var profile = new LibraryProfile(); var before = profile.encode();
        for (var bad : new String[]{"", "https://example.com/", "/SDCard/games", "file:///a\nb/", "file:///" + "a".repeat(505)}) {
            assertThatThrownBy(() -> profile.setDirectory(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> profile.setBios(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(profile.encode()).isEqualTo(before);
    }
    @Test
    void rejectsUnknownTruncatedAndTrailingSettings() {
        var profile = new LibraryProfile(); var image = profile.encode();
        var bad = image.clone(); bad[0] = 2;
        assertThatThrownBy(() -> LibraryProfile.decode(bad)).hasMessageContaining("version");
        assertThatThrownBy(() -> LibraryProfile.decode(java.util.Arrays.copyOf(image, image.length - 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LibraryProfile.decode(java.util.Arrays.copyOf(image, image.length + 1))).hasMessageContaining("trailing");
        var oversized = image.clone(); for (var i = 4; i < 8; i++) oversized[i] = (byte) 0xff;
        assertThatThrownBy(() -> LibraryProfile.decode(oversized)).hasMessageContaining("length");
    }
}
