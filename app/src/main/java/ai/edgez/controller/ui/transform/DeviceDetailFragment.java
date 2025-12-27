package ai.edgez.controller.ui.transform;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.edgez.controller.R;

/** Displays a device's LwM2M objects/instances and navigates to resources. */
public class DeviceDetailFragment extends Fragment {

    private static final String TAG = "DeviceDetail";
    private static final String ARG_ENDPOINT = "endpoint";
    private static final String ARG_HOST = "host";
    private static final int REST_PORT = 8088;

    private String endpoint;
    private String host;
    private RecyclerView list;
    private ProgressBar progress;
    private TextView subtitle;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<ObjectLink> links = new ArrayList<>();
    private ObjectAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_device_detail, container, false);
        list = root.findViewById(R.id.object_list);
        progress = root.findViewById(R.id.progress);
        subtitle = root.findViewById(R.id.subtitle);
        adapter = new ObjectAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            endpoint = args.getString(ARG_ENDPOINT, "");
            host = args.getString(ARG_HOST, "");
        }
        subtitle.setText(endpoint + " @ " + host + ":" + REST_PORT);
        fetchClient();
    }

    @Override
    public void onDestroyView() {
        io.shutdown();
        super.onDestroyView();
    }

    private void fetchClient() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + REST_PORT + "/api/clients/" + endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    postToast("Fetch failed: " + code);
                    return;
                }
                String body = readAll(conn.getInputStream());
                parseLinks(body);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "fetchClient", e);
                postToast("Fetch error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                postProgressGone();
            }
        });
    }

    private void parseLinks(String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        JSONArray arr = obj.optJSONArray("objectLinks");
        links.clear();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject linkObj = arr.optJSONObject(i);
                if (linkObj == null) continue;
                String url = linkObj.optString("url", "");
                String[] parts = url.split("/");
                if (parts.length >= 3) {
                    // e.g. /3311/0
                    try {
                        int objId = Integer.parseInt(parts[1]);
                        int instId = Integer.parseInt(parts[2]);
                        links.add(new ObjectLink(objId, instId));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }
        requireActivity().runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private void navigateToResources(ObjectLink link) {
        Bundle args = new Bundle();
        args.putString("endpoint", endpoint);
        args.putString("host", host);
        args.putInt("objId", link.objId);
        args.putInt("instId", link.instId);
        Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_content_main)
                .navigate(R.id.action_deviceDetail_to_resourceList, args);
    }

    private void postToast(String msg) {
        requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show());
    }

    private void postProgressGone() {
        requireActivity().runOnUiThread(() -> progress.setVisibility(View.GONE));
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

    private class ObjectAdapter extends RecyclerView.Adapter<ObjectViewHolder> {
        @NonNull
        @Override
        public ObjectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_object_link, parent, false);
            return new ObjectViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ObjectViewHolder holder, int position) {
            ObjectLink link = links.get(position);
            holder.title.setText("Object " + link.objId + " / Instance " + link.instId);
            holder.itemView.setOnClickListener(v -> navigateToResources(link));
        }

        @Override
        public int getItemCount() {
            return links.size();
        }
    }

    private static class ObjectViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        ObjectViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_object);
        }
    }

    private static class ObjectLink {
        final int objId;
        final int instId;
        ObjectLink(int objId, int instId) {
            this.objId = objId;
            this.instId = instId;
        }
    }
}
