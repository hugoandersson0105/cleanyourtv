package com.gathin.tvhome;

// Ported from the "Clean TV for TCL G10" fork, github.com/itsmevizz/cleanyourtv (branch clean-tv-launcher, MIT),
// for the Control Center panel (control_center). Unchanged apart from this note.

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The TV's paired Bluetooth devices: name, type, whether each is connected, and its battery where it can
 * be read. Battery comes from the standard Battery Service of a low-energy device (the remote), read with a
 * short GATT connection; a classic device gets one only if Android exposes it. Needs BLUETOOTH_CONNECT,
 * which the install script grants.
 */
final class BluetoothInfo {

    static final class Device {
        final String name;
        final String address;
        final boolean lowEnergy;
        boolean connected;
        /** 0 to 100, or -1 when this device does not report a battery to the TV. */
        int battery = -1;

        Device(String name, String address, boolean lowEnergy) {
            this.name = name;
            this.address = address;
            this.lowEnergy = lowEnergy;
        }
    }

    interface Listener {
        /** Called on the main thread with the paired devices, or null when Bluetooth is off or unavailable. */
        void onDevices(List<Device> devices);
    }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final long GATT_TIMEOUT_MS = 6_000L;
    private static final long PROFILE_TIMEOUT_MS = 3_000L;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    BluetoothInfo(Context ctx) {
        this.ctx = ctx;
    }

    /** Whether the launcher may read Bluetooth names and state (granted with pm grant at install). */
    boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Reads the paired devices, their connection state and their batteries, then reports on the main thread. */
    void refresh(Listener listener) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled() || !hasPermission()) {
            main.post(() -> listener.onDevices(null));
            return;
        }
        List<Device> devices = new ArrayList<>();
        List<BluetoothDevice> raw = new ArrayList<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName() != null ? d.getName() : d.getAddress();
                boolean le = d.getType() == BluetoothDevice.DEVICE_TYPE_LE;
                devices.add(new Device(name, d.getAddress(), le));
                raw.add(d);
            }
        } catch (SecurityException e) {
            main.post(() -> listener.onDevices(null));
            return;
        }
        markConnected(adapter, devices, raw, () -> readBatteries(devices, raw, () -> listener.onDevices(devices)));
    }

    /** Asks the headset, audio and HID profiles which devices are connected, then calls done. */
    private void markConnected(BluetoothAdapter adapter, List<Device> devices, List<BluetoothDevice> raw, Runnable done) {
        // HEADSET and A2DP are public; 4 is the HID host profile (BluetoothProfile.INPUT_DEVICE, hidden in the SDK).
        int[] profiles = {BluetoothProfile.HEADSET, BluetoothProfile.A2DP, 4};
        Set<String> connected = new HashSet<>();
        int[] pending = {profiles.length};
        // A profile this TV does not offer (the headset profile here) never answers: go on after a while anyway.
        final boolean[] reported = {false};
        Runnable once = () -> {
            if (reported[0]) return;
            reported[0] = true;
            for (Device d : devices) d.connected = connected.contains(d.address);
            done.run();
        };
        main.postDelayed(once, PROFILE_TIMEOUT_MS);
        for (int profile : profiles) {
            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int p, BluetoothProfile proxy) {
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) connected.add(d.getAddress());
                    } catch (SecurityException ignored) {
                    }
                    adapter.closeProfileProxy(p, proxy);
                    finishOne(pending, connected, once);
                }

                @Override
                public void onServiceDisconnected(int p) {
                    finishOne(pending, connected, once);
                }
            }, profile);
        }
    }

    private void finishOne(int[] pending, Set<String> connected, Runnable once) {
        synchronized (pending) {
            if (--pending[0] > 0) return;
        }
        main.post(once);
    }

    /** Reads each connected device's battery one after another, then calls done. */
    private void readBatteries(List<Device> devices, List<BluetoothDevice> raw, Runnable done) {
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).connected) index.add(i);
        }
        readNext(0, index, devices, raw, done);
    }

    private void readNext(int at, List<Integer> index, List<Device> devices, List<BluetoothDevice> raw, Runnable done) {
        if (at >= index.size()) {
            done.run();
            return;
        }
        int i = index.get(at);
        Device dev = devices.get(i);
        BluetoothDevice bd = raw.get(i);
        Runnable next = () -> readNext(at + 1, index, devices, raw, done);
        if (dev.lowEnergy) {
            readGattBattery(bd, dev, next);
        } else {
            dev.battery = classicBattery(bd);
            next.run();
        }
    }

    /** The standard Battery Level of a low-energy device, read over a short GATT connection. */
    private void readGattBattery(BluetoothDevice bd, Device dev, Runnable done) {
        final BluetoothGatt[] gatt = new BluetoothGatt[1];
        final boolean[] finished = {false};
        final Runnable[] self = new Runnable[1];
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            main.removeCallbacks(self[0]);
            if (gatt[0] != null) {
                try {
                    gatt[0].disconnect();
                    gatt[0].close();
                } catch (SecurityException ignored) {
                    // BLUETOOTH_CONNECT was revoked while the read ran; the connection dies with the process.
                }
            }
            main.post(done);
        };
        self[0] = finish;
        main.postDelayed(finish, GATT_TIMEOUT_MS);
        BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                boolean discovering = false;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        discovering = g.discoverServices();
                    } catch (SecurityException ignored) {
                    }
                }
                if (!discovering) finish.run();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService service = g.getService(BATTERY_SERVICE);
                BluetoothGattCharacteristic level = service != null ? service.getCharacteristic(BATTERY_LEVEL) : null;
                boolean reading = false;
                if (level != null) {
                    try {
                        reading = g.readCharacteristic(level);
                    } catch (SecurityException ignored) {
                    }
                }
                if (!reading) finish.run();
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                byte[] value = c.getValue();
                if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                    dev.battery = value[0] & 0xFF;
                }
                finish.run();
            }
        };
        try {
            gatt[0] = bd.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            finish.run();
        }
    }

    /** A classic device's battery, if Android exposes it (hidden on most builds); -1 otherwise. */
    private static int classicBattery(BluetoothDevice bd) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(bd);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
