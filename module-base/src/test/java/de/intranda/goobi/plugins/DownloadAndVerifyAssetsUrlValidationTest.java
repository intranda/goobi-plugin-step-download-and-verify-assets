package de.intranda.goobi.plugins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;

import org.junit.Test;

public class DownloadAndVerifyAssetsUrlValidationTest {

    @Test
    public void isSafeUrlRejectsLoopbackAddress() throws IOException {
        assertFalse(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("http://127.0.0.1/file.txt")));
    }

    @Test
    public void isSafeUrlRejectsPrivateClassA() throws IOException {
        assertFalse(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("http://10.0.0.1/file.txt")));
    }

    @Test
    public void isSafeUrlRejectsPrivateClassB() throws IOException {
        assertFalse(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("http://172.16.0.1/file.txt")));
    }

    @Test
    public void isSafeUrlRejectsPrivateClassC() throws IOException {
        assertFalse(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("http://192.168.1.1/file.txt")));
    }

    @Test
    public void isSafeUrlRejectsFileScheme() throws IOException {
        assertFalse(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("file:///etc/passwd")));
    }

    @Test
    public void isSafeUrlAcceptsPublicAddress() throws IOException {
        // 8.8.8.8 ist eine öffentliche IP (Google DNS) – kein DNS-Lookup nötig
        assertTrue(DownloadAndVerifyAssetsStepPlugin.isSafeUrl(new URL("http://8.8.8.8/file.txt")));
    }
}
