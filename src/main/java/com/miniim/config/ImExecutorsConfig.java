package com.miniim.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties({
        ImDbExecutorProperties.class,
        ImWsEncodeExecutorProperties.class,
        ImPostDbExecutorProperties.class,
        ImAckExecutorProperties.class
})
public class ImExecutorsConfig {

    @Bean("imDbExecutor")
    @Primary
    public Executor imDbExecutor(ImDbExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("im-db-");
        int core = props == null ? 8 : props.corePoolSizeEffective();
        int max = props == null ? 32 : props.maxPoolSizeEffective();
        if (max < core) {
            max = core;
        }
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(props == null ? 10_000 : props.queueCapacityEffective());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean("imWsEncodeExecutor")
    public Executor imWsEncodeExecutor(ImWsEncodeExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("im-ws-enc-");
        int core = props == null ? ImWsEncodeExecutorProperties.defaultCorePoolSize() : props.corePoolSizeEffective();
        int max = props == null ? core : props.maxPoolSizeEffective();
        if (max < core) {
            max = core;
        }
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(props == null ? 5_000 : props.queueCapacityEffective());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean("imPostDbExecutor")
    public Executor imPostDbExecutor(ImPostDbExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("im-post-db-");
        int core = props == null ? 8 : props.corePoolSizeEffective();
        int max = props == null ? 32 : props.maxPoolSizeEffective();
        if (max < core) {
            max = core;
        }
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(props == null ? 10_000 : props.queueCapacityEffective());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Bean("imAckExecutor")
    public Executor imAckExecutor(ImAckExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("im-ack-");
        int core = props == null ? ImAckExecutorProperties.defaultCorePoolSize() : props.corePoolSizeEffective();
        int max = props == null ? Math.max(core, 8) : props.maxPoolSizeEffective();
        if (max < core) {
            max = core;
        }
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(props == null ? 10_000 : props.queueCapacityEffective());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setAwaitTerminationSeconds(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
