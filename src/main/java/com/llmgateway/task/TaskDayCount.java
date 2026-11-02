package com.llmgateway.task;

/** 某一天创建的任务条数。day 是 yyyy-MM-dd，和库里的日期同一时钟。 */
public class TaskDayCount {

    private String day;
    private long total;

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
