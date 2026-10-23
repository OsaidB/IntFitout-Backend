package life.work.IntFit.backend.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/** Shared bounded, single-attempt transport. Never follow a redirect carrying credentials. */
public final class WhatsAppHttpTransport {
    private WhatsAppHttpTransport() { }
    public static RequestConfig requestConfig() {
        return RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(10)).build();
    }
    public static CloseableHttpClient create() {
        return HttpClients.custom().setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(10)).build()).build())
                .disableAutomaticRetries().disableRedirectHandling().build();
    }
}
