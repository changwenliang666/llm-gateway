package com.llmgateway.routing;

import org.springframework.stereotype.Component;

/** 成功调用结束后把 Token 加进 models.consumed_tokens。 */
@Component
public class DbTokenUsage implements TokenUsage {

    private final TokenUsageMapper mapper;

    public DbTokenUsage(TokenUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void add(int modelId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        mapper.add(modelId, tokens);
    }
}
