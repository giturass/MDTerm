package com.termux.app;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
public class TermuxOpenReceiverUriTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Before
    public void setUpMimeTypes() {
        // Robolectric's MIME map starts empty instead of loading Android's built-in types.
        Shadows.shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypMapping("txt", "text/plain");
    }

    @Test
    public void viewingRawHashFilenameSharesTheWholeEncodedPath() throws Exception {
        File file = temporary.newFile("report#1.txt");

        Intent launched = receive(Intent.ACTION_VIEW, Uri.parse("file://" + file.getAbsolutePath()));

        assertNotNull(launched);
        assertEquals(Intent.ACTION_VIEW, launched.getAction());
        assertSharedPath(file, launched.getData());
    }

    @Test
    public void sendingEncodedFilenameRetainsHashPercentAndSpaces() throws Exception {
        File file = temporary.newFile("report#1 %.txt");

        Intent launched = receive(Intent.ACTION_SEND, Uri.fromFile(file));

        assertNotNull(launched);
        assertEquals(Intent.ACTION_SEND, launched.getAction());
        assertSharedPath(file, launched.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals("text/plain", launched.getType());
    }

    @Test
    public void rawTrailingHashRemainsPartOfTheFilename() throws Exception {
        File file = temporary.newFile("report#");

        Intent launched = receive(Intent.ACTION_VIEW, Uri.parse("file://" + file.getAbsolutePath()));

        assertNotNull(launched);
        assertSharedPath(file, launched.getData());
    }

    @Test
    public void opaqueAndEmptyFilePathsAreIgnoredWithoutCrashing() {
        assertNull(receive(Intent.ACTION_VIEW, Uri.parse("file:opaque")));
        assertNull(receive(Intent.ACTION_VIEW, Uri.parse("file://authority")));
        assertNull(receive(Intent.ACTION_VIEW, Uri.EMPTY));
    }

    @Test
    public void externalOpaqueUriStillOpensWithoutAFilePath() {
        Uri uri = Uri.parse("mailto:person@example.com");

        Intent launched = receive(Intent.ACTION_VIEW, uri);

        assertNotNull(launched);
        assertEquals(uri, launched.getData());
    }

    private static Intent receive(String action, Uri uri) {
        Application application = RuntimeEnvironment.getApplication();
        new TermuxOpenReceiver().onReceive(application, new Intent(action, uri));
        return Shadows.shadowOf(application).getNextStartedActivity();
    }

    private static void assertSharedPath(File file, Uri uri) {
        assertNotNull(uri);
        assertEquals("content", uri.getScheme());
        assertEquals(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, uri.getAuthority());
        assertEquals(file.getAbsolutePath(), uri.getPath());
        assertNull(uri.getFragment());
        assertTrue(uri.toString().contains("%23"));
    }
}
