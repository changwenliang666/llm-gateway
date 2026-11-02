package com.llmgateway.task;

/** 一段创建日期内，某种任务状态的条数。 */
public class TaskStatusCount {

    private String status;
    private long total;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
