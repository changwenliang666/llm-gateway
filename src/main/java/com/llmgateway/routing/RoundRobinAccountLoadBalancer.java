package com.llmgateway.routing;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 平滑轮询。账号顺序就是 SQL 的 {@code ORDER BY pa.id, ak.id}，本类不重排。
 *
 * <p>Redis 只保存一个递增整数。第 n 次 {@code INCR} 得到 n，下标是 {@code (n - 1) % 账号数}。
 * 三个账号时，连续请求落到 0、1、2、0。并发请求各自加一，所以会分到不同位置。
 * 有人停用了中间那个账号后，下标仍然按新的长度取余，只会跳号，不会把旧下标写回数据库。
 *
 * <p>选中下标之后还要过这个账号自己的熔断。打开的账号跳过，换下一个还没打开的。
 * 负载均衡关闭时只检查 id 最小的那个，不再往下找。
 *
 * <p>{@link #select} 和 {@link #pickKey} 会推进游标。{@link #nextAccount} 只在本次快照里向后找，
 * 不再 {@code INCR} 账号键。否则一次 429 重试会让下一个用户也跳过这个账号。
 */
@Component
public class RoundRobinAccountLoadBalancer implements AccountLoadBalancer {

    private final RotationCursor cursor;
    private final ModelCircuitBreaker breaker;

    public RoundRobinAccountLoadBalancer(RotationCursor cursor, ModelCircuitBreaker breaker) {
        this.cursor = cursor;
        this.breaker = breaker;
    }

    /**
     * 新请求的第一跳。
     * 负载均衡开启时账号游标加一，从该下标起跳过熔断打开的账号。
     * 关闭时固定用 id 最小的那个（快照已经按 id 升序），不推进账号游标。这个账号如果已打开，直接 503。
     * 两种情况都会为选中的账号轮换 Key。同一次请求失败后的那一次换账号仍走 {@link #nextAccount}，不在这里。
     */
    @Override
    public Selection select(ModelRoute route) {
        List<AccountCandidate> accounts = route.accounts();
        if (!route.loadBalanceEnabled()) {
            requirePool(accounts.size());
            return admit(route, accounts.get(0), 0).orElseThrow(RoundRobinAccountLoadBalancer::unavailable);
        }
        int start = index(cursor.increment(RedisKeys.accountCursor(route.modelId())), accounts.size());
        return scan(route, accounts, start, 0).orElseThrow(RoundRobinAccountLoadBalancer::unavailable);
    }

    /**
     * 同一次请求的下一次尝试。从当前账号的下一个开始，跳过熔断打开的账号。
     * 不再推进账号游标。池里没有其他还能放行的账号时返回空，调用方就把第一次的失败原样返回。
     */
    @Override
    public Optional<Selection> nextAccount(ModelRoute route, Selection current) {
        List<AccountCandidate> accounts = route.accounts();
        if (accounts.size() < 2) {
            return Optional.empty();
        }
        return scan(route, accounts, current.accountIndex(), 1);
    }

    /**
     * 视频查询回到创建任务的账号。该账号已经停用或不在这次快照里时 503，
     * 不悄悄改投别的账号，否则会拿着别人的 Key 去查一个不属于它的任务。
     * 账号还在，但它自己的熔断已打开时，同样 503，也不改投。
     */
    @Override
    public Selection selectKey(ModelRoute route, int accountId) {
        List<AccountCandidate> accounts = route.accounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).accountId() == accountId) {
                return admit(route, accounts.get(i), i).orElseThrow(RoundRobinAccountLoadBalancer::unavailable);
            }
        }
        throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "创建任务的账号已不可用");
    }

    @Override
    public Optional<Selection> nextKey(Selection current) {
        if (current.account().keys().size() < 2) {
            return Optional.empty();
        }
        ApiCredential key = pickKey(current.route().modelId(), current.account());
        return Optional.of(new Selection(current.route(), current.account(), key, current.accountIndex(),
                current.circuitPermit()));
    }

    /**
     * 从 start 起走一圈。step 从 offset 开始，这样 {@code nextAccount} 不会再次选中当前账号。
     * 熔断存储不可用时直接抛出，不再试后面的账号，避免同一份故障被记成「这个账号开了、那个账号没开」。
     */
    private Optional<Selection> scan(ModelRoute route, List<AccountCandidate> accounts, int start, int offset) {
        for (int step = offset; step < accounts.size(); step++) {
            int index = Math.floorMod(start + step, accounts.size());
            Optional<Selection> admitted = admit(route, accounts.get(index), index);
            if (admitted.isPresent()) {
                return admitted;
            }
        }
        return Optional.empty();
    }

    /**
     * 熔断拒绝时返回空，并且不轮换这个账号的 Key。
     * 已经拿到名额之后，Key 游标如果失败，把半开名额还回去，这次不算失败。
     */
    private Optional<Selection> admit(ModelRoute route, AccountCandidate account, int index) {
        Permit permit = breaker.acquire(route.modelId(), account.accountId());
        if (!permit.allowed()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Selection(route, account, pickKey(route.modelId(), account), index, permit));
        } catch (RuntimeException ex) {
            breaker.release(route.modelId(), account.accountId(), permit);
            throw ex;
        }
    }

    private ApiCredential pickKey(int modelId, AccountCandidate account) {
        int index = index(cursor.increment(RedisKeys.keyCursor(modelId, account.accountId())), account.keys().size());
        return account.keys().get(index);
    }

    private static int index(long incremented, int size) {
        requirePool(size);
        return (int) Math.floorMod(incremented - 1, size);
    }

    private static void requirePool(int size) {
        if (size <= 0) {
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "没有可用的供应商账号");
        }
    }

    private static GatewayException unavailable() {
        return new GatewayException(503, ErrorTypes.SERVER_ERROR, "模型暂时不可用");
    }
}
