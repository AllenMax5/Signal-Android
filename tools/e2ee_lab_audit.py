#!/usr/bin/env python3
"""Audit a complete 01–08 E2EE capture without printing captured secrets."""

import argparse
from collections import Counter
import json
from pathlib import Path
import sys


def read_device(directory):
    events = []
    for path in sorted(directory.glob("*.jsonl")):
        for number, line in enumerate(path.open(encoding="utf-8"), 1):
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            events.append((event, path.name, number))
    starts = [event for event, _, _ in events if event.get("operation") == "trace_started"]
    if not starts:
        return None, []
    latest = max(starts, key=lambda event: event.get("timestampMs", 0))["runId"]
    return latest, [event for event, _, _ in events if event.get("runId") == latest]


def matches(event, needle):
    data = event.get("data") or {}
    return needle in (event.get("operation") or "") or needle in str(data.get("path") or data.get("uri") or "")


def first(events, needle, after=0):
    return next((event for event in sorted(events, key=lambda item: item.get("timestampMs", 0))
                 if event.get("timestampMs", 0) > after and matches(event, needle)), None)


def completed_at(events, request, success_status=None):
    if not request:
        return 0
    for event in sorted(events, key=lambda item: item.get("timestampMs", 0)):
        if event.get("callId") != request.get("callId") or event.get("timestampMs", 0) < request.get("timestampMs", 0):
            continue
        data = event.get("data") or {}
        if event.get("kind") in ("response", "result"):
            if success_status is None or data.get("status") == success_status or (data.get("decodedResult") or {}).get("kind") == "success":
                return event.get("timestampMs", 0)
    return 0


def message(events, operation, body, after=0):
    return next((event for event in events if event.get("operation") == operation
                 and event.get("timestampMs", 0) > after
                 and (event.get("data") or {}).get("body") == body), None)


def saw_offline_recovery(screenshots):
    offline_seen = False
    for item in sorted(screenshots, key=lambda entry: entry.get("timestampMs", 0)):
        if item.get("role") != "linked" or item.get("kind") != "default_network":
            continue
        network = str(item.get("network", "")).lower()
        online = network.isdecimal() and network != "0"
        offline = network in ("none", "null", "-1", "0")
        if offline:
            offline_seen = True
        elif offline_seen and online:
            return True
    return False


