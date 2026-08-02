package com.pcloud.sdk.internal;

import com.pcloud.sdk.Authenticator;

import java.io.IOException;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Adapter for pCloud's documented legacy {@code auth} token.
 *
 * <p>The upstream SDK only exposes an OAuth bearer authenticator. Its internal
 * builder requires authenticators to extend {@link RealAuthenticator}, so this
 * deliberately small split-package adapter keeps the legacy token behind the
 * same SDK client without copying or forking the SDK. Read requests are changed
 * from URL-query GETs to HTTPS form POSTs so the token and original parameters
 * do not enter URLs.</p>
 */
public final class LegacyTokenAuthenticators {
    private LegacyTokenAuthenticators() {
    }

    private static boolean isAllowedHost(String host) {
        return "api.pcloud.com".equals(host) || "eapi.pcloud.com".equals(host);
    }

    public static Authenticator create(String authToken) {
        if (authToken == null || authToken.trim().isEmpty()) {
            throw new IllegalArgumentException("authToken must not be blank");
        }
        return new LegacyTokenAuthenticator(authToken);
    }

    static Request authenticate(Request request, String authToken) throws IOException {
        HttpUrl originalUrl = request.url();
        if (!originalUrl.isHttps() || !isAllowedHost(originalUrl.host())) {
            throw new IOException("Refusing to attach a pCloud auth token to an untrusted endpoint.");
        }

        FormBody.Builder form = new FormBody.Builder();
        for (String name : originalUrl.queryParameterNames()) {
            if ("auth".equals(name)) {
                continue;
            }
            List<String> values = originalUrl.queryParameterValues(name);
            if (values.isEmpty()) {
                form.add(name, "");
            } else {
                for (String value : values) {
                    form.add(name, value == null ? "" : value);
                }
            }
        }

        if (request.body() instanceof FormBody) {
            FormBody existing = (FormBody) request.body();
            for (int index = 0; index < existing.size(); index += 1) {
                if (!"auth".equals(existing.name(index))) {
                    form.add(existing.name(index), existing.value(index));
                }
            }
        } else if (request.body() != null) {
            throw new IOException("Legacy pCloud authentication supports read and form requests only.");
        } else if (!"GET".equals(request.method())) {
            throw new IOException("Legacy pCloud authentication supports read and form requests only.");
        }

        form.add("auth", authToken);
        HttpUrl formUrl = originalUrl.newBuilder().query(null).build();
        return request.newBuilder()
                .url(formUrl)
                .removeHeader("Authorization")
                .post(new OneShotFormBody(form.build()))
                .build();
    }

    static Request prepareRequest(Request request, String authToken) throws IOException {
        if (!request.url().isHttps()) {
            throw new IOException("Refusing an insecure pCloud or content request.");
        }
        if (!isAllowedHost(request.url().host())) {
            // File-link downloads are capability URLs on temporary pCloud-selected hosts.
            // They need the same SDK HTTP client but must never receive the account token.
            return request;
        }
        return authenticate(request, authToken);
    }

    static final class OneShotFormBody extends RequestBody {
        private final FormBody delegate;

        OneShotFormBody(FormBody delegate) {
            this.delegate = delegate;
        }

        int size() {
            return delegate.size();
        }

        String name(int index) {
            return delegate.name(index);
        }

        String value(int index) {
            return delegate.value(index);
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            delegate.writeTo(sink);
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public String toString() {
            return "OneShotFormBody(<redacted>)";
        }
    }

    private static final class LegacyTokenAuthenticator extends RealAuthenticator {
        private final String authToken;

        private LegacyTokenAuthenticator(String authToken) {
            this.authToken = authToken;
        }

        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
            return chain.proceed(prepareRequest(chain.request(), authToken));
        }

        @Override
        public String toString() {
            return "LegacyTokenAuthenticator(authToken=<redacted>)";
        }
    }
}
