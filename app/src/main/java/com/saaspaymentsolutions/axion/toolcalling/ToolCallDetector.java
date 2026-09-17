package com.saaspaymentsolutions.axion.toolcalling;

public interface ToolCallDetector {
    ToolCallParseResult detect(ToolCallResponse response);

    void registerParser(ToolCallParser parser);
}
