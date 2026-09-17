package com.saaspaymentsolutions.axion.provider;
import com.saaspaymentsolutions.axion.R;

import okhttp3.Headers;
import com.saaspaymentsolutions.axion.port.VoidPortLlmMessage.ProviderConfig;
import com.saaspaymentsolutions.axion.port.VoidPortLlmMessage.ProviderFamily;

/** Protocol-specific endpoint and authentication behavior. */
public interface AiProviderAdapter {
    ProviderFamily family();
    Headers headers(ProviderConfig config);
    String streamingUrl(ProviderConfig config, String modelName);

    /** Endpoint equivalente sem streaming. Por padrao e o mesmo endpoint. */
    default String nonStreamingUrl(ProviderConfig config, String modelName) {
        return streamingUrl(config, modelName);
    }
}
