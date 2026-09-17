package com.saaspaymentsolutions.axion.toolcalling;

public interface ToolCallParser {
    String protocol();

    boolean recognizes(ToolCallResponse response);

    ToolCallParseResult parse(ToolCallResponse response);
}
