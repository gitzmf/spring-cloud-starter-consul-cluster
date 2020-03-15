package org.springframework.cloud.consul.cluster;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.validation.constraints.NotNull;

import com.ecwid.consul.transport.TransportException;
import com.ecwid.consul.v1.OperationException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 集群ConsulClient配置
 */
@ConfigurationProperties("spring.cloud.consul")
@Validated
public class ClusterConsulProperties extends ConsulProperties {

  @Setter
  @Getter
  private NodeModeEnum nodeMode;

  @Setter
  @Getter
  private List<String> clusterNodes;

  /**
   * 集群ConsulClient客户端一致性哈希算法的Key 建议与spring.cloud.client.ip-address对应的值一致
   */
  @NotNull
  @Getter
  @Setter
  private String clusterClientKey;

  /**
   * 集群节点健康检测周期(毫秒)
   */
  @Getter
  @Setter
  private long healthCheckInterval = 10000;

  /**
   * 重试其他集群节点的前提条件(异常)
   */
  @Getter
  @Setter
  private List<Class<? extends Throwable>> retryableExceptions = Arrays.asList(
      TransportException.class, OperationException.class, IOException.class,
      ConnectException.class, TimeoutException.class, SocketTimeoutException.class);

  /**
   * 初始化时是否要求所有节点都必须是client节点
   */
  public boolean isOnlyClients() {
    boolean onlyClients = NodeModeEnum.CLIENT.equals(this.nodeMode);
    return onlyClients;
  }

  /**
   * 初始化时是否要求所有节点都必须是server节点
   */
  public boolean isOnlyServers() {
    boolean onlyServers = NodeModeEnum.SERVER.equals(this.nodeMode);
    return onlyServers;
  }

  @Override
  public String toString() {
    return "ClusterConsulProperties{" + "clusterNodes='" + getClusterNodes() + '\''
        + ", scheme=" + getScheme() + ", tls=" + getTls()
        + ", enabled=" + isEnabled() + ", nodeMode=" + getNodeMode()
        + ", clusterClientKey="
        + getClusterClientKey() + ", healthCheckInterval="
        + getHealthCheckInterval() + ", retryableExceptions="
        + getRetryableExceptions() + '}';
  }
}
