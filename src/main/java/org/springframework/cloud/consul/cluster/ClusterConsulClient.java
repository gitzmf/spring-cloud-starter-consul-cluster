package org.springframework.cloud.consul.cluster;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;

import com.ecwid.consul.transport.TransportException;
import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.OperationException;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.acl.AclClient;
import com.ecwid.consul.v1.acl.model.Acl;
import com.ecwid.consul.v1.acl.model.NewAcl;
import com.ecwid.consul.v1.acl.model.UpdateAcl;
import com.ecwid.consul.v1.agent.AgentClient;
import com.ecwid.consul.v1.agent.model.Member;
import com.ecwid.consul.v1.agent.model.NewCheck;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.agent.model.Self;
import com.ecwid.consul.v1.agent.model.Service;
import com.ecwid.consul.v1.catalog.CatalogClient;
import com.ecwid.consul.v1.catalog.model.CatalogDeregistration;
import com.ecwid.consul.v1.catalog.model.CatalogNode;
import com.ecwid.consul.v1.catalog.model.CatalogRegistration;
import com.ecwid.consul.v1.catalog.model.CatalogService;
import com.ecwid.consul.v1.coordinate.CoordinateClient;
import com.ecwid.consul.v1.coordinate.model.Datacenter;
import com.ecwid.consul.v1.coordinate.model.Node;
import com.ecwid.consul.v1.event.EventClient;
import com.ecwid.consul.v1.event.model.Event;
import com.ecwid.consul.v1.event.model.EventParams;
import com.ecwid.consul.v1.health.HealthClient;
import com.ecwid.consul.v1.health.model.Check;
import com.ecwid.consul.v1.health.model.Check.CheckStatus;
import com.ecwid.consul.v1.health.model.HealthService;
import com.ecwid.consul.v1.kv.KeyValueClient;
import com.ecwid.consul.v1.kv.model.GetBinaryValue;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.ecwid.consul.v1.kv.model.PutParams;
import com.ecwid.consul.v1.query.QueryClient;
import com.ecwid.consul.v1.query.model.QueryExecution;
import com.ecwid.consul.v1.session.SessionClient;
import com.ecwid.consul.v1.session.model.NewSession;
import com.ecwid.consul.v1.session.model.Session;
import com.ecwid.consul.v1.status.StatusClient;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * 集群版ConsulClient
 *
 * 在客户端实现集群，省去使用Nginx/HAProxy等实现集群所带来的一系列问题(Nginx/HAProxy单点故障问题，服务注册与发现问题)
 *
 * 该集群版ConsulClient仅仅为基于spring-cloud-consul作为服务配置、服务注册、服务发现的客户端所定制：
 *
 * 1、默认组成客户端集群的节点必须是client模式的节点，并且在服务启动注册前都要求是可用的(健康的)
 *
 * 2、服务配置模块：关于ConsulClient KV操作仅在当前节点上执行一次，
 *
 * 如果当前节点不可用则使用RetryTemplate进行fallback重试!
 *
 * 3、服务注册模块：
 *
 * 3.1、ConsulServiceRegistry 中所用到的几个方法(agentServiceRegister,agentServiceDeregister,agentServiceSetMaintenance)，
 *
 * 在调用前均要求各集群节点必须可用(健康的)，并且在每个节点上执行一次！
 *
 * 为什么要每个client节点都注册一遍?
 *
 * 原因有二：
 *
 * (1)、在单个client节点上注册的服务信息仅在该client节点可用的情况下才会出现在集群中(ui/ConsulClient.getHealthServices())中可见，
 *
 * 如果该client节点挂了，那么集群中(ui/ConsulClient.getHealthServices())看不到它上面注册的服务了，因此必须多节点注册；
 *
 * (2)、在单个client节点上注册的服务信息及其healthcheck，仅在该节点可用的情况下healthcheck才被执行，
 *
 * 如果该节点挂了，那么该节点上注册的服务的healthcheck将无法执行，因此会出现服务实际是健康的，但是consul集群认为其是不健康的(因为负责健康检测的那个节点挂了)
 *
 * 3.2 TtlScheduler 中所用到的方法(agentCheckPass)，则尽最大努力在每个节点上执行一次!
 *
 * 4、服务发现模块：
 *
 * 4.1、服务发现模块所用到的几个方法(getCatalogServices，getHealthServices)，
 *
 * 仅在当前节点上执行一次，如果当前节点不可用则使用RetryTemplate进行fallback重试!
 *
 * 4.2、由3.1可知，服务发现模块所用到的获取服务实例列表方法(getHealthServices)，
 *
 * 它的调用结果存在重复，因此调用处(ConsulServiceRegistry.getInstances()、ConsulServerList.getXxxServers())需要加入排重逻辑!
 *
 * 5、其他SpringCloud中未使用到的方法，使用默认策略，即仅在当前节点上执行一次，
 *
 * 如果当前节点不可用则使用RetryTemplate进行fallback重试!
 */

