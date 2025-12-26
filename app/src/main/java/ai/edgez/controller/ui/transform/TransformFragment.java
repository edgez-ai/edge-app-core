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

import ai.edgez.controller.databinding.FragmentTransformBinding;
import ai.edgez.controller.databinding.ItemTransformBinding;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private FragmentTransformBinding binding;
    private DevicesAdapter adapter;
    private final List<Lwm2mService> services = new ArrayList<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
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
        adapter.submitList(new ArrayList<>(services));
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
        adapter.submitList(new ArrayList<>(services));
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
            addOrUpdateService(new Lwm2mService(serviceInfo.getServiceName(), address, port));
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

    private static class DevicesAdapter extends ListAdapter<Lwm2mService, DeviceViewHolder> {

        protected DevicesAdapter() {
            super(new DiffUtil.ItemCallback<Lwm2mService>() {
                @Override
                public boolean areItemsTheSame(@NonNull Lwm2mService oldItem, @NonNull Lwm2mService newItem) {
                    return oldItem.name.equals(newItem.name) && oldItem.address.equals(newItem.address) && oldItem.port == newItem.port;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Lwm2mService oldItem, @NonNull Lwm2mService newItem) {
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
            Lwm2mService service = getItem(position);
            holder.name.setText(service.name);
            holder.address.setText(service.address + ":" + service.port);
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