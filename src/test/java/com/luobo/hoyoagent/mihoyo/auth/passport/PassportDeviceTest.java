package com.luobo.hoyoagent.mihoyo.auth.passport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PassportDeviceTest {

    @TempDir
    Path dir;

    @Test
    void generatesExpectedShape() {
        PassportDevice device = new PassportDevice(dir.resolve("passport-device"));

        assertThat(device.deviceId()).matches("[0-9A-F]{16}");
        assertThat(device.deviceFingerprint()).matches("[0-9a-f]{32}");
    }

    @Test
    void reloadsSameIdentity() {
        Path file = dir.resolve("passport-device");
        PassportDevice first = new PassportDevice(file);
        PassportDevice second = new PassportDevice(file);

        // device_id 变了，下一次 queryQRLoginStatus 会被 -3503 打回，所以必须稳定
        assertThat(second.deviceId()).isEqualTo(first.deviceId());
        assertThat(second.deviceFingerprint()).isEqualTo(first.deviceFingerprint());
    }

    @Test
    void regeneratesWhenFileIsCorrupt() {
        Path file = dir.resolve("passport-device");
        try {
            java.nio.file.Files.writeString(file, "device_id=not-hex\ndevice_fp=zzz\n");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        PassportDevice device = new PassportDevice(file);
        assertThat(device.deviceId()).matches("[0-9A-F]{16}");
    }
}
