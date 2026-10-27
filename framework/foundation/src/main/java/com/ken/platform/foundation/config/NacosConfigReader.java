package com.ken.platform.foundation.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.concurrent.Executor;

/** 固定客户端的 getConfig 会回退磁盘快照；此公开 API 先网络查询，失败不回退。 */
public final class NacosConfigReader {
  private NacosConfigReader() {}

  public static String read(ConfigService client, String dataId) throws NacosException {
    Listener oneShot =
        new Listener() {
          @Override
          public Executor getExecutor() {
            return null;
          }

          @Override
          public void receiveConfigInfo(String ignored) {}
        };
    try {
      return client.getConfigAndSignListener(dataId, "DEFAULT_GROUP", 5000, oneShot);
    } finally {
      client.removeListener(dataId, "DEFAULT_GROUP", oneShot);
    }
  }
}
