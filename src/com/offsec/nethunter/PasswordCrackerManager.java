package com.offsec.nethunter;

import android.content.Context;
import android.util.Log;

import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PasswordCrackerManager {

    private static final String TAG = "PasswordCrackerManager";
    private static final String SESSION_PATTERN = "[A-Za-z0-9_-]{1,64}";
    private static final String WORK_DIR = "/root/password-cracker/sessions";

    private static PasswordCrackerManager instance;

    public interface Listener {
        void onOutput(String line);
        void onFinished(int exitCode);
        void onError(String message);
    }

    private final File logDirectory;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Listener listener;
    private volatile Process process;
    private volatile String activeSession;
    private volatile String activeCommand;
    private volatile String activeToken;
    private volatile boolean stopRequested;
    private volatile boolean stopping;

    private PasswordCrackerManager(Context context) {
        Context appContext = context.getApplicationContext();
        NhPaths.getInstance(appContext);

        logDirectory = new File(
                appContext.getFilesDir(),
                "password_cracker_logs"
        );

        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            Log.w(TAG, "Unable to create log directory");
        }
    }

    public static synchronized PasswordCrackerManager getInstance(
            Context context) {

        if (instance == null) {
            instance = new PasswordCrackerManager(
                    context.getApplicationContext()
            );
        }

        return instance;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isStopping() {
        return stopping;
    }

    public String getActiveSession() {
        return activeSession;
    }

    public String getActiveCommand() {
        return activeCommand;
    }

    public void setListener(Listener callback) {
        listener = callback;
    }

    public synchronized void start(
            String command,
            String session,
            Listener callback) {

        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Command cannot be empty."
            );
        }

        validateSession(session);

        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "A Password Cracker job is already running."
            );
        }

        activeSession = session;
        activeCommand = command;
        activeToken = UUID.randomUUID().toString();

        stopRequested = false;
        stopping = false;
        listener = callback;

        final String token = activeToken;

        new Thread(
                () -> execute(command, session, token),
                "PasswordCracker-" + session
        ).start();
    }

    private void execute(
            String command,
            String session,
            String token) {

        int exitCode = -1;
        String failure = null;

        String pidFile =
                "/tmp/nethunter-pc-" + token + ".pid";

        String marker =
                "__PC_EXIT_" + token.replace("-", "") + "__:";

        Process current = null;

        try (FileWriter log = new FileWriter(
                getLogFile(session), true)) {

            if (NhPaths.BUSYBOX == null ||
                    NhPaths.BUSYBOX.isEmpty() ||
                    NhPaths.CHROOT_PATH() == null ||
                    NhPaths.CHROOT_PATH().isEmpty()) {

                throw new IOException(
                        "NetHunter BusyBox or Kali chroot is unavailable."
                );
            }

            emit("========================================", log);
            emit("Session: " + session, log);
            emit("Starting job...", log);

            /*
             * Use the same working directory for
             * initial cracking jobs and restores.
             */
            String job =
                    "mkdir -p " +
                    ShellExecuter.shellEscape(WORK_DIR) +
                    " && cd " +
                    ShellExecuter.shellEscape(WORK_DIR) +
                    " && " + command;

            /*
             * Launch the job in its own process group
             * and record its PID for Stop.
             */
            String wrapped =
                    "setsid sh -c " +
                    ShellExecuter.shellEscape(job) +
                    " & child=$!; " +

                    "printf '%s\\n' \"$child\" > " +
                    ShellExecuter.shellEscape(pidFile) +
                    "; " +

                    "wait \"$child\"; result=$?; " +

                    "rm -f " +
                    ShellExecuter.shellEscape(pidFile) +
                    "; " +

                    "printf '\\n" + marker +
                    "%s\\n' \"$result\"";

            ProcessBuilder builder =
                    new ProcessBuilder("su", "-mm");

            builder.redirectErrorStream(true);

            current = builder.start();
            process = current;

            try (Writer writer = new OutputStreamWriter(
                    current.getOutputStream(),
                    StandardCharsets.UTF_8)) {

                writer.write(buildChrootCommand(wrapped));
                writer.write("\nexit\n");
                writer.flush();
            }

            if (stopRequested) {
                requestTermination(pidFile);
            }

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    current.getInputStream(),
                                    StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {

                    String trimmed = line.trim();

                    if (trimmed.startsWith(marker)) {

                        try {
                            exitCode = Integer.parseInt(
                                    trimmed.substring(
                                            marker.length()
                                    ).trim()
                            );
                        } catch (NumberFormatException ignored) {
                            exitCode = -1;
                        }

                    } else {
                        emit(line, log);
                    }
                }
            }

            int shellExit = current.waitFor();

            if (exitCode == -1) {
                emit(
                        "Command exit status unavailable. " +
                        "Shell exit: " + shellExit,
                        log
                );
            }

            if (stopRequested) {
                emit("Stop requested by user.", log);
            }

            emit("Exit code: " + exitCode, log);
            emit("========================================", log);

        } catch (IOException e) {

            failure = "Execution error: " + e.getMessage();
            Log.e(TAG, failure, e);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            failure = "Execution interrupted.";
            Log.e(TAG, failure, e);

        } finally {

            if (current != null) {
                current.destroy();
            }

            process = null;

            synchronized (this) {
                running.set(false);
                stopping = false;
                stopRequested = false;
            }

            Listener callback = listener;

            if (callback != null) {
                try {
                    if (failure != null) {
                        callback.onError(failure);
                    } else {
                        callback.onFinished(exitCode);
                    }
                } catch (RuntimeException e) {
                    Log.e(
                            TAG,
                            "Completion callback failed",
                            e
                    );
                }
            }
        }
    }

    private String buildChrootCommand(String command) {

        return ShellExecuter.shellEscape(
                NhPaths.BUSYBOX
        ) +
                " chroot " +
                ShellExecuter.shellEscape(
                        NhPaths.CHROOT_PATH()
                ) +
                " " +
                ShellExecuter.shellEscape(
                        NhPaths.CHROOT_SUDO
                ) +
                " -E PATH=/usr/local/sbin:/usr/local/bin:" +
                "/usr/sbin:/usr/bin:/sbin:/bin " +
                "su -c " +
                ShellExecuter.shellEscape(command);
    }

    private void emit(
            String line,
            FileWriter log) throws IOException {

        log.write(line);
        log.write("\n");
        log.flush();

        Listener callback = listener;

        if (callback != null) {
            try {
                callback.onOutput(line);
            } catch (RuntimeException e) {
                Log.e(
                        TAG,
                        "Output callback failed",
                        e
                );
            }
        }
    }

    public synchronized void stop() {

        if (!running.get() || stopping) {
            return;
        }

        stopRequested = true;
        stopping = true;

        String token = activeToken;

        if (token != null) {
            requestTermination(
                    "/tmp/nethunter-pc-" + token + ".pid"
            );
        }
    }

    private void requestTermination(String pidFile) {

        new Thread(() -> {

            for (int attempt = 0; attempt < 50; attempt++) {

                if (!running.get()) {
                    return;
                }

                String command =
                        "if [ ! -f " +
                        ShellExecuter.shellEscape(pidFile) +
                        " ]; then exit 3; fi; " +

                        "pid=$(cat " +
                        ShellExecuter.shellEscape(pidFile) +
                        "); " +

                        "case \"$pid\" in " +
                        "''|*[!0-9]*) exit 2 ;; " +
                        "esac; " +

                        "pgid=$(ps -o pgid= -p \"$pid\" " +
                        "2>/dev/null | tr -d ' '); " +

                        "[ \"$pgid\" = \"$pid\" ] || exit 4; " +

                        "kill -TERM -- \"-$pgid\" " +
                        "2>/dev/null";

                ShellExecuter.ShellResult result =
                        new ShellExecuter()
                                .RunAsChrootWithResult(command);

                if (result.exitCode == 0) {
                    Log.i(
                            TAG,
                            "Termination signal sent"
                    );
                    return;
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
                    "Unable to confirm process termination."
            );

        }, "PasswordCrackerStop").start();
    }

    public File getLogFile(String session) {

        validateSession(session);

        return new File(
                logDirectory,
                session + ".log"
        );
    }

    private static void validateSession(String session) {

        if (session == null ||
                !session.matches(SESSION_PATTERN)) {

            throw new IllegalArgumentException(
                    "Session names must contain 1–64 letters, " +
                    "numbers, underscores or hyphens."
            );
        }
    }
}
