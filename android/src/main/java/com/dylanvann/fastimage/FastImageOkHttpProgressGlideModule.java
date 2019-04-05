package com.quantfive.fastimage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.module.LibraryGlideModule;
import com.bumptech.glide.util.ContentLengthInputStream;
import com.bumptech.glide.util.Preconditions;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import expolib_v1.okhttp3.Call;
import expolib_v1.okhttp3.Callback;
import expolib_v1.okhttp3.Interceptor;
import expolib_v1.okhttp3.MediaType;
import expolib_v1.okhttp3.OkHttpClient;
import expolib_v1.okhttp3.Request;
import expolib_v1.okhttp3.Response;
import expolib_v1.okhttp3.ResponseBody;
import expolib_v1.okio.Buffer;
import expolib_v1.okio.BufferedSource;
import expolib_v1.okio.ForwardingSource;
import expolib_v1.okio.Okio;
import expolib_v1.okio.Source;


@GlideModule
public class FastImageOkHttpProgressGlideModule extends LibraryGlideModule {

    private class OkHttpStreamFetcher implements DataFetcher<InputStream>, Callback {
        private final Call.Factory client;
        private final GlideUrl url;
        private InputStream stream;
        private ResponseBody responseBody;
        private DataCallback<? super InputStream> callback;
        // call may be accessed on the main thread while the object is in use on other threads. All other
        // accesses to variables may occur on different threads, but only one at a time.
        private volatile Call call;

        // Public API.
        @SuppressWarnings("WeakerAccess")
        public OkHttpStreamFetcher(Call.Factory client, GlideUrl url) {
            this.client = client;
            this.url = url;
        }


        @Override
        public void loadData(@NonNull Priority priority,
                             @NonNull final DataCallback<? super InputStream> callback) {
            Request.Builder requestBuilder = new Request.Builder().url(url.toStringUrl());
            for (Map.Entry<String, String> headerEntry : url.getHeaders().entrySet()) {
                String key = headerEntry.getKey();
                requestBuilder.addHeader(key, headerEntry.getValue());
            }
            Request request = requestBuilder.build();
            this.callback = callback;

            call = client.newCall(request);
            call.enqueue(this);
        }

        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
            callback.onLoadFailed(e);
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) {
            responseBody = response.body();
            if (response.isSuccessful()) {
                long contentLength = Preconditions.checkNotNull(responseBody).contentLength();
                stream = ContentLengthInputStream.obtain(responseBody.byteStream(), contentLength);
                callback.onDataReady(stream);
            } else {
                callback.onLoadFailed(new HttpException(response.message(), response.code()));
            }
        }

        @Override
        public void cleanup() {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {
                // Ignored
            }
            if (responseBody != null) {
                responseBody.close();
            }
            callback = null;
        }

        @Override
        public void cancel() {
            Call local = call;
            if (local != null) {
                local.cancel();
            }
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.REMOTE;
        }
    }

    private static DispatchingProgressListener progressListener = new DispatchingProgressListener();

    @Override
    public void registerComponents(
            @NonNull Context context,
            @NonNull Glide glide,
            @NonNull Registry registry
    ) {
       final OkHttpClient client = OkHttpClientProvider
                .getOkHttpClient()
                .newBuilder()
                .addInterceptor(createInterceptor(progressListener))
                .build();
        registry.replace(GlideUrl.class, InputStream.class, new ModelLoaderFactory<GlideUrl, InputStream>() {
            @NonNull
            @Override
            public ModelLoader<GlideUrl, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
                return new ModelLoader<GlideUrl, InputStream>() {
                    @Override
                    public LoadData<InputStream> buildLoadData(@NonNull final GlideUrl glideUrl, int width, int height, @NonNull Options options) {
                        return new LoadData<>(glideUrl, new OkHttpStreamFetcher(client, glideUrl));
                    }

                    @Override
                    public boolean handles(@NonNull GlideUrl glideUrl) {
                        return true;
                    }
                };
            }

            @Override
            public void teardown() {
                // Do nothing, this instance doesn't own the client.
            }
        });
    }

    private static Interceptor createInterceptor(final ResponseProgressListener listener) {
        return new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                Response response = chain.proceed(request);
                final String key = request.url().toString();
                return response
                        .newBuilder()
                        .body(new OkHttpProgressResponseBody(key, response.body(), listener))
                        .build();
            }
        };
    }

    static void forget(String key) {
        progressListener.forget(key);
    }

    static void expect(String key, FastImageProgressListener listener) {
        progressListener.expect(key, listener);
    }

    private interface ResponseProgressListener {
        void update(String key, long bytesRead, long contentLength);
    }

    private static class DispatchingProgressListener implements ResponseProgressListener {
        private final Map<String, FastImageProgressListener> LISTENERS = new WeakHashMap<>();
        private final Map<String, Long> PROGRESSES = new HashMap<>();

        private final Handler handler;

        DispatchingProgressListener() {
            this.handler = new Handler(Looper.getMainLooper());
        }

        void forget(String key) {
            LISTENERS.remove(key);
            PROGRESSES.remove(key);
        }

        void expect(String key, FastImageProgressListener listener) {
            LISTENERS.put(key, listener);
        }

        @Override
        public void update(final String key, final long bytesRead, final long contentLength) {
            final FastImageProgressListener listener = LISTENERS.get(key);
            if (listener == null) {
                return;
            }
            if (contentLength <= bytesRead) {
                forget(key);
            }
            if (needsDispatch(key, bytesRead, contentLength, listener.getGranularityPercentage())) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onProgress(key, bytesRead, contentLength);
                    }
                });
            }
        }

        private boolean needsDispatch(String key, long current, long total, float granularity) {
            if (granularity == 0 || current == 0 || total == current) {
                return true;
            }
            float percent = 100f * current / total;
            long currentProgress = (long) (percent / granularity);
            Long lastProgress = PROGRESSES.get(key);
            if (lastProgress == null || currentProgress != lastProgress) {
                PROGRESSES.put(key, currentProgress);
                return true;
            } else {
                return false;
            }
        }
    }

    private static class OkHttpProgressResponseBody extends ResponseBody {
        private final String key;
        private final ResponseBody responseBody;
        private final ResponseProgressListener progressListener;
        private BufferedSource bufferedSource;

        OkHttpProgressResponseBody(
                String key,
                ResponseBody responseBody,
                ResponseProgressListener progressListener
        ) {
            this.key = key;
            this.responseBody = responseBody;
            this.progressListener = progressListener;
        }

        @Override
        public MediaType contentType() {
            return responseBody.contentType();
        }

        @Override
        public long contentLength() {
            return responseBody.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (bufferedSource == null) {
                bufferedSource = Okio.buffer(source(responseBody.source()));
            }
            return bufferedSource;
        }

        private Source source(Source source) {
            return new ForwardingSource(source) {
                long totalBytesRead = 0L;

                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    long bytesRead = super.read(sink, byteCount);
                    long fullLength = responseBody.contentLength();
                    if (bytesRead == -1) {
                        // this source is exhausted
                        totalBytesRead = fullLength;
                    } else {
                        totalBytesRead += bytesRead;
                    }
                    progressListener.update(key, totalBytesRead, fullLength);
                    return bytesRead;
                }
            };
        }
    }
}