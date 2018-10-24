/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.workflow;

import static io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord.PROP_WORKFLOW_ACTIVITY_ID;
import static io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord.PROP_WORKFLOW_BPMN_PROCESS_ID;
import static io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord.PROP_WORKFLOW_INSTANCE_KEY;
import static io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord.PROP_WORKFLOW_VERSION;
import static io.zeebe.test.broker.protocol.brokerapi.DeploymentStubs.DEFAULT_PARTITION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.clientapi.ExecuteCommandResponseDecoder;
import io.zeebe.protocol.clientapi.ValueType;
import io.zeebe.protocol.impl.record.value.deployment.ResourceType;
import io.zeebe.protocol.intent.DeploymentIntent;
import io.zeebe.protocol.intent.JobIntent;
import io.zeebe.protocol.intent.WorkflowInstanceIntent;
import io.zeebe.test.broker.protocol.clientapi.ClientApiRule;
import io.zeebe.test.broker.protocol.clientapi.ExecuteCommandResponse;
import io.zeebe.test.broker.protocol.clientapi.SubscribedRecord;
import io.zeebe.test.broker.protocol.clientapi.TestPartitionClient;
import java.io.File;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.util.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class WorkflowInstanceFunctionalTest {

  private static final String PROP_JOB_TYPE = "type";
  private static final String PROP_JOB_RETRIES = "retries";

  public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule();
  public ClientApiRule apiRule = new ClientApiRule(brokerRule::getClientAddress);

  @Rule public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(apiRule);

  private TestPartitionClient testClient;

  @Before
  public void init() {
    testClient = apiRule.partition();
  }

  @Test
  public void shouldStartWorkflowInstanceAtNoneStartEvent() {
    // given
    testClient.deploy(Bpmn.createExecutableProcess("process").startEvent("foo").endEvent().done());

    // when
    final ExecuteCommandResponse response =
        testClient.createWorkflowInstanceWithResponse("process");

    // then
    final SubscribedRecord workflowCreateCmd =
        testClient.receiveFirstWorkflowInstanceCommand(WorkflowInstanceIntent.CREATE);
    final SubscribedRecord startEvent =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.START_EVENT_OCCURRED);
    final long workflowInstanceKey = response.key();

    assertThat(response.sourceRecordPosition()).isEqualTo(workflowCreateCmd.position());

    assertThat(startEvent.key()).isGreaterThan(0).isNotEqualTo(workflowInstanceKey);
    assertThat(startEvent.position()).isGreaterThan(response.position());
    assertThat(startEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo");
  }

  @Test
  public void shouldTakeSequenceFlowFromStartEvent() {
    // given
    testClient.deploy(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .sequenceFlowId("foo")
            .endEvent()
            .done());

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord startEvent =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.START_EVENT_OCCURRED);
    final SubscribedRecord sequenceFlow =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN);

    assertThat(sequenceFlow.key()).isGreaterThan(0).isNotEqualTo(workflowInstanceKey);
    assertThat(sequenceFlow.sourceRecordPosition()).isEqualTo(startEvent.position());
    assertThat(sequenceFlow.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo");
  }

  @Test
  public void shouldOccureEndEvent() {
    // given
    testClient.deploy(Bpmn.createExecutableProcess("process").startEvent().endEvent("foo").done());

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord sequenceFlow =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN);
    final SubscribedRecord event =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.END_EVENT_OCCURRED);

    assertThat(event.key()).isGreaterThan(0).isNotEqualTo(workflowInstanceKey);
    assertThat(event.sourceRecordPosition()).isEqualTo(sequenceFlow.position());
    assertThat(event.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo");
  }

  @Test
  public void shouldCompleteWorkflowInstance() {
    // given
    testClient.deploy(Bpmn.createExecutableProcess("process").startEvent().endEvent().done());

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord endEvent =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.END_EVENT_OCCURRED);
    final SubscribedRecord completedEvent =
        testClient.receiveElementInState("process", WorkflowInstanceIntent.ELEMENT_COMPLETED);

    assertThat(completedEvent.key()).isEqualTo(workflowInstanceKey);
    assertThat(completedEvent.position()).isGreaterThan(endEvent.position());
    assertThat(completedEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "process");
  }

  @Test
  public void shouldConsumeTokenIfEventHasNoOutgoingSequenceflow() {
    // given
    testClient.deploy(Bpmn.createExecutableProcess("process").startEvent().done());

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord startEvent =
        testClient.receiveFirstWorkflowInstanceEvent(WorkflowInstanceIntent.START_EVENT_OCCURRED);
    final SubscribedRecord completedEvent =
        testClient.receiveElementInState("process", WorkflowInstanceIntent.ELEMENT_COMPLETED);

    assertThat(completedEvent.key()).isEqualTo(workflowInstanceKey);
    assertThat(completedEvent.position()).isGreaterThan(startEvent.position());
    assertThat(completedEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "process");
  }

  @Test
  public void shouldConsumeTokenIfActivityHasNoOutgoingSequenceflow() {
    // given
    final BpmnModelInstance definition =
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask("foo", t -> t.zeebeTaskType("bar"))
            .done();
    testClient.deploy(definition);
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // when
    testClient.completeJobOfType("bar");

    // then
    final SubscribedRecord activityCompleted =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_COMPLETED);
    final SubscribedRecord completedEvent =
        testClient.receiveElementInState("process", WorkflowInstanceIntent.ELEMENT_COMPLETED);

    assertThat(completedEvent.key()).isEqualTo(workflowInstanceKey);
    assertThat(completedEvent.position()).isGreaterThan(activityCompleted.position());
    assertThat(completedEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "process");
  }

  @Test
  public void shouldActivateServiceTask() {
    // given
    final BpmnModelInstance model =
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask("foo", t -> t.zeebeTaskType("bar"))
            .endEvent()
            .done();

    testClient.deploy(model);

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord activityReady =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_READY);
    final SubscribedRecord activatedEvent =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_ACTIVATED);

    assertThat(activatedEvent.key()).isGreaterThan(0).isNotEqualTo(workflowInstanceKey);
    assertThat(activatedEvent.sourceRecordPosition()).isEqualTo(activityReady.position());
    assertThat(activatedEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo");
  }

  @Test
  public void shouldCreateTaskWhenServiceTaskIsActivated() {
    // given
    testClient.deploy(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask("foo", t -> t.zeebeTaskType("bar").zeebeTaskRetries(5))
            .endEvent()
            .done());

    // when
    testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord activityActivated =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_ACTIVATED);
    final SubscribedRecord createJobCmd = testClient.receiveFirstJobCommand(JobIntent.CREATE);

    assertThat(createJobCmd.key()).isEqualTo(ExecuteCommandResponseDecoder.keyNullValue());
    assertThat(createJobCmd.sourceRecordPosition()).isEqualTo(activityActivated.position());
    assertThat(createJobCmd.value())
        .containsEntry(PROP_JOB_TYPE, "bar")
        .containsEntry(PROP_JOB_RETRIES, 5L);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldCreateJobWithWorkflowInstanceAndCustomHeaders() {
    // given
    testClient.deploy(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask(
                "foo",
                t -> t.zeebeTaskType("bar").zeebeTaskHeader("a", "b").zeebeTaskHeader("c", "d"))
            .endEvent()
            .done());

    // when
    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // then
    final SubscribedRecord event = testClient.receiveFirstJobCommand(JobIntent.CREATE);

    final Map<String, Object> headers = (Map<String, Object>) event.value().get("headers");
    assertThat(headers)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry("workflowDefinitionVersion", 1L)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo")
        .containsKey("activityInstanceKey");

    final Map<String, Object> customHeaders =
        (Map<String, Object>) event.value().get("customHeaders");
    assertThat(customHeaders).containsEntry("a", "b").containsEntry("c", "d");
  }

  @Test
  public void shouldCompleteServiceTaskWhenTaskIsCompleted() {
    // given
    final BpmnModelInstance definition =
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask("foo", t -> t.zeebeTaskType("bar"))
            .endEvent()
            .done();

    testClient.deploy(definition);

    final long workflowInstanceKey = testClient.createWorkflowInstance("process");

    // when
    testClient.completeJobOfType("bar");

    // then
    final SubscribedRecord activityActivatedEvent =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_ACTIVATED);
    final SubscribedRecord jobCompleted = testClient.receiveFirstJobEvent(JobIntent.COMPLETED);
    final SubscribedRecord activityCompleting =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_COMPLETING);
    final SubscribedRecord activityCompletedEvent =
        testClient.receiveElementInState("foo", WorkflowInstanceIntent.ELEMENT_COMPLETED);

    assertThat(activityCompleting.sourceRecordPosition()).isEqualTo(jobCompleted.position());
    assertThat(activityCompletedEvent.key()).isEqualTo(activityActivatedEvent.key());
    assertThat(activityCompletedEvent.sourceRecordPosition())
        .isEqualTo(activityCompleting.position());
    assertThat(activityCompletedEvent.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "process")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "foo");
  }

  @Test
  public void testWorkflowInstanceStatesWithServiceTask() {
    // given
    final BpmnModelInstance definition =
        Bpmn.createExecutableProcess("process")
            .startEvent("a")
            .serviceTask("b", t -> t.zeebeTaskType("foo"))
            .endEvent("c")
            .done();

    testClient.deploy(definition);

    testClient.createWorkflowInstance("process");

    // when
    testClient.completeJobOfType("foo");

    // then
    final List<SubscribedRecord> workflowEvents =
        testClient.receiveRecords().ofTypeWorkflowInstance().limit(14).collect(Collectors.toList());

    assertThat(workflowEvents)
        .extracting(e -> e.intent())
        .containsExactly(
            WorkflowInstanceIntent.CREATE,
            WorkflowInstanceIntent.CREATED,
            WorkflowInstanceIntent.ELEMENT_READY,
            WorkflowInstanceIntent.ELEMENT_ACTIVATED,
            WorkflowInstanceIntent.START_EVENT_OCCURRED,
            WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN,
            WorkflowInstanceIntent.ELEMENT_READY,
            WorkflowInstanceIntent.ELEMENT_ACTIVATED,
            WorkflowInstanceIntent.ELEMENT_COMPLETING,
            WorkflowInstanceIntent.ELEMENT_COMPLETED,
            WorkflowInstanceIntent.SEQUENCE_FLOW_TAKEN,
            WorkflowInstanceIntent.END_EVENT_OCCURRED,
            WorkflowInstanceIntent.ELEMENT_COMPLETING,
            WorkflowInstanceIntent.ELEMENT_COMPLETED);
  }

  @Test
  public void shouldCreateAndCompleteInstanceOfYamlWorkflow() throws URISyntaxException {
    // given
    final File yamlFile =
        new File(getClass().getResource("/workflows/simple-workflow.yaml").toURI());
    final String yamlWorkflow = Files.contentOf(yamlFile, UTF_8);

    final Map<String, Object> deploymentResource = new HashMap<>();
    deploymentResource.put("resource", yamlWorkflow.getBytes(UTF_8));
    deploymentResource.put("resourceType", ResourceType.YAML_WORKFLOW);
    deploymentResource.put("resourceName", "simple-workflow.yaml");

    final ExecuteCommandResponse deploymentResp =
        apiRule
            .createCmdRequest()
            .partitionId(DEFAULT_PARTITION)
            .type(ValueType.DEPLOYMENT, DeploymentIntent.CREATE)
            .command()
            .put("resources", Collections.singletonList(deploymentResource))
            .done()
            .sendAndAwait();

    testClient.receiveFirstDeploymentEvent(DeploymentIntent.CREATED, deploymentResp.key());

    final long workflowInstanceKey = testClient.createWorkflowInstance("yaml-workflow");

    // when
    testClient.completeJobOfType("foo");
    testClient.completeJobOfType("bar");

    // then
    final SubscribedRecord event =
        testClient.receiveElementInState("yaml-workflow", WorkflowInstanceIntent.ELEMENT_COMPLETED);

    assertThat(event.key()).isEqualTo(workflowInstanceKey);
    assertThat(event.value())
        .containsEntry(PROP_WORKFLOW_BPMN_PROCESS_ID, "yaml-workflow")
        .containsEntry(PROP_WORKFLOW_VERSION, 1L)
        .containsEntry(PROP_WORKFLOW_INSTANCE_KEY, workflowInstanceKey)
        .containsEntry(PROP_WORKFLOW_ACTIVITY_ID, "yaml-workflow");
  }

  /**
   * Scenario: When system partition stream processor is reprocessing after restart, it may not be
   * able to return a workflow that it already returned successfully before restart.
   */
  @Test
  public void shouldReprocessWorkflowInstanceRecordsWhenWorkflowIsTemporarilyUnavailable() {
    // given
    // make a couple of deployments to ensure that the deployment stream processor will need some
    // time for reprocessing
    final int numDeployments = 25;
    for (int i = 0; i < numDeployments; i++) {
      testClient.deploy(
          Bpmn.createExecutableProcess("process")
              .startEvent()
              .serviceTask("foo", t -> t.zeebeTaskType("bar"))
              .endEvent()
              .done());
    }

    testClient.createWorkflowInstance("process");

    brokerRule.stopBroker();
    brokerRule.purgeSnapshots();

    // when
    brokerRule.startBroker();

    // then I can still start workflow instance (i.e. stream processor did not crash
    final long newWorkflowInstancekey = testClient.createWorkflowInstance("process");
    assertThat(newWorkflowInstancekey).isGreaterThan(0);
  }
}
