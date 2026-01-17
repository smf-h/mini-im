package com.miniim.config;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class IdWorkerConfig {
    private static final Logger log = LoggerFactory.getLogger(IdWorkerConfig.class);
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)(?!.*\\d)");

    private final long datacenterId;
    private final long workerId;
    private final String instanceId;

    public IdWorkerConfig(
            @Value("${im.id.datacenter-id:1}") long datacenterId,
            @Value("${im.id.worker-id:-1}") long workerId,
            @Value("${im.gateway.ws.instance-id:}") String instanceId
    ) {
        this.datacenterId = datacenterId;
        this.workerId = workerId;
        this.instanceId = instanceId;
    }

    @PostConstruct
    public void init() {
        long[] resolved = resolveIds();
        if (resolved == null) {
            log.info("IdWorker: keep default (no im.id.worker-id and no numeric instance-id)");
            return;
        }
        long wid = resolved[0];
        long dc = resolved[1];
        IdWorker.initSequence(wid, dc);
        log.info("IdWorker: initSequence(workerId={}, datacenterId={}, instanceId={})", wid, dc, instanceId);
    }

    @Bean
    public IdentifierGenerator identifierGenerator() {
        long[] resolved = resolveIds();
        if (resolved == null) {
            return DefaultIdentifierGenerator.getInstance();
        }
        return new DefaultIdentifierGenerator(resolved[0], resolved[1]);
    }

    private long[] resolveIds() {
        long dc = normalize5Bits(datacenterId);
        long wid = workerId;
        if (wid < 0) {
            wid = parseWorkerIdFromInstanceId(instanceId);
        }
        if (wid < 0) {
            return null;
        }
        wid = normalize5Bits(wid);
        return new long[]{wid, dc};
    }

    private static long parseWorkerIdFromInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return -1;
        }
        Matcher matcher = LAST_NUMBER.matcher(instanceId);
        if (!matcher.find()) {
            return -1;
        }
        try {
            long n = Long.parseLong(matcher.group(1));
            return n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long normalize5Bits(long v) {
        long x = v % 32;
        return x < 0 ? x + 32 : x;
    }
}
