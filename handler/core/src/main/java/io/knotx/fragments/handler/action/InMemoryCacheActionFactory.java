/*
 * Copyright (C) 2019 Knot.x Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.knotx.fragments.handler.action;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.knotx.fragments.api.Fragment;
import io.knotx.fragments.handler.api.Action;
import io.knotx.fragments.handler.api.ActionFactory;
import io.knotx.fragments.handler.api.Cacheable;
import io.knotx.fragments.handler.api.actionlog.ActionLogLevel;
import io.knotx.fragments.handler.api.actionlog.ActionLogger;
import io.knotx.fragments.handler.api.domain.FragmentContext;
import io.knotx.fragments.handler.api.domain.FragmentResult;
import io.knotx.fragments.handler.helper.TimeCalculator;
import io.knotx.server.api.context.ClientRequest;
import io.knotx.server.common.placeholders.PlaceholdersResolver;
import io.knotx.server.common.placeholders.SourceDefinitions;
import io.reactivex.Maybe;
import io.reactivex.Single;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

/**
 * Payload Cache Action factory class. It can be initialized with a configuration:
 * <pre>
 *   productDetails {
 *     name = in-memory-cache,
 *     config {
 *       cache {
 *         maximumSize = 1000
 *         ttl = 5000
 *       }
 *       cacheKey = product-{param.id}
 *       payloadKey = product
 *       logLevel = info
 *     }
 *   }
 * </pre>
 */
@Cacheable
public class InMemoryCacheActionFactory implements ActionFactory {

  public static final String CACHE_LOOKUP = "CACHE_LOOKUP";
  public static final String CACHE_MISS = "CACHE_MISS";
  public static final String CACHE_HIT = "CACHE_HIT";
  public static final String CACHE_PASS = "CACHE_PASS";
  public static final String TRANSITION = "TRANSITION";

  private static final String LOG_LEVEL_KEY = "logLevel";

  private static final long DEFAULT_MAXIMUM_SIZE = 1000;
  private static final long DEFAULT_TTL = 5000;


  @Override
  public String getName() {
    return "in-memory-cache";
  }

  @Override
  public Action create(String alias, JsonObject config, Vertx vertx, Action doAction) {

    return new Action() {
      private Cache<String, Object> cache = createCache(config);
      private String payloadKey = getPayloadKey(config);
      private String logLevel = getActionLogLevel(config);

      @Override
      public void apply(FragmentContext fragmentContext,
          Handler<AsyncResult<FragmentResult>> resultHandler) {
        ActionLogger actionLogger = ActionLogger.create(alias, logLevel);
        String cacheKey = getCacheKey(config, fragmentContext.getClientRequest());

        getFromCache(fragmentContext, cacheKey, actionLogger)
            .switchIfEmpty(callDoActionAndCache(fragmentContext, cacheKey, actionLogger))
            .map(Future::succeededFuture)
            .onErrorReturn(Future::failedFuture)
            .doOnSuccess(future -> future.setHandler(resultHandler))
            .subscribe();
      }

      private Maybe<FragmentResult> getFromCache(FragmentContext fragmentContext, String cacheKey,
          ActionLogger actionLogger) {
        return Maybe.just(cacheKey)
            .doOnSuccess(key -> logCacheLookup(actionLogger, key))
            .flatMap(this::findInCache)
            .doOnSuccess(cachedValue -> logCacheHit(actionLogger, cachedValue))
            .map(cachedValue -> fragmentContext.getFragment()
                .appendPayload(payloadKey, cachedValue))
            .map(fragment -> toResultWithLog(actionLogger, fragment));
      }

      private Maybe<Object> findInCache(String key) {
        Object cachedValue = cache.getIfPresent(key);
        if (cachedValue == null) {
          return Maybe.empty();
        } else {
          return Maybe.just(cachedValue);
        }
      }

      private Single<FragmentResult> callDoActionAndCache(FragmentContext fragmentContext,
          String cacheKey,
          ActionLogger actionLogger) {
        long startTime = Instant.now().toEpochMilli();
        return io.knotx.fragments.handler.reactivex.api.Action.newInstance(doAction)
            .rxApply(fragmentContext)
            .doOnSuccess(fr -> logDoAction(actionLogger, startTime, fr))
            .doOnSuccess(fr -> savePayloadToCache(actionLogger, cacheKey, fr))
            .map(fr -> toResultWithLog(actionLogger, fr));
      }

      private void savePayloadToCache(ActionLogger actionLogger, String cacheKey,
          FragmentResult fragmentResult) {
        if (isCacheable(fragmentResult)) {
          Object resultPayload = getAppendedPayload(fragmentResult);
          cache.put(cacheKey, resultPayload);
          logCacheMiss(actionLogger, resultPayload);
        } else {
          logCachePass(actionLogger, fragmentResult);
        }
      }

      private boolean isCacheable(FragmentResult fragmentResult) {
        return FragmentResult.SUCCESS_TRANSITION.equals(fragmentResult.getTransition())
            && fragmentResult.getFragment()
            .getPayload()
            .containsKey(payloadKey);
      }

      private Object getAppendedPayload(FragmentResult fragmentResult) {
        return fragmentResult.getFragment()
            .getPayload().getMap().get(payloadKey);
      }

      private FragmentResult toResultWithLog(ActionLogger actionLogger, Fragment fragment) {
        return new FragmentResult(fragment, FragmentResult.SUCCESS_TRANSITION,
            actionLogger.toLog().toJson());
      }

      private FragmentResult toResultWithLog(ActionLogger actionLogger,
          FragmentResult fragmentResult) {
        return new FragmentResult(fragmentResult.getFragment(), fragmentResult.getTransition(),
            actionLogger.toLog().toJson());
      }
    };
  }

