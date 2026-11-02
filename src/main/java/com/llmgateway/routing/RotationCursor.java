package com.llmgateway.routing;

/**
 * 轮询游标。生产用 Redis INCR，测试用内存实现。
 * 失败时抛出 503，调用方不得退化为永远选择第一把 Key。
 */
public interface RotationCursor {

    /** 返回递增之后的值，第一次调用为 1，与 Redis INCR 一致。 */
    long increment(String key);
}