@Slf4j
public class ClusterConsulClient extends ConsulClient implements AclClient, AgentClient,
    CatalogClient, CoordinateClient, EventClient, HealthClient, KeyValueClient,
    QueryClient, SessionClient, StatusClient, RetryListener {

  private static final String CURRENT_CLIENT_KEY = "currentClient";

  private static final String CLIENT = "client";
  private static final String SERVER = "server";

  @Getter
  private final ScheduledExecutorService consulClientsHealthCheckExecutor = Executors
      .newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 4);

  /**
   * ConsulClient配置
   */
  @Getter
  private final ClusterConsulProperties clusterConsulProperties;

  /**
   * 所有ConsulClient
   */
  @Getter
  private final List<ConsulClientHolder> consulClients;

  /**
   * 重试RetryTemplate
   */
  @Getter
  private final RetryTemplate retryTemplate;

  /**
   * 通过一致性hash算法算出来的节点,该节点作为主要使用节点,这样避免集群节点压力问题
   */
  private final ConsulClientHolder primaryClient;

  /**
   * 当前正在使用的ConsulClient
   */
  @Getter
  @Setter
  private volatile ConsulClientHolder currentClient;


  private Map<ConsulClientHolder, Boolean> consulClientHealthMap;

  /**
   * 集群节点在出错时切换的锁
   */
  private final Lock chooseLock = new ReentrantLock();

  public ClusterConsulClient(ClusterConsulProperties clusterConsulProperties) {
    super();
    Assert.notNull(clusterConsulProperties,
        "lansheng228: >>> Parameter 'consulProperties' must be required!");
    this.clusterConsulProperties = clusterConsulProperties;
    // 创建所有集群节点
    this.consulClients = createConsulClients();
    // 创建重试模板
    this.retryTemplate = createRetryTemplate();
    // 初始化主要客户端
    ConsulClientHolder tmpPrimaryClient = initPrimaryClient();

    // 如果存在不可用节点则立即快速失败
    Assert.state(this.consulClients.stream().allMatch(ConsulClientHolder::isHealthy),
        "lansheng228: >>> Creating ClusterConsulClient failed：all consul nodes of cluster must be available!");

    List<String> modeList = getAllConsulAgentMode();
    // 集群中的节点只能是client模式的节点?
    if (clusterConsulProperties.isOnlyClients()) {
      boolean isAllClientNode = modeList.stream().allMatch(CLIENT::equals);
      Assert.state(isAllClientNode,
          "lansheng228: >>> Creating ClusterConsulClient failed：all consul nodes of cluster must be in 'client' mode!");
    } else if (clusterConsulProperties.isOnlyServers()) {
      boolean isAllServerNode = modeList.stream().allMatch(SERVER::equals);
      Assert.state(isAllServerNode,
          "lansheng228: >>> Creating ClusterConsulClient failed：all consul nodes of cluster must be in 'server' mode!");
    }
    this.primaryClient = tmpPrimaryClient;
    this.currentClient = tmpPrimaryClient;
    this.consulClientHealthMap = new HashMap<>();
    this.scheduleConsulClientsHealthCheck();
  }

  //得到所有consul节点的模式：server 或者 client
  private List<String> getAllConsulAgentMode() {
    List<String> modeList = Lists.newArrayList();
    consulClients.forEach(client -> {
      Response<Self> response = client.getClient().getAgentSelf();
      if (response.getValue().getConfig().isServer()) {
        modeList.add(SERVER);
      } else {
        modeList.add(CLIENT);
      }
    });

    return modeList;
  }

  @Override
  public Response<String> getStatusLeader() {
    return retryTemplate.execute(context -> {
      Response<String> leader = getRetryConsulClient(context).getStatusLeader();
      log.info("lansheng228: >>> function getStatusLeader => leader: {} <<<", leader);

      return leader;
    });
  }

  @Override
  public Response<List<String>> getStatusPeers() {
    return retryTemplate.execute(context -> {
      Response<List<String>> peers = getRetryConsulClient(context).getStatusPeers();
      log.info("lansheng228: >>> function getStatusPeers => peers: {} <<<", peers);

      return peers;
    });
  }

  @Override
  public Response<String> sessionCreate(NewSession newSession, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<String> sessionCreate = getRetryConsulClient(context).sessionCreate(newSession, queryParams);
      log.info("lansheng228: >>> function sessionCreate => newSession: {} === queryParams: {} === sessionCreate: {} <<<", newSession, queryParams,
          sessionCreate);

      return sessionCreate;
    });
  }

  @Override
  public Response<String> sessionCreate(NewSession newSession, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<String> sessionCreate = getRetryConsulClient(context).sessionCreate(newSession,
          queryParams, token);
      log.info("lansheng228: >>> function sessionCreate => newSession: {} === queryParams: {} === token: {} === sessionCreate: {} <<<", newSession,
          queryParams, token, sessionCreate);

      return sessionCreate;
    });
  }

  @Override
  public Response<Void> sessionDestroy(String session, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      log.info("lansheng228: >>> function sessionDestroy => session: {} === queryParams: {}  <<<", session, queryParams);

      return getRetryConsulClient(context).sessionDestroy(session, queryParams);
    });
  }

  @Override
  public Response<Void> sessionDestroy(String session, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      log.info("lansheng228: >>> function sessionDestroy => session: {} === queryParams: {}  === token: {} <<<", session, queryParams, token);

      return getRetryConsulClient(context).sessionDestroy(session, queryParams, token);
    });
  }

  @Override
  public Response<Session> getSessionInfo(String session, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Session> sessionInfo = getRetryConsulClient(context).getSessionInfo(session, queryParams);
      log.info("lansheng228: >>> function getSessionInfo => session: {} === queryParams: {}  === sessionInfo: {} <<<", session, queryParams,
          sessionInfo);

      return sessionInfo;
    });
  }

  @Override
  public Response<Session> getSessionInfo(String session, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<Session> sessionInfo = getRetryConsulClient(context).getSessionInfo(session, queryParams, token);
      log.info("lansheng228: >>> function getSessionInfo => session: {} === queryParams: {}  === sessionInfo: {} <<<", session, queryParams,
          sessionInfo);

      return sessionInfo;
    });
  }

  @Override
  public Response<List<Session>> getSessionNode(String node, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Session>> sessionNode = getRetryConsulClient(context).getSessionNode(node, queryParams);
      log.info("lansheng228: >>> function getSessionNode => node: {} === queryParams: {}  === sessionNode: {} <<<", node, queryParams, sessionNode);

      return sessionNode;
    });
  }

  @Override
  public Response<List<Session>> getSessionNode(String node, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<Session>> sessionNode = getRetryConsulClient(context).getSessionNode(node,
          queryParams, token);
      log.info("lansheng228: >>> function getSessionNode => node: {} === queryParams: {}  === token: {}  === sessionNode: {} <<<", node, queryParams,
          token, sessionNode);

      return sessionNode;
    });
  }

  @Override
  public Response<List<Session>> getSessionList(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Session>> sessionList = getRetryConsulClient(context).getSessionList(queryParams);
      log.info("lansheng228: >>> function getSessionList => queryParams: {}   === sessionList: {} <<<", queryParams, sessionList);

      return sessionList;
    });
  }

  @Override
  public Response<List<Session>> getSessionList(QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<Session>> sessionList = getRetryConsulClient(context).getSessionList(queryParams, token);
      log.info("lansheng228: >>> function getSessionList => queryParams: {}   === token: {} === sessionList: {} <<<", queryParams, token,
          sessionList);

      return sessionList;
    });
  }

  @Override
  public Response<Session> renewSession(String session, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Session> renewSession = getRetryConsulClient(context).renewSession(session,
          queryParams);
      log.info("lansheng228: >>> function renewSession => session: {}   ===  queryParams: {}   === renewSession: {} <<<", session, queryParams,
          renewSession);

      return renewSession;
    });
  }

  @Override
  public Response<Session> renewSession(String session, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<Session> renewSession = getRetryConsulClient(context).renewSession(session,
          queryParams, token);
      log.info("lansheng228: >>> function renewSession => session: {}   ===  queryParams: {}   === token: {} === renewSession: {} <<<", session,
          queryParams, token, renewSession);

      return renewSession;
    });
  }

  @Override
  public Response<QueryExecution> executePreparedQuery(String uuid, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<QueryExecution> queryExecution = getRetryConsulClient(context).executePreparedQuery(uuid,
          queryParams);
      log.info("lansheng228: >>> function executePreparedQuery => uuid: {}   ===  queryParams: {}   === queryExecution: {}  <<<", uuid,
          queryParams, queryExecution);

      return queryExecution;
    });
  }

  @Override
  public Response<GetValue> getKVValue(String key) {
    return retryTemplate.execute(context -> {
      Response<GetValue> value = getRetryConsulClient(context).getKVValue(key);
      log.info("lansheng228: >>> function getKVValue => key: {}   ===  value: {} <<<", key, value);

      return value;
    });
  }

  @Override
  public Response<GetValue> getKVValue(String key, String token) {
    return retryTemplate.execute(context -> {
      Response<GetValue> value = getRetryConsulClient(context).getKVValue(key, token);
      log.info("lansheng228: >>> function getKVValue => key: {}   ===  token: {}  ===  value: {} <<<", key, token, value);

      return value;
    });
  }

  @Override
  public Response<GetValue> getKVValue(String key, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<GetValue> value = getRetryConsulClient(context).getKVValue(key, queryParams);
      log.info("lansheng228: >>> function getKVValue => key: {}   ===  queryParams: {}  ===  value: {} <<<", key, queryParams, value);

      return value;
    });
  }

  @Override
  public Response<GetValue> getKVValue(String key, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<GetValue> value = getRetryConsulClient(context).getKVValue(key, token,
          queryParams);
      log.info("lansheng228: >>> function getKVValue => key: {}   ===  token: {}  ===  queryParams: {}  ===  value: {} <<<", key, token, queryParams,
          value);

      return value;
    });
  }

  @Override
  public Response<GetBinaryValue> getKVBinaryValue(String key) {
    return retryTemplate.execute(context -> {
      Response<GetBinaryValue> binaryValue = getRetryConsulClient(context).getKVBinaryValue(key);
      log.info("lansheng228: >>> function getKVBinaryValue => key: {}  ===  binaryValue: {} <<<", key, binaryValue);

      return binaryValue;
    });
  }

  @Override
  public Response<GetBinaryValue> getKVBinaryValue(String key, String token) {
    return retryTemplate.execute(context -> {
      Response<GetBinaryValue> binaryValue = getRetryConsulClient(context).getKVBinaryValue(key, token);
      log.info("lansheng228: >>> function getKVBinaryValue => key: {}  ===  token: {}  ===  binaryValue: {} <<<", key, token, binaryValue);

      return binaryValue;
    });
  }

  @Override
  public Response<GetBinaryValue> getKVBinaryValue(String key, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<GetBinaryValue> binaryValue = getRetryConsulClient(context).getKVBinaryValue(key,
          queryParams);
      log.info("lansheng228: >>> function getKVBinaryValue => key: {}  ===  queryParams: {}  ===  binaryValue: {} <<<", key, queryParams,
          binaryValue);

      return binaryValue;
    });
  }

  @Override
  public Response<GetBinaryValue> getKVBinaryValue(String key, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<GetBinaryValue> binaryValue = getRetryConsulClient(context).getKVBinaryValue(key, token,
          queryParams);
      log.info("lansheng228: >>> function getKVBinaryValue => key: {}  ===  token: {}  ===  queryParams: {}  ===  binaryValue: {} <<<", key, token,
          queryParams, binaryValue);

      return binaryValue;
    });
  }

  @Override
  public Response<List<GetValue>> getKVValues(String keyPrefix) {
    return retryTemplate.execute(context -> {
      Response<List<GetValue>> valueList = getRetryConsulClient(context).getKVValues(keyPrefix);
      log.info("lansheng228: >>> function getKVValues => keyPrefix: {}  ===  valueList: {} <<<", keyPrefix, valueList);

      return valueList;
    });
  }

  @Override
  public Response<List<GetValue>> getKVValues(String keyPrefix, String token) {
    return retryTemplate.execute(context -> {
      Response<List<GetValue>> valueList = getRetryConsulClient(context).getKVValues(keyPrefix,
          token);
      log.info("lansheng228: >>> function getKVValues => keyPrefix: {}  ===  token: {}  ===  valueList: {} <<<", keyPrefix, token, valueList);

      return valueList;
    });
  }

  @Override
  public Response<List<GetValue>> getKVValues(String keyPrefix, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<GetValue>> valueList = getRetryConsulClient(context).getKVValues(keyPrefix,
          queryParams);
      log.info("lansheng228: >>> function getKVValues => keyPrefix: {}  ===  queryParams: {}  ===  valueList: {} <<<", keyPrefix, queryParams,
          valueList);

      return valueList;
    });
  }

  @Override
  public Response<List<GetValue>> getKVValues(String keyPrefix, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<GetValue>> valueList = getRetryConsulClient(context).getKVValues(keyPrefix, token,
          queryParams);
      log.info("lansheng228: >>> function getKVValues => keyPrefix: {}  ===  token: {}  ===  queryParams: {}  ===  valueList: {} <<<", keyPrefix,
          token, queryParams, valueList);

      return valueList;
    });
  }

  @Override
  public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix) {
    return retryTemplate.execute(context -> {
      Response<List<GetBinaryValue>> binaryValueList = getRetryConsulClient(context).getKVBinaryValues(keyPrefix);
      log.info("lansheng228: >>> function getKVBinaryValues => keyPrefix: {}  ===  binaryValueList: {} <<<", keyPrefix,
          binaryValueList);

      return binaryValueList;
    });
  }

  @Override
  public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix, String token) {
    return retryTemplate.execute(context -> {
      Response<List<GetBinaryValue>> binaryValueList = getRetryConsulClient(context).getKVBinaryValues(keyPrefix, token);
      log.info("lansheng228: >>> function getKVBinaryValues => keyPrefix: {}  ===  token: {}  ===  binaryValueList: {} <<<", keyPrefix, token,
          binaryValueList);

      return binaryValueList;
    });
  }

  @Override
  public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<GetBinaryValue>> binaryValueList = getRetryConsulClient(context).getKVBinaryValues(keyPrefix,
          queryParams);
      log.info("lansheng228: >>> function getKVBinaryValues => keyPrefix: {}  ===  queryParams: {}  ===  binaryValueList: {} <<<", keyPrefix,
          queryParams,
          binaryValueList);

      return binaryValueList;
    });
  }

  @Override
  public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<GetBinaryValue>> binaryValueList = getRetryConsulClient(context).getKVBinaryValues(keyPrefix, token, queryParams);
      log.info("lansheng228: >>> function getKVBinaryValues => keyPrefix: {}  ===  token: {}  ===  queryParams: {}  ===  binaryValueList: {} <<<",
          keyPrefix, token, queryParams, binaryValueList);

      return binaryValueList;
    });
  }

  @Override
  public Response<List<String>> getKVKeysOnly(String keyPrefix) {
    return retryTemplate.execute(context -> {
      Response<List<String>> keyList = getRetryConsulClient(context).getKVKeysOnly(keyPrefix);
      log.info("lansheng228: >>> function getKVKeysOnly => keyPrefix: {}  ===  keyList: {} <<<",
          keyPrefix, keyList);

      return keyList;
    });
  }

  @Override
  public Response<List<String>> getKVKeysOnly(String keyPrefix, String separator, String token) {
    return retryTemplate.execute(context -> {
      Response<List<String>> keyList = getRetryConsulClient(context).getKVKeysOnly(keyPrefix,
          separator, token);
      log.info("lansheng228: >>> function getKVKeysOnly => keyPrefix: {}  ===  separator: {}  ===  token: {}  ===  keyList: {} <<<",
          keyPrefix, separator, token, keyList);

      return keyList;
    });
  }

  @Override
  public Response<List<String>> getKVKeysOnly(String keyPrefix, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<String>> keyList = getRetryConsulClient(context).getKVKeysOnly(keyPrefix, queryParams);
      log.info("lansheng228: >>> function getKVKeysOnly => keyPrefix: {}  ===  queryParams: {} ===  keyList: {} <<<",
          keyPrefix, queryParams, keyList);

      return keyList;
    });
  }

  @Override
  public Response<List<String>> getKVKeysOnly(String keyPrefix, String separator, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<String>> keyList = getRetryConsulClient(context).getKVKeysOnly(keyPrefix,
          separator, token, queryParams);
      log.info(
          "lansheng228: >>> function getKVKeysOnly => keyPrefix: {}  ===  separator: {}  ===  token: {}  ===  queryParams: {} ===  keyList: {} <<<",
          keyPrefix, separator, token, queryParams, keyList);

      return keyList;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  result: {} <<<",
          key, value, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value, PutParams putParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value, putParams);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  putParams: {} ===  result: {} <<<",
          key, value, putParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value, String token,
      PutParams putParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value, token,
          putParams);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  token: {}  ===  putParams: {} ===  result: {} <<<",
          key, value, token, putParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value, queryParams);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  queryParams: {} ===  result: {} <<<",
          key, value, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value, PutParams putParams, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value, putParams, queryParams);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  putParams: {}  ===  queryParams: {} ===  result: {} <<<",
          key, value, putParams, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVValue(String key, String value, String token,
      PutParams putParams, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVValue(key, value, token,
          putParams, queryParams);
      log.info(
          "lansheng228: >>> function setKVValue => key: {}  ===  value: {}  ===  token: {}   ===  putParams: {}  ===  queryParams: {} ===  result: {} <<<",
          key, value, token, putParams, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  result: {} <<<",
          key, value, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value, PutParams putParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value, putParams);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  putParams: {}  ===  result: {} <<<",
          key, value, putParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value, String token, PutParams putParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value,
          token, putParams);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  token: {}  ===  putParams: {}  ===  result: {} <<<",
          key, value, token, putParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value, queryParams);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  queryParams: {}  ===  result: {} <<<",
          key, value, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value, PutParams putParams, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value, putParams, queryParams);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  putParams: {}   ===  queryParams: {}  ===  result: {} <<<",
          key, value, putParams, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Boolean> setKVBinaryValue(String key, byte[] value, String token,
      PutParams putParams, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Boolean> result = getRetryConsulClient(context).setKVBinaryValue(key, value,
          token, putParams, queryParams);
      log.info(
          "lansheng228: >>> function setKVBinaryValue => key: {}  ===  value: {}  ===  putParams: {}   ===  queryParams: {}  ===  result: {} <<<",
          key, value, putParams, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValue(String key) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValue(key);
      log.info("lansheng228: >>> function deleteKVValue => key: {} ===  result: {} <<<", key, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValue(String key, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValue(key, token);
      log.info("lansheng228: >>> function deleteKVValue => key: {}  ===  token: {}  ===  result: {} <<<", key, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValue(String key, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValue(key, queryParams);
      log.info("lansheng228: >>> function deleteKVValue => key: {}  ===  queryParams: {}  ===  result: {} <<<", key, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValue(String key, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValue(key, token, queryParams);
      log.info("lansheng228: >>> function deleteKVValue => key: {}  ===  token: {}  ===  queryParams: {}  ===  result: {} <<<", key, token,
          queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValues(String key) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValues(key);
      log.info("lansheng228: >>> function deleteKVValues => key: {}  ===  result: {} <<<", key, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValues(String key, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValues(key, token);
      log.info("lansheng228: >>> function deleteKVValues => key: {}  ===  token: {}  ===  result: {} <<<", key, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValues(String key, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValues(key, queryParams);
      log.info("lansheng228: >>> function deleteKVValues => key: {}  ===  queryParams: {}  ===  result: {} <<<", key, queryParams, result);

      return result;
    });
  }

  @Override
  public Response<Void> deleteKVValues(String key, String token, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).deleteKVValues(key, token, queryParams);
      log.info("lansheng228: >>> function deleteKVValues => key: {}  ===  token: {}  ===  queryParams: {}  ===  result: {} <<<", key, token,
          queryParams, result);

      return result;
    });
  }

  @Override
  public Response<List<Check>> getHealthChecksForNode(String nodeName, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Check>> checkList = getRetryConsulClient(context).getHealthChecksForNode(nodeName, queryParams);
      log.info("lansheng228: >>> function getHealthChecksForNode => nodeName: {}  ===  queryParams: {}  ===  checkList: {} <<<", nodeName,
          queryParams, checkList);

      return checkList;
    });
  }

  @Override
  public Response<List<Check>> getHealthChecksForService(String serviceName, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Check>> checkList = getRetryConsulClient(context).getHealthChecksForService(serviceName, queryParams);
      log.info("lansheng228: >>> function getHealthChecksForService => serviceName: {}  ===  queryParams: {}  ===  checkList: {} <<<", serviceName,
          queryParams, checkList);

      return checkList;
    });
  }

  @Override
  public Response<List<HealthService>> getHealthServices(String serviceName,
      boolean onlyPassing, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<HealthService>> healthServiceList = getRetryConsulClient(context)
          .getHealthServices(serviceName, onlyPassing, queryParams);
      log.info(
          "lansheng228: >>> function getHealthServices => serviceName: {}  ===  onlyPassing: {}  ===  queryParams: {}  ===  healthServiceList: {} <<<",
          serviceName, onlyPassing, queryParams, healthServiceList);

      return healthServiceList;
    });
  }

  @Override
  public Response<List<HealthService>> getHealthServices(String serviceName, String tag,
      boolean onlyPassing, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<HealthService>> healthServiceList = getRetryConsulClient(context).getHealthServices(
          serviceName, tag, onlyPassing, queryParams);
      log.info(
          "lansheng228: >>> function getHealthServices => serviceName: {}  ===  tag: {}  ===  onlyPassing: {}  ===  queryParams: {}  ===  healthServiceList: {} <<<",
          serviceName, tag, onlyPassing, queryParams, healthServiceList);

      return healthServiceList;
    });
  }

  @Override
  public Response<List<HealthService>> getHealthServices(String serviceName,
      boolean onlyPassing, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<HealthService>> healthServiceList = getRetryConsulClient(context).getHealthServices(
          serviceName, onlyPassing, queryParams, token);
      log.info(
          "lansheng228: >>> function getHealthServices => serviceName: {}  ===  onlyPassing: {}  ===  queryParams: {}  ===  token: {}  ===  healthServiceList: {} <<<",
          serviceName, onlyPassing, queryParams, token, healthServiceList);

      return healthServiceList;
    });
  }

  @Override
  public Response<List<HealthService>> getHealthServices(String serviceName, String tag,
      boolean onlyPassing, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<HealthService>> healthServiceList = getRetryConsulClient(context).getHealthServices(
          serviceName, tag, onlyPassing, queryParams, token);
      log.info(
          "lansheng228: >>> function getHealthServices => serviceName: {}  ===  tag: {}  ===  onlyPassing: {}  ===  queryParams: {}  ===  token: {}  ===  healthServiceList: {} <<<",
          serviceName, tag, onlyPassing, queryParams, token, healthServiceList);

      return healthServiceList;
    });
  }

  @Override
  public Response<List<Check>> getHealthChecksState(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Check>> checkList = getRetryConsulClient(context).getHealthChecksState(queryParams);
      log.info(
          "lansheng228: >>> function getHealthChecksState =>  queryParams: {}  ===  checkList: {} <<<",
          queryParams, checkList);

      return checkList;
    });
  }

  @Override
  public Response<List<Check>> getHealthChecksState(CheckStatus checkStatus, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Check>> checkList = getRetryConsulClient(context).getHealthChecksState(checkStatus, queryParams);
      log.info(
          "lansheng228: >>> function getHealthChecksState =>  checkStatus: {}  ===  queryParams: {}  ===  checkList: {} <<<",
          checkStatus, queryParams, checkList);

      return checkList;
    });
  }

  @Override
  public Response<Event> eventFire(String event, String payload, EventParams eventParams, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Event> eventFire = getRetryConsulClient(context).eventFire(event, payload, eventParams, queryParams);
      log.info(
          "lansheng228: >>> function eventFire =>  event: {}  ===  payload: {}  ===  eventParams: {} ===  queryParams: {}  ===  eventFire: {} <<<",
          event, payload, eventParams, queryParams, eventFire);

      return eventFire;
    });
  }

  @Override
  public Response<List<Event>> eventList(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Event>> eventList = getRetryConsulClient(context).eventList(queryParams);
      log.info(
          "lansheng228: >>> function eventList =>  queryParams: {}  ===  eventList: {} <<<",
          queryParams, eventList);

      return eventList;
    });
  }

  @Override
  public Response<List<Event>> eventList(String event, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Event>> eventList = getRetryConsulClient(context).eventList(event, queryParams);
      log.info(
          "lansheng228: >>> function eventList =>  event: {}  ===  queryParams: {}  ===  eventList: {} <<<",
          event, queryParams, eventList);

      return eventList;
    });
  }

  @Override
  public Response<List<Datacenter>> getDatacenters() {
    return retryTemplate.execute(context -> {
      Response<List<Datacenter>> datacenterList = getRetryConsulClient(context).getDatacenters();
      log.info(
          "lansheng228: >>> function getDatacenters =>  datacenterList: {} <<<",
          datacenterList);

      return datacenterList;
    });
  }

  @Override
  public Response<List<Node>> getNodes(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<Node>> nodeList = getRetryConsulClient(context).getNodes(queryParams);
      log.info(
          "lansheng228: >>> function getNodes =>  queryParams: {}  === nodeList: {} <<<",
          queryParams, nodeList);

      return nodeList;
    });
  }

  @Override
  public Response<Void> catalogRegister(CatalogRegistration catalogRegistration) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).catalogRegister(catalogRegistration);
      log.info(
          "lansheng228: >>> function catalogRegister =>  catalogRegistration: {}  === result: {} <<<",
          catalogRegistration, result);

      return result;
    });
  }

  @Override
  public Response<Void> catalogRegister(CatalogRegistration catalogRegistration, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).catalogRegister(catalogRegistration, token);
      log.info(
          "lansheng228: >>> function catalogRegister =>  catalogRegistration: {}  === token: {}  === result: {} <<<",
          catalogRegistration, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> catalogDeregister(CatalogDeregistration catalogDeregistration) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).catalogDeregister(catalogDeregistration);
      log.info(
          "lansheng228: >>> function catalogDeregister =>  catalogDeregistration: {}  === result: {} <<<",
          catalogDeregistration, result);

      return result;
    });
  }

  @Override
  public Response<List<String>> getCatalogDatacenters() {
    return retryTemplate.execute(context -> {
      Response<List<String>> catalogDatacenterList = getRetryConsulClient(context).getCatalogDatacenters();
      log.info(
          "lansheng228: >>> function getCatalogDatacenters =>  catalogDatacenterList: {} <<<",
          catalogDatacenterList);

      return catalogDatacenterList;
    });
  }

  @Override
  public Response<List<com.ecwid.consul.v1.catalog.model.Node>> getCatalogNodes(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<com.ecwid.consul.v1.catalog.model.Node>> catalogNodeList = getRetryConsulClient(context)
          .getCatalogNodes(queryParams);
      log.info(
          "lansheng228: >>> function getCatalogNodes =>  queryParams: {}  ===  catalogNodeList: {} <<<",
          queryParams, catalogNodeList);

      return catalogNodeList;
    });
  }

  @Override
  public Response<Map<String, List<String>>> getCatalogServices(QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<Map<String, List<String>>> catalogServiceMap = getRetryConsulClient(context)
          .getCatalogServices(queryParams);
      log.info(
          "lansheng228: >>> function getCatalogServices =>  queryParams: {}  ===  catalogServiceMap: {} <<<",
          queryParams, catalogServiceMap);

      return catalogServiceMap;
    });
  }

  @Override
  public Response<Map<String, List<String>>> getCatalogServices(QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<Map<String, List<String>>> catalogServiceMap = getRetryConsulClient(context)
          .getCatalogServices(queryParams, token);
      log.info(
          "lansheng228: >>> function getCatalogServices =>  queryParams: {}  ===  token: {}  ===  catalogServiceMap: {} <<<",
          queryParams, token, catalogServiceMap);

      return catalogServiceMap;
    });
  }

  @Override
  public Response<List<CatalogService>> getCatalogService(String serviceName, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<CatalogService>> catalogServiceList = getRetryConsulClient(context)
          .getCatalogService(serviceName, queryParams);
      log.info(
          "lansheng228: >>> function getCatalogService =>  serviceName: {}  ===  queryParams: {}  ===  catalogServiceList: {} <<<",
          serviceName, queryParams, catalogServiceList);

      return catalogServiceList;
    });
  }

  @Override
  public Response<List<CatalogService>> getCatalogService(String serviceName,
      String tag, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<List<CatalogService>> catalogServiceList = getRetryConsulClient(context)
          .getCatalogService(serviceName, tag, queryParams);
      log.info(
          "lansheng228: >>> function getCatalogService =>  serviceName: {}  ===  tag: {}  ===  queryParams: {}  ===  catalogServiceList: {} <<<",
          serviceName, tag, queryParams, catalogServiceList);

      return catalogServiceList;
    });
  }

  @Override
  public Response<List<CatalogService>> getCatalogService(String serviceName,
      QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<CatalogService>> catalogServiceList = getRetryConsulClient(context)
          .getCatalogService(serviceName, queryParams, token);
      log.info(
          "lansheng228: >>> function getCatalogService =>  serviceName: {}  ===  queryParams: {}  ===  token: {}  ===  catalogServiceList: {} <<<",
          serviceName, queryParams, token, catalogServiceList);

      return catalogServiceList;
    });
  }

  @Override
  public Response<List<CatalogService>> getCatalogService(String serviceName,
      String tag, QueryParams queryParams, String token) {
    return retryTemplate.execute(context -> {
      Response<List<CatalogService>> catalogServiceList = getRetryConsulClient(context)
          .getCatalogService(serviceName, tag, queryParams, token);
      log.info(
          "lansheng228: >>> function getCatalogService =>  serviceName: {}  ===  tag: {} ===  queryParams: {}  ===  token: {}  ===  catalogServiceList: {} <<<",
          serviceName, tag, queryParams, token, catalogServiceList);

      return catalogServiceList;
    });
  }

  @Override
  public Response<CatalogNode> getCatalogNode(String nodeName, QueryParams queryParams) {
    return retryTemplate.execute(context -> {
      Response<CatalogNode> catalogNode = getRetryConsulClient(context).getCatalogNode(nodeName, queryParams);
      log.info(
          "lansheng228: >>> function getCatalogNode =>  nodeName: {}  ===  queryParams: {}  ===  catalogNode: {} <<<",
          nodeName, queryParams, catalogNode);

      return catalogNode;
    });
  }

  @Override
  public Response<Map<String, com.ecwid.consul.v1.agent.model.Check>> getAgentChecks() {
    return retryTemplate.execute(context -> {
      Response<Map<String, com.ecwid.consul.v1.agent.model.Check>> checkList = getRetryConsulClient(context)
          .getAgentChecks();
      log.info(
          "lansheng228: >>> function getAgentChecks =>  checkList: {} <<<", checkList);

      return checkList;
    });
  }

  @Override
  public Response<Map<String, Service>> getAgentServices() {
    return retryTemplate.execute(context -> {
      Response<Map<String, Service>> agentServiceMap = getRetryConsulClient(context).getAgentServices();
      log.info(
          "lansheng228: >>> function getAgentServices =>  agentServiceMap: {} <<<", agentServiceMap);

      return agentServiceMap;
    });
  }

  @Override
  public Response<List<Member>> getAgentMembers() {
    return retryTemplate.execute(context -> {
      Response<List<Member>> agentMemberList = getRetryConsulClient(context).getAgentMembers();
      log.info(
          "lansheng228: >>> function getAgentMembers =>  agentMemberList: {} <<<", agentMemberList);

      return agentMemberList;
    });
  }

  @Override
  public Response<Self> getAgentSelf() {
    return retryTemplate.execute(context -> {
      Response<Self> agentSelf = getRetryConsulClient(context).getAgentSelf();
      log.info(
          "lansheng228: >>> function getAgentSelf =>  agentSelf: {} <<<", agentSelf);

      return agentSelf;
    });
  }

  @Override
  public Response<Self> getAgentSelf(String token) {
    return retryTemplate.execute(context -> {
      Response<Self> agentSelf = getRetryConsulClient(context).getAgentSelf(token);
      log.info(
          "lansheng228: >>> function getAgentSelf =>  token: {}  ===  agentSelf: {} <<<", token, agentSelf);

      return agentSelf;
    });
  }

  @Override
  public Response<Void> agentSetMaintenance(boolean maintenanceEnabled) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentSetMaintenance(maintenanceEnabled);
      log.info(
          "lansheng228: >>> function agentSetMaintenance =>  maintenanceEnabled: {}  ===  result: {} <<<", maintenanceEnabled, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentSetMaintenance(boolean maintenanceEnabled, String reason) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentSetMaintenance(maintenanceEnabled, reason);
      log.info(
          "lansheng228: >>> function agentSetMaintenance =>  maintenanceEnabled: {}  ===  reason: {}  ===  result: {} <<<",
          maintenanceEnabled, reason, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentJoin(String address, boolean wan) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentJoin(address, wan);
      log.info(
          "lansheng228: >>> function agentJoin =>  address: {}  ===  wan: {}  ===  result: {} <<<",
          address, wan, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentForceLeave(String node) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentForceLeave(node);
      log.info(
          "lansheng228: >>> function agentForceLeave => node: {}  ===  result: {} <<<",
          node, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckRegister(NewCheck newCheck) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckRegister(newCheck);
      log.info(
          "lansheng228: >>> function agentCheckRegister => newCheck: {}  ===  result: {} <<<",
          newCheck, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckRegister(NewCheck newCheck, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckRegister(newCheck, token);
      log.info(
          "lansheng228: >>> function agentCheckRegister => newCheck: {}  ===  token: {}  ===  result: {} <<<",
          newCheck, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckDeregister(String checkId) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckDeregister(checkId);
      log.info(
          "lansheng228: >>> function agentCheckDeregister => checkId: {}  ===  result: {} <<<",
          checkId, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckDeregister(String checkId, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckDeregister(checkId, token);
      log.info(
          "lansheng228: >>> function agentCheckDeregister => checkId: {}  ===  token: {}  ===  result: {} <<<",
          checkId, token, result);

      return result;
    });
  }

  /**
   * 尽最大努力向每个节点报告自身的健康状况
   *
   * see TtlScheduler
   */
  @Override
  public Response<Void> agentCheckPass(String checkId) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      try {
        response = consulClient.getClient().agentCheckPass(checkId);
      } catch (Exception e) {
        log.error("lansheng228: >>> {} <<<", e.getMessage());
      }
    }
    return response;
  }

  /**
   * 尽最大努力向每个节点报告自身的健康状况
   *
   * see TtlScheduler
   */
  @Override
  public Response<Void> agentCheckPass(String checkId, String note) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      try {
        response = consulClient.getClient().agentCheckPass(checkId, note);
      } catch (Exception e) {
        log.error("lansheng228: >>> {} <<<", e.getMessage());
      }
    }

    return response;
  }

  /**
   * 尽最大努力向每个节点报告自身的健康状况
   *
   * see TtlScheduler
   */
  @Override
  public Response<Void> agentCheckPass(String checkId, String note, String token) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      try {
        response = consulClient.getClient().agentCheckPass(checkId, note, token);
      } catch (Exception e) {
        log.error("lansheng228: >>> {} <<<", e.getMessage());
      }
    }
    return response;
  }

  @Override
  public Response<Void> agentCheckWarn(String checkId) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckWarn(checkId);
      log.info(
          "lansheng228: >>> function agentCheckWarn => checkId: {}  ===  result: {} <<<",
          checkId, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckWarn(String checkId, String note) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckWarn(checkId, note);
      log.info(
          "lansheng228: >>> function agentCheckWarn => checkId: {}  ===  note: {}  ===  result: {} <<<",
          checkId, note, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckWarn(String checkId, String note, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckWarn(checkId, note, token);
      log.info(
          "lansheng228: >>> function agentCheckWarn => checkId: {}  ===  note: {}  ===  token: {}  ===  result: {} <<<",
          checkId, note, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckFail(String checkId) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckFail(checkId);
      log.info(
          "lansheng228: >>> function agentCheckFail => checkId: {}  ===  result: {} <<<",
          checkId, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckFail(String checkId, String note) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckFail(checkId, note);
      log.info(
          "lansheng228: >>> function agentCheckFail => checkId: {}  ===  note: {}  ===  result: {} <<<",
          checkId, note, result);

      return result;
    });
  }

  @Override
  public Response<Void> agentCheckFail(String checkId, String note, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).agentCheckFail(checkId, note, token);
      log.info(
          "lansheng228: >>> function agentCheckFail => checkId: {}  ===  note: {}  ===  token: {}  ===  result: {} <<<",
          checkId, note, token, result);

      return result;
    });
  }

  /**
   * 则向可用节点注册服务
   *
   * see ConsulServiceRegistry.register(...)
   */
  @Override
  public Response<Void> agentServiceRegister(NewService newService) {
//    Response<Void> response = null;
//    for (ConsulClientHolder consulClient : consulClients) {
//      if (consulClient.isHealthy()) {
//        response = consulClient.getClient().agentServiceRegister(newService);
//      }
//    }
//    log.info(
//        "lansheng228: >>> function agentServiceRegister => newService: {}  ===  response: {} <<<",
//        newService, response);
//
//    return response;

    return retryTemplate.execute(context -> {
      Response<Void> result = null;
      for (ConsulClientHolder consulClient : consulClients) {
        if (consulClient.isHealthy()) {
          result = consulClient.getClient().agentServiceRegister(newService);
        }
      }
      log.info(
          "lansheng228: >>> function agentServiceRegister => newService: {}  ===  result: {}  <<<",
          newService, result);

      return result;
    });
  }

  /**
   * 则向可用节点注册服务
   *
   * see ConsulServiceRegistry.register(...)
   */
  @Override
  public Response<Void> agentServiceRegister(NewService newService, String token) {
//    Response<Void> response = null;
//    for (ConsulClientHolder consulClient : consulClients) {
//      if (consulClient.isHealthy()) {
//        response = consulClient.getClient().agentServiceRegister(newService, token);
//      }
//    }
//
//    log.info(
//        "lansheng228: >>> function agentServiceRegister => newService: {}  ===  token: {} ===  response: {} <<<",
//        newService, token, response);
//
//    return response;

    return retryTemplate.execute(context -> {
      Response<Void> result = null;
      for (ConsulClientHolder consulClient : consulClients) {
        if (consulClient.isHealthy()) {
          result = consulClient.getClient().agentServiceRegister(newService, token);
        }
      }
      log.info(
          "lansheng228: >>> function agentServiceRegister => newService: {}  ===  token: {} ===  response: {} <<<",
          newService, token, result);

      return result;
    });
  }

  /**
   * 则向可用节点注销服务
   *
   * see ConsulServiceRegistry.deregister(...)
   */
  @Override
  public Response<Void> agentServiceDeregister(String serviceId) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      if (consulClient.isHealthy()) {
        response = consulClient.getClient().agentServiceDeregister(serviceId);
      }
    }

    log.info(
        "lansheng228: >>> function agentServiceDeregister => serviceId: {}   ===  response: {}  <<<",
        serviceId, response);

    return response;
  }

  /**
   * 则向可用节点注销服务
   *
   * see ConsulServiceRegistry.deregister(...)
   */
  @Override
  public Response<Void> agentServiceDeregister(String serviceId, String token) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      if (consulClient.isHealthy()) {
        response = consulClient.getClient().agentServiceDeregister(serviceId, token);
      }
    }

    log.info(
        "lansheng228: >>> function agentServiceDeregister => serviceId: {}  ===  token: {}  ===  response: {}  <<<",
        serviceId, token, response);

    return response;
  }

  /**
   * 则向可用节点执行setMaintenance
   *
   * see ConsulServiceRegistry.setStatus(...)
   */
  @Override
  public Response<Void> agentServiceSetMaintenance(String serviceId, boolean maintenanceEnabled) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      if (consulClient.isHealthy()) {
        response = consulClient.getClient().agentServiceSetMaintenance(serviceId,
            maintenanceEnabled);
      }
    }

    log.info(
        "lansheng228: >>> function agentServiceSetMaintenance => serviceId: {}  ===  maintenanceEnabled: {}  ===  response: {}  <<<",
        serviceId, maintenanceEnabled, response);

    return response;
  }

  /**
   * 向可用节点执行setMaintenance
   *
   * see ConsulServiceRegistry.setStatus(...)
   */
  @Override
  public Response<Void> agentServiceSetMaintenance(String serviceId,
      boolean maintenanceEnabled, String reason) {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      if (consulClient.isHealthy()) {
        response = consulClient.getClient().agentServiceSetMaintenance(serviceId,
            maintenanceEnabled, reason);
      }
    }

    log.info(
        "lansheng228: >>> function agentServiceSetMaintenance => serviceId: {}  ===  maintenanceEnabled: {}  ===  reason: {} ===  response: {}  <<<",
        serviceId, maintenanceEnabled, reason, response);

    return response;
  }

  /**
   * 尽最大努力在每个节点上执行agentReload()操作
   */
  @Override
  public Response<Void> agentReload() {
    Response<Void> response = null;
    for (ConsulClientHolder consulClient : consulClients) {
      try {
        response = consulClient.getClient().agentReload();
      } catch (Exception e) {
        log.error("lansheng228: >>> {} <<<", e.getMessage());
      }
    }

    log.info(
        "lansheng228: >>> function agentReload =>  response: {}  <<<", response);

    return response;
  }

  @Override
  public Response<String> aclCreate(NewAcl newAcl, String token) {
    return retryTemplate.execute(context -> {
      Response<String> acl = getRetryConsulClient(context).aclCreate(newAcl, token);
      log.info(
          "lansheng228: >>> function aclCreate => newAcl: {}  ===  token: {}  ===  acl: {} <<<",
          newAcl, token, acl);

      return acl;
    });
  }

  @Override
  public Response<Void> aclUpdate(UpdateAcl updateAcl, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).aclUpdate(updateAcl, token);
      log.info(
          "lansheng228: >>> function aclUpdate => updateAcl: {}  ===  token: {}  ===  result: {} <<<",
          updateAcl, token, result);

      return result;
    });
  }

  @Override
  public Response<Void> aclDestroy(String aclId, String token) {
    return retryTemplate.execute(context -> {
      Response<Void> result = getRetryConsulClient(context).aclDestroy(aclId, token);
      log.info(
          "lansheng228: >>> function aclDestroy => aclId: {}  ===  token: {}  ===  result: {} <<<",
          aclId, token, result);

      return result;
    });
  }

  @Override
  public Response<Acl> getAcl(String id) {
    return retryTemplate.execute(context -> {
      Response<Acl> acl = getRetryConsulClient(context).getAcl(id);
      log.info(
          "lansheng228: >>> function getAcl => id: {}  ===  acl: {} <<<",
          id, acl);

      return acl;
    });
  }

  @Override
  public Response<String> aclClone(String aclId, String token) {
    return retryTemplate.execute(context -> {
      Response<String> aclClone = getRetryConsulClient(context).aclClone(aclId, token);
      log.info(
          "lansheng228: >>> function aclClone => aclId: {}  ===  token: {}  ===  aclClone: {} <<<",
          aclId, token, aclClone);

      return aclClone;
    });
  }

  @Override
  public Response<List<Acl>> getAclList(String token) {
    return retryTemplate.execute(context -> {
      Response<List<Acl>> aclList = getRetryConsulClient(context).getAclList(token);
      log.info(
          "lansheng228: >>> function getAclList => token: {}  ===  aclList: {} <<<",
          token, aclList);

      return aclList;
    });
  }

  /**
   * 创建所有ConsulClient
   *
   * @return 返回所有节点
   */
  protected List<ConsulClientHolder> createConsulClients() {
    List<String> connectList = prepareConnectList();
    List<ConsulClientHolder> tmpConsulClients = connectList.stream().map(connect -> {
      String[] connects = connect.split(CommonConstant.SEPARATOR_COLON);
      ConsulProperties properties = new ConsulProperties();
      properties.setEnabled(clusterConsulProperties.isEnabled());
      properties.setScheme(clusterConsulProperties.getScheme());
      properties.setTls(clusterConsulProperties.getTls());
      properties.setHost(connects[0]);
      properties.setPort(Integer.parseInt(connects[1]));
      return new ConsulClientHolder(properties);
    }).sorted().collect(Collectors.toList()); // 排序
    connectList = tmpConsulClients.stream().map(ConsulClientHolder::getClientId)
        .collect(Collectors.toList());
    log.info("lansheng228: >>> Creating cluster consul clients: {} <<<", connectList);

    return tmpConsulClients;
  }

  /**
   * 准备ConsulClient的连接标识
   */
  protected List<String> prepareConnectList() {
    List<String> connectList = clusterConsulProperties.getClusterNodes();
    log.info("lansheng228: >>> Connect list: " + connectList + " <<<");

    return connectList;
  }

  /**
   * 创建重试RetryTemplate， 默认使用SimpleRetryPolicy(maxAttempts定为consulClients.size() + 1)
   */
  protected RetryTemplate createRetryTemplate() {
    Map<Class<? extends Throwable>, Boolean> retryableExceptions = null;

    if (!CollectionUtils.isEmpty(clusterConsulProperties.getRetryableExceptions())) {
      retryableExceptions = clusterConsulProperties.getRetryableExceptions().stream()
          .collect(Collectors.toMap(Function.identity(), e -> Boolean.TRUE,
              (oldValue, newValue) -> newValue));
    }

    if (!CollectionUtils.isEmpty(retryableExceptions)) {
      retryableExceptions = createDefaultRetryableExceptions();
    }
    RetryTemplate tmpRetryTemplate = new RetryTemplate();
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(consulClients.size(),
        retryableExceptions, true);
    tmpRetryTemplate.setRetryPolicy(retryPolicy);
    tmpRetryTemplate.setListeners(new RetryListener[]{this});

    return tmpRetryTemplate;
  }

  /**
   * 创建默认的retryableExceptions
   */
  protected Map<Class<? extends Throwable>, Boolean> createDefaultRetryableExceptions() {
    Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
    retryableExceptions.put(TransportException.class, true);
    retryableExceptions.put(OperationException.class, true);
    retryableExceptions.put(IOException.class, true);
    retryableExceptions.put(ConnectException.class, true);
    retryableExceptions.put(TimeoutException.class, true);
    retryableExceptions.put(SocketTimeoutException.class, true);

    return retryableExceptions;
  }

  /**
   * 根据已生成的集群列表(不管节点健康状况)初始化主要ConsulClient
   */
  protected ConsulClientHolder initPrimaryClient() {
    String key = this.clusterConsulProperties.getClusterClientKey();
    List<ConsulClientHolder> clients = this.consulClients;
    ConsulClientHolder chooseClient = ConsulClientUtil.chooseClient(key, clients);
    log.info("lansheng228: >>>  Hash Key: {}  ==== Hash List: {}  ====  Hash Result: {} <<<", key, clients, chooseClient);

    return chooseClient;
  }

  /**
   * 通过哈希一致性算法选择一个健康的ConsulClient
   */
  protected void chooseConsulClient() {
    try {
      this.chooseLock.lock();
      if (!this.currentClient.isHealthy()) {
        // 过滤出健康节点
        List<ConsulClientHolder> availableClients = this.consulClients.stream()
            .filter(ConsulClientHolder::isHealthy).sorted()
            .collect(Collectors.toList());
        log.info("lansheng228: >>> Available ConsulClients: " + availableClients + " <<<");

        if (ObjectUtils.isEmpty(availableClients)) {
          checkConsulClientsHealth(); // 一个健康节点都没有，则立马执行一次全部健康检测
          throw new IllegalStateException("lansheng228: >>> No consul client is available!!!");
        }

        // 在健康节点中通过哈希一致性算法选取一个节点
        ConsulClientHolder choosedClient = ConsulClientUtil.chooseClient(
            this.clusterConsulProperties.getClusterClientKey(), availableClients);

        if (choosedClient == null) {
          throw new IllegalStateException("lansheng228: >>> Choosed New ConsulClient Fail!!!");
        }

        log.info("lansheng228: >>> Successfully choosed a new ConsulClient : {} <<<",
            choosedClient);
        this.currentClient = choosedClient;
      }
    } finally {
      this.chooseLock.unlock();
    }
  }

  /**
   * 获取重试的ConsulClient
   *
   * @param context - 重试上下文
   */
  protected ConsulClient getRetryConsulClient(RetryContext context) {
    context.setAttribute(CURRENT_CLIENT_KEY, this.currentClient);
    int retryCount = context.getRetryCount();
    if (!this.currentClient.isHealthy()) {
      log.info("lansheng228: >>> Current ConsulClient[{}] Is Unhealthy. Choose Again! <<<",
          this.currentClient);
      chooseConsulClient();
    }
    if (retryCount > 0) {
      log.info("lansheng228: >>> Using current ConsulClient[{}] for retry {} <<<",
          this.currentClient, retryCount);
    }

    return this.currentClient.getClient();
  }

  @Override
  public final <T, E extends Throwable> boolean open(RetryContext context,
      RetryCallback<T, E> callback) {
    return true;
  }

  @Override
  public final <T, E extends Throwable> void close(RetryContext context,
      RetryCallback<T, E> callback, Throwable throwable) {
    context.removeAttribute(CURRENT_CLIENT_KEY);
  }

  /**
   * 每次ConsulClient调用出错之后且在下次重试之前调用该方法
   */
  @Override
  public <T, E extends Throwable> void onError(RetryContext context,
      RetryCallback<T, E> callback, Throwable throwable) {
    ConsulClientHolder tmpCurrentClient = (ConsulClientHolder) context
        .getAttribute(CURRENT_CLIENT_KEY);
    if (tmpCurrentClient != null) {
      tmpCurrentClient.setHealthy(false);
    }
  }

  /**
   * ConsulClient集群的健康检测
   */
  protected void scheduleConsulClientsHealthCheck() {
    consulClientsHealthCheckExecutor.scheduleAtFixedRate(
        this::checkConsulClientsHealth, clusterConsulProperties.getHealthCheckInterval(),
        clusterConsulProperties.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
  }

  /**
   * 对全部的ConsulClient检测一次健康状况
   */
  protected void checkConsulClientsHealth() {
    this.consulClientHealthMap = checkAllConsulClientsHealth();

    boolean allHealthy = isAllConsulClientsHealthy();
    if (allHealthy && (this.currentClient != this.primaryClient)) { // 如果所有节点都是健康的，那么恢复currentClient为primaryClient
      this.currentClient = this.primaryClient;
      log.info("lansheng228: >>> The primaryClient is recovered when all consul clients is healthy. <<<");
    }
  }

  private Map<ConsulClientHolder, Boolean> checkAllConsulClientsHealth() {
    Map<ConsulClientHolder, Boolean> tmpConsulClientHealthMap = new HashMap<>();
    for (ConsulClientHolder consulClient : this.consulClients) {
      consulClient.checkHealth();
      tmpConsulClientHealthMap.put(consulClient, consulClient.isHealthy());
      consulClient.setPrimary(consulClient == this.primaryClient);
    }

    return tmpConsulClientHealthMap;
  }

  /**
   * 判断全部的ConsulClient是否都是健康的?
   */
  protected boolean isAllConsulClientsHealthy() {
    AtomicBoolean allHealthy = new AtomicBoolean(true);
    this.consulClientHealthMap.values().forEach(isHealthy -> allHealthy.set(allHealthy.get() && isHealthy));

    return allHealthy.get();
  }
}
