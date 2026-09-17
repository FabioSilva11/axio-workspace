package com.saaspaymentsolutions.axion.provider;
import com.saaspaymentsolutions.axion.R;

import okhttp3.Headers;
import com.saaspaymentsolutions.axion.port.VoidPortLlmMessage;
import com.saaspaymentsolutions.axion.port.VoidPortLlmMessage.ProviderConfig;
import com.saaspaymentsolutions.axion.port.VoidPortLlmMessage.ProviderFamily;

public final class OpenAiCompatibleAdapter extends BaseAiProviderAdapter {
    @Override
    public ProviderFamily family() {
        return ProviderFamily.OPENAI_COMPATIBLE;
    }

    @Override
    public Headers headers(ProviderConfig config) {
        Headers.Builder headers = jsonHeaders(config);
        if (config != null && !config.apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + config.apiKey);
        }
        return headers.build();
    }

    @Override
    public String streamingUrl(ProviderConfig config, String modelName) {
        return VoidPortLlmMessage.resolveRequestUrl(config, modelName);
    }
}
