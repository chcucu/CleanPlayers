package dev.cleanplayers.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @TempDir
    Path tempDir;

    @Test
    void offlineDaysUsesWholeDaysAndClampsNegativeValues() {
        long now = 1_700_000_000_000L;
        assertEquals(0L, TimeUtil.offlineDays(now - 1000L, now));
        assertEquals(1L, TimeUtil.offlineDays(now - TimeUtil.MILLIS_PER_DAY, now));
        assertEquals(90L, TimeUtil.offlineDays(now - 90L * TimeUtil.MILLIS_PER_DAY, now));
        assertEquals(0L, TimeUtil.offlineDays(0L, now));
        assertEquals(0L, TimeUtil.offlineDays(now + 1000L, now));
    }

    @Test
    void runFolderNamesRoundTrip() {
        LocalDateTime parsed = TimeUtil.parseRunId("2026-10-31_18-30-00");
        assertEquals(2026, parsed.getYear());
        assertEquals(10, parsed.getMonthValue());
        assertEquals(31, parsed.getDayOfMonth());
        assertEquals(18, parsed.getHour());
        assertNull(TimeUtil.parseRunId("not-a-run"));
        assertEquals("2026-10-31", TimeUtil.DATE_FORMAT.format(parsed.toLocalDate()));
    }

    @Test
    void uuidValidationIsStrict() {
        assertTrue(UuidUtil.isValid("00000000-0000-0000-0000-000000000001"));
        assertTrue(UuidUtil.isValid("00000000-0000-0000-0000-00000000000A"));
        assertFalse(UuidUtil.isValid("00000000000000000000000000000001"));
        assertFalse(UuidUtil.isValid("00000000-0000-0000-0000-00000000000"));
        assertFalse(UuidUtil.isValid("../../etc/passwd"));
        assertEquals("00000000-0000-0000-0000-000000000001",
                UuidUtil.parse("00000000-0000-0000-0000-000000000001").orElseThrow().toString());
        assertFalse(UuidUtil.parseFileName("not-a-uuid.dat", ".dat").isPresent());
    }

    @Test
    void ipDetectionNeverResolvesHostnames() {
        assertTrue(IpUtil.isIp("1.2.3.4"));
        assertTrue(IpUtil.isIp("::1"));
        assertTrue(IpUtil.isIp("2001:db8::1"));
        assertFalse(IpUtil.isIp("localhost"));
        assertFalse(IpUtil.isIp("example.com"));
        assertFalse(IpUtil.isIp("999.1.1.1"));
        assertFalse(IpUtil.isIp("Steve"));
        assertEquals("1.2.3.4", IpUtil.normalize("1.2.3.4"));
        assertNull(IpUtil.normalize("player"));
    }

    @Test
    void ipProtectionMatchesCanonicalForms() {
        var protectedIps = java.util.Set.of("1.2.3.4");
        assertTrue(IpUtil.isIpProtected(java.util.List.of("1.2.3.4"), protectedIps));
        assertFalse(IpUtil.isIpProtected(java.util.List.of("1.2.3.5"), protectedIps));
        assertFalse(IpUtil.isIpProtected(java.util.List.of(), protectedIps));
    }

    @Test
    void worldNamesCannotEscapeTheServerRoot() {
        assertTrue(FileUtil.isSafeWorldName("world"));
        assertTrue(FileUtil.isSafeWorldName("world_nether"));
        assertFalse(FileUtil.isSafeWorldName("../world"));
        assertFalse(FileUtil.isSafeWorldName("/etc"));
        assertFalse(FileUtil.isSafeWorldName(".."));
        assertFalse(FileUtil.isSafeWorldName(""));
    }

    @Test
    void copyVerifiedKeepsByteIdenticalContentAndDetectsTampering() throws Exception {
        Path source = tempDir.resolve("source.dat");
        Files.writeString(source, "playerdata-payload");
        Path target = tempDir.resolve("target.dat");
        assertEquals(18L, FileUtil.copyVerified(source, target));
        assertEquals(FileUtil.sha256(source), FileUtil.sha256(target));

        Files.writeString(target, "corrupted!!");
        assertFalse(FileUtil.sha256(source).equals(FileUtil.sha256(target)));
    }
}
