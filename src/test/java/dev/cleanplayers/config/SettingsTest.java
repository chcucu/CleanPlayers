package dev.cleanplayers.config;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @Test
    void loadsDefaultsAndNormalizesPrefixes() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("offline-days", 90);
        config.set("check-interval-hours", 0);
        config.set("backup-keep-days", 7);
        config.set("name-prefixes", List.of("X_", "  "));
        config.set("worlds", List.of("world", "world_nether"));
        config.set("io-threads", 99);

        Settings settings = Settings.load(config);
        assertEquals(90, settings.offlineDays());
        assertEquals(0, settings.checkIntervalHours());
        assertEquals(7, settings.backupKeepDays());
        assertEquals(List.of("x_"), settings.namePrefixes());
        assertEquals(2, settings.worlds().size());
        assertEquals(8, settings.ioThreads());
        assertTrue(settings.deleteRelatedData());
        assertFalse(settings.purgeUsercacheOrphans());
    }

    @Test
    void rejectsUnsafeWorldNamesAndEmptyWorldList() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("worlds", List.of("../world"));
        assertThrows(IllegalArgumentException.class, () -> Settings.load(config));

        MemoryConfiguration empty = new MemoryConfiguration();
        assertThrows(IllegalArgumentException.class, () -> Settings.load(empty));
    }

    @Test
    void clampsNegativeValues() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("offline-days", -5);
        config.set("backup-keep-days", -1);
        config.set("active-guard-minutes", -10);
        config.set("worlds", List.of("world"));
        Settings settings = Settings.load(config);
        assertEquals(0, settings.offlineDays());
        assertEquals(0, settings.backupKeepDays());
        assertEquals(0, settings.activeGuardMinutes());
    }

    @Test
    void defaultsAreSafeAndUsableWhenConfigIsBroken() {
        Settings settings = Settings.defaults();
        assertEquals(90, settings.offlineDays());
        assertEquals(0, settings.checkIntervalHours());
        assertEquals(List.of("world", "world_nether", "world_the_end"), settings.worlds());
        assertTrue(settings.deleteRelatedData());
        assertTrue(settings.protectWhitelist());
        assertTrue(settings.protectOps());
        assertTrue(settings.protectBanned());
    }
}
