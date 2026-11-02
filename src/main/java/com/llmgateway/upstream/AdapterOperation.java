package com.llmgateway.upstream;

/** 网关对外的操作。适配器用它声明自己能接哪些入口，控制器不写供应商分支。 */
public enum AdapterOperation {
    CHAT_COMPLETION,
    VIDEO_CREATE,
    VIDEO_QUERY
}
