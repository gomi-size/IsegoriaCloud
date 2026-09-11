package com.ruwei.client.innersevice;

import java.util.List; /** post 服务提供（词表全量；消费方本地缓存 + 定时刷新） */
public interface InnerSensitiveWordService {
    /**
     * 返回启用中的敏感词列表
     * @return
     */
    List<String> getAllWords();   // （原 SensitiveWordFilter 加载逻辑）
}
