package com.termux.app.api.file;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
public class FileReceiverUriTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void viewingRawHashFilenameOpensTheRequestedFile() throws Exception {
        File file = temporary.newFile("received#1.txt");
        Files.write(file.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("file://" + file.getAbsolutePath()));

        RecordingReceiver activity = Robolectric.buildActivity(RecordingReceiver.class, intent)
            .create().start().resume().get();

        assertNull(activity.error);
        assertEquals(file.getName(), activity.receivedName);
        assertEquals("payload", activity.receivedText);
    }

    @Test
    public void contentWithoutDisplayNameOrTitleFallsBackToUriBasename() {
        RecordingReceiver activity = receiveContent(null);

        assertNull(activity.error);
        assertEquals("received#1.txt", activity.receivedName);
        assertEquals("payload", activity.receivedText);
    }

    @Test
    public void sharedTitleStillTakesPrecedenceOverUriBasename() {
        RecordingReceiver activity = receiveContent("chosen-name.txt");

        assertNull(activity.error);
        assertEquals("chosen-name.txt", activity.receivedName);
    }

    private static RecordingReceiver receiveContent(String title) {
        RecordingReceiver activity = Robolectric.buildActivity(RecordingReceiver.class).create().get();
        Uri uri = Uri.parse("content://test.files/document/received%231.txt");
        Shadows.shadowOf(activity.getContentResolver()).registerInputStream(uri,
            new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
        activity.handleContentUri(uri, title);
        return activity;
    }

    public static class RecordingReceiver extends FileReceiverActivity {
        String receivedName;
        String receivedText;
        String error;

        @Override
        void promptNameAndSave(InputStream input, String attachmentFileName) {
            receivedName = attachmentFileName;
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[128];
                int count;
                while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
                receivedText = new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        void showErrorDialogAndQuit(String message) {
            error = message;
        }
    }
}
