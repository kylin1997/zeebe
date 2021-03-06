/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.test.util;

import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.util.ZbLogger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;

public final class BrokerClassRuleHelper extends TestWatcher {

  public static final Logger LOG = new ZbLogger("io.zeebe.test");

  private String currentTestMethod;

  @Override
  protected void failed(final Throwable e, final Description description) {
    LOG.info("Test failed, following records were exported:");
    RecordingExporter.getRecords().forEach(r -> LOG.info(r.toString()));
  }

  @Override
  protected void starting(final Description description) {
    currentTestMethod = description.getMethodName();

    RecordingExporter.reset();
  }

  public String getCurrentTestMethod() {
    return currentTestMethod;
  }

  public String getJobType() {
    return "job-" + currentTestMethod;
  }

  public String getBpmnProcessId() {
    return "workflow-" + currentTestMethod;
  }

  public String getCorrelationValue() {
    return "correlation-" + currentTestMethod;
  }

  public String getMessageName() {
    return "message-" + currentTestMethod;
  }
}
