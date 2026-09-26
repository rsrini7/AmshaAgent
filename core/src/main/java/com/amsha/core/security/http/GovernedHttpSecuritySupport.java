package com.amsha.core.security.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayInputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

/**
 * Internal security helper for {@link GovernedHttpClient} configuring proxy authentication
 * and custom-CA TLS trust from {@link SecretVault}.
 */
final class GovernedHttpSecuritySupport {

    private GovernedHttpSecuritySupport() {}

    static void applyProxyCredentials(
            HttpClient.Builder builder, SecretVault vault, String actorUserId, String ref, List<Runnable> cleanups) {
        if (vault == null) {
            throw OutboundHttpException.policy("Proxy credentials are configured but no vault is available");
        }
        Optional<VaultKey> key = vault.get(ref, actorUserId);
        if (key.isEmpty()) {
            throw OutboundHttpException.policy("Proxy credential vault reference could not be resolved");
        }
        try (VaultKey k = key.get()) {
            byte[] bytes = k.get();
            int colonIdx = -1;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == ':') {
                    colonIdx = i;
                    break;
                }
            }
            if (colonIdx <= 0 || colonIdx == bytes.length - 1) {
                throw OutboundHttpException.policy("Proxy credential must be user:password");
            }
            String username = new String(bytes, 0, colonIdx, StandardCharsets.UTF_8);
            CharBuffer cb =
                    StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes, colonIdx + 1, bytes.length - (colonIdx + 1)));
            char[] password = new char[cb.remaining()];
            cb.get(password);
            char[] passwordCopy = password.clone();
            Arrays.fill(password, '\0');
            if (cb.hasArray()) {
                Arrays.fill(cb.array(), '\0');
            }
            cleanups.add(() -> Arrays.fill(passwordCopy, '\0'));
            Authenticator auth = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, passwordCopy.clone());
                }
            };
            builder.authenticator(auth);
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.policy("Failed to resolve proxy credentials");
        }
    }

    static SSLContext buildCustomCaContext(SecretVault vault, String actorUserId, String caRef) {
        if (vault == null) {
            throw OutboundHttpException.tls("Custom-CA trust requires a vault");
        }
        Optional<VaultKey> key = vault.get(caRef, actorUserId);
        if (key.isEmpty()) {
            throw OutboundHttpException.tls("Custom-CA vault reference could not be resolved");
        }
        try (VaultKey k = key.get()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate ca = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(k.get()));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            ks.setCertificateEntry("amsha-custom-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception e) {
            throw OutboundHttpException.tls("Failed to establish custom-CA trust: " + e.getMessage());
        }
    }
}
