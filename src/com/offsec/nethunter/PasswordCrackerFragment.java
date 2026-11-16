package com.offsec.nethunter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PasswordCrackerFragment extends Fragment {

    private static final String PREFS = "password_cracker";
    private static final String PREF_SETUP_COMPLETE = "setup_complete";

    private static final String KALI_IMPORT_DIR =
            "/root/password-cracker/imports";

    private static final String KALI_TERMINAL =
            "/data/data/com.offsec.nhterm/files/usr/bin/kali";

    private static final int FILE_HASH = 1;
    private static final int FILE_WORDLIST = 2;

    private static final String RESULT_BEGIN =
            "__PC_RESULTS_BEGIN__";

    private static final String RESULT_END =
            "__PC_RESULTS_END__";

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private PasswordCrackerManager manager;

    private Spinner engineSpinner;
    private Spinner attackSpinner;
    private Spinner hashTypeSpinner;

    private EditText hashFileInput;
    private EditText wordlistInput;
    private EditText rulesInput;
    private EditText maskInput;
    private EditText sessionInput;

    private TextView commandPreview;
    private TextView output;
    private TextView resultStatus;
    private TextView resultsOutput;

    private Button startButton;
    private Button stopButton;
    private Button resumeButton;
    private Button copyResultsButton;

    private int pendingFileType;

    private boolean collectingResults = false;
    private boolean resultLookupComplete = false;
    private boolean resultLookupFailed = false;

    private String activePotfilePath = null;
    private String activeJohnPotfilePath = null;

    private final StringBuilder recoveredResults =
            new StringBuilder();

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    this::importSelectedFile
            );

    public static PasswordCrackerFragment newInstance(int itemId) {
        return new PasswordCrackerFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        return inflater.inflate(
                R.layout.password_cracker,
                container,
                false
        );
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);

        Context context = requireContext();

        NhPaths.getInstance(context);

        manager = PasswordCrackerManager.getInstance(context);

        engineSpinner = view.findViewById(R.id.pc_engine);
        attackSpinner = view.findViewById(R.id.pc_attack);
        hashTypeSpinner = view.findViewById(R.id.pc_hash_type);

        hashFileInput = view.findViewById(R.id.pc_hash_file);
        wordlistInput = view.findViewById(R.id.pc_wordlist);
        rulesInput = view.findViewById(R.id.pc_rules);
        maskInput = view.findViewById(R.id.pc_mask);
        sessionInput = view.findViewById(R.id.pc_session);

        commandPreview =
                view.findViewById(R.id.pc_command_preview);

        output = view.findViewById(R.id.pc_output);
        resultStatus = view.findViewById(R.id.pc_result_status);
        resultsOutput = view.findViewById(R.id.pc_results);

        copyResultsButton =
                view.findViewById(R.id.pc_copy_results);

        startButton = view.findViewById(R.id.pc_start);
        stopButton = view.findViewById(R.id.pc_stop);
        resumeButton = view.findViewById(R.id.pc_resume);

        configureOutput(view);
        configureResults();

        if (sessionInput.getText().toString().trim().isEmpty()) {
            sessionInput.setText("nethunter");
        }

        configureSpinners();
        configureButtons(view);
        configureMenu();

        manager.setListener(createListener());

        refreshButtons();
        checkDependencies();
    }

    private boolean hasActiveView() {
        return isAdded()
                && getView() != null
                && output != null;
    }

    /*
     * Session Output
     */

    private void configureOutput(View root) {

        output.setMovementMethod(
                new ScrollingMovementMethod()
        );

        output.setOnTouchListener((v, event) -> {

            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN ||
                    action == MotionEvent.ACTION_MOVE) {

                v.getParent()
                        .requestDisallowInterceptTouchEvent(true);

            } else if (
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL) {

                v.getParent()
                        .requestDisallowInterceptTouchEvent(false);
            }

            return false;
        });

        root.findViewById(R.id.pc_clear_output)
                .setOnClickListener(v -> output.setText(""));
    }

    private void appendOutput(String line) {

        if (output == null || line == null) {
            return;
        }

        boolean atBottom =
                output.getLayout() == null ||
                output.getScrollY() + output.getHeight() >=
                output.getLayout().getHeight() -
                output.getTotalPaddingTop() -
                output.getTotalPaddingBottom() - 48;

        output.append(line + "\n");

        if (atBottom) {

            output.post(() -> {

                if (output == null ||
                        output.getLayout() == null) {
                    return;
                }

                int scrollAmount =
                        output.getLayout().getHeight()
                        - output.getHeight()
                        + output.getTotalPaddingTop()
                        + output.getTotalPaddingBottom();

                output.scrollTo(
                        0,
                        Math.max(0, scrollAmount)
                );
            });
        }
    }

    /*
     * Cracking Results
     */

    private void configureResults() {

        resultStatus.setText("Status: Idle");
        resultsOutput.setText("No results yet.");
        copyResultsButton.setEnabled(false);

        copyResultsButton.setOnClickListener(v -> {

            String text = recoveredResults.toString().trim();

            if (text.isEmpty()) {
                return;
            }

            ClipboardManager clipboard =
                    (ClipboardManager) requireContext()
                            .getSystemService(
                                    Context.CLIPBOARD_SERVICE
                            );

            if (clipboard == null) {
                showMessage("Clipboard unavailable.");
                return;
            }

            clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                            "Recovered passwords",
                            text
                    )
            );

            showMessage("Results copied.");
        });
    }

    private void resetResults() {

        collectingResults = false;
        resultLookupComplete = false;
        resultLookupFailed = false;

        recoveredResults.setLength(0);

        resultStatus.setText("Status: Running");
        resultsOutput.setText("Waiting for results...");
        copyResultsButton.setEnabled(false);
    }

    /*
     * Run the attack and retrieve recovered credentials.
     */

    private String commandWithResults(
            String attackCommand,
            PasswordCrackerCommandBuilder.Config config) {

        String showCommand;

        if (config.engine ==
                PasswordCrackerCommandBuilder.Engine.HASHCAT) {

            showCommand =
                    "hashcat -m " + config.hashMode +
                    " --show " +
                    (activePotfilePath == null ? "" :
                            " --potfile-path " +
                            ShellExecuter.shellEscape(activePotfilePath) +
                            " ") +
                    ShellExecuter.shellEscape(config.hashFile);

        } else {

            showCommand =
                    PasswordCrackerCommandBuilder.buildJohnShow(
                            config.hashFile,
                            config.format
                    ) +
                    (activeJohnPotfilePath == null ? "" :
                            " --pot=" +
                            ShellExecuter.shellEscape(activeJohnPotfilePath));
        }

        return attackCommand +
                "; pc_attack_rc=$?; " +
                "printf '\\n" + RESULT_BEGIN + "\\n'; " +
                showCommand +
                "; pc_show_rc=$?; " +
                "printf '\\n" + RESULT_END +
                ":%s\\n' \"$pc_show_rc\"; " +
                "exit \"$pc_attack_rc\"";
    }

    /*
     * Parse recovered results.
     */

    private void processOutputLine(String line) {

        if (line == null) {
            return;
        }

        String trimmed = line.trim();

        if (trimmed.equals(RESULT_BEGIN)) {

            collectingResults = true;
            recoveredResults.setLength(0);
            return;
        }

        if (trimmed.startsWith(RESULT_END + ":")) {

            collectingResults = false;
            resultLookupComplete = true;

            String showExitCode =
                    trimmed.substring(
                            (RESULT_END + ":").length()
                    );

            if (!"0".equals(showExitCode)) {

                resultLookupFailed = true;

                resultStatus.setText(
                        "Status: Result lookup failed"
                );

                resultsOutput.setText(
                        "Unable to retrieve recovered passwords.\n" +
                        "Check Session Output."
                );

                copyResultsButton.setEnabled(false);
                return;
            }

            String results =
                    recoveredResults.toString().trim();

            if (results.isEmpty()) {

                resultStatus.setText(
                        "Status: No passwords recovered"
                );

                resultsOutput.setText(
                        "No matching passwords were recovered."
                );

                copyResultsButton.setEnabled(false);

            } else {

                resultStatus.setText("Status: Recovered");
                resultsOutput.setText(results);
                copyResultsButton.setEnabled(true);
            }

            return;
        }

        if (collectingResults) {

            if (!trimmed.isEmpty()
                    && !trimmed.matches(
                            "^\\d+ password hashes? cracked, \\d+ left.*$"
                    )) {

                if (recoveredResults.length() > 0) {
                    recoveredResults.append('\n');
                }

                recoveredResults.append(line);
            }

        } else {

            appendOutput(line);
        }
    }

    /*
     * Engine and attack selection.
     */

    private void configureSpinners() {

        setSpinnerItems(
                engineSpinner,
                new String[]{
                        "Hashcat",
                        "John the Ripper"
                }
        );

        engineSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {

                        boolean hashcat = position == 0;

                        if (hashcat) {

                            setSpinnerItems(
                                    attackSpinner,
                                    new String[]{
                                            "Dictionary",
                                            "Mask",
                                            "Hybrid Wordlist + Mask",
                                            "Hybrid Mask + Wordlist"
                                    }
                            );

                            setSpinnerItems(
                                    hashTypeSpinner,
                                    new String[]{
                                            "MD5 (0)",
                                            "SHA1 (100)",
                                            "SHA256 (1400)",
                                            "SHA512 (1700)",
                                            "NTLM (1000)",
                                            "WPA-PBKDF2-PMKID+EAPOL (22000)",
                                            "NetNTLMv2 (5600)",
                                            "bcrypt (3200)",
                                            "sha512crypt (1800)"
                                    }
                            );

                        } else {

                            setSpinnerItems(
                                    attackSpinner,
                                    new String[]{
                                            "Dictionary",
                                            "Incremental"
                                    }
                            );

                            setSpinnerItems(
                                    hashTypeSpinner,
                                    new String[]{
                                            "Raw-MD5",
                                            "Raw-SHA1",
                                            "Raw-SHA256",
                                            "Raw-SHA512",
                                            "NT",
                                            "bcrypt",
                                            "sha512crypt"
                                    }
                            );
                        }

                        updateFieldVisibility();
                    }

                    @Override
                    public void onNothingSelected(
                            AdapterView<?> parent) {
                    }
                }
        );

        attackSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {

                        updateFieldVisibility();
                    }

                    @Override
                    public void onNothingSelected(
                            AdapterView<?> parent) {
                    }
                }
        );
    }

    private void setSpinnerItems(
            Spinner spinner,
            String[] values) {

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        values
                );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spinner.setAdapter(adapter);
    }

    private void updateFieldVisibility() {

        if (engineSpinner == null ||
                attackSpinner == null ||
                wordlistInput == null ||
                maskInput == null ||
                rulesInput == null) {
            return;
        }

        boolean hashcat =
                engineSpinner.getSelectedItemPosition() == 0;

        int attack = attackSpinner.getSelectedItemPosition();

        boolean dictionary = attack == 0;
        boolean hybrid = hashcat && attack >= 2;
        boolean mask = hashcat && attack >= 1;

        wordlistInput.setEnabled(dictionary || hybrid);
        maskInput.setEnabled(mask);
        rulesInput.setEnabled(dictionary);
    }

    /*
     * Buttons
     */

    private void configureButtons(View root) {

        root.findViewById(R.id.pc_browse_hash)
                .setOnClickListener(v -> {

                    pendingFileType = FILE_HASH;
                    filePicker.launch(new String[]{"*/*"});
                });

        root.findViewById(R.id.pc_browse_wordlist)
                .setOnClickListener(v -> {

                    pendingFileType = FILE_WORDLIST;
                    filePicker.launch(new String[]{"*/*"});
                });

        root.findViewById(R.id.pc_preview)
                .setOnClickListener(v -> previewCommand());

        startButton.setOnClickListener(
                v -> startSession()
        );

        stopButton.setOnClickListener(v -> {

            if (manager.isRunning()) {

                manager.stop();

                appendOutput("Stop requested...");
                resultStatus.setText("Status: Stopping");

                refreshButtons();
            }
        });

        resumeButton.setOnClickListener(
                v -> resumeSession()
        );
    }

    /*
     * Setup / Update menu
     */

    private void configureMenu() {

        MenuHost menuHost = (MenuHost) requireActivity();

        menuHost.addMenuProvider(
                new MenuProvider() {

                    @Override
                    public void onCreateMenu(
                            @NonNull Menu menu,
                            @NonNull MenuInflater inflater) {

                        inflater.inflate(
                                R.menu.password_cracker_menu,
                                menu
                        );
                    }

                    @Override
                    public boolean onMenuItemSelected(
                            @NonNull MenuItem item) {

                        int id = item.getItemId();

                        if (id ==
                                R.id.action_password_cracker_setup) {

                            installDependencies();
                            return true;
                        }

                        if (id ==
                                R.id.action_password_cracker_update) {

                            updateDependencies();
                            return true;
                        }

                        return false;
                    }
                },
                getViewLifecycleOwner(),
                Lifecycle.State.RESUMED
        );
    }

    /*
     * Build attack configuration.
     */

    private PasswordCrackerCommandBuilder.Config buildConfig() {

        PasswordCrackerCommandBuilder.Config config =
                new PasswordCrackerCommandBuilder.Config();

        boolean hashcat =
                engineSpinner.getSelectedItemPosition() == 0;

        config.engine = hashcat
                ? PasswordCrackerCommandBuilder.Engine.HASHCAT
                : PasswordCrackerCommandBuilder.Engine.JOHN;

        int attack = attackSpinner.getSelectedItemPosition();

        if (hashcat) {

            switch (attack) {

                case 0:
                    config.attack =
                            PasswordCrackerCommandBuilder.Attack.DICTIONARY;
                    break;

                case 1:
                    config.attack =
                            PasswordCrackerCommandBuilder.Attack.MASK;
                    break;

                case 2:
                    config.attack =
                            PasswordCrackerCommandBuilder.Attack.HYBRID_WORDLIST_MASK;
                    break;

                default:
                    config.attack =
                            PasswordCrackerCommandBuilder.Attack.HYBRID_MASK_WORDLIST;
                    break;
            }

            int[] modes = {
                    0, 100, 1400, 1700, 1000,
                    22000, 5600, 3200, 1800
            };

            config.hashMode = modes[
                    hashTypeSpinner.getSelectedItemPosition()
            ];

        } else {

            config.attack = attack == 0
                    ? PasswordCrackerCommandBuilder.Attack.DICTIONARY
                    : PasswordCrackerCommandBuilder.Attack.INCREMENTAL;

            String[] formats = {
                    "Raw-MD5",
                    "Raw-SHA1",
                    "Raw-SHA256",
                    "Raw-SHA512",
                    "NT",
                    "bcrypt",
                    "sha512crypt"
            };

            config.format = formats[
                    hashTypeSpinner.getSelectedItemPosition()
            ];
        }

        config.hashFile =
                hashFileInput.getText().toString().trim();

        config.wordlist =
                wordlistInput.getText().toString().trim();

        config.ruleFile =
                rulesInput.getText().toString().trim();

        config.mask =
                maskInput.getText().toString().trim();

        config.session =
                sessionInput.getText().toString().trim();

        return config;
    }

    private void previewCommand() {

        try {

            String command =
                    PasswordCrackerCommandBuilder.build(
                            buildConfig()
                    );

            commandPreview.setText(command);

        } catch (IllegalArgumentException e) {

            showMessage(e.getMessage());
        }
    }

    /*
     * Start cracking.
     */

    private void startSession() {

        try {

            if (manager.isRunning()) {
                showMessage("A job is already running.");
                return;
            }

            PasswordCrackerCommandBuilder.Config config =
                    buildConfig();

            String command =
                    PasswordCrackerCommandBuilder.build(config);

            if (config.engine ==
                    PasswordCrackerCommandBuilder.Engine.HASHCAT) {

                activePotfilePath =
                        "/tmp/pc-" + UUID.randomUUID() + ".pot";

                activeJohnPotfilePath = null;

                command += " --potfile-path " +
                        ShellExecuter.shellEscape(activePotfilePath);

            } else {

                activePotfilePath = null;

                activeJohnPotfilePath =
                        "/tmp/pc-john-" + UUID.randomUUID() + ".pot";

                command += " --pot=" +
                        ShellExecuter.shellEscape(activeJohnPotfilePath);
            }

            commandPreview.setText(command);

            resetResults();

            manager.start(
                    commandWithResults(command, config),
                    config.session,
                    createListener()
            );

            appendOutput("Starting cracking session...");
            refreshButtons();

        } catch (RuntimeException e) {

            showMessage(e.getMessage());
        }
    }

    /*
     * Resume cracking.
     */

    private void resumeSession() {

        try {

            if (manager.isRunning()) {
                showMessage("A job is already running.");
                return;
            }

            PasswordCrackerCommandBuilder.Config config =
                    buildConfig();

            String command;

            if (config.engine ==
                    PasswordCrackerCommandBuilder.Engine.HASHCAT) {

                command =
                        PasswordCrackerCommandBuilder.buildHashcatRestore(
                                config.session
                        );

            } else {

                command =
                        PasswordCrackerCommandBuilder.buildJohnRestore(
                                config.session
                        );
            }

            commandPreview.setText(command);

            resetResults();

            manager.start(
                    commandWithResults(command, config),
                    config.session,
                    createListener()
            );

            appendOutput("Restoring session...");
            refreshButtons();

        } catch (RuntimeException e) {

            showMessage(e.getMessage());
        }
    }

    /*
     * Session manager callbacks.
     */

    private PasswordCrackerManager.Listener createListener() {

        return new PasswordCrackerManager.Listener() {

            @Override
            public void onOutput(String line) {

                mainHandler.post(() -> {

                    if (hasActiveView()) {
                        processOutputLine(line);
                    }
                });
            }

            @Override
            public void onFinished(int exitCode) {

                mainHandler.post(() -> {

                    if (!hasActiveView()) {
                        return;
                    }

                    appendOutput(
                            "Job finished. Exit code: " + exitCode
                    );

                    if (!resultLookupComplete) {

                        collectingResults = false;

                        resultStatus.setText(
                                exitCode == 1
                                        ? "Status: Exhausted"
                                        : exitCode == 0
                                                ? "Status: Result lookup incomplete"
                                                : "Status: Error (" + exitCode + ")"
                        );

                        resultsOutput.setText(
                                exitCode == 1
                                        ? "No passwords recovered by this attack."
                                        : "Result lookup did not complete. " +
                                          "Check Session Output."
                        );

                        copyResultsButton.setEnabled(false);

                    } else if (!resultLookupFailed &&
                            recoveredResults.length() == 0 &&
                            exitCode != 0) {

                        resultStatus.setText(
                                exitCode == 1
                                        ? "Status: Exhausted"
                                        : "Status: Error (" + exitCode + ")"
                        );
                    }

                    refreshButtons();
                });
            }

            @Override
            public void onError(String message) {

                mainHandler.post(() -> {

                    if (!hasActiveView()) {
                        return;
                    }

                    collectingResults = false;

                    appendOutput("ERROR: " + message);

                    resultStatus.setText("Status: Error");
                    resultsOutput.setText(message);
                    copyResultsButton.setEnabled(false);

                    refreshButtons();
                });
            }
        };
    }

    private void refreshButtons() {

        if (!hasActiveView() || manager == null) {
            return;
        }

        boolean running = manager.isRunning();

        startButton.setEnabled(!running);
        resumeButton.setEnabled(!running);

        stopButton.setEnabled(
                running && !manager.isStopping()
        );
    }

    /*
     * Dependency checking.
     */

    private void checkDependencies() {

        appendOutput("Checking Hashcat and John...");

        final Context context =
                requireContext().getApplicationContext();

        executor.execute(() -> {

            ShellExecuter.ShellResult result =
                    new ShellExecuter()
                            .RunAsChrootWithResult(
                                    "command -v hashcat >/dev/null 2>&1 " +
                                    "&& command -v john >/dev/null 2>&1"
                            );

            boolean available = result.exitCode == 0;

            context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
            ).edit()
                    .putBoolean(PREF_SETUP_COMPLETE, available)
                    .apply();

            mainHandler.post(() -> {

                if (!hasActiveView()) {
                    return;
                }

                appendOutput(
                        available
                                ? "Hashcat and John are installed."
                                : "Missing dependencies. Use Setup."
                );
            });
        });
    }

    /*
     * Setup and Update launch Kali Terminal.
     */

    private void installDependencies() {

        launchKaliTerminal(
                "apt-get update && " +
                "apt-get install -y hashcat john && exit 0"
        );
    }

    private void updateDependencies() {

        launchKaliTerminal(
                "apt-get update && " +
                "apt-get install -y --only-upgrade hashcat john && exit 0"
        );
    }

    private void launchKaliTerminal(String command) {

        try {

            Intent intent =
                    Bridge.createExecuteIntent(
                            KALI_TERMINAL,
                            command
                    );

            startActivity(intent);

        } catch (Exception e) {

            showMessage(
                    "Unable to launch Kali Terminal: " +
                    e.getMessage()
            );
        }
    }

    /*
     * Import files from Android storage into Kali.
     */

    private void importSelectedFile(Uri uri) {

        if (uri == null) {
            return;
        }

        final int type = pendingFileType;

        final Context context =
                requireContext().getApplicationContext();

        appendOutput("Importing selected file...");

        executor.execute(() -> {

            File stagedFile = null;

            try {

                String name =
                        "import-" +
                        UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                stagedFile = new File(
                        context.getCacheDir(),
                        name
                );

                try (
                        InputStream input =
                                context.getContentResolver()
                                        .openInputStream(uri);

                        FileOutputStream stream =
                                new FileOutputStream(stagedFile)
                ) {

                    if (input == null) {
                        throw new IOException(
                                "Unable to open selected file."
                        );
                    }

                    byte[] buffer = new byte[8192];
                    int length;

                    while ((length = input.read(buffer)) != -1) {
                        stream.write(buffer, 0, length);
                    }
                }

                String chrootPath = NhPaths.CHROOT_PATH();

                if (chrootPath == null || chrootPath.isEmpty()) {
                    throw new IOException(
                            "Kali chroot path is unavailable."
                    );
                }

                String kaliPath =
                        KALI_IMPORT_DIR + "/" + name;

                String androidDirectory =
                        chrootPath + KALI_IMPORT_DIR;

                String androidDestination =
                        chrootPath + kaliPath;

                String rootCommand =
                        "mkdir -p " +
                        ShellExecuter.shellEscape(androidDirectory) +
                        " && cp " +
                        ShellExecuter.shellEscape(
                                stagedFile.getAbsolutePath()
                        ) +
                        " " +
                        ShellExecuter.shellEscape(androidDestination) +
                        " && chmod 600 " +
                        ShellExecuter.shellEscape(androidDestination);

                Process process =
                        new ProcessBuilder(
                                "su", "-mm"
                        ).redirectErrorStream(true).start();

                try (Writer writer =
                        new OutputStreamWriter(
                                process.getOutputStream(),
                                StandardCharsets.UTF_8)) {

                    writer.write(rootCommand);
                    writer.write("\nexit $?\n");
                    writer.flush();
                }

                StringBuilder rootOutput =
                        new StringBuilder();

                try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(),
                                        StandardCharsets.UTF_8))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        rootOutput.append(line).append('\n');
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    throw new IOException(
                            "Root copy failed (" + exitCode + "): " +
                            rootOutput.toString().trim()
                    );
                }

                mainHandler.post(() -> {

                    if (!hasActiveView()) {
                        return;
                    }

                    if (type == FILE_HASH) {
                        hashFileInput.setText(kaliPath);
                    } else {
                        wordlistInput.setText(kaliPath);
                    }

                    appendOutput("Imported: " + kaliPath);
                });

            } catch (Exception e) {

                final String message = e.getMessage();

                mainHandler.post(() -> {

                    if (hasActiveView()) {
                        appendOutput(
                                "Import failed: " + message
                        );
                    }
                });

            } finally {

                if (stagedFile != null) {
                    stagedFile.delete();
                }
            }
        });
    }

    private void showMessage(String message) {

        if (!isAdded()) {
            return;
        }

        Toast.makeText(
                requireContext(),
                message == null ? "Unknown error" : message,
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    public void onDestroyView() {

        if (manager != null) {
            manager.setListener(null);
        }

        engineSpinner = null;
        attackSpinner = null;
        hashTypeSpinner = null;

        hashFileInput = null;
        wordlistInput = null;
        rulesInput = null;
        maskInput = null;
        sessionInput = null;

        commandPreview = null;
        output = null;
        resultStatus = null;
        resultsOutput = null;

        startButton = null;
        stopButton = null;
        resumeButton = null;
        copyResultsButton = null;

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {

        executor.shutdown();

        super.onDestroy();
    }
}