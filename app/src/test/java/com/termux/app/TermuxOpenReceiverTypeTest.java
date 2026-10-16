package com.termux.app;

import android.app.Application;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TermuxOpenReceiverTypeTest {
    @Before
    public void populatePlatformMimeTypes() {
        Shadows.shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypMapping("txt", "text/plain");
    }

    @Test
    public void emptyProviderPathsHaveNoMimeType() {
        TermuxOpenReceiver.ContentProvider provider = new TermuxOpenReceiver.ContentProvider();
        assertNull(provider.getType(Uri.parse("content://com.termux.files")));
        assertNull(provider.getType(Uri.parse("content://com.termux.files/")));
        assertNull(provider.getType(Uri.parse("content://com.termux.files/?query=value#fragment")));
    }

    @Test
    public void fileExtensionsStillResolveTheirMimeType() {
        TermuxOpenReceiver.ContentProvider provider = new TermuxOpenReceiver.ContentProvider();
        assertEquals("text/plain", provider.getType(Uri.parse("content://com.termux.files/home/README.TXT")));
        assertNull(provider.getType(Uri.parse("content://com.termux.files/home/README")));
    }
}
