package com.ruwei.common.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置（六服务共享）。
 *
 * <p>提供名为 {@code eventTaskExecutor} 的通用线程池，供各服务以
 * {@code @Async("eventTaskExecutor")} 标注的方法执行通知装配、索引同步等
 * 非关键路径任务，避免占用 Tomcat 请求线程。</p>
 *
 * <p>Bean 名称与旧单体保持一致，迁移到各服务时调用方无需改动。
 * 队列打满后采用 {@link ThreadPoolExecutor.CallerRunsPolicy}：由提交任务的线程
 * 直接执行，形成天然背压，保证任务不丢。</p>
 *
 * @author ruwei
 */
@Configuration
public class AsyncConfig {

    /**
     * 构建事件异步执行线程池。
     *
     * <p>核心 10 / 最大 20 / 队列 200 / 空闲存活 60s，线程名前缀 {@code async-event-}
     * 便于日志排查（可在堆栈中直接定位任务来源）。</p>
     *
     * @return 已初始化的 {@link ThreadPoolTaskExecutor} 实例
     */
    @Bean(name = "eventTaskExecutor")
    public Executor eventTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：线程池创建时候初始化的线程数
        executor.setCorePoolSize(10);
        // 最大线程数：线程池最大的线程数，只有在缓冲队列满了之后才会申请超过核心线程数的线程
        executor.setMaxPoolSize(20);
        // 缓冲队列：用来缓冲执行任务的队列
        executor.setQueueCapacity(200);
        // 允许线程的空闲时间：当超过了核心线程数之外的线程在空闲时间到达之后会被销毁
        executor.setKeepAliveSeconds(60);
        // 线程池名的前缀：设置好了之后可以方便我们定位处理任务所在的线程池
        executor.setThreadNamePrefix("async-event-");
        // 拒绝策略：当队列满了并且工作线程也满了，直接在 execute 方法的调用线程中运行被拒绝的任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 初始化线程池
        executor.initialize();

        return executor;
    }
}
