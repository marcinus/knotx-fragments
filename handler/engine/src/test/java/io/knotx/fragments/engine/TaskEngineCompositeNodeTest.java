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
package io.knotx.fragments.engine;

import static io.knotx.fragments.engine.FragmentEventLogVerifier.verifyAllLogEntries;
import static io.knotx.fragments.engine.FragmentEventLogVerifier.verifyLogEntries;
import static io.knotx.fragments.engine.helpers.TestFunction.appendBody;
import static io.knotx.fragments.engine.helpers.TestFunction.appendPayload;
import static io.knotx.fragments.engine.helpers.TestFunction.failure;
import static io.knotx.fragments.engine.helpers.TestFunction.fatal;
import static io.knotx.fragments.engine.helpers.TestFunction.success;
import static io.knotx.fragments.engine.helpers.TestFunction.successWithNodeLog;
import static io.knotx.fragments.handler.api.domain.FragmentResult.ERROR_TRANSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knotx.fragments.api.Fragment;
import io.knotx.fragments.engine.FragmentEvent.Status;
import io.knotx.fragments.engine.FragmentEventLogVerifier.Operation;
import io.knotx.fragments.engine.graph.CompositeNode;
import io.knotx.fragments.engine.graph.Node;
import io.knotx.fragments.engine.graph.SingleNode;
import io.knotx.fragments.handler.api.exception.NodeFatalException;
import io.knotx.server.api.context.ClientRequest;
import io.reactivex.Single;
import io.reactivex.exceptions.CompositeException;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class TaskEngineCompositeNodeTest {

  private static final String COMPOSITE_NODE_ID = "composite";
  private static final String INNER_COMPOSITE_NODE_ID = "innerComposite";
  private static final String INITIAL_BODY = "initial body";
  private static final Map<String, Node> NO_TRANSITIONS = Collections.emptyMap();

  private FragmentEventContext eventContext;
  private Fragment initialFragment = new Fragment("snippet", new JsonObject(), INITIAL_BODY);

  @BeforeEach
  void setUp() {
    eventContext = new FragmentEventContext(new FragmentEvent(initialFragment),
        new ClientRequest());
  }

  @Test
  @DisplayName("Expect unprocessed status when empty parallel action processing ends")
  void expectUnprocessedWhenEmptyParallelEnds(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        Collections.emptyList(),
        null,
        null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.UNPROCESSED, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect success status when single parallel action processing ends")
  void expectSuccessWhenSingleProcessingEnds(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", success(), NO_TRANSITIONS)
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.SUCCESS, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect success parallel event log entry when parallel action processing ends")
  void expectSuccessEventLogEntry(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    JsonObject successNodeLog = new JsonObject().put("debug", "success");
    JsonObject successNode2Log = new JsonObject().put("debug", "success2");
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", successWithNodeLog(successNodeLog), NO_TRANSITIONS),
            new SingleNode("action1", success(), NO_TRANSITIONS),
            new SingleNode("action2", successWithNodeLog(successNode2Log), NO_TRANSITIONS)
        ), null, null
    );
    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        event -> verifyAllLogEntries(event.getLogAsJson(),
            Operation.exact("task", "action", "SUCCESS", 0, successNodeLog),
            Operation.exact("task", "action1", "SUCCESS", 1),
            Operation.exact("task", "action2", "SUCCESS", 2, successNode2Log),
            Operation.exact("task", COMPOSITE_NODE_ID, "SUCCESS", 3)
        ));
  }


  @Test
  @DisplayName("Expect failure status when single parallel action processing fails")
  void expectErrorWhenSingleProcessingFails(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", failure(), NO_TRANSITIONS)
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.FAILURE, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect error parallel event log entry when error transition is handled.")
  void expectErrorEventLogEntry(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", failure(), NO_TRANSITIONS)
        ),
        null,
        new SingleNode("action", success(), NO_TRANSITIONS)
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> verifyLogEntries(fragmentEvent.getLogAsJson(),
            Operation.exact("task", COMPOSITE_NODE_ID, "ERROR", 2)
        ));
  }

  @Test
  @DisplayName("Expect unsupported event log entries when error transition not handled")
  void expectUnsupportedEventLogEntryWhenError(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", failure(), NO_TRANSITIONS)
        ), null, null
    );
    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        event -> verifyLogEntries(event.getLogAsJson(),
            Operation.exact("task", COMPOSITE_NODE_ID, "UNSUPPORTED_TRANSITION", 3)
        ));
  }

  @Test
  @DisplayName("Expect fatal when single parallel action throws fatal")
  void expectExceptionWhenSingleProcessingThrowsFatal(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("action", fatal(eventContext.getFragmentEvent().getFragment()),
                NO_TRANSITIONS)
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyError(result, testContext,
        error -> assertTrue(error.getExceptions().stream()
            .anyMatch(NodeFatalException.class::isInstance))
    );
  }

  @Test
  @DisplayName("Expect payload updated when parallel action ends")
  void expectPayloadUpdatedInParallelProcessing(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    JsonObject taskAPayload = new JsonObject().put("key", "taskAOperation");

    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", appendPayload("A", taskAPayload), NO_TRANSITIONS)
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(taskAPayload,
            fragmentEvent.getFragment().getPayload().getJsonObject("A")));
  }


  @Test
  @DisplayName("Expect success status when parallel inside parallel ends successfully")
  void inception(VertxTestContext testContext, Vertx vertx) throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new CompositeNode(INNER_COMPOSITE_NODE_ID,
                parallel(
                    new SingleNode("action", success(), NO_TRANSITIONS)
                ), null, null)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.SUCCESS, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect payload updated when parallel inside parallel action ends")
  void expectPayloadUpdatedInParallelInsideParallelProcessing(VertxTestContext testContext,
      Vertx vertx) throws Throwable {
    // given
    JsonObject taskAPayload = new JsonObject().put("key", "taskAOperation");
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new CompositeNode(INNER_COMPOSITE_NODE_ID,
                parallel(
                    new SingleNode("action", appendPayload("A", taskAPayload), NO_TRANSITIONS)
                ), null, null)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(taskAPayload,
            fragmentEvent.getFragment().getPayload().getJsonObject("A")));
  }

  @Test
  @DisplayName("Expect success when only one of parallel actions ends and another is empty")
  void expectSuccessWhenParallelConsistsOfEmptyAndSuccessActions(VertxTestContext testContext,
      Vertx vertx) throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new CompositeNode(INNER_COMPOSITE_NODE_ID, parallel(), null, null),
            new SingleNode("action", success(), NO_TRANSITIONS)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.SUCCESS, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect error when one of parallel actions ends with error")
  void expectError(VertxTestContext testContext, Vertx vertx) throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("failing", failure(), NO_TRANSITIONS),
            new SingleNode("success", success(), NO_TRANSITIONS)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.FAILURE, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect all parallel log entries when one of parallel actions ends with error")
  void expectLogEntriesOfAllParallelActions(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("failing", failure(), NO_TRANSITIONS),
            new SingleNode("success", success(), NO_TRANSITIONS)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        event -> verifyAllLogEntries(event.getLogAsJson(),
            Operation.range("task", "success", "SUCCESS", 0, 3),
            Operation.range("task", "failing", "ERROR", 0, 3),
            Operation.range("task", "failing", "UNSUPPORTED_TRANSITION", 0, 3),
            Operation.exact("task", COMPOSITE_NODE_ID, "ERROR", 3),
            Operation.exact("task", COMPOSITE_NODE_ID, "UNSUPPORTED_TRANSITION", 4)
        ));
  }

  @Test
  @DisplayName("Expect inner composite node log entry")
  void expectNamedInnerCompositeLogEntry(VertxTestContext testContext,
      Vertx vertx) throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new CompositeNode(INNER_COMPOSITE_NODE_ID,
                parallel(
                    new SingleNode("success", success(), NO_TRANSITIONS)
                ), null, null)
        ), null, null);

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        event -> verifyLogEntries(event.getLogAsJson(),
            Operation.exact("task", INNER_COMPOSITE_NODE_ID, "SUCCESS", 1)
        ));
  }

  @Test
  @DisplayName("Expect success status when parallel processing and one of parallel actions returns error that is handled by parallel section fallback")
  void expectFallbackAppliedAfterParallelProcessing(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", success(), NO_TRANSITIONS),
            new SingleNode("B", failure(), NO_TRANSITIONS)
        ), null, new SingleNode("fallback", success(), NO_TRANSITIONS)
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.SUCCESS, fragmentEvent.getStatus()));
  }

  @Test
  @DisplayName("Expect success status when parallel processing and one of parallel actions returns error that is handled by action fallback")
  void expectFallbackAppliedDuringParallelProcessing(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", success(), NO_TRANSITIONS),
            new SingleNode("B", failure(), Collections.singletonMap(
                ERROR_TRANSITION, new SingleNode("fallback", success(), NO_TRANSITIONS)
            ))
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(Status.SUCCESS, fragmentEvent.getStatus()));
  }

  /*
   * scenario: scenario: parallel[A -error-> , B -error-> A3(fallback) ] -error->
   */
  @Test
  @DisplayName("Expect fallback payload entry when parallel processing returns error and one of parallel actions returns error that is handled by action fallback and ")
  void expectFallbackPayloadWhenParallelProcessingFails(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", failure(), NO_TRANSITIONS),
            new SingleNode("B", failure(), Collections.singletonMap(
                ERROR_TRANSITION,
                new SingleNode("fallback", appendPayload("fallback", "value"), NO_TRANSITIONS)
            ))
        ), null, null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertTrue(
            fragmentEvent.getFragment().getPayload().containsKey("fallback")));
  }

  @Test
  @DisplayName("Expect success operation applied when parallel processing ends with success")
  void expectSuccessAppliedAfterParallelProcessingSuccess(VertxTestContext testContext, Vertx vertx)
      throws Throwable {
    // given
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", success(), NO_TRANSITIONS),
            new SingleNode("B", success(), NO_TRANSITIONS)
        ), new SingleNode("last", appendBody(":last"), NO_TRANSITIONS), null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(INITIAL_BODY + ":last",
            fragmentEvent.getFragment().getBody()));
  }

  @Test
  @DisplayName("Expect success after composite action applied on parallel processing success")
  void expectSuccessAfterParallelProcessingAppliedAfterSuccessParallel(VertxTestContext testContext,
      Vertx vertx) throws Throwable {
    // given

    JsonObject expectedPayload = new JsonObject().put("key", "value");
    Node rootNode = new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("A", success(), NO_TRANSITIONS),
            new SingleNode("B", success(), NO_TRANSITIONS)
        ), new CompositeNode(COMPOSITE_NODE_ID,
        parallel(
            new SingleNode("last", appendPayload("last", expectedPayload), NO_TRANSITIONS)
        ), null, null),
        null
    );

    // when
    Single<FragmentEvent> result = new TaskEngine(vertx).start("task", rootNode, eventContext);

    // then
    verifyExecution(result, testContext,
        fragmentEvent -> assertEquals(expectedPayload,
            fragmentEvent.getFragment().getPayload().getJsonObject("last")));
  }

  private List<Node> parallel(Node... nodes) {
    return Arrays.asList(nodes);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void verifyExecution(Single<FragmentEvent> result, VertxTestContext testContext,
      Consumer<FragmentEvent> successConsumer) throws Throwable {
    // execute
    // verifyAllLogEntries
    result.subscribe(
        onSuccess -> testContext.verify(() -> {
          successConsumer.accept(onSuccess);
          testContext.completeNow();
        }), testContext::failNow);

    assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void verifyError(Single<FragmentEvent> result, VertxTestContext testContext,
      Consumer<CompositeException> errorConsumer) throws Throwable {
    // execute
    // verifyAllLogEntries
    result.subscribe(
        onSuccess -> testContext.failNow(new IllegalStateException()),
        onError -> testContext.verify(() -> {
          errorConsumer.accept((CompositeException) onError);
          testContext.completeNow();
        }));

    assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }


}