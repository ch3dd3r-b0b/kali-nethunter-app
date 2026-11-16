
package com.offsec.nethunter;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SharkHunterManager {

    private static final String TAG = "SharkHunterManager";

    public interface Listener {
        void onStatus(String status);
        void onPackets(List<SharkHunterPacket> packets);
        void onError(String error);
        void onCaptureStopped();
    }

    public interface ResultCallback {
        void onResult(String result);
        void onError(String error);
    }

    private static volatile SharkHunterManager instance;

    private final Context context;
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newCachedThreadPool();

    private final AtomicBoolean capturing =
            new AtomicBoolean(false);

    private final AtomicBoolean stopRequested =
            new AtomicBoolean(false);

    private volatile Listener listener;
    private volatile Process captureProcess;

    private volatile String captureFile;
    private volatile String captureInterface;
    private volatile String captureFilter;
    private volatile String capturePidFile;


    private volatile long captureGeneration = 0;

    private final ReentrantReadWriteLock fileLock =
            new ReentrantReadWriteLock(true);

    private static boolean isLiveTemporaryCapture(String path) {
        return path != null && path.matches(
                "^/tmp/sharkhunter_[0-9]+_[0-9]+\\.pcapng$");
    }

    public boolean isUnsavedLiveCapture() {
        return !capturing.get()
                && isLiveTemporaryCapture(captureFile);
    }

    public void discardCapture(
            String path, ResultCallback callback) {

        executor.execute(() -> {
            fileLock.writeLock().lock();
            try {
                if (!isLiveTemporaryCapture(path)) {
                    throw new IOException(
                            "Not a temporary live capture.");
                }

                synchronized (SharkHunterManager.this) {
                    if (capturing.get()
                            || !path.equals(captureFile)) {
                        throw new IOException(
                                "Capture is active or has changed.");
                    }

                    run("rm -f -- " + quote(path));
                    captureFile = null;
                    capturePidFile = null;
                }

                success(callback, "Capture discarded");
            } catch (Exception e) {
                failure(callback, e);
            } finally {
                fileLock.writeLock().unlock();
            }
        });
    }


    private SharkHunterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static SharkHunterManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SharkHunterManager.class) {
                if (instance == null) {
                    instance = new SharkHunterManager(context);
                }
            }
        }
        return instance;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isCapturing() {
        return capturing.get();
    }

    public String getCaptureFile() {
        return captureFile;
    }

    public String getCaptureInterface() {
        return captureInterface;
    }

    public String getCaptureFilter() {
        return captureFilter;
    }

    private static String quote(String value) {
        return ShellExecuter.shellEscape(value);
    }

    private String buildChrootCommand(String command) {
        return quote(NhPaths.BUSYBOX)
                + " chroot "
                + quote(NhPaths.CHROOT_PATH())
                + " "
                + quote(NhPaths.CHROOT_SUDO)
                + " -E PATH=/usr/local/sbin:/usr/local/bin:"
                + "/usr/sbin:/usr/bin:/sbin:/bin "
                + "su -c "
                + quote("export WIRESHARK_RUN_FROM_ROOT=1; " + command);
    }

    private Process startChroot(String command)
            throws IOException {

        ProcessBuilder builder =
                new ProcessBuilder("su", "-mm");

        builder.redirectErrorStream(true);

        Process process = builder.start();

        try (Writer writer = new OutputStreamWriter(
                process.getOutputStream(),
                StandardCharsets.UTF_8)) {

            writer.write(buildChrootCommand(command));
            writer.write("\nexit\n");
            writer.flush();
        }

        return process;
    }

    private String run(String command) throws Exception {
        Process process = startChroot(command);

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int exit = process.waitFor();

        if (exit != 0) {
            throw new IOException(
                    "Command exited with code "
                            + exit + ": " + output.toString().trim()
            );
        }

        return output.toString();
    }

    private void postStatus(String message) {
        Listener target = listener;

        if (target != null) {
            mainHandler.post(() -> {
                if (listener == target) {
                    target.onStatus(message);
                }
            });
        }
    }

    private void postError(String message) {
        Listener target = listener;

        if (target != null) {
            mainHandler.post(() -> {
                if (listener == target) {
                    target.onError(message);
                }
            });
        }
    }

    private void postPackets(List<SharkHunterPacket> packets) {
        Listener target = listener;

        if (target != null) {
            mainHandler.post(() -> {
                if (listener == target) {
                    target.onPackets(packets);
                }
            });
        }
    }

    private void postStopped() {
        Listener target = listener;

        if (target != null) {
            mainHandler.post(() -> {
                if (listener == target) {
                    target.onCaptureStopped();
                }
            });
        }
    }

    private void success(ResultCallback callback, String result) {
        mainHandler.post(() -> callback.onResult(result));
    }

    private void failure(ResultCallback callback, Exception error) {
        String message = error.getMessage();

        mainHandler.post(() ->
                callback.onError(
                        message == null ? "Unknown error" : message
                )
        );
    }

    private void runAsync(
            String command,
            ResultCallback callback) {

        executor.execute(() -> {
            fileLock.readLock().lock();
            try {
                success(callback, run(command));
            } catch (Exception e) {
                failure(callback, e);
            } finally {
                fileLock.readLock().unlock();
            }
        });
    }

    public void checkDependencies(ResultCallback callback) {
        runAsync(
                "command -v tshark >/dev/null && "
                        + "command -v dumpcap >/dev/null && "
                        + "command -v capinfos >/dev/null && "
                        + "command -v setsid >/dev/null && "
                        + "printf 'SharkHunter dependencies ready\\n'",
                callback
        );
    }

    public void getInterfaces(ResultCallback callback) {
        runAsync("tshark -D", callback);
    }

    public synchronized void startCapture(
            String networkInterface,
            String bpfFilter) {

        if (networkInterface == null
                || networkInterface.trim().isEmpty()) {

            postError("Select a network interface.");
            return;
        }

        if (isUnsavedLiveCapture()) {
            postError(
                    "Save or discard the previous capture first.");
            return;
        }

        if (!capturing.compareAndSet(false, true)) {
            postError("A capture is already running.");
            return;
        }

        stopRequested.set(false);

        captureInterface = networkInterface.trim();
        captureFilter =
                bpfFilter == null ? "" : bpfFilter.trim();

        long generation = ++captureGeneration;

        String token =
                System.currentTimeMillis() + "_"
                        + generation;

        captureFile =
                "/tmp/sharkhunter_" + token + ".pcapng";

        capturePidFile =
                "/tmp/sharkhunter_" + token + ".pid";

        final String file = captureFile;
        final String pidFile = capturePidFile;
        final String iface = captureInterface;
        final String filter = captureFilter;

        executor.execute(() ->
                runCapture(generation, file, pidFile, iface, filter)
        );
    }

    private void runCapture(
            long generation,
            String file,
            String pidFile,
            String iface,
            String filter) {

        Process current = null;

        try {
            StringBuilder command = new StringBuilder();

            command.append("tshark -i ")
                    .append(quote(iface))
                    .append(" -n -w ")
                    .append(quote(file));

            if (!filter.isEmpty()) {
                command.append(" -f ")
                        .append(quote(filter));
            }

            String job = "exec " + command;

            String wrapped =
                    "setsid sh -c "
                            + quote(job)
                            + " & child=$!; "
                            + "printf '%s\\n' \"$child\" > "
                            + quote(pidFile)
                            + "; "
                            + "wait \"$child\"; result=$?; "
                            + "rm -f "
                            + quote(pidFile)
                            + "; "
                            + "exit \"$result\"";

            postStatus("Starting capture on " + iface);

            current = startChroot(wrapped);
            captureProcess = current;

            if (stopRequested.get()) {
                requestTermination(pidFile, generation);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            current.getInputStream(),
                            StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();

                    if (trimmed.contains("Capturing on")) {
                        postStatus(trimmed);

                    } else if (
                            trimmed.toLowerCase(Locale.ROOT)
                                    .contains("error")) {

                        postError(trimmed);
                    }

                    Log.d(TAG, trimmed);
                }
            }

            int exit = current.waitFor();

            if (!stopRequested.get() && exit != 0) {
                postError(
                        "Capture exited with code " + exit
                );
            }

        } catch (Exception e) {

            if (!stopRequested.get()) {
                postError(
                        "Capture failed: " + e.getMessage()
                );
            }

            Log.e(TAG, "Capture failure", e);

        } finally {

            if (generation == captureGeneration) {
                captureProcess = null;
                capturing.set(false);
                stopRequested.set(false);

                postStatus("Capture stopped");
                postStopped();
            }
        }
    }

    public void stopCapture() {

        if (!capturing.get()) {
            return;
        }

        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }

        String pidFile = capturePidFile;
        long generation = captureGeneration;

        postStatus("Stopping capture...");

        if (pidFile != null) {
            requestTermination(pidFile, generation);
        }
    }

    private void requestTermination(
            String pidFile,
            long generation) {

        executor.execute(() -> {

            for (int attempt = 0; attempt < 50; attempt++) {

                if (!capturing.get()
                        || generation != captureGeneration) {
                    return;
                }

                String command =
                        "if [ ! -f "
                                + quote(pidFile)
                                + " ]; then exit 3; fi; "
                                + "pid=$(cat "
                                + quote(pidFile)
                                + "); "
                                + "case \"$pid\" in "
                                + "''|*[!0-9]*) exit 2 ;; "
                                + "esac; "
                                + "pgid=$(ps -o pgid= -p \"$pid\" "
                                + "2>/dev/null | tr -d ' '); "
                                + "[ \"$pgid\" = \"$pid\" ] || exit 4; "
                                + "kill -INT -- \"-$pgid\" "
                                + "2>/dev/null";

                try {
                    ShellExecuter.ShellResult result =
                            new ShellExecuter()
                                    .RunAsChrootWithResult(command);

                    if (result.exitCode == 0) {
                        Log.i(
                                TAG,
                                "SIGINT sent to capture process group"
                        );
                        return;
                    }

                } catch (Exception e) {
                    Log.w(
                            TAG,
                            "Capture stop attempt failed",
                            e
                    );
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            Log.w(
                    TAG,
                    "Could not confirm capture termination"
            );

            postError(
                    "Unable to stop capture automatically."
            );
        });
    }

    public void loadPackets(
            String pcapFile,
            String displayFilter) {

        if (pcapFile == null || pcapFile.isEmpty()) {
            postError("No capture file selected.");
            return;
        }

        executor.execute(() -> {
            fileLock.readLock().lock();
            try {
                StringBuilder command = new StringBuilder();

                command.append("tshark -r ")
                        .append(quote(pcapFile))
                        .append(" -n -T fields")
                        .append(" -E separator=/t")
                        .append(" -E quote=n")
                        .append(" -e frame.number")
                        .append(" -e frame.time_relative")
                        .append(" -e _ws.col.Source")
                        .append(" -e _ws.col.Destination")
                        .append(" -e _ws.col.Protocol")
                        .append(" -e frame.len")
                        .append(" -e _ws.col.Info");

                if (displayFilter != null
                        && !displayFilter.trim().isEmpty()) {

                    command.append(" -Y ")
                            .append(quote(displayFilter.trim()));
                }

                String output = run(command.toString());

                postPackets(parsePackets(output));

            } catch (Exception e) {
                postError(
                        "Packet loading failed: " + e.getMessage()
                );
            } finally {
                fileLock.readLock().unlock();
            }
        });
    }

    private List<SharkHunterPacket> parsePackets(
            String output) {

        List<SharkHunterPacket> result =
                new ArrayList<>();

        if (output == null || output.isEmpty()) {
            return result;
        }

        String[] lines = output.split("\\r?\\n");

        for (String line : lines) {
            String[] fields = line.split("\\t", 7);

            if (fields.length < 7) {
                continue;
            }

            try {
                result.add(new SharkHunterPacket(
                        Integer.parseInt(fields[0].trim()),
                        fields[1],
                        fields[2],
                        fields[3],
                        fields[4],
                        Integer.parseInt(fields[5].trim()),
                        fields[6]
                ));

            } catch (NumberFormatException ignored) {
                Log.w(TAG, "Skipping malformed packet");
            }
        }

        return result;
    }

    public void getPacketDetails(
            String file,
            int number,
            ResultCallback callback) {

        runAsync(
                "tshark -r " + quote(file)
                        + " -n -Y "
                        + quote("frame.number == " + number)
                        + " -V",
                callback
        );
    }

    public void getPacketHex(
            String file,
            int number,
            ResultCallback callback) {

        runAsync(
                "tshark -r " + quote(file)
                        + " -n -Y "
                        + quote("frame.number == " + number)
                        + " -x",
                callback
        );
    }

    public void getStreamIndex(
            String file,
            int number,
            String protocol,
            ResultCallback callback) {

        String field =
                "udp".equalsIgnoreCase(protocol)
                        ? "udp.stream"
                        : "tcp.stream";

        runAsync(
                "tshark -r " + quote(file)
                        + " -n -Y "
                        + quote("frame.number == " + number)
                        + " -T fields -e " + field,
                callback
        );
    }

    public void followStream(
            String file,
            String protocol,
            int streamNumber,
            ResultCallback callback) {

        String type =
                "udp".equalsIgnoreCase(protocol)
                        ? "udp"
                        : "tcp";

        runAsync(
                "tshark -r " + quote(file)
                        + " -q -z "
                        + quote(
                                "follow," + type
                                        + ",raw," + streamNumber
                        ),
                callback
        );
    }

    public void validateDisplayFilter(
            String filter,
            ResultCallback callback) {

        if (filter == null || filter.trim().isEmpty()) {
            success(callback, "Valid");
            return;
        }

        executor.execute(() -> {
            try {
                String command =
                        "tmp=$(mktemp /tmp/sharkhunter_filter_XXXXXX); "
                                + "printf '\\324\\303\\262\\241"
                                + "\\002\\000\\004\\000"
                                + "\\000\\000\\000\\000"
                                + "\\000\\000\\000\\000"
                                + "\\377\\377\\000\\000"
                                + "\\001\\000\\000\\000' > \"$tmp\"; "
                                + "tshark -r \"$tmp\" -Y "
                                + quote(filter.trim())
                                + " -q; "
                                + "rc=$?; "
                                + "rm -f \"$tmp\"; "
                                + "exit \"$rc\"";

                run(command);
                success(callback, "Valid");

            } catch (Exception e) {
                failure(callback, e);
            }
        });
    }

    public void getCaptureInfo(
            String file,
            ResultCallback callback) {

        runAsync(
                "capinfos " + quote(file),
                callback
        );
    }

    private void rootCopy(
            String source,
            String destination) throws Exception {

        Process process = new ProcessBuilder(
                "su",
                "-mm"
        ).redirectErrorStream(true).start();

        try (Writer writer = new OutputStreamWriter(
                process.getOutputStream(),
                StandardCharsets.UTF_8)) {

            writer.write(
                    "cp " + quote(source)
                            + " " + quote(destination)
                            + "\nexit\n"
            );
            writer.flush();
        }

        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        if (process.waitFor() != 0) {
            throw new IOException(
                    "File copy failed: " + output
            );
        }
    }

    public void importCapture(
            Uri uri,
            ResultCallback callback) {

        executor.execute(() -> {

            File temporary = null;

            try {
                temporary = File.createTempFile(
                        "sharkhunter_import_",
                        ".pcapng",
                        context.getCacheDir()
                );

                try (InputStream input =
                             context.getContentResolver()
                                     .openInputStream(uri);
                     OutputStream output =
                             new FileOutputStream(temporary)) {

                    if (input == null) {
                        throw new IOException(
                                "Unable to read selected file."
                        );
                    }

                    byte[] buffer = new byte[65536];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }

                String destination =
                        "/tmp/sharkhunter_import_"
                                + System.currentTimeMillis()
                                + ".pcapng";

                rootCopy(
                        temporary.getAbsolutePath(),
                        NhPaths.CHROOT_PATH() + destination
                );

                // Verify the imported capture is readable.
                run("capinfos " + quote(destination));

                captureFile = destination;

                success(callback, destination);

            } catch (Exception e) {
                failure(callback, e);

            } finally {
                if (temporary != null) {
                    temporary.delete();
                }
            }
        });
    }

    public void exportCapture(
            Uri uri,
            ResultCallback callback) {

        exportCapture(captureFile, uri, callback);
    }

    public void exportCapture(
            String sourceFile,
            Uri uri,
            ResultCallback callback) {

        executor.execute(() -> {
            fileLock.readLock().lock();
            File temporary = null;

            try {
                if (sourceFile == null
                        || sourceFile.isEmpty()) {

                    throw new IOException(
                            "No capture file selected."
                    );
                }

                temporary = File.createTempFile(
                        "sharkhunter_export_",
                        ".pcapng",
                        context.getCacheDir()
                );

                rootCopy(
                        NhPaths.CHROOT_PATH() + sourceFile,
                        temporary.getAbsolutePath()
                );

                try (InputStream input =
                             new FileInputStream(temporary);
                     OutputStream output =
                             context.getContentResolver()
                                     .openOutputStream(uri)) {

                    if (output == null) {
                        throw new IOException(
                                "Unable to open destination."
                        );
                    }

                    byte[] buffer = new byte[65536];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }

                success(callback, "Capture saved");

            } catch (Exception e) {
                failure(callback, e);

            } finally {
                if (temporary != null) {
                    temporary.delete();
                }
                fileLock.readLock().unlock();
            }
        });
    }

    public void shutdown() {
        stopCapture();
        executor.shutdown();
    }
}
