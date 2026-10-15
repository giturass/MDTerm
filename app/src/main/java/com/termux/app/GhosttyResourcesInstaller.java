package com.termux.app;

import android.content.Context;
import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.GhosttyTerminfo;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;

final class GhosttyResourcesInstaller {

    private static final String LOG_TAG = "GhosttyResourcesInstaller";

    static final String ASSET_PATH = "ghostty/terminfo/x/xterm-ghostty";

    private GhosttyResourcesInstaller() {
    }

    static synchronized boolean installIfNeeded(Context context) {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        if (!prefix.isDirectory())
            return false;

        boolean terminfo = install(context, new File(GhosttyTerminfo.TERMINFO_FILE_PATH));
        boolean integration = installShellIntegration(context, prefix);
        return terminfo && integration;
    }

    static synchronized boolean install(Context context, File destination) {
        // Retain an existing terminfo entry if an update cannot be installed.
        return installAsset(context, ASSET_PATH, destination) || destination.isFile();
    }

    static synchronized boolean installShellIntegration(Context context, File prefix) {
        File resources = new File(prefix, "share/ghostty/shell-integration");
        boolean installed = true;
        for (String path : new String[]{
            "bash/ghostty.bash", "bash/bash-preexec.sh",
            "zsh/zshenv", "zsh/ghostty-integration",
            "fish/vendor_conf.d/ghostty-shell-integration.fish",
            "elvish/lib/ghostty-integration.elv",
            "nushell/vendor/autoload/ghostty.nu"}) {
            String destination = path.equals("zsh/zshenv") ? "zsh/.zshenv" : path;
            installed &= installAsset(context, "ghostty/shell-integration/" + path,
                new File(resources, destination));
        }
        // Do not enable loaders that could source an incomplete first installation.
        if (!installed)
            return false;

        installed &= installAsset(context, "ghostty/shell-integration/termux-ghostty.fish",
            new File(prefix, "etc/fish/conf.d/termux-ghostty.fish"));
        installed &= installAsset(context, "ghostty/shell-integration/elvish/lib/ghostty-integration.elv",
            new File(prefix, "share/elvish/lib/ghostty-integration.elv"));
        // Nushell already emits OSC 133 natively. Its upstream module only adds
        // desktop CLI wrappers, so keep it available without importing those wrappers.
        installed &= installRcLoader(new File(prefix, "etc/bash.bashrc"), "bash/ghostty.bash");
        installed &= installRcLoader(new File(prefix, "etc/zshrc"), "zsh/ghostty-integration");
        return installed;
    }

    static synchronized boolean installRcLoader(File destination, String loader) {
        // Do not preempt an absent shell package's conffile or replace custom rc
        // symlinks, whose targets may be user-managed files under HOME.
        if (!Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS))
            return true;

        File temporary = null;
        try {
            String hook = "\n# Termux Ghostty shell integration (managed by the app).\n" +
                "if [ -n \"${GHOSTTY_RESOURCES_DIR-}\" ] &&\n" +
                "   [ -r \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + loader + "\" ]; then\n" +
                "    . \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + loader + "\"\nfi\n";
            byte[] existing = Files.readAllBytes(destination.toPath());
            if (new String(existing, StandardCharsets.ISO_8859_1).contains(hook))
                return true;

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(destination.toPath());
            temporary = File.createTempFile(destination.getName() + "-ghostty-", ".tmp",
                destination.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(existing);
                output.write(hook.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.setPosixFilePermissions(temporary.toPath(), permissions);
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to install system rc loader at " + destination, e);
            return false;
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private static boolean installAsset(Context context, String assetPath, File destination) {
        File parent = destination.getParentFile();
        File temporary = null;

        try {
            byte[] assetDigest;
            try (InputStream input = context.getAssets().open(assetPath)) {
                assetDigest = digest(input);
            }

            if (destination.isFile()) {
                try (InputStream input = new FileInputStream(destination)) {
                    if (Arrays.equals(assetDigest, digest(input)))
                        return true;
                }
            }

            if (!parent.isDirectory() && !parent.mkdirs())
                throw new IllegalStateException("Failed to create " + parent);

            temporary = File.createTempFile(destination.getName() + "-ghostty-", ".tmp", parent);
            try (InputStream input = context.getAssets().open(assetPath);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1)
                    output.write(buffer, 0, count);
                output.getFD().sync();
            }

            //noinspection OctalInteger
            Os.chmod(temporary.getAbsolutePath(), 0600);
            Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

            Logger.logInfo(LOG_TAG, "Installed " + assetPath + " at \"" +
                destination + "\".");
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Failed to install " + assetPath, e);
            return false;
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private static byte[] digest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1)
            digest.update(buffer, 0, count);
        return digest.digest();
    }
}
