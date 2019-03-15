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
package io.knotx.engine.handler.options;

import io.knotx.engine.api.KnotFlow;
import io.knotx.engine.api.KnotFlowStep;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Objects;

@DataObject(generateConverter = true)
public class KnotEngineHandlerOptions {

  private Map<String, KnotFlow> flows;

  private Map<String, KnotFlowStep> steps;

  public KnotEngineHandlerOptions(Map<String, KnotFlow> flows,
      Map<String, KnotFlowStep> steps) {
    this.flows = flows;
    this.steps = steps;
  }

  public KnotEngineHandlerOptions(JsonObject json) {
    KnotEngineHandlerOptionsConverter.fromJson(json, this);
  }

  public JsonObject toJson() {
    JsonObject jsonObject = new JsonObject();
    KnotEngineHandlerOptionsConverter.toJson(this, jsonObject);
    return jsonObject;
  }

  public Map<String, KnotFlow> getFlows() {
    return flows;
  }

  public KnotEngineHandlerOptions setFlows(
      Map<String, KnotFlow> flows) {
    this.flows = flows;
    return this;
  }

  public Map<String, KnotFlowStep> getSteps() {
    return steps;
  }

  public KnotEngineHandlerOptions setSteps(
      Map<String, KnotFlowStep> steps) {
    this.steps = steps;
    return this;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    KnotEngineHandlerOptions that = (KnotEngineHandlerOptions) o;
    return Objects.equals(flows, that.flows) &&
        Objects.equals(steps, that.steps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(flows, steps);
  }

  @Override
  public String toString() {
    return "KnotEngineHandlerOptions{" +
        "flows=" + flows +
        ", steps=" + steps +
        '}';
  }
}