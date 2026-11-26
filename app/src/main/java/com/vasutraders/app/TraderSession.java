package com.vasutraders.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

final class TraderSession {
    private static final String KEY = "vasu-traders-session-v1";
    private final SharedPreferences prefs;

    TraderSession(Context context) { prefs = context.getSharedPreferences("traders_state", Context.MODE_PRIVATE); }

    JSONObject read() {
        try {
            String saved = prefs.getString(KEY, "");
            if (saved.isEmpty()) return null;
            byte[] data = Base64.decode(saved, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
            return new JSONObject(new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8));
        } catch (Exception invalid) {
            clear();
            return null;
        }
    }

    void write(JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV(), bytes = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        byte[] data = Arrays.copyOf(iv, iv.length + bytes.length);
        System.arraycopy(bytes, 0, data, iv.length, bytes.length);
        if (!prefs.edit().putString(KEY, Base64.encodeToString(data, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Unable to save this session.");
    }

    void clear() { prefs.edit().remove(KEY).apply(); }

    private SecretKey key() throws Exception {
        var store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        var stored = store.getKey(KEY, null);
        if (stored instanceof SecretKey key) return key;
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
