package ai.edgez.controller;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class WasmtimeRunner {

    static {
        System.loadLibrary("wasmtime_bridge");
    }

    /**
     * Invoke exported function "add" (i32, i32) -> i32 from the provided Wasm module bytes.
     */
    public int invokeAdd(byte[] wasmBytes, int a, int b) {
        return runAdd(wasmBytes, a, b);
    }

    /**
     * Convenience helper that loads a module from assets before invoking "add".
     */
    public int invokeAddFromAsset(Context context, String assetName, int a, int b) throws IOException {
        byte[] module = readAll(context.getAssets(), assetName);
        return runAdd(module, a, b);
    }

    private native int runAdd(byte[] wasmBytes, int a, int b);

    private static byte[] readAll(AssetManager assets, String assetName) throws IOException {
        try (InputStream input = assets.open(assetName);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