def check(primary, linked, screenshots, marker_prefix=None):
    link_request = first(linked, "/v1/devices/link")
    link_time = completed_at(linked, link_request, 200)
    remove = first(primary, "AuthDevicesService.removeDevice", after=link_time)
    removed_at = completed_at(primary, remove)
    checks = {
        "01_registration_request": bool(first(primary, "/v1/registration")),
        "01_registration_keys": bool(first(primary, "/v2/keys")),
        "01_aci_identity_key_source": bool(first(primary, "aci_identity_key_selected") or first(primary, "aci_identity_key_restored")),
        "02_contact_profile": bool(first(primary, "/v1/profile/")),
        "02_profile_key_source": bool(first(primary, "contact_profile_key_observed")),
        "03_safety_displayed": bool(first(primary, "safety_number_displayed")),
        "03_manual_mark_requested": bool(first(primary, "safety_number_manual_mark_requested")),
        "03_manual_mark_saved": bool(first(primary, "safety_number_manual_mark_saved")),
        "03_sync_queued": bool(first(primary, "safety_number_sync_queued")),
        "04_outbound_plaintext": bool(first(primary, "outbound_message_pre_encrypt")),
        "04_primary_decrypted": bool(first(primary, "inbound_message_decrypted")),
        "05_kt_api": bool(first(primary, "KeyTransparencyClient.check")),
        "05_automatic_verification_inputs": bool(first(primary, "safety_number_automatic_inputs")),
        "05_kt_proof": bool(first(primary, "native_kt_verify_search_result")),
        "06_provisioning_address": bool(first(linked, "provisioning_address_received")),
        "06_primary_provisioning": bool(first(primary, "provisioning_message_plaintext")),
        "06_linked_provisioning": bool(first(linked, "secondary_provisioning_message_decrypted")),
        "06_link_response_200": bool(link_time),
        "07_verified_sync_submitted": bool(link_time and first(primary, "verified_sync_job_submitted", after=link_time)),
        "07_verified_sync_received": bool(link_time and first(linked, "verified_sync_received", after=link_time)),
        "07_storage_identity_pre_encrypt": bool(link_time and first(primary, "storage_contact_identity_pre_encrypt", after=link_time)),
        "08_remove_success": bool(removed_at),
    }
    if marker_prefix:
        checks.update({
            "04_primary_outbound_marker": bool(message(primary, "outbound_message_pre_encrypt", f"{marker_prefix}-P-001")),
            "04_primary_inbound_marker": bool(message(primary, "inbound_message_decrypted", f"{marker_prefix}-B-001")),
            "07_primary_new_delivery": bool(link_time and message(primary, "inbound_message_decrypted", f"{marker_prefix}-B-002", link_time)),
            "07_linked_new_delivery": bool(link_time and message(linked, "inbound_message_decrypted", f"{marker_prefix}-B-002", link_time)),
            "07_linked_outbound": bool(link_time and message(linked, "outbound_message_pre_encrypt", f"{marker_prefix}-L-001", link_time)),
            "07_primary_offline_window_delivery": bool(link_time and message(primary, "inbound_message_decrypted", f"{marker_prefix}-B-003", link_time)),
            "07_linked_offline_recovery": bool(link_time and message(linked, "inbound_message_decrypted", f"{marker_prefix}-B-003", link_time)),
            "08_primary_post_remove_delivery": bool(removed_at and message(primary, "inbound_message_decrypted", f"{marker_prefix}-B-004", removed_at)),
            "08_no_linked_decryption_observed": bool(removed_at and not message(linked, "inbound_message_decrypted", f"{marker_prefix}-B-004", removed_at)),
        })
    role_counts = Counter(item.get("role") for item in screenshots if item.get("kind") == "screenshot")
    checks["screenshots_primary"] = role_counts["primary"] > 0
    checks["screenshots_linked"] = role_counts["linked"] > 0
    checks["07_linked_offline_recovery_network"] = saw_offline_recovery(screenshots)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture_root", type=Path, help="Private folder containing primary/ and linked/ JSONL")
    parser.add_argument("--screenshots", type=Path, help="screenshots.jsonl from e2ee_lab_capture.py")
    parser.add_argument("--screenshots-after-ms", type=int, default=0, help="Host timestamp boundary when the observer contains an earlier abandoned attempt")
    parser.add_argument("--marker-prefix", help="Message prefix used in the scripted 01–08 test, e.g. E2EE-R3")
    args = parser.parse_args()
    run_ids = {}
    events = {}
    for role in ("primary", "linked"):
        run_ids[role], events[role] = read_device(args.capture_root / role)
    shots = []
    if args.screenshots:
        shots = [item for line in args.screenshots.open(encoding="utf-8") if line.strip()
                 if (item := json.loads(line)).get("timestampMs", 0) >= args.screenshots_after_ms]
    checks = check(events["primary"], events["linked"], shots, args.marker_prefix)
    summaries = {}
    for role, items in events.items():
        summaries[role] = {
            "runId": run_ids[role],
            "events": len(items),
            "traceStarts": sum(item.get("operation") == "trace_started" for item in items),
            "traceStops": sum(item.get("operation") == "trace_stopped" for item in items),
            "captureSummaries": sum(item.get("operation") == "capture_summary" for item in items),
        }
    result = {"devices": summaries, "checks": checks, "missing": [name for name, present in checks.items() if not present]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not result["missing"] else 2


if __name__ == "__main__":
    sys.exit(main())
