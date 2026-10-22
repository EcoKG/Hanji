package io.hanji.electron;

import io.hanji.HanjiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectronRuntimeTest {

    @Test
    void platformIdMatchesElectronReleaseNames() {
        assertTrue(ElectronRuntime.platform().matches("(linux|darwin|win32)-(x64|arm64)"), ElectronRuntime.platform());
    }

    @Test
    void explicitDistributionFolderIsUsed(@TempDir Path dir) throws IOException {
        Path exe = ElectronRuntime.executableIn(dir);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "");
        HanjiConfig config = new HanjiConfig().setElectronPath(dir.toString()).setCacheDir(dir.resolve("cache"));
        assertEquals(exe, ElectronRuntime.findInstalled(config));
        assertEquals(exe, ElectronRuntime.findInstalled(new HanjiConfig().setElectronPath(exe.toString())));
    }

    @Test
    void missingRuntimeIsNotFoundAndNotDownloadedWhenDisabled(@TempDir Path dir) throws IOException {
        HanjiConfig config = new HanjiConfig().setCacheDir(dir).setElectronDownload(false);
        assertNull(ElectronRuntime.findInstalled(config));
        assertNull(ElectronRuntime.resolve(config));
        assertNull(ElectronRuntime.findInstalled(new HanjiConfig().setElectronPath(dir.resolve("nope").toString())));
    }

    @Test
    void incompleteCacheEntryIsIgnored(@TempDir Path dir) throws IOException {
        HanjiConfig config = new HanjiConfig().setCacheDir(dir);
        Path entry = dir.resolve("electron").resolve(config.getElectronVersion()).resolve(ElectronRuntime.platform());
        Path exe = ElectronRuntime.executableIn(entry);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, ""); // extracted but not marked complete (interrupted install)
        assertNull(ElectronRuntime.findInstalled(config));
    }
}
