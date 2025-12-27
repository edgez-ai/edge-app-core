package ai.edgez.controller.ui.transform;

import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import ai.edgez.controller.R;

/** Lists resources for a specific LwM2M object instance and supports read/write. */
public class ResourceListFragment extends Fragment {

    private static final String TAG = "ResourceList";
    private static final String ARG_ENDPOINT = "endpoint";
    private static final String ARG_HOST = "host";
    private static final String ARG_OBJ_ID = "objId";
    private static final String ARG_INST_ID = "instId";
    private static final int REST_PORT = 8088;

    private String endpoint;
    private String host;
    private int objId;
    private int instId;
    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView subtitle;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<ResourceDef> resources = new ArrayList<>();
    private ResourceAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_resource_list, container, false);
        recycler = root.findViewById(R.id.resource_list);
        progress = root.findViewById(R.id.progress);
        subtitle = root.findViewById(R.id.subtitle);
        adapter = new ResourceAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            endpoint = args.getString(ARG_ENDPOINT, "");
            host = args.getString(ARG_HOST, "");
            objId = args.getInt(ARG_OBJ_ID, -1);
            instId = args.getInt(ARG_INST_ID, -1);
        }
        subtitle.setText("Endpoint " + endpoint + " — Object " + objId + " / Instance " + instId);
        loadModel();
    }

    @Override
    public void onDestroyView() {
        io.shutdown();
        super.onDestroyView();
    }

    private void loadModel() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                List<ResourceDef> parsed = parseModel(objId);
                resources.clear();
                resources.addAll(parsed);
                requireActivity().runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    progress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                Log.w(TAG, "loadModel", e);
                postToast("Failed to load model: " + e.getMessage());
                postProgressGone();
            }
        });
    }

    private List<ResourceDef> parseModel(int objId) throws Exception {
        AssetManager am = requireContext().getAssets();
        String base = "models/" + objId + ".xml";
        String fallback = "models/" + objId + "-1_0.xml";
        InputStream in;
        try {
            in = am.open(base);
        } catch (IOException e) {
            in = am.open(fallback);
        }
        try (InputStream closeable = in) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(closeable);
            NodeList items = doc.getElementsByTagName("Item");
            List<ResourceDef> defs = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                String idStr = n.getAttributes().getNamedItem("ID").getNodeValue();
                int id = Integer.parseInt(idStr);
                String name = textOfChild(n, "Name");
                String ops = textOfChild(n, "Operations");
                String type = textOfChild(n, "Type");
                defs.add(new ResourceDef(id, name, ops, type));
            }
            return defs;
        }
    }

    private String textOfChild(Node parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (tag.equals(c.getNodeName()) && c.getFirstChild() != null) {
                return c.getFirstChild().getNodeValue();
            }
        }
        return "";
    }

    private void readResource(int resId) {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + REST_PORT + "/api/clients/" + endpoint + "/" + objId + "/" + instId + "/" + resId + "?timeout=5&format=TLV");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                int code = conn.getResponseCode();
                String body = readAll(conn.getInputStream());
                postToast("Read " + resId + " code=" + code + " body=" + body);
            } catch (IOException e) {
                postToast("Read error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                postProgressGone();
            }
        });
    }

    private void writeResource(int resId, String value) {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + REST_PORT + "/api/clients/" + endpoint + "/" + objId + "/" + instId + "/" + resId + "?timeout=5&format=TLV");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                byte[] bytes = value.getBytes();
                conn.setRequestProperty("Content-Type", "text/plain");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                String body = readAll(conn.getInputStream());
                postToast("Write " + resId + " code=" + code + " body=" + body);
            } catch (IOException e) {
                postToast("Write error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                postProgressGone();
            }
        });
    }

    private void promptWrite(ResourceDef def) {
        final EditText input = new EditText(requireContext());
        input.setHint("Value");
        new AlertDialog.Builder(requireContext())
                .setTitle("Write resource " + def.id)
                .setView(input)
                .setPositiveButton("Write", (d, which) -> writeResource(def.id, input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
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

    private class ResourceAdapter extends RecyclerView.Adapter<ResourceViewHolder> {
        @NonNull
        @Override
        public ResourceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_resource, parent, false);
            return new ResourceViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ResourceViewHolder holder, int position) {
            ResourceDef def = resources.get(position);
            holder.title.setText(def.name + " (" + def.id + ")");
            holder.subtitle.setText(def.type + " • ops " + def.ops);
            holder.read.setEnabled(def.ops.contains("R"));
            holder.write.setEnabled(def.ops.contains("W"));
            holder.read.setOnClickListener(v -> readResource(def.id));
            holder.write.setOnClickListener(v -> promptWrite(def));
        }

        @Override
        public int getItemCount() {
            return resources.size();
        }
    }

    private static class ResourceViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final View read;
        final View write;
        ResourceViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_res_title);
            subtitle = itemView.findViewById(R.id.text_res_subtitle);
            read = itemView.findViewById(R.id.btn_read);
            write = itemView.findViewById(R.id.btn_write);
        }
    }

    private static class ResourceDef {
        final int id;
        final String name;
        final String ops;
        final String type;
        ResourceDef(int id, String name, String ops, String type) {
            this.id = id;
            this.name = name;
            this.ops = ops == null ? "" : ops;
            this.type = type == null ? "" : type;
        }
    }
}
