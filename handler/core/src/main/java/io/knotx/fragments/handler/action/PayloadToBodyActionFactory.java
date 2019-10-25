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

import static io.knotx.commons.json.JsonObjectUtil.getJsonObject;
import static io.vertx.core.Future.succeededFuture;

import io.knotx.fragments.api.Fragment;
import io.knotx.fragments.handler.api.Action;
import io.knotx.fragments.handler.api.ActionConfig;
import io.knotx.fragments.handler.api.ActionFactory;
import io.knotx.fragments.handler.api.actionlog.ActionLogger;
import io.knotx.fragments.handler.api.domain.FragmentResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.Optional;

public class PayloadToBodyActionFactory implements ActionFactory {

  private static final String KEY = "key";

  @Override
  public String getName() {
    return "payload-to-body";
  }

  @Override
  public Action create(ActionConfig config, Vertx vertx) {
    checkArgument(config.hasAction(), "Payload to body action does not support doAction");

    JsonObject options = config.getOptions();
    return (fragmentContext, resultHandler) -> {
      Fragment fragment = fragmentContext.getFragment();
      String payloadKey = Objects.nonNull(options) ? options.getString(KEY) : null;
      ActionLogger actionLogger = ActionLogger.create(config);
      FragmentResult result = getBodyFromPayload(payloadKey, fragment.getPayload())
          .map(body -> toFragmentResult(fragment, body, actionLogger))
          .orElseGet(() -> toErrorFragmentResult(fragment, payloadKey, actionLogger));

      Future<FragmentResult> resultFuture = succeededFuture(result);
      resultFuture.setHandler(resultHandler);
    };
  }

  private FragmentResult toErrorFragmentResult(Fragment fragment, String payloadKey, ActionLogger actionLogger) {
    actionLogger.error("body", String.format("No value found under key in payload %s", payloadKey));
    return new FragmentResult(fragment, FragmentResult.ERROR_TRANSITION, actionLogger.toLog());
  }

  private FragmentResult toFragmentResult(Fragment fragment, String body, ActionLogger actionLogger) {
    fragment.setBody(body);
    actionLogger.info("body", body);
    return new FragmentResult(fragment, FragmentResult.SUCCESS_TRANSITION, actionLogger.toLog());
  }

  private Optional<String> getBodyFromPayload(String key, JsonObject payload) {
    JsonObject body = Objects.isNull(key) ? payload : getJsonObject(key, payload);
    return Optional.ofNullable(body)
        .map(JsonObject::encodePrettily);

  }

  private void checkArgument(boolean condition, String message) {
    if (condition) {
      throw new IllegalArgumentException(message);
    }
  }
}