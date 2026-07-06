package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

public class DownloadAndVerifyAssetsFilenameTest {

    private static Header[] headers(String... values) {
        Header[] result = new Header[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = new BasicHeader("Content-Disposition", values[i]);
        }
        return result;
    }

    @Test
    public void extractsQuotedFilename() {
        String fileName = DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(headers("attachment; filename=\"report.pdf\""));
        assertEquals("report.pdf", fileName);
    }

    @Test
    public void extractsUnquotedFilename() {
        String fileName = DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(headers("attachment; filename=report.pdf"));
        assertEquals("report.pdf", fileName);
    }

    @Test
    public void prefersFilenameStarOverPlainFilename() {
        String fileName = DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(
                headers("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"));
        assertEquals("résumé.pdf", fileName);
    }

    @Test
    public void sanitizesPathTraversalAttempt() {
        String fileName =
                DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(headers("attachment; filename=\"../../etc/passwd\""));
        assertEquals("passwd", fileName);
    }

    @Test
    public void returnsNullWhenNoContentDispositionHeaders() {
        String fileName = DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(headers());
        assertNull(fileName);
    }

    @Test
    public void returnsNullWhenHeaderHasNoFilenameParam() {
        String fileName = DownloadAndVerifyAssetsStepPlugin.extractFilenameFromContentDisposition(headers("attachment"));
        assertNull(fileName);
    }
}