  private String getPayloadKey(JsonObject config) {
    String result = config.getString("payloadKey");
    if (StringUtils.isBlank(result)) {
      throw new IllegalArgumentException(
          "Action requires payloadKey value in configuration.");
    }
    return result;
  }

  private String getCacheKey(JsonObject config, ClientRequest clientRequest) {
    String key = config.getString("cacheKey");
    if (StringUtils.isBlank(key)) {
      throw new IllegalArgumentException("Action requires cacheKey value in configuration.");
    }
    return PlaceholdersResolver.resolve(key, buildSourceDefinitions(clientRequest));
  }

  private SourceDefinitions buildSourceDefinitions(ClientRequest clientRequest) {
    return SourceDefinitions.builder()
        .addClientRequestSource(clientRequest)
        .build();
  }

  private Cache<String, Object> createCache(JsonObject config) {
    JsonObject cache = config.getJsonObject("cache");
    long maxSize =
        cache == null ? DEFAULT_MAXIMUM_SIZE : cache.getLong("maximumSize", DEFAULT_MAXIMUM_SIZE);
    long ttl = cache == null ? DEFAULT_TTL : cache.getLong("ttl", DEFAULT_TTL);
    return CacheBuilder.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(ttl, TimeUnit.MILLISECONDS)
        .build();
  }

  private static String getActionLogLevel(JsonObject config) {
    return config.containsKey(LOG_LEVEL_KEY) ? config.getString(LOG_LEVEL_KEY)
        : ActionLogLevel.ERROR.getLevel();
  }

  private static void logDoAction(ActionLogger actionLogger, long startTime,
      FragmentResult fragmentResult) {
    actionLogger.doActionLog(TimeCalculator.executionTime(startTime), fragmentResult.getNodeLog());
  }

  private static void logCacheLookup(ActionLogger actionLogger, String cacheKey) {
    actionLogger.info(CACHE_LOOKUP, cacheKey);
  }

  private static void logCacheHit(ActionLogger actionLogger, Object cachedValue) {
    actionLogger.info(CACHE_HIT, cachedValue);
  }

  private static void logCacheMiss(ActionLogger actionLogger, Object computedValue) {
    actionLogger.info(CACHE_MISS, computedValue);
  }

  private static void logCachePass(ActionLogger actionLogger, FragmentResult failedResult) {
    actionLogger.info(CACHE_PASS,
        new JsonObject().put(TRANSITION, failedResult.getTransition()));
  }
}
