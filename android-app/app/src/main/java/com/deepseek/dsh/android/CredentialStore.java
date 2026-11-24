package com.deepseek.dsh.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CredentialStore {

    private static final String KEY_ALIAS = "dsh_api_key";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFS_NAME = "dsh_secure_credentials";
    private static final String PREF_API_KEY = "api_key_gcm_v1";
    private static final int KEY_SIZE_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final byte FORMAT_VERSION = 1;

    private final SharedPreferences preferences;

    public CredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void saveApiKey(String apiKey) throws GeneralSecurityException, IOException {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("API key is empty");
        }
        String encrypted = encrypt(normalized);
        if (!preferences.edit().putString(PREF_API_KEY, encrypted).commit()) {
            throw new IOException("Encrypted credential persistence failed");
        }
    }

    public synchronized String getApiKey() throws GeneralSecurityException {
        String encrypted = preferences.getString(PREF_API_KEY, null);
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        return decrypt(encrypted);
    }

    public synchronized boolean hasApiKey() {
        try {
            String value = getApiKey();
            return value != null && !value.isEmpty();
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public String encrypt(String plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer packed = ByteBuffer.allocate(2 + iv.length + ciphertext.length);
        packed.put(FORMAT_VERSION);
        packed.put((byte) iv.length);
        packed.put(iv);
        packed.put(ciphertext);
        return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
    }

    public String decrypt(String encoded) throws GeneralSecurityException {
        final byte[] packed;
        try {
            packed = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid encrypted credential encoding", e);
        }
        if (packed.length < 2 + GCM_IV_BYTES + GCM_TAG_BITS / 8
                || packed[0] != FORMAT_VERSION) {
            throw new GeneralSecurityException("Invalid encrypted credential format");
        }
        int ivLength = packed[1] & 0xff;
        if (ivLength != GCM_IV_BYTES || packed.length < 2 + ivLength + GCM_TAG_BITS / 8) {
            throw new GeneralSecurityException("Invalid encrypted credential IV");
        }
        byte[] iv = new byte[ivLength];
        byte[] ciphertext = new byte[packed.length - 2 - ivLength];
        System.arraycopy(packed, 2, iv, 0, ivLength);
        System.arraycopy(packed, 2 + ivLength, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateSecretKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
                if (!(key instanceof SecretKey)) {
                    throw new GeneralSecurityException("Keystore alias is not an AES key");
                }
                return (SecretKey) key;
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            generator.init(spec);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Android Keystore operation failed", e);
        }
    }
}
