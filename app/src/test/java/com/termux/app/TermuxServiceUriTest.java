package com.termux.app;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class,
    shadows = TermuxServiceUriTest.RecordingTaskService.class)
public class TermuxServiceUriTest {

    @Test
    public void executeIntentIncludesRawHashInExecutablePath() {
        String path = "/data/data/com.termux/files/home/te#st.sh";
        ExecutionCommand command = execute(Uri.parse("file://" + path));

        assertEquals(path, command.executable);
        assertArrayEquals(new String[]{"argument"}, command.arguments);
    }

    @Test
    public void executeIntentDoesNotDuplicateEncodedHash() {
        String path = "/data/data/com.termux/files/home/te#st.sh";

        assertEquals(path, execute(Uri.fromFile(new File(path))).executable);
    }

    private static ExecutionCommand execute(Uri uri) {
        TermuxService service = Robolectric.buildService(TermuxService.class).get();
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, uri)
            .putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true)
            .putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"argument"});
        ReflectionHelpers.callInstanceMethod(service, "actionServiceExecute", ClassParameter.from(Intent.class, intent));
        RecordingTaskService shadow = Shadow.extract(service);
        assertNotNull(shadow.command);
        return shadow.command;
    }

    @Implements(TermuxService.class)
    public static class RecordingTaskService {
        ExecutionCommand command;

        @Implementation
        protected TermuxTask createTermuxTask(ExecutionCommand command) {
            // Capture intent parsing before starting any real process or inspecting device state.
            this.command = command;
            return null;
        }
    }
}
