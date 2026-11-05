/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.signalservice.api.groupsv2;

import org.signal.network.trace.NetworkTrace;
import java.util.Map;
import java.util.function.Supplier;

/** Private Debug sink only. Observations must never change protocol execution. */
public final class GroupTrace {
  private GroupTrace() {}

  public static void event(String operation, Supplier<Map<String, Object>> fields) {
    if (!NetworkTrace.INSTANCE.getEnabled()) return;
    try {
      NetworkTrace.INSTANCE.event(operation, fields.get());
    } catch (Exception ignored) {
      // An unavailable diagnostic accessor must not break encryption or verification.
    }
  }

  public static void proto(String operation, String type, Supplier<byte[]> bytes, String boundary) {
    event(operation, () -> Map.of("protobufType", type, "protobuf", bytes.get(), "boundary", boundary));
  }
}
