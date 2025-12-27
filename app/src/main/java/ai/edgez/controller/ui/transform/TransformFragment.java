package ai.edgez.controller.ui.transform;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.navigation.Navigation;

import ai.edgez.controller.R;
import ai.edgez.controller.databinding.FragmentTransformBinding;
import ai.edgez.controller.databinding.ItemTransformBinding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment that demonstrates a responsive layout pattern where the format of the content
 * transforms depending on the size of the screen. Specifically this Fragment shows items in
 * the [RecyclerView] using LinearLayoutManager in a small screen
 * and shows items using GridLayoutManager in a large screen.
 */
public class TransformFragment extends Fragment {

    private static final String TAG = "DevicesFragment";
    private static final String SERVICE_TYPE = "_lwm2m._udp.";
    private static final String NAME_FILTER = "wakaama-lwm2m";
    private static final long REDISCOVER_INTERVAL_MS = 30_000L;
    private static final int REST_PORT = 8088;
    private static final String CLIENTS_PATH = "/api/clients";

    private FragmentTransformBinding binding;
    private DevicesAdapter adapter;
    private final List<Lwm2mService> services = new ArrayList<>();
    private final Map<String, List<Device>> devicesByService = new HashMap<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startDiscovery();
                } else {
                    Log.w(TAG, "NSD permission denied; discovery skipped");
                }
            });
    private final Runnable periodicRediscover = new Runnable() {
        @Override
        public void run() {
            startDiscovery();
            handler.postDelayed(this, REDISCOVER_INTERVAL_MS);
        }
    };

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentTransformBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        RecyclerView recyclerView = binding.recyclerviewTransform;
        adapter = new DevicesAdapter();
        adapter.setOnDeviceClick(device -> {
            Bundle args = new Bundle();
            args.putString("endpoint", device.endpoint);
            args.putString("host", device.address);
            Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_content_main)
                    .navigate(R.id.action_transform_to_deviceDetail, args);
        });
        recyclerView.setAdapter(adapter);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        startDiscovery();
        handler.postDelayed(periodicRediscover, REDISCOVER_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(periodicRediscover);
        stopDiscovery();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        stopDiscovery();
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        binding = null;
    }

    private void startDiscovery() {
        stopDiscovery();
        Context fragmentContext = requireContext();
        String permission = requiredPermission();
        if (ContextCompat.checkSelfPermission(fragmentContext, permission)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission);
            return;
        }

        Context appContext = fragmentContext.getApplicationContext();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available");
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String type = serviceInfo.getServiceType();
                if (type == null || !type.equalsIgnoreCase(SERVICE_TYPE)) {
                    return;
                }
                String name = serviceInfo.getServiceName();
                if (name == null || !name.toLowerCase(Locale.US).contains(NAME_FILTER)) {
                    return;
                }
                nsdManager.resolveService(serviceInfo, new ResolveListener());
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                if (name != null) {
                    removeServiceByName(name);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped for " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Start discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Stop discovery failed: " + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Discovery not started", e);
        }
    }

    private void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Stop discovery ignored", e);
            }
        }
        discoveryListener = null;
    }

    private String requiredPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.NEARBY_WIFI_DEVICES;
        }
        return Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void addOrUpdateService(Lwm2mService service) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> addOrUpdateService(service));
            return;
        }
        int existingIndex = -1;
        for (int i = 0; i < services.size(); i++) {
            if (services.get(i).sameService(service)) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            services.set(existingIndex, service);
        } else {
            services.add(service);
        }
        rebuildDeviceList();
    }

    private void removeServiceByName(String name) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> removeServiceByName(name));
            return;
        }
        List<Lwm2mService> updated = new ArrayList<>();
        for (Lwm2mService svc : services) {
            if (!svc.name.equals(name)) {
                updated.add(svc);
            }
        }
        services.clear();
        services.addAll(updated);
        devicesByService.remove(name);
        rebuildDeviceList();
    }

    private class ResolveListener implements NsdManager.ResolveListener {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.w(TAG, "Resolve failed for " + serviceInfo.getServiceName() + ": " + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            InetAddress host = serviceInfo.getHost();
            if (host == null) {
                return;
            }
            String address = host.getHostAddress();
            int port = serviceInfo.getPort();
            Lwm2mService service = new Lwm2mService(serviceInfo.getServiceName(), address, port);
            addOrUpdateService(service);
            fetchDevices(service);
        }
    }

    private void fetchDevices(Lwm2mService service) {
        if (ioExecutor == null || ioExecutor.isShutdown()) {
            ioExecutor = Executors.newSingleThreadExecutor();
        }
        ioExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String host = formatHost(service.address);
                URL url = new URL("http://" + host + ":" + REST_PORT + CLIENTS_PATH);
                Log.d(TAG, "Fetching devices from " + url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);

                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "REST fetch failed for " + service.address + " code=" + code);
                    return;
                }
                String body = readAll(connection.getInputStream());
                Log.d(TAG, "HTTP 200 from " + service.address + " bodyLen=" + body.length());
                if (body.length() < 256) {
                    Log.d(TAG, "Body=" + body);
                }
                List<Device> devices = parseDevices(body, service);
                Log.d(TAG, "Parsed " + devices.size() + " devices from " + service.address);
                handler.post(() -> updateDevicesForService(service.name, devices));
            } catch (IOException e) {
                Log.w(TAG, "REST fetch error for " + service.address, e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void updateDevicesForService(String serviceName, List<Device> devices) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> updateDevicesForService(serviceName, devices));
            return;
        }
        devicesByService.put(serviceName, devices);
        rebuildDeviceList();
    }

    private void rebuildDeviceList() {
        List<Device> merged = new ArrayList<>();
        for (List<Device> list : devicesByService.values()) {
            merged.addAll(list);
        }
        adapter.submitList(merged);
    }

    private List<Device> parseDevices(String body, Lwm2mService service) {
        List<Device> devices = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String endpoint = obj.optString("endpoint", "");
                if (endpoint.isEmpty()) {
                    continue;
                }
                devices.add(new Device(endpoint, service.address, REST_PORT));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse devices JSON", e);
        }
        return devices;
    }

    private String formatHost(String addr) {
        if (addr == null) {
            return "";
        }
        if (addr.contains(":") && !(addr.startsWith("[") && addr.endsWith("]"))) {
            return "[" + addr + "]";
        }
        return addr;
    }

    private String readAll(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static class Lwm2mService {
        final String name;
        final String address;
        final int port;

        Lwm2mService(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        boolean sameService(Lwm2mService other) {
            return name.equals(other.name) && address.equals(other.address) && port == other.port;
        }
    }

    private static class Device {
        final String endpoint;
        final String address;
        final int port;

        Device(String endpoint, String address, int port) {
            this.endpoint = endpoint;
            this.address = address;
            this.port = port;
        }
    }

    private static class DevicesAdapter extends ListAdapter<Device, DeviceViewHolder> {

        interface OnDeviceClick {
            void onClick(Device device);
        }

        private OnDeviceClick click;

        void setOnDeviceClick(OnDeviceClick click) {
            this.click = click;
        }

        protected DevicesAdapter() {
            super(new DiffUtil.ItemCallback<Device>() {
                @Override
                public boolean areItemsTheSame(@NonNull Device oldItem, @NonNull Device newItem) {
                    return oldItem.endpoint.equals(newItem.endpoint) && oldItem.address.equals(newItem.address) && oldItem.port == newItem.port;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Device oldItem, @NonNull Device newItem) {
                    return areItemsTheSame(oldItem, newItem);
                }
            });
        }

        @NonNull
        @Override
        public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Inflate with parent to ensure proper LayoutParams and non-null binding views
            ItemTransformBinding binding = ItemTransformBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new DeviceViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
            Device device = getItem(position);
            holder.name.setText(device.endpoint);
            holder.address.setText(device.address + ":" + device.port);
            holder.itemView.setOnClickListener(v -> {
                if (click != null) click.onClick(device);
            });
        }
    }

    private static class DeviceViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView address;

        DeviceViewHolder(ItemTransformBinding binding) {
            super(binding.getRoot());
            name = binding.textServiceName;
            address = binding.textServiceAddress;
        }
    }
}