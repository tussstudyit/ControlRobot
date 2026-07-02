package com.example.robot;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_BLUETOOTH_CONNECT = 42;
    private static final int REQUEST_ENABLE_BLUETOOTH = 43;
    private static final String DEFAULT_DEVICE_NAME = "ESP32_ROBOT";
    private static final long SERVO_SEND_INTERVAL_MS = 80L;
    private static final int BASE_START_ANGLE = 90;
    private static final int DEFAULT_SERVO_START_ANGLE = 90;
    private static final char SERVO_BASE_ID = 'A';
    private static final char SERVO_SHOULDER_ID = 'V';
    private static final char SERVO_ELBOW_ID = 'C';
    private static final char SERVO_WRIST_ID = 'D';
    private static final char SERVO_GRIP_ID = 'E';
    private static final String PREFS_NAME = "robot_servo_angles";
    private static final String PREF_BASE = "base_angle";
    private static final String PREF_SHOULDER = "shoulder_angle";
    private static final String PREF_ELBOW = "elbow_angle";
    private static final String PREF_WRIST = "wrist_angle";
    private static final String PREF_GRIP = "grip_angle";
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bluetoothExecutor = Executors.newSingleThreadExecutor();
    private final List<DeviceItem> pairedDevices = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private ArrayAdapter<DeviceItem> deviceAdapter;
    private SharedPreferences servoPrefs;

    private Spinner deviceSpinner;
    private TextView statusChip;
    private TextView deviceMetric;
    private TextView linkMetric;
    private TextView lastCommandMetric;
    private TextView logText;
    private LinearLayout connectionDrawer;
    private ImageButton drawerToggleButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button forwardButton;
    private Button backwardButton;
    private Button leftButton;
    private Button rightButton;
    private Button stopButton;
    private Button emergencyStopButton;
    private Button armCenterButton;
    private Button armTestButton;

    private boolean isConnected = false;
    private boolean drawerOpen = false;
    private StringBuilder commandLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        servoPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setupConnectionDrawer();
        setupBluetooth();
        setupDevicePicker();
        setupControls();
        setupServoControls();
        setConnectionState(false, "Standby");
        ensureBluetoothReady();
    }

    private void bindViews() {
        deviceSpinner = findViewById(R.id.deviceSpinner);
        statusChip = findViewById(R.id.statusChip);
        deviceMetric = findViewById(R.id.deviceMetric);
        linkMetric = findViewById(R.id.linkMetric);
        lastCommandMetric = findViewById(R.id.lastCommandMetric);
        logText = findViewById(R.id.logText);
        connectionDrawer = findViewById(R.id.connectionDrawer);
        drawerToggleButton = findViewById(R.id.drawerToggleButton);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        forwardButton = findViewById(R.id.forwardButton);
        backwardButton = findViewById(R.id.backwardButton);
        leftButton = findViewById(R.id.leftButton);
        rightButton = findViewById(R.id.rightButton);
        stopButton = findViewById(R.id.stopButton);
        emergencyStopButton = findViewById(R.id.emergencyStopButton);
        armCenterButton = findViewById(R.id.armCenterButton);
        armTestButton = findViewById(R.id.armTestButton);
        ImageButton refreshButton = findViewById(R.id.refreshButton);
        refreshButton.setOnClickListener(view -> loadPairedDevices());
    }

    private void setupConnectionDrawer() {
        if (connectionDrawer == null || drawerToggleButton == null) {
            return;
        }

        drawerToggleButton.setOnClickListener(view -> setDrawerOpen(!drawerOpen, true));
        connectionDrawer.post(() -> setDrawerOpen(false, false));
    }

    private void setDrawerOpen(boolean open, boolean animate) {
        if (connectionDrawer == null || drawerToggleButton == null) {
            return;
        }

        drawerOpen = open;
        float drawerWidth = connectionDrawer.getWidth();
        float drawerX = open ? 0f : -drawerWidth;
        float toggleX = open ? drawerWidth : 0f;

        if (animate) {
            connectionDrawer.animate().translationX(drawerX).setDuration(220).start();
            drawerToggleButton.animate().translationX(toggleX).setDuration(220).start();
        } else {
            connectionDrawer.setTranslationX(drawerX);
            drawerToggleButton.setTranslationX(toggleX);
        }

        drawerToggleButton.setImageResource(
                open ? R.drawable.ic_chevron_left : R.drawable.ic_chevron_right
        );
    }

    private void setupBluetooth() {
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
    }

    private void setupDevicePicker() {
        deviceAdapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,
                pairedDevices
        );
        deviceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
    }

    private void setupControls() {
        connectButton.setOnClickListener(view -> connectSelectedDevice());
        disconnectButton.setOnClickListener(view -> disconnectRobot());

        bindDriveButton(forwardButton, 'F');
        bindDriveButton(backwardButton, 'B');
        bindDriveButton(leftButton, 'L');
        bindDriveButton(rightButton, 'R');

        stopButton.setOnClickListener(view -> sendCommand('S'));
        emergencyStopButton.setOnClickListener(view -> sendCommand('S'));
        if (armCenterButton != null) {
            armCenterButton.setOnClickListener(view -> centerArm());
        }
        if (armTestButton != null) {
            armTestButton.setOnClickListener(view -> sendCommand('X'));
        }
    }

    private void setupServoControls() {
        bindServoSlider(R.id.servoBaseSeek, R.id.servoBaseValue, SERVO_BASE_ID, "Base", PREF_BASE, BASE_START_ANGLE);
        bindServoSlider(R.id.servoShoulderSeek, R.id.servoShoulderValue, SERVO_SHOULDER_ID, "Shoulder x2", PREF_SHOULDER, DEFAULT_SERVO_START_ANGLE);
        bindServoSlider(R.id.servoElbowSeek, R.id.servoElbowValue, SERVO_ELBOW_ID, "Elbow", PREF_ELBOW, DEFAULT_SERVO_START_ANGLE);
        bindServoSlider(R.id.servoWristSeek, R.id.servoWristValue, SERVO_WRIST_ID, "Wrist", PREF_WRIST, DEFAULT_SERVO_START_ANGLE);
        bindServoSlider(R.id.servoGripSeek, R.id.servoGripValue, SERVO_GRIP_ID, "Grip", PREF_GRIP, DEFAULT_SERVO_START_ANGLE);
    }

    private void bindServoSlider(
            int seekId,
            int valueId,
            char servoId,
            String label,
            String prefKey,
            int defaultAngle
    ) {
        SeekBar seekBar = findViewById(seekId);
        TextView valueText = findViewById(valueId);
        if (seekBar == null || valueText == null) {
            return;
        }

        long[] lastSentAt = {0L};
        int savedAngle = getSavedServoAngle(prefKey, defaultAngle);
        seekBar.setProgress(savedAngle);
        valueText.setText(String.valueOf(seekBar.getProgress()));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueText.setText(String.valueOf(progress));
                if (!fromUser) {
                    return;
                }

                saveServoAngle(prefKey, progress);
                long now = SystemClock.uptimeMillis();
                if (now - lastSentAt[0] >= SERVO_SEND_INTERVAL_MS) {
                    lastSentAt[0] = now;
                    sendServoCommand(servoId, progress, label);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveServoAngle(prefKey, seekBar.getProgress());
                sendServoCommand(servoId, seekBar.getProgress(), label);
            }
        });
    }

    private void centerArm() {
        setServoSliderProgress(R.id.servoBaseSeek, BASE_START_ANGLE);
        setServoSliderProgress(R.id.servoShoulderSeek, DEFAULT_SERVO_START_ANGLE);
        setServoSliderProgress(R.id.servoElbowSeek, DEFAULT_SERVO_START_ANGLE);
        setServoSliderProgress(R.id.servoWristSeek, DEFAULT_SERVO_START_ANGLE);
        setServoSliderProgress(R.id.servoGripSeek, DEFAULT_SERVO_START_ANGLE);
        saveCurrentServoAngles();

        sendServoCommand(SERVO_BASE_ID, BASE_START_ANGLE, "Base");
        sendServoCommand(SERVO_SHOULDER_ID, DEFAULT_SERVO_START_ANGLE, "Shoulder x2");
        sendServoCommand(SERVO_ELBOW_ID, DEFAULT_SERVO_START_ANGLE, "Elbow");
        sendServoCommand(SERVO_WRIST_ID, DEFAULT_SERVO_START_ANGLE, "Wrist");
        sendServoCommand(SERVO_GRIP_ID, DEFAULT_SERVO_START_ANGLE, "Grip");
    }

    private void setServoSliderProgress(int seekId, int progress) {
        SeekBar seekBar = findViewById(seekId);
        if (seekBar != null) {
            seekBar.setProgress(progress);
        }
    }

    private int getSavedServoAngle(String key, int defaultAngle) {
        if (servoPrefs == null) {
            return defaultAngle;
        }
        return Math.max(0, Math.min(180, servoPrefs.getInt(key, defaultAngle)));
    }

    private void saveServoAngle(String key, int angle) {
        if (servoPrefs == null) {
            return;
        }
        servoPrefs.edit().putInt(key, Math.max(0, Math.min(180, angle))).apply();
    }

    private void saveCurrentServoAngles() {
        saveSeekBarAngle(R.id.servoBaseSeek, PREF_BASE);
        saveSeekBarAngle(R.id.servoShoulderSeek, PREF_SHOULDER);
        saveSeekBarAngle(R.id.servoElbowSeek, PREF_ELBOW);
        saveSeekBarAngle(R.id.servoWristSeek, PREF_WRIST);
        saveSeekBarAngle(R.id.servoGripSeek, PREF_GRIP);
    }

    private void saveSeekBarAngle(int seekId, String key) {
        SeekBar seekBar = findViewById(seekId);
        if (seekBar != null) {
            saveServoAngle(key, seekBar.getProgress());
        }
    }

    private void bindDriveButton(Button button, char command) {
        button.setOnClickListener(view -> {
        });
        button.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) {
                return true;
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                sendCommand(command);
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sendCommand('S');
                view.performClick();
                return true;
            }

            return true;
        });
    }

    private void ensureBluetoothReady() {
        if (bluetoothAdapter == null) {
            showError("This phone does not support Bluetooth");
            return;
        }

        if (!hasBluetoothConnectPermission()) {
            requestBluetoothPermission();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);
            return;
        }

        loadPairedDevices();
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_CONNECT
            );
        }
    }

    private void loadPairedDevices() {
        if (bluetoothAdapter == null) {
            showError("Bluetooth unavailable");
            return;
        }

        if (!hasBluetoothConnectPermission()) {
            requestBluetoothPermission();
            return;
        }

        pairedDevices.clear();
        try {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            if (bondedDevices != null) {
                for (BluetoothDevice device : bondedDevices) {
                    pairedDevices.add(new DeviceItem(device));
                }
            }
        } catch (SecurityException exception) {
            showError("Bluetooth permission denied");
            return;
        }

        if (pairedDevices.isEmpty()) {
            pairedDevices.add(DeviceItem.empty("No paired devices"));
            deviceMetric.setText("--");
            deviceAdapter.notifyDataSetChanged();
            appendLog("Pair ESP32_ROBOT in Android Bluetooth settings first.");
        } else {
            deviceAdapter.notifyDataSetChanged();
            selectDefaultDevice();
            appendLog("Paired devices loaded: " + pairedDevices.size());
        }
    }

    private void selectDefaultDevice() {
        for (int index = 0; index < pairedDevices.size(); index++) {
            BluetoothDevice device = pairedDevices.get(index).device;
            if (device != null && DEFAULT_DEVICE_NAME.equals(getDeviceName(device))) {
                deviceSpinner.setSelection(index);
                deviceMetric.setText(DEFAULT_DEVICE_NAME);
                return;
            }
        }

        BluetoothDevice firstDevice = pairedDevices.get(0).device;
        deviceMetric.setText(firstDevice == null ? "--" : getDeviceName(firstDevice));
    }

    private void connectSelectedDevice() {
        if (!hasBluetoothConnectPermission()) {
            requestBluetoothPermission();
            return;
        }

        DeviceItem selectedItem = (DeviceItem) deviceSpinner.getSelectedItem();
        if (selectedItem == null || selectedItem.device == null) {
            appendLog("No paired Bluetooth device selected.");
            return;
        }

        setConnectionState(false, "Connecting");
        connectButton.setEnabled(false);
        appendLog("Connecting to " + selectedItem + "...");

        bluetoothExecutor.execute(() -> {
            closeSocket();
            try {
                BluetoothSocket socket =
                        selectedItem.device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                bluetoothSocket = socket;
                mainHandler.post(() -> {
                    setConnectionState(true, getDeviceName(selectedItem.device));
                    sendCommand('S');
                    appendLog("Connected to " + selectedItem + ".");
                });
            } catch (IOException | SecurityException exception) {
                closeSocket();
                mainHandler.post(() -> {
                    connectButton.setEnabled(true);
                    showError("Connection failed");
                    appendLog("Connection failed: " + exception.getMessage());
                });
            }
        });
    }

    private void sendCommand(char command) {
        if (!isConnected || outputStream == null) {
            appendLog("Not connected. Command " + command + " skipped.");
            return;
        }

        bluetoothExecutor.execute(() -> {
            try {
                outputStream.write(command);
                outputStream.flush();
                mainHandler.post(() -> recordCommand(command));
            } catch (IOException exception) {
                closeSocket();
                mainHandler.post(() -> {
                    setConnectionState(false, "Lost");
                    showError("Bluetooth link lost");
                    appendLog("Bluetooth link lost: " + exception.getMessage());
                });
            }
        });
    }

    private void sendServoCommand(char servoId, int angle, String label) {
        int safeAngle = Math.max(0, Math.min(180, angle));
        String packet = servoId + String.valueOf(safeAngle) + "\n";

        if (!isConnected || outputStream == null) {
            appendLog("Not connected. Servo " + label + " skipped.");
            return;
        }

        bluetoothExecutor.execute(() -> {
            try {
                outputStream.write(packet.getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();
                mainHandler.post(() -> recordServoCommand(servoId, safeAngle, label));
            } catch (IOException exception) {
                closeSocket();
                mainHandler.post(() -> {
                    setConnectionState(false, "Lost");
                    showError("Bluetooth link lost");
                    appendLog("Bluetooth link lost: " + exception.getMessage());
                });
            }
        });
    }

    private void recordCommand(char command) {
        lastCommandMetric.setText(String.valueOf(command));
        appendLog("TX > " + command + "   " + commandLabel(command));
    }

    private void recordServoCommand(char servoId, int angle, String label) {
        String packet = servoId + String.valueOf(angle);
        lastCommandMetric.setText(packet);
        appendLog("TX > " + packet + "   Servo " + label);
    }

    private String commandLabel(char command) {
        switch (command) {
            case 'F':
                return "Forward";
            case 'B':
                return "Backward";
            case 'L':
                return "Turn left";
            case 'R':
                return "Turn right";
            case 'S':
                return "Stop";
            case 'X':
                return "Servo self-test";
            default:
                return "Unknown";
        }
    }

    private void disconnectRobot() {
        sendStopBeforeDisconnect();
        closeSocket();
        setConnectionState(false, "Standby");
        appendLog("Disconnected.");
    }

    private void sendStopBeforeDisconnect() {
        if (outputStream == null) {
            return;
        }

        try {
            outputStream.write('S');
            outputStream.flush();
        } catch (IOException ignored) {
        }
    }

    private void setConnectionState(boolean connected, String detail) {
        isConnected = connected;
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        forwardButton.setEnabled(connected);
        backwardButton.setEnabled(connected);
        leftButton.setEnabled(connected);
        rightButton.setEnabled(connected);
        stopButton.setEnabled(connected);
        emergencyStopButton.setEnabled(connected);
        if (armCenterButton != null) {
            armCenterButton.setEnabled(connected);
        }
        if (armTestButton != null) {
            armTestButton.setEnabled(connected);
        }
        setServoControlsEnabled(connected);

        if (connected) {
            statusChip.setText("ONLINE");
            statusChip.setTextColor(getColor(R.color.green));
            statusChip.setBackgroundResource(R.drawable.chip_connected);
            linkMetric.setText("Online");
            linkMetric.setTextColor(getColor(R.color.green));
            deviceMetric.setText(detail);
        } else if ("Lost".equals(detail)) {
            statusChip.setText("LOST");
            statusChip.setTextColor(getColor(R.color.red));
            statusChip.setBackgroundResource(R.drawable.chip_error);
            linkMetric.setText("Lost");
            linkMetric.setTextColor(getColor(R.color.red));
        } else if ("Connecting".equals(detail)) {
            statusChip.setText("LINKING");
            statusChip.setTextColor(getColor(R.color.amber));
            statusChip.setBackgroundResource(R.drawable.chip_idle);
            linkMetric.setText("Linking");
            linkMetric.setTextColor(getColor(R.color.amber));
        } else {
            statusChip.setText("IDLE");
            statusChip.setTextColor(getColor(R.color.amber));
            statusChip.setBackgroundResource(R.drawable.chip_idle);
            linkMetric.setText("Standby");
            linkMetric.setTextColor(getColor(R.color.amber));
        }
    }

    private void setServoControlsEnabled(boolean enabled) {
        int[] seekIds = {
                R.id.servoBaseSeek,
                R.id.servoShoulderSeek,
                R.id.servoElbowSeek,
                R.id.servoWristSeek,
                R.id.servoGripSeek
        };

        for (int seekId : seekIds) {
            SeekBar seekBar = findViewById(seekId);
            if (seekBar != null) {
                seekBar.setEnabled(enabled);
            }
        }
    }

    private void appendLog(String message) {
        if (commandLog.length() > 0) {
            commandLog.append('\n');
        }
        commandLog.append(message);

        String[] lines = commandLog.toString().split("\n");
        if (lines.length > 5) {
            StringBuilder compactLog = new StringBuilder();
            for (int index = lines.length - 5; index < lines.length; index++) {
                if (compactLog.length() > 0) {
                    compactLog.append('\n');
                }
                compactLog.append(lines[index]);
            }
            commandLog = compactLog;
        }

        logText.setText(commandLog.toString());
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setConnectionState(false, "Lost");
    }

    private String getDeviceName(BluetoothDevice device) {
        if (!hasBluetoothConnectPermission()) {
            return "Bluetooth device";
        }

        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException exception) {
            return "Bluetooth device";
        }
    }

    private void closeSocket() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException ignored) {
        }

        outputStream = null;
        bluetoothSocket = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            loadPairedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ensureBluetoothReady();
        } else if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            showError("Bluetooth permission is required");
        }
    }

    @Override
    protected void onPause() {
        saveCurrentServoAngles();
        sendStopBeforeDisconnect();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveCurrentServoAngles();
        sendStopBeforeDisconnect();
        closeSocket();
        bluetoothExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class DeviceItem {
        private final BluetoothDevice device;
        private final String label;

        private DeviceItem(BluetoothDevice device) {
            this.device = device;
            this.label = null;
        }

        private DeviceItem(String label) {
            this.device = null;
            this.label = label;
        }

        private static DeviceItem empty(String label) {
            return new DeviceItem(label);
        }

        @NonNull
        @Override
        public String toString() {
            if (device == null) {
                return label;
            }

            try {
                String name = device.getName();
                if (name == null || name.trim().isEmpty()) {
                    return device.getAddress();
                }
                return name + "  " + device.getAddress();
            } catch (SecurityException exception) {
                return "Bluetooth device";
            }
        }
    }
}
