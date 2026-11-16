
package com.offsec.nethunter;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SharkHunterFragment extends Fragment
        implements SharkHunterManager.Listener,
        SharkHunterPacketAdapter.OnPacketClickListener {

    private static final long REFRESH_INTERVAL = 2000L;

    private static final int MENU_OPEN = 10001;
    private static final int MENU_SAVE = 10002;
    private static final int MENU_INFO = 10003;
    private static final int MENU_REFRESH = 10004;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final List<String> interfaceNames =
            new ArrayList<>();

    private SharkHunterManager manager;
    private SharkHunterPacketAdapter packetAdapter;

    private Spinner interfaceSpinner;
    private EditText captureFilterInput;
    private EditText displayFilterInput;

    private Button startButton;
    private Button stopButton;
    private Button pauseButton;

    private TextView packetCount;
    private TextView captureState;
    private TextView statusText;

    private ProgressBar progressBar;
    private RecyclerView packetList;

    private ActivityResultLauncher<String[]> openLauncher;
    private ActivityResultLauncher<String> saveLauncher;

    private boolean viewActive = false;
    private boolean displayPaused = false;

    /*
     * Packet refresh synchronization.
     *
     * Only one manager.loadPackets() request is allowed
     * at a time.
     *
     * requestGeneration changes whenever the selected
     * capture or display filter changes.
     *
     * loadingGeneration records which generation
     * was used for the currently running request.
     *
     * If the generations differ when results arrive,
     * those results are discarded.
     */
    private boolean refreshInProgress = false;
    private boolean refreshPending = false;

    private long requestGeneration = 0;
    private long loadingGeneration = -1;

    private String currentCaptureFile;
    private String pendingSaveFile;
    private boolean stopDecisionVisible = false;
    private String activeDisplayFilter = "";

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!viewActive) {
                return;
            }

            if (!displayPaused) {
                refreshPackets();
            }

            handler.postDelayed(this, REFRESH_INTERVAL);
        }
    };

    public static SharkHunterFragment newInstance(int itemId) {
        SharkHunterFragment fragment =
                new SharkHunterFragment();

        Bundle args = new Bundle();
        args.putInt("item_id", itemId);

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        openLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::importCapture
        );

        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(
                        "application/octet-stream"
                ),
                this::exportCapture
        );
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        return inflater.inflate(
                R.layout.sharkhunter,
                container,
                false
        );
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);

        viewActive = true;

        manager = SharkHunterManager.getInstance(
                requireContext()
        );

        manager.setListener(this);

        bindViews(view);
        setupPacketList();
        setupCaptureControls(view);
        setupDisplayFilters(view);
        setupToolbarMenu();

        currentCaptureFile = manager.getCaptureFile();

        if (manager.isUnsavedLiveCapture()) {
            handler.post(this::promptSaveOrDiscard);
        }

        updateCaptureButtons(manager.isCapturing());
        updatePacketCount();

        checkDependencies();

        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(
                refreshRunnable,
                REFRESH_INTERVAL
        );
    }

    private void bindViews(View view) {
        interfaceSpinner = view.findViewById(
                R.id.sharkhunter_interface_spinner
        );

        captureFilterInput = view.findViewById(
                R.id.sharkhunter_capture_filter
        );

        displayFilterInput = view.findViewById(
                R.id.sharkhunter_display_filter_input
        );

        startButton = view.findViewById(
                R.id.sharkhunter_start_capture
        );

        stopButton = view.findViewById(
                R.id.sharkhunter_stop_capture
        );

        pauseButton = view.findViewById(
                R.id.sharkhunter_pause_capture
        );

        packetCount = view.findViewById(
                R.id.sharkhunter_packet_count
        );

        captureState = view.findViewById(
                R.id.sharkhunter_capture_state
        );

        statusText = view.findViewById(
                R.id.sharkhunter_status
        );

        progressBar = view.findViewById(
                R.id.sharkhunter_progress
        );

        packetList = view.findViewById(
                R.id.sharkhunter_packet_list
        );
    }

    private void setupPacketList() {
        packetAdapter =
                new SharkHunterPacketAdapter(this);

        packetList.setLayoutManager(
                new LinearLayoutManager(requireContext())
        );

        packetList.setAdapter(packetAdapter);
    }

    private void setupCaptureControls(View view) {
        startButton.setOnClickListener(
                v -> startCapture()
        );

        stopButton.setOnClickListener(
                v -> stopCapture()
        );

        pauseButton.setOnClickListener(
                v -> togglePause()
        );

        view.findViewById(
                R.id.sharkhunter_refresh_interfaces
        ).setOnClickListener(
                v -> loadInterfaces()
        );
    }

    private void setupDisplayFilters(View view) {
        view.findViewById(
                R.id.sharkhunter_apply_filter
        ).setOnClickListener(
                v -> applyDisplayFilter()
        );

        view.findViewById(
                R.id.sharkhunter_clear_filter
        ).setOnClickListener(v -> {
            displayFilterInput.setText("");
            setDisplayFilter("");
        });

        bindPreset(
                view,
                R.id.sharkhunter_filter_tcp,
                "tcp"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_udp,
                "udp"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_dns,
                "dns"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_http,
                "http"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_tls,
                "tls"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_eapol,
                "eapol"
        );

        bindPreset(
                view,
                R.id.sharkhunter_filter_arp,
                "arp"
        );
    }

    private void bindPreset(
            View view,
            int id,
            String filter) {

        view.findViewById(id).setOnClickListener(v -> {
            displayFilterInput.setText(filter);
            applyDisplayFilter();
        });
    }

    private void setupToolbarMenu() {
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(
            @NonNull Menu menu,
            @NonNull MenuInflater inflater) {

        super.onCreateOptionsMenu(menu, inflater);

        menu.add(Menu.NONE, MENU_OPEN, 0, "Open Capture")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        menu.add(Menu.NONE, MENU_SAVE, 1, "Save Capture")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        menu.add(Menu.NONE, MENU_INFO, 2, "Capture Information")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        menu.add(Menu.NONE, MENU_REFRESH, 3, "Refresh Packets")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case MENU_OPEN:
                openLauncher.launch(new String[]{"*/*"});
                return true;

            case MENU_SAVE:
                saveCurrentCapture();
                return true;

            case MENU_INFO:
                showCaptureInformation();
                return true;

            case MENU_REFRESH:
                requestPacketRefresh();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void checkDependencies() {
        setBusy(true);
        setStatus("Checking TShark...");

        manager.checkDependencies(
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String result) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);
                        setStatus("TShark ready");

                        loadInterfaces();
                    }

                    @Override
                    public void onError(String error) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);
                        showError(error);
                    }
                }
        );
    }

    private void loadInterfaces() {
        setBusy(true);
        setStatus("Discovering interfaces...");

        manager.getInterfaces(
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String output) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);

                        String previous = "";

                        Object selected =
                                interfaceSpinner.getSelectedItem();

                        if (selected != null) {
                            previous = selected.toString();
                        }

                        interfaceNames.clear();

                        for (String line :
                                output.split("\\r?\\n")) {

                            if (!line.trim().matches("^\\d+\\.\\s+.*")) {
                                continue;
                            }

                            String name = line.trim()
                                    .replaceFirst(
                                            "^\\d+\\.\\s*",
                                            ""
                                    );

                            int space = name.indexOf(' ');

                            if (space >= 0) {
                                name = name.substring(
                                        0,
                                        space
                                );
                            }

                            if (!name.isEmpty()) {
                                interfaceNames.add(name);
                            }
                        }

                        ArrayAdapter<String> adapter =
                                new ArrayAdapter<>(
                                        requireContext(),
                                        android.R.layout
                                                .simple_spinner_item,
                                        interfaceNames
                                );

                        adapter.setDropDownViewResource(
                                android.R.layout
                                        .simple_spinner_dropdown_item
                        );

                        interfaceSpinner.setAdapter(adapter);

                        String desired =
                                manager.isCapturing()
                                        ? manager.getCaptureInterface()
                                        : previous;

                        if (desired != null) {
                            int index =
                                    interfaceNames.indexOf(desired);

                            if (index >= 0) {
                                interfaceSpinner.setSelection(index);
                            }
                        }

                        setStatus(
                                interfaceNames.size()
                                        + " interfaces available"
                        );
                    }

                    @Override
                    public void onError(String error) {
                        if (viewActive) {
                            setBusy(false);
                            showError(error);
                        }
                    }
                }
        );
    }

    private void startCapture() {
        if (manager.isUnsavedLiveCapture()) {
            promptSaveOrDiscard();
            return;
        }
        if (manager.isCapturing()) {
            showToast("Capture already running");
            return;
        }

        Object selected =
                interfaceSpinner.getSelectedItem();

        if (selected == null) {
            showError("Select a network interface.");
            return;
        }

        String networkInterface =
                selected.toString();

        String bpf =
                captureFilterInput.getText()
                        .toString()
                        .trim();

        /*
         * Invalidate any pending packet results.
         */
        requestGeneration++;

        displayPaused = false;
        refreshPending = true;

        packetAdapter.clearPackets();
        updatePacketCount();

        pauseButton.setText(
                R.string.sharkhunter_pause
        );

        manager.startCapture(
                networkInterface,
                bpf
        );

        currentCaptureFile =
                manager.getCaptureFile();

        updateCaptureButtons(
                manager.isCapturing()
        );

        setStatus("Starting capture...");

        refreshPackets();
    }

    private void stopCapture() {
        if (!manager.isCapturing()) {
            return;
        }

        manager.stopCapture();
        setStatus("Stopping capture...");
    }

    private void togglePause() {
        displayPaused = !displayPaused;

        pauseButton.setText(
                displayPaused
                        ? R.string.sharkhunter_resume
                        : R.string.sharkhunter_pause
        );

        if (displayPaused) {
            setStatus(
                    "Display paused; capture continues"
            );
        } else {
            setStatus("Display resumed");
            requestPacketRefresh();
        }
    }

    private void updateCaptureButtons(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);

        interfaceSpinner.setEnabled(!running);
        captureFilterInput.setEnabled(!running);

        captureState.setText(
                running
                        ? R.string.sharkhunter_capturing
                        : R.string.sharkhunter_stopped
        );

        captureState.setTextColor(
                running
                        ? 0xFF78C9A7
                        : 0xFFB0BEC5
        );
    }

    private void applyDisplayFilter() {
        String filter =
                displayFilterInput.getText()
                        .toString()
                        .trim();

        if (filter.isEmpty()) {
            setDisplayFilter("");
            return;
        }

        setBusy(true);
        setStatus("Validating display filter...");

        manager.validateDisplayFilter(
                filter,
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String result) {
                        if (!viewActive) {
                            return;
                        }

                        /*
                         * Ignore validation results if the
                         * user has edited the filter since
                         * this request was started.
                         */
                        String current =
                                displayFilterInput.getText()
                                        .toString()
                                        .trim();

                        if (!filter.equals(current)) {
                            setBusy(false);
                            return;
                        }

                        setBusy(false);
                        setDisplayFilter(filter);
                    }

                    @Override
                    public void onError(String error) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);
                        showError(
                                "Invalid filter: " + error
                        );
                    }
                }
        );
    }

    private void setDisplayFilter(String filter) {
        activeDisplayFilter = filter;

        /*
         * Any in-flight result from the previous
         * filter is now obsolete.
         */
        requestGeneration++;

        refreshPending = true;

        packetAdapter.clearPackets();
        updatePacketCount();

        setStatus(
                filter.isEmpty()
                        ? "Display filter cleared"
                        : "Display filter: " + filter
        );

        if (!displayPaused) {
            refreshPackets();
        }
    }

    private void requestPacketRefresh() {
        refreshPending = true;

        if (!displayPaused) {
            refreshPackets();
        }
    }

    private void refreshPackets() {
        if (!viewActive
                || displayPaused
                || currentCaptureFile == null
                || currentCaptureFile.isEmpty()) {
            return;
        }

        /*
         * Do not start another request while the
         * previous TShark request is running.
         */
        if (refreshInProgress) {
            refreshPending = true;
            return;
        }

        refreshInProgress = true;
        refreshPending = false;

        loadingGeneration = requestGeneration;

        manager.loadPackets(
                currentCaptureFile,
                activeDisplayFilter
        );
    }

    @Override
    public void onPackets(
            List<SharkHunterPacket> packets) {

        if (!viewActive) {
            return;
        }

        refreshInProgress = false;

        /*
         * The filter or capture changed while
         * TShark was loading packets.
         *
         * Discard the old result and load again.
         */
        if (loadingGeneration != requestGeneration) {
            refreshPending = true;
        } else if (!displayPaused) {
            packetAdapter.setPackets(packets);
            updatePacketCount();

            if (!manager.isCapturing()) {
                setStatus(
                        "Loaded "
                                + packets.size()
                                + " packets"
                );
            }
        }

        if (refreshPending && !displayPaused) {
            refreshPackets();
        }
    }

    @Override
    public void onStatus(String status) {
        if (!viewActive) {
            return;
        }

        setStatus(status);

        updateCaptureButtons(
                manager.isCapturing()
        );
    }

    @Override
    public void onError(String error) {
        if (!viewActive) {
            return;
        }

        /*
         * Manager errors can originate from either
         * capture or packet loading.
         *
         * Release the refresh lock so the interface
         * cannot remain stuck indefinitely.
         */
        refreshInProgress = false;

        if (manager.isCapturing()
                && error != null
                && error.startsWith("Packet loading failed")) {
            refreshPending = true;
            return;
        }

        showError(error);
    }

    @Override
    public void onCaptureStopped() {
        if (!viewActive) return;

        updateCaptureButtons(false);
        setStatus("Capture stopped");
        requestPacketRefresh();
        promptSaveOrDiscard();
    }

    private void promptSaveOrDiscard() {
        if (!viewActive || !isAdded()
                || stopDecisionVisible
                || !manager.isUnsavedLiveCapture()) {
            return;
        }

        currentCaptureFile = manager.getCaptureFile();
        stopDecisionVisible = true;

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Save capture?")
                .setMessage(
                        "Save this packet capture to your phone, "
                        + "or discard it?")
                .setPositiveButton("Save", (ignoredDialog, which) -> {
                    stopDecisionVisible = false;
                    saveCurrentCapture();
                })
                .setNegativeButton("Discard", (ignoredDialog, which) -> {
                    stopDecisionVisible = false;
                    discardCurrentCapture();
                })
                .setNeutralButton("Later", (ignoredDialog, which) -> {
                    stopDecisionVisible = false;
                    setStatus("Unsaved capture retained");
                })
                .setOnCancelListener(
                        ignoredDialog -> stopDecisionVisible = false)
                .create();

        dialog.setOnShowListener(ignored -> {
            int color = 0xFF86D5F8;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(color);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(color);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(color);
        });

        dialog.show();
    }

    private void discardCurrentCapture() {
        final String file = currentCaptureFile;
        setBusy(true);

        manager.discardCapture(
                file,
                new SharkHunterManager.ResultCallback() {
                    @Override
                    public void onResult(String result) {
                        if (!viewActive) return;

                        setBusy(false);
                        if (file.equals(currentCaptureFile)) {
                            currentCaptureFile = null;
                            requestGeneration++;
                            packetAdapter.clearPackets();
                            updatePacketCount();
                        }
                        setStatus(result);
                    }

                    @Override
                    public void onError(String error) {
                        if (!viewActive) return;
                        setBusy(false);
                        showError("Discard failed: " + error);
                    }
                }
        );
    }

    private void updatePacketCount() {
        if (packetAdapter == null
                || packetCount == null) {
            return;
        }

        packetCount.setText(
                String.format(
                        Locale.US,
                        "Packets: %d",
                        packetAdapter.getItemCount()
                )
        );
    }

    @Override
    public void onPacketClick(
            SharkHunterPacket packet) {

        inspectPacket(packet, false);
    }

    @Override
    public void onPacketLongClick(
            SharkHunterPacket packet) {

        String[] actions = {
                "Inspect Packet",
                "Filter by Source",
                "Filter by Destination",
                "Filter by Protocol",
                "Follow TCP Stream",
                "Follow UDP Stream"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(
                        "Packet #" + packet.getNumber()
                )
                .setItems(
                        actions,
                        (dialog, which) -> {
                            switch (which) {
                                case 0:
                                    onPacketClick(packet);
                                    break;

                                case 1:
                                    filterByAddress(
                                            packet.getSource(),
                                            true
                                    );
                                    break;

                                case 2:
                                    filterByAddress(
                                            packet.getDestination(),
                                            false
                                    );
                                    break;

                                case 3:
                                    applyContextFilter(
                                            packet.getProtocol()
                                                    .toLowerCase(
                                                            Locale.ROOT
                                                    )
                                    );
                                    break;

                                case 4:
                                    followStream(packet, "tcp");
                                    break;

                                case 5:
                                    followStream(packet, "udp");
                                    break;

                                default:
                                    break;
                            }
                        }
                )
                .show();
    }

    private void filterByAddress(
            String address,
            boolean source) {

        if (address == null || address.trim().isEmpty()) {
            showError("Packet address unavailable.");
            return;
        }

        String field;

        if (address.contains(":")) {
            /*
             * IPv6 address.
             */
            field = source
                    ? "ipv6.src"
                    : "ipv6.dst";

        } else if (address.matches(
                "\\d{1,3}(\\.\\d{1,3}){3}")) {

            /*
             * IPv4 address.
             */
            field = source
                    ? "ip.src"
                    : "ip.dst";

        } else {
            showError(
                    "Context filtering requires an IP address."
            );
            return;
        }

        applyContextFilter(
                field + " == " + address
        );
    }

    private void applyContextFilter(String filter) {
        displayFilterInput.setText(filter);
        applyDisplayFilter();
    }


    private void inspectPacket(
            SharkHunterPacket packet,
            boolean hex) {

        if (currentCaptureFile == null) {
            showError("No capture file loaded.");
            return;
        }

        if (hex) {
            if (!packet.getHexDump().isEmpty()) {
                showPacketInspector(packet, true);
                return;
            }

            setBusy(true);

            manager.getPacketHex(
                    currentCaptureFile,
                    packet.getNumber(),
                    new SharkHunterManager.ResultCallback() {
                        @Override
                        public void onResult(String result) {
                            if (!viewActive) return;
                            setBusy(false);
                            packet.setHexDump(result);
                            showPacketInspector(packet, true);
                        }

                        @Override
                        public void onError(String error) {
                            if (!viewActive) return;
                            setBusy(false);
                            showError(error);
                        }
                    }
            );
            return;
        }

        if (!packet.getProtocolDetails().isEmpty()) {
            showPacketInspector(packet, false);
            return;
        }

        setBusy(true);

        manager.getPacketDetails(
                currentCaptureFile,
                packet.getNumber(),
                new SharkHunterManager.ResultCallback() {
                    @Override
                    public void onResult(String result) {
                        if (!viewActive) return;
                        setBusy(false);
                        packet.setProtocolDetails(result);
                        showPacketInspector(packet, false);
                    }

                    @Override
                    public void onError(String error) {
                        if (!viewActive) return;
                        setBusy(false);
                        showError(error);
                    }
                }
        );
    }

    private int inspectorDp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private TextView inspectorText(
            String value,
            int size,
            int color,
            boolean bold) {

        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTextIsSelectable(true);

        if (bold) {
            text.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        return text;
    }

    private void showPacketInspector(
            SharkHunterPacket packet,
            boolean showHex) {

        if (!isAdded()) return;

        final int background = 0xFF101820;
        final int panel = 0xFF1B2A38;
        final int foreground = 0xFFF0F4F8;
        final int secondary = 0xFFA9C1D3;
        final int accent = 0xFF86D5F8;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(
                inspectorDp(16),
                inspectorDp(16),
                inspectorDp(16),
                inspectorDp(16)
        );

        TextView heading = inspectorText(
                "FRAME " + packet.getNumber()
                        + "  ·  " + packet.getProtocol(),
                20,
                foreground,
                true
        );

        root.addView(heading);

        TextView summary = inspectorText(
                packet.getLength() + " bytes"
                        + "\nSource: " + packet.getSource()
                        + "\nDestination: " + packet.getDestination()
                        + "\nTime: " + packet.getTimestamp(),
                13,
                secondary,
                false
        );

        summary.setPadding(
                0,
                inspectorDp(10),
                0,
                inspectorDp(14)
        );

        root.addView(summary);

        String streamProtocol = packet.getProtocol()
                .toLowerCase(Locale.ROOT);

        if (streamProtocol.equals("tcp")
                || streamProtocol.equals("udp")
                || packet.getInformation().contains("TCP")
                || packet.getInformation().contains("UDP")) {

            final String selectedProtocol =
                    streamProtocol.equals("udp")
                            || packet.getInformation().contains("UDP")
                            ? "udp" : "tcp";

            android.widget.Button followButton =
                    new android.widget.Button(requireContext());

            followButton.setText(
                    "Follow " + selectedProtocol.toUpperCase(Locale.ROOT)
                            + " Stream"
            );

            followButton.setOnClickListener(
                    view -> followStream(packet, selectedProtocol)
            );

            root.addView(followButton);
        }

        LinearLayout tabs = new LinearLayout(requireContext());
        tabs.setOrientation(LinearLayout.HORIZONTAL);

        android.widget.Button detailsButton =
                new android.widget.Button(requireContext());

        detailsButton.setText("Protocol Details");

        android.widget.Button hexButton =
                new android.widget.Button(requireContext());

        hexButton.setText("Hex / ASCII");

        tabs.addView(
                detailsButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        tabs.addView(
                hexButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        root.addView(tabs);

        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);

        root.addView(content);

        final boolean[] hexMode = {showHex};

        Runnable render = () -> {
            content.removeAllViews();

            if (hexMode[0]) {
                String hex = packet.getHexDump();

                if (hex.isEmpty()) {
                    TextView loading = inspectorText(
                            "Loading hex dump...",
                            13,
                            secondary,
                            false
                    );
                    content.addView(loading);

                    manager.getPacketHex(
                            currentCaptureFile,
                            packet.getNumber(),
                            new SharkHunterManager.ResultCallback() {
                                @Override
                                public void onResult(String result) {
                                    if (!viewActive) return;
                                    packet.setHexDump(result);
                                    showPacketInspector(packet, true);
                                }

                                @Override
                                public void onError(String error) {
                                    if (viewActive) showError(error);
                                }
                            }
                    );
                    return;
                }

                TextView hexView = inspectorText(
                        hex,
                        11,
                        foreground,
                        false
                );

                hexView.setTypeface(
                        android.graphics.Typeface.MONOSPACE
                );

                hexView.setBackgroundColor(panel);
                hexView.setPadding(
                        inspectorDp(10),
                        inspectorDp(10),
                        inspectorDp(10),
                        inspectorDp(10)
                );

                content.addView(hexView);
                return;
            }

            String details = packet.getProtocolDetails();

            if (details.isEmpty()) {
                TextView loading = inspectorText(
                        "Loading protocol details...",
                        13,
                        secondary,
                        false
                );
                content.addView(loading);

                manager.getPacketDetails(
                        currentCaptureFile,
                        packet.getNumber(),
                        new SharkHunterManager.ResultCallback() {
                            @Override
                            public void onResult(String result) {
                                if (!viewActive) return;
                                packet.setProtocolDetails(result);
                                showPacketInspector(packet, false);
                            }

                            @Override
                            public void onError(String error) {
                                if (viewActive) showError(error);
                            }
                        }
                );
                return;
            }


            renderProtocolTree(content, details);

        };

        detailsButton.setOnClickListener(view -> {
            if (!hexMode[0]) return;
            hexMode[0] = false;
            render.run();
        });

        hexButton.setOnClickListener(view -> {
            if (hexMode[0]) return;
            hexMode[0] = true;
            render.run();
        });

        render.run();

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);

        AlertDialog dialog =
                new AlertDialog.Builder(requireContext())
                        .setView(scroll)
                        .setPositiveButton("Close", null)
                        .create();

        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(
                        (int) (getResources()
                                .getDisplayMetrics().widthPixels * 0.96),
                        (int) (getResources()
                                .getDisplayMetrics().heightPixels * 0.85)
                );
            }
        });

        dialog.show();
    }


    private static class ProtocolNode {
        String text;
        int indent;
        java.util.List<ProtocolNode> children =
                new java.util.ArrayList<>();

        ProtocolNode(String text, int indent) {
            this.text = text;
            this.indent = indent;
        }
    }

    private void renderProtocolTree(
            LinearLayout container,
            String details) {

        java.util.List<ProtocolNode> roots =
                new java.util.ArrayList<>();

        java.util.Deque<ProtocolNode> stack =
                new java.util.ArrayDeque<>();

        for (String line : details.split("\\r?\\n")) {

            if (line.trim().isEmpty()) continue;

            if (line.contains(
                    "Running as user")) {
                continue;
            }

            int indent = 0;

            while (indent < line.length()
                    && Character.isWhitespace(
                            line.charAt(indent))) {
                indent++;
            }

            ProtocolNode node = new ProtocolNode(
                    line.trim(), indent
            );

            while (!stack.isEmpty()
                    && stack.peek().indent >= indent) {
                stack.pop();
            }

            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }

            stack.push(node);
        }

        for (ProtocolNode node : roots) {
            addProtocolNode(container, node, 0);
        }
    }

    private void addProtocolNode(
            LinearLayout parent,
            ProtocolNode node,
            int depth) {

        final boolean expandable =
                !node.children.isEmpty();

        LinearLayout wrapper =
                new LinearLayout(requireContext());

        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(
                depth == 0 ? 0xFF1B2A38 : 0xFF101820
        );

        LinearLayout.LayoutParams wrapperParams =
                new LinearLayout.LayoutParams(-1, -2);

        wrapperParams.bottomMargin =
                inspectorDp(depth == 0 ? 8 : 2);

        parent.addView(wrapper, wrapperParams);

        TextView title = inspectorText(
                (expandable ? "▸ " : "  ") + node.text,
                depth == 0 ? 13 : 12,
                expandable ? 0xFF86D5F8 : 0xFFD6E7F2,
                depth == 0
        );

        title.setPadding(
                inspectorDp(10),
                inspectorDp(8),
                inspectorDp(8),
                inspectorDp(8)
        );

        wrapper.addView(title);

        if (!expandable) return;

        LinearLayout children =
                new LinearLayout(requireContext());

        children.setOrientation(LinearLayout.VERTICAL);

        children.setPadding(
                inspectorDp(12),
                0,
                0,
                inspectorDp(4)
        );

        children.setVisibility(View.GONE);
        wrapper.addView(children);

        for (ProtocolNode child : node.children) {
            addProtocolNode(children, child, depth + 1);
        }

        title.setOnClickListener(view -> {
            boolean expand =
                    children.getVisibility() == View.GONE;

            children.setVisibility(
                    expand ? View.VISIBLE : View.GONE
            );

            title.setText(
                    (expand ? "▾ " : "▸ ") + node.text
            );
        });
    }

    private void followStream(
            SharkHunterPacket packet,
            String protocol) {

        if (currentCaptureFile == null) {
            showError("No capture file loaded.");
            return;
        }

        setBusy(true);

        final String file = currentCaptureFile;

        manager.getStreamIndex(
                file,
                packet.getNumber(),
                protocol,
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String result) {
                        if (!viewActive) {
                            return;
                        }

                        android.util.Log.d(
                                "SharkHunterStream",
                                "Protocol: " + protocol
                                        + " | Packet: " + packet.getNumber()
                                        + " | Raw index: [" + result + "]"
                        );

                        Integer parsedIndex = null;

                        for (String line : result.split("\\r?\\n")) {
                            String candidate = line.trim();

                            if (candidate.matches("[0-9]+")) {
                                try {
                                    parsedIndex = Integer.parseInt(candidate);
                                    break;
                                } catch (NumberFormatException ignored) {
                                    // Ignore out-of-range numeric output.
                                }
                            }
                        }

                        if (parsedIndex == null) {
                            setBusy(false);
                            showError(
                                    "No valid "
                                            + protocol.toUpperCase(Locale.ROOT)
                                            + " stream index for packet "
                                            + packet.getNumber()
                                            + ". Check SharkHunterStream log."
                            );
                            return;
                        }

                        final int streamNumber = parsedIndex;

                        manager.followStream(
                                file,
                                protocol,
                                streamNumber,
                                new SharkHunterManager.ResultCallback() {

                                    @Override
                                    public void onResult(String stream) {
                                        if (!viewActive) {
                                            return;
                                        }

                                        setBusy(false);

                                        showStreamViewer(
                                                protocol,
                                                streamNumber,
                                                stream
                                        );
                                    }

                                    @Override
                                    public void onError(String error) {
                                        if (viewActive) {
                                            setBusy(false);
                                            showError(error);
                                        }
                                    }
                                }
                        );
                    }

                    @Override
                    public void onError(String error) {
                        if (viewActive) {
                            setBusy(false);
                            showError(error);
                        }
                    }
                }
        );
    }




    private static class StreamChunk {
        final int direction;
        final byte[] data;

        StreamChunk(int direction, byte[] data) {
            this.direction = direction;
            this.data = data;
        }
    }

    private static class ParsedStream {
        String node0 = "";
        String node1 = "";
        final List<StreamChunk> chunks = new ArrayList<>();
    }


    private ParsedStream parseRawStream(String raw) {
        ParsedStream parsed = new ParsedStream();
        if (raw == null) return parsed;

        boolean payloadStarted = false;

        for (String line : raw.split("\\r?\\n")) {

            String trimmed = line.trim();

            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("Running as user ")) continue;

            if (trimmed.startsWith("Node 0:")) {
                parsed.node0 = trimmed.substring(7).trim();
                continue;
            }

            if (trimmed.startsWith("Node 1:")) {
                parsed.node1 = trimmed.substring(7).trim();
                continue;
            }

            if (trimmed.startsWith("Follow:")
                    || trimmed.startsWith("Filter:")) {
                continue;
            }

            if (trimmed.matches("=+")) {
                if (payloadStarted) break;
                continue;
            }

            // RAW stream payload: optional tab for Node 1,
            // followed by an even number of hex digits.
            if (!trimmed.matches("(?i)(?:[0-9a-f]{2})+")) {
                continue;
            }

            payloadStarted = true;

            byte[] bytes = new byte[trimmed.length() / 2];

            for (int i = 0; i < bytes.length; i++) {
                int offset = i * 2;

                bytes[i] = (byte) Integer.parseInt(
                        trimmed.substring(offset, offset + 2),
                        16
                );
            }

            parsed.chunks.add(
                    new StreamChunk(
                            line.startsWith("\t") ? 1 : 0,
                            bytes
                    )
            );
        }

        return parsed;
    }

    private String streamAscii(byte[] data) {
        StringBuilder out = new StringBuilder();

        for (byte value : data) {
            int b = value & 0xFF;

            if (b == 10) out.append('\n');
            else if (b == 13) out.append('\r');
            else if (b == 9) out.append('\t');
            else if (b >= 32 && b <= 126) out.append((char) b);
            else out.append('.');
        }

        return out.toString();
    }

    private String streamHex(byte[] data) {
        StringBuilder out = new StringBuilder();

        for (int offset = 0; offset < data.length; offset += 16) {
            out.append(String.format(
                    Locale.ROOT, "%08X  ", offset
            ));

            for (int i = 0; i < 16; i++) {
                if (offset + i < data.length) {
                    out.append(String.format(
                            Locale.ROOT, "%02X ",
                            data[offset + i] & 0xFF
                    ));
                } else {
                    out.append("   ");
                }
            }

            out.append(" |");

            for (int i = offset;
                    i < Math.min(offset + 16, data.length); i++) {
                int b = data[i] & 0xFF;
                out.append(
                        b >= 32 && b <= 126 ? (char) b : '.'
                );
            }

            out.append("|\n");
        }

        return out.toString();
    }

    private void showStreamViewer(
            String protocol,
            int streamNumber,
            String stream) {

        if (!isAdded()) return;

        android.util.Log.d(
                "SharkHunterRaw",
                "RAW STREAM OUTPUT:\\n" + stream
        );

        final ParsedStream parsed = parseRawStream(stream);

        android.util.Log.d(
                "SharkHunterRaw",
                "Decoded chunks: " + parsed.chunks.size()
        );

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                inspectorDp(16), inspectorDp(16),
                inspectorDp(16), inspectorDp(16)
        );
        root.setBackgroundColor(0xFF101820);

        TextView heading = inspectorText(
                protocol.toUpperCase(Locale.ROOT)
                        + " STREAM #" + streamNumber,
                20, 0xFFF0F4F8, true
        );
        root.addView(heading);

        TextView subtitle = inspectorText(
                parsed.chunks.size() + " reassembled data chunks",
                13, 0xFFA9C1D3, false
        );
        subtitle.setPadding(
                0, inspectorDp(8), 0, inspectorDp(12)
        );
        root.addView(subtitle);

        TextView endpoint0 = inspectorText(
                "▶ Node 0  " + parsed.node0,
                12, 0xFF86D5F8, true
        );
        root.addView(endpoint0);

        TextView endpoint1 = inspectorText(
                "◀ Node 1  " + parsed.node1,
                12, 0xFFFFC58A, true
        );
        endpoint1.setPadding(
                0, inspectorDp(6), 0, inspectorDp(12)
        );
        root.addView(endpoint1);

        LinearLayout controls = new LinearLayout(requireContext());
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button asciiButton = new Button(requireContext());
        asciiButton.setText("ASCII");

        Button hexButton = new Button(requireContext());
        hexButton.setText("HEX");

        controls.addView(
                asciiButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );
        controls.addView(
                hexButton,
                new LinearLayout.LayoutParams(0, -2, 1)
        );
        root.addView(controls);

        TextView payload = inspectorText(
                "", 12, 0xFFD6E7F2, false
        );
        payload.setTypeface(android.graphics.Typeface.MONOSPACE);
        payload.setTextIsSelectable(true);
        payload.setBackgroundColor(0xFF1B2A38);
        payload.setPadding(
                inspectorDp(12), inspectorDp(12),
                inspectorDp(12), inspectorDp(12)
        );
        root.addView(payload);

        final boolean[] hexMode = {false};

        Runnable render = () -> {
            android.text.SpannableStringBuilder display =
                    new android.text.SpannableStringBuilder();

            for (StreamChunk chunk : parsed.chunks) {
                int color = chunk.direction == 0
                        ? 0xFF86D5F8 : 0xFFFFC58A;

                String label = chunk.direction == 0
                        ? "▶ NODE 0  " : "◀ NODE 1  ";

                String text = label
                        + chunk.data.length + " bytes\n"
                        + (hexMode[0]
                                ? streamHex(chunk.data)
                                : streamAscii(chunk.data))
                        + "\n\n";

                int start = display.length();
                display.append(text);

                display.setSpan(
                        new android.text.style.ForegroundColorSpan(color),
                        start,
                        start + label.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }

            if (parsed.chunks.isEmpty()) {
                display.append("No stream data decoded.\n");
            }

            payload.setText(display);
        };

        asciiButton.setOnClickListener(v -> {
            hexMode[0] = false;
            render.run();
        });

        hexButton.setOnClickListener(v -> {
            hexMode[0] = true;
            render.run();
        });

        render.run();

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(scroll)
                .setNeutralButton("Copy", (d, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager)
                                    requireContext().getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE
                                    );

                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                        "SharkHunter Stream",
                                        payload.getText().toString()
                                )
                        );
                    }
                })
                .setPositiveButton("Close", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(
                        (int) (getResources()
                                .getDisplayMetrics().widthPixels * 0.96),
                        (int) (getResources()
                                .getDisplayMetrics().heightPixels * 0.85)
                );
            }
        });

        dialog.show();
    }

    private void showTextDialog(
            String title,
            String content) {

        if (!isAdded()) {
            return;
        }

        TextView text = new TextView(requireContext());

        text.setText(
                content == null ? "" : content
        );

        text.setTextSize(12);
        text.setTextColor(0xFF202020);

        text.setPadding(
                24,
                24,
                24,
                24
        );

        text.setTextIsSelectable(true);

        ScrollView scroll =
                new ScrollView(requireContext());

        scroll.addView(text);

        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private void importCapture(Uri uri) {
        if (uri == null) return;

        if (manager.isUnsavedLiveCapture()) {
            promptSaveOrDiscard();
            return;
        }

        if (manager.isCapturing()) {
            showError(
                    "Stop the current capture before opening a file."
            );
            return;
        }

        setBusy(true);
        setStatus("Importing capture...");

        manager.importCapture(
                uri,
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String path) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);

                        /*
                         * Invalidate results belonging
                         * to the previously loaded file.
                         */
                        requestGeneration++;

                        currentCaptureFile = path;
                        refreshPending = true;

                        packetAdapter.clearPackets();
                        updatePacketCount();

                        setStatus("Capture imported");

                        if (!displayPaused) {
                            refreshPackets();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (viewActive) {
                            setBusy(false);

                            showError(
                                    "Import failed: " + error
                            );
                        }
                    }
                }
        );
    }

    private void saveCurrentCapture() {
        if (currentCaptureFile == null
                || currentCaptureFile.isEmpty()) {

            showError("No capture available.");
            return;
        }

        pendingSaveFile = currentCaptureFile;
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());

        saveLauncher.launch(
                "SharkHunter_" + timestamp + ".pcapng");
    }

    private void exportCapture(Uri uri) {
        final String file = pendingSaveFile;
        pendingSaveFile = null;

        if (uri == null) {
            if (viewActive && manager.isUnsavedLiveCapture()) {
                promptSaveOrDiscard();
            }
            return;
        }

        if (file == null || file.isEmpty()) {

            showError("No capture available.");
            return;
        }

        setBusy(true);
        setStatus("Saving capture...");

        manager.exportCapture(
                file,
                uri,
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String result) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);
                        setStatus(result);
                        showToast(result);

                        if (file.equals(currentCaptureFile)
                                && manager.isUnsavedLiveCapture()) {
                            discardCurrentCapture();
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (viewActive) {
                            setBusy(false);
                            showError("Save failed: " + error);

                            if (manager.isUnsavedLiveCapture()) {
                                promptSaveOrDiscard();
                            }
                        }
                    }
                }
        );
    }

    private void showCaptureInformation() {
        if (currentCaptureFile == null
                || currentCaptureFile.isEmpty()) {

            showError("No capture loaded.");
            return;
        }

        setBusy(true);

        manager.getCaptureInfo(
                currentCaptureFile,
                new SharkHunterManager.ResultCallback() {

                    @Override
                    public void onResult(String result) {
                        if (!viewActive) {
                            return;
                        }

                        setBusy(false);

                        showTextDialog(
                                "Capture Information",
                                result
                        );
                    }

                    @Override
                    public void onError(String error) {
                        if (viewActive) {
                            setBusy(false);
                            showError(error);
                        }
                    }
                }
        );
    }

    private void setBusy(boolean busy) {
        if (progressBar != null) {
            progressBar.setVisibility(
                    busy
                            ? View.VISIBLE
                            : View.GONE
            );
        }
    }

    private void setStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private void showError(String message) {
        setStatus(message);
        showToast(message);
    }

    private void showToast(String message) {
        if (isAdded()) {
            Toast.makeText(
                    requireContext(),
                    message,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onDestroyView() {
        viewActive = false;

        /*
         * Invalidate any outstanding results.
         * Do not shut down the singleton manager,
         * since a capture may still be running.
         */
        requestGeneration++;

        refreshPending = false;
        refreshInProgress = false;

        handler.removeCallbacks(refreshRunnable);

        if (manager != null) {
            manager.setListener(null);
        }

        super.onDestroyView();
    }
}
